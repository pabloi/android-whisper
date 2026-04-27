package dev.pabloi.whisper.recording

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import dev.pabloi.whisper.MainActivity
import dev.pabloi.whisper.R
import dev.pabloi.whisper.WhisprApp
import dev.pabloi.whisper.audio.AacWriter
import dev.pabloi.whisper.audio.AudioCapture
import dev.pabloi.whisper.audio.ChunkBuilder
import dev.pabloi.whisper.audio.PcmFrame
import dev.pabloi.whisper.audio.Vad
import dev.pabloi.whisper.data.AudioSourcePreset
import dev.pabloi.whisper.engine.AudioSource
import dev.pabloi.whisper.engine.TranscribeEvent
import dev.pabloi.whisper.engine.TranscribeOptions
import dev.pabloi.whisper.engine.local.LocalQnnWhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var capture: AudioCapture? = null
    private var aac: AacWriter? = null
    private var m4aPfd: ParcelFileDescriptor? = null
    private var sidecarOut: java.io.OutputStream? = null
    private var engineJob: Job? = null
    private var pcmJob: Job? = null
    private var clockJob: Job? = null
    private var chunkForwardJob: Job? = null
    private var chunkBuilder: ChunkBuilder? = null
    private var engineChunks: Channel<FloatArray>? = null
    private var currentRecording: Recording? = null
    private var startedAtMs: Long = 0L

    private var focusRequest: android.media.AudioFocusRequest? = null
    private val focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pauseDueToFocus()
            android.media.AudioManager.AUDIOFOCUS_GAIN -> resumeFromFocus()
            android.media.AudioManager.AUDIOFOCUS_LOSS -> stopSelfAsync()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (state.value !is RecordingState.Idle) return START_NOT_STICKY
        val transcribeLive = intent?.getBooleanExtra(EXTRA_TRANSCRIBE_LIVE, true) ?: true
        val sourceName = intent?.getStringExtra(EXTRA_SOURCE_PRESET) ?: AudioSourcePreset.VOICE_RECOGNITION.name
        val effectsOn = intent?.getBooleanExtra(EXTRA_EFFECTS_ON, true) ?: true

        _state.value = RecordingState.Starting
        startInForeground(buildNotification("Starting…", indeterminate = true))
        acquireWakeLock()

        scope.launch {
            try {
                val app = applicationContext as WhisprApp
                val settings = app.settings.flow.first()
                val folderUri = settings.recordingsFolderUri.takeIf { it.isNotBlank() }
                    ?: error("No recordings folder configured")
                val tree = DocumentFile.fromTreeUri(this@RecordingService, Uri.parse(folderUri))
                    ?: error("Cannot open recordings folder $folderUri")

                val now = System.currentTimeMillis()
                val baseName = "Recording ${SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(now))}"
                val m4aDoc = tree.createFile("audio/mp4", "$baseName.m4a")
                    ?: error("Cannot create .m4a in recordings folder")
                val sidecarDoc = tree.createFile("application/octet-stream", ".$baseName.aac")
                    ?: error("Cannot create ADTS sidecar in recordings folder")

                val pfd = contentResolver.openFileDescriptor(m4aDoc.uri, "rw")
                    ?: error("Cannot open .m4a for writing")
                val sidecar = contentResolver.openOutputStream(sidecarDoc.uri)
                    ?: error("Cannot open sidecar for writing")
                m4aPfd = pfd
                sidecarOut = sidecar

                val cap = AudioCapture(this@RecordingService,
                    sourcePreset = AudioSourcePreset.valueOf(sourceName),
                    effectsOn = effectsOn)
                cap.open()
                capture = cap
                val rate = cap.sampleRate.value
                aac = AacWriter(pfd, sidecar, sampleRate = rate, channels = 1)

                val rec = Recording(
                    id = UUID.randomUUID().toString(),
                    displayName = baseName,
                    audioUri = m4aDoc.uri.toString(),
                    transcriptUri = "",
                    startedAt = now,
                    durationMs = 0L,
                    sampleRate = rate,
                    routeLabel = cap.route.value,
                    state = Recording.State.RECORDING,
                )
                currentRecording = rec
                startedAtMs = now
                RecordingsStore(applicationContext).add(rec)

                _state.value = RecordingState.Recording(
                    recordingId = rec.id, startedAtMs = now, durationMs = 0,
                    levelDb = -120f, routeLabel = cap.route.value, sampleRate = rate,
                    transcribing = transcribeLive,
                )

                val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                focusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .setAcceptsDelayedFocusGain(false)
                    .build()
                am.requestAudioFocus(focusRequest!!)

                pcmJob = launchPcmConsumers(cap, transcribeLive, rate)
                if (transcribeLive) startEngineLoop()
                clockJob = startClock()
            } catch (t: Throwable) {
                _state.value = RecordingState.Failure(t)
                cleanup()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun launchPcmConsumers(cap: AudioCapture, transcribeLive: Boolean, nativeRate: Int): Job {
        val resampler = LiveResampler(srcRate = nativeRate, dstRate = Vad.SAMPLE_RATE_HZ, frameSamples = Vad.FRAME_SAMPLES)
        val cb = if (transcribeLive) ChunkBuilder().also { chunkBuilder = it } else null
        if (transcribeLive) {
            engineChunks = Channel(capacity = 4)
            chunkForwardJob = scope.launch {
                cb!!.flow.collect { engineChunks!!.send(it) }
                // Don't close engineChunks here — the channel is shared across
                // pause/resume cycles. stopSelfAsync closes it explicitly.
            }
        }
        return scope.launch {
            cap.frames.collect { frame ->
                // Writer: native-rate PCM straight into AAC. Continues during the
                // pre-pause window so the .m4a is continuous.
                val bb = ByteBuffer.allocate(frame.pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in frame.pcm) bb.putShort(s)
                bb.flip()
                aac?.append(bb)

                // Transcriber: only feed when actively Recording (not Paused/Stopping).
                // The chunkBuilder may be closed/replaced by a focus pause; guard
                // against the small window where capture.pause() hasn't yet taken
                // effect and a stray frame would otherwise hit a closed channel.
                if (transcribeLive && state.value is RecordingState.Recording) {
                    val builder = chunkBuilder
                    if (builder != null) {
                        try {
                            for (frame16k in resampler.process(frame.pcm)) builder.feed(frame16k)
                        } catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
                            /* paused mid-feed; ignore */
                        }
                    }
                }

                // Update state.
                val st = state.value
                if (st is RecordingState.Recording) {
                    _state.value = st.copy(levelDb = cap.levelDb.value, routeLabel = cap.route.value)
                }
            }
        }
    }

    private fun startEngineLoop() {
        val app = applicationContext as WhisprApp
        val repo = app.repoForId(runBlocking { app.settings.flow.first().localModelId })
        val engine = LocalQnnWhisperEngine(this, repo)
        val chunks = engineChunks!!.consumeAsFlow()
        engineJob = engine.transcribe(AudioSource.LiveStream(chunks), TranscribeOptions(timestamps = true))
            .onEach { ev -> _events.emit(ev) }
            .launchIn(scope)
    }

    private fun startClock(): Job = scope.launch {
        while (true) {
            kotlinx.coroutines.delay(250)
            val st = state.value
            if (st is RecordingState.Recording) {
                _state.value = st.copy(durationMs = System.currentTimeMillis() - startedAtMs)
            } else break
        }
    }

    fun stopRequest() = stopSelfAsync()

    private fun stopSelfAsync() {
        if (state.value is RecordingState.Stopping || state.value is RecordingState.Idle) return
        _state.value = RecordingState.Stopping
        scope.launch {
            try {
                capture?.close()
                pcmJob?.cancel()
                chunkBuilder?.close()
                chunkForwardJob?.cancel()
                engineChunks?.close()
                engineJob?.join()
                aac?.close()
                val rec = currentRecording
                if (rec != null) {
                    val durMs = System.currentTimeMillis() - startedAtMs
                    RecordingsStore(applicationContext).update(rec.id) {
                        it.copy(durationMs = durMs, state = Recording.State.FINALISED)
                    }
                }
            } finally {
                cleanup()
                _state.value = RecordingState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun pauseDueToFocus() {
        val st = state.value
        if (st !is RecordingState.Recording) return
        capture?.pause()
        val toClose = chunkBuilder
        chunkBuilder = null
        scope.launch { toClose?.close() }  // emits the pre-call partial chunk if speech in buffer
        _state.value = RecordingState.Paused(st.recordingId, "phone call", st.routeLabel)
    }

    private fun resumeFromFocus() {
        val st = state.value
        if (st !is RecordingState.Paused) return
        capture?.resume()
        // Recreate ChunkBuilder for the post-resume buffer (the previous one is closed).
        val newBuilder = ChunkBuilder()
        chunkBuilder = newBuilder
        // Replace the previous forwarder; its source flow already completed.
        chunkForwardJob?.cancel()
        chunkForwardJob = scope.launch {
            newBuilder.flow.collect { engineChunks?.send(it) }
        }
        _state.value = RecordingState.Recording(
            recordingId = st.recordingId,
            startedAtMs = startedAtMs,
            durationMs = System.currentTimeMillis() - startedAtMs,
            levelDb = -120f,
            routeLabel = st.routeLabel,
            sampleRate = capture?.sampleRate?.value ?: 48_000,
            transcribing = newBuilder != null,
        )
    }

    private fun cleanup() {
        focusRequest?.let {
            (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).abandonAudioFocusRequest(it)
        }
        focusRequest = null
        clockJob?.cancel(); clockJob = null
        engineJob?.cancel(); engineJob = null
        chunkForwardJob?.cancel(); chunkForwardJob = null
        engineChunks = null
        chunkBuilder = null
        // Close the AAC writer first so it can flush its EOS frames into both
        // the MediaMuxer .m4a (writing the moov) and the ADTS sidecar.
        runCatching { aac?.close() }
        aac = null
        // Then release the underlying SAF resources we opened, sidecar first
        // so AacWriter's adtsOut flush has settled.
        runCatching { sidecarOut?.close() }
        sidecarOut = null
        runCatching { m4aPfd?.close() }
        m4aPfd = null
        capture = null
        releaseWakeLock()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        releaseWakeLock()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(NOTIF_ID, notification)
    }

    private fun buildNotification(text: String, indeterminate: Boolean = false): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whispr — recording")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setProgress(if (indeterminate) 0 else 100, 0, indeterminate)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Whispr:recording").apply {
            setReferenceCounted(false)
            acquire(TimeUnit.HOURS.toMillis(4))
        }
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    companion object {
        const val CHANNEL_ID = "whispr_recording"
        const val EXTRA_TRANSCRIBE_LIVE = "transcribe_live"
        const val EXTRA_SOURCE_PRESET = "source_preset"
        const val EXTRA_EFFECTS_ON = "effects_on"
        const val EXTRA_STOP = "stop"
        private const val NOTIF_ID = 2001

        private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        val state: StateFlow<RecordingState> = _state.asStateFlow()
        private val _events = MutableSharedFlow<TranscribeEvent>(replay = 0, extraBufferCapacity = 32)
        val events: SharedFlow<TranscribeEvent> = _events.asSharedFlow()

        fun start(context: Context, transcribeLive: Boolean, source: AudioSourcePreset, effectsOn: Boolean) {
            val intent = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_TRANSCRIBE_LIVE, transcribeLive)
                .putExtra(EXTRA_SOURCE_PRESET, source.name)
                .putExtra(EXTRA_EFFECTS_ON, effectsOn)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // Send a STOP intent that the service interprets in onStartCommand.
            // Simpler: use a static reference set in onCreate. But for now, the
            // ViewModel calls stopService() and the service's onDestroy path
            // also drives cleanup. RecordingService handles a STOP intent via
            // the same pathway in case of redelivery.
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}

/**
 * Linear resampler producing 20-ms 16-kHz frames from arbitrary-rate native PCM.
 * Maintains state across `process()` calls so a 1-sample partial frame at the
 * end of one call rolls into the next call cleanly.
 */
internal class LiveResampler(val srcRate: Int, val dstRate: Int, val frameSamples: Int) {
    private val ratio = srcRate.toDouble() / dstRate
    private var srcPos = 0.0
    private val accum = ArrayList<Float>()
    private val pending = ArrayList<Float>()

    fun process(srcInt16: ShortArray): List<FloatArray> {
        // Append new source samples (scaled to f32 [-1,1]).
        for (s in srcInt16) accum.add(s.toFloat() / Short.MAX_VALUE)

        val out = ArrayList<Float>()
        // Produce as many destination samples as we can given current `accum` buffer.
        while (true) {
            val i0 = srcPos.toInt()
            val i1 = i0 + 1
            if (i1 >= accum.size) break
            val frac = (srcPos - i0).toFloat()
            out.add(accum[i0] + (accum[i1] - accum[i0]) * frac)
            srcPos += ratio
        }
        // Trim consumed source samples (keep one sample of context for next call).
        val keepFrom = (srcPos.toInt() - 1).coerceAtLeast(0)
        if (keepFrom > 0) {
            repeat(keepFrom) { accum.removeAt(0) }
            srcPos -= keepFrom
        }

        // Concatenate with any leftover and split into FRAME_SAMPLES-sized FloatArrays.
        pending.addAll(out)
        val frames = ArrayList<FloatArray>()
        while (pending.size >= frameSamples) {
            val f = FloatArray(frameSamples) { pending[it] }
            repeat(frameSamples) { pending.removeAt(0) }
            frames.add(f)
        }
        return frames
    }
}
