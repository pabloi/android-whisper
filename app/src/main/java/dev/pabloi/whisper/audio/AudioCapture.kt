package dev.pabloi.whisper.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.pabloi.whisper.data.AudioSourcePreset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.log10
import kotlin.math.sqrt

/** A 20-ms frame at the native sample rate, mono, 16-bit LE. */
data class PcmFrame(
    val pcm: ShortArray,
    val sampleRate: Int,
)

class AudioCapture(
    private val context: Context,
    private val sourcePreset: AudioSourcePreset,
    private val effectsOn: Boolean,
) {
    private var record: AudioRecord? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var deviceCb: AudioDeviceCallback? = null
    private var commDeviceListener: android.media.AudioManager.OnCommunicationDeviceChangedListener? = null
    private var scoStateReceiver: android.content.BroadcastReceiver? = null
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    @Volatile private var paused = false

    private val reopenMutex = kotlinx.coroutines.sync.Mutex()
    private var reopenJob: Job? = null

    private val _frames = MutableSharedFlow<PcmFrame>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND
    )
    val frames: SharedFlow<PcmFrame> = _frames.asSharedFlow()

    private val _levelDb = MutableStateFlow(-120f)
    val levelDb: StateFlow<Float> = _levelDb.asStateFlow()

    private val _route = MutableStateFlow("Built-in mic")
    val route: StateFlow<String> = _route.asStateFlow()

    private val _sampleRate = MutableStateFlow(48_000)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    /** Throws SecurityException if RECORD_AUDIO is not granted. */
    suspend fun open() {
        check(record == null) { "AudioCapture already open" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        var device = pickActiveInputDevice()

        // If we picked a BT SCO device, bring up the SCO link FIRST and wait
        // for the OS to confirm. Without this, AudioRecord starts before the
        // link is up and captures silence indefinitely.
        if (device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            val ok = bringUpBluetoothSco(device)
            if (!ok) {
                // SCO failed to come up; fall back to built-in mic so the user
                // still gets *some* audio rather than a silent recording.
                android.util.Log.w(TAG, "BT SCO bring-up failed; falling back to built-in mic")
                device = pickBuiltInMic()
            }
        }

        val nativeRate = device?.sampleRates?.firstOrNull() ?: 48_000
        configureAndStart(device, nativeRate)
        registerRouteCallback()
    }

    private fun pickBuiltInMic(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    private fun configureAndStart(device: AudioDeviceInfo?, nativeRate: Int) {
        val source = if (device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            // BT SCO requires the VOIP-style source on Samsung; the user's
            // audio source preset (RECOGNITION / MIC / CAMCORDER) doesn't
            // route through the comm device on this firmware.
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else when (sourcePreset) {
            AudioSourcePreset.MIC -> MediaRecorder.AudioSource.MIC
            AudioSourcePreset.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            AudioSourcePreset.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
        }
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(nativeRate, channelMask, encoding)
        val bufBytes = (minBuf.coerceAtLeast(nativeRate * 2 / 5))  // ~200 ms

        val rec = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(nativeRate)
                .setChannelMask(channelMask)
                .setEncoding(encoding).build())
            .setBufferSizeInBytes(bufBytes).build()
        if (device != null) rec.preferredDevice = device

        // Note: BT SCO link is brought up in `open()` BEFORE we get here, so
        // by the time we call startRecording() the comm-device route has been
        // confirmed by the OS via the OnCommunicationDeviceChangedListener.

        if (effectsOn) {
            if (NoiseSuppressor.isAvailable())     ns = NoiseSuppressor.create(rec.audioSessionId).apply { enabled = true }
            if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(rec.audioSessionId).apply { enabled = true }
        }

        rec.startRecording()
        record = rec
        _sampleRate.value = nativeRate
        _route.value = describeRoute(device)

        val frameSamples = nativeRate * 20 / 1000
        val readBuf = ShortArray(frameSamples)
        readerJob = scope.launch {
            while (record === rec && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                if (paused) { kotlinx.coroutines.delay(20); continue }
                val n = rec.read(readBuf, 0, frameSamples)
                if (n <= 0) continue
                val frame = ShortArray(n)
                System.arraycopy(readBuf, 0, frame, 0, n)
                _frames.emit(PcmFrame(frame, nativeRate))
                _levelDb.value = rmsDb(frame)
            }
        }
    }

    private fun pickActiveInputDevice(): AudioDeviceInfo? {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        // Prefer BT SCO or wired headset if present as input route, mirroring
        // what the system picks when it routes voice (calls, voice recognition).
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: inputs.firstOrNull()
    }

    private fun describeRoute(d: AudioDeviceInfo?): String = when (d?.type) {
        null, AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth: ${d.productName}"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        else -> d.productName?.toString() ?: "Mic"
    }

    private fun registerRouteCallback() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                if (shouldReopenOn(added)) reopen()
            }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) {
                if (shouldReopenOn(removed)) reopen()
            }
        }
        deviceCb = cb
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
    }

    /**
     * Decide whether a device-list change merits a [reopen]. Triggers when:
     *   - the currently-active device itself appeared or disappeared, or
     *   - a higher-priority headset/SCO/USB input is now present while we are
     *     recording from the built-in mic (so plugging in a BT headset mid-record
     *     reroutes to it).
     */
    private fun shouldReopenOn(devices: Array<out AudioDeviceInfo>?): Boolean {
        if (devices == null || devices.isEmpty()) return false
        val activeId = record?.routedDevice?.id
        val inputs = devices.filter { it.isSource }
        if (inputs.isEmpty()) return false
        val touchesActive = activeId != null && inputs.any { it.id == activeId }
        val higherPriority = inputs.any { d ->
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } && (record?.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || activeId == null)
        return touchesActive || higherPriority
    }

    private fun reopen() {
        reopenJob?.cancel()
        reopenJob = scope.launch {
            reopenMutex.withLock {
                try {
                    stopReaderAndRecord()
                    var device = pickActiveInputDevice()
                    if (device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        val ok = bringUpBluetoothSco(device)
                        if (!ok) {
                            android.util.Log.w(TAG, "BT SCO bring-up failed on reopen; falling back to built-in mic")
                            device = pickBuiltInMic()
                        }
                    }
                    val rate = device?.sampleRates?.firstOrNull() ?: 48_000
                    configureAndStart(device, rate)
                } catch (_: Throwable) {
                    /* surfaced as silence in level meter */
                }
            }
        }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    fun close() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        deviceCb?.let { am.unregisterAudioDeviceCallback(it) }; deviceCb = null
        stopReaderAndRecord()
        scope.cancel()
    }

    private fun stopReaderAndRecord() {
        readerJob?.cancel(); readerJob = null
        // Release SCO before stopping the AudioRecord so the OS doesn't keep
        // the link warm after we've gone idle.
        tearDownBluetoothSco()
        runCatching { record?.stop() }
        runCatching { record?.release() }; record = null
        runCatching { ns?.release() }; ns = null
        runCatching { agc?.release() }; agc = null
    }

    /**
     * Set the system communication device to [btDevice] and wait for the OS
     * to confirm the route is active. Returns true on success, false on
     * timeout / permission failure.
     *
     * `setCommunicationDevice` is asynchronous on API 31+: it returns
     * immediately but SCO link establishment can take several hundred
     * milliseconds. AudioRecord started before the link is up captures zeros
     * and on most Samsung devices never recovers. We register an
     * OnCommunicationDeviceChangedListener BEFORE the request so we don't
     * miss the edge, then wait up to 3 s for the listener to confirm.
     */
    private suspend fun bringUpBluetoothSco(btDevice: AudioDeviceInfo): Boolean {
        // BLUETOOTH_CONNECT is required for setCommunicationDevice on API 31+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            android.util.Log.w(TAG, "BLUETOOTH_CONNECT not granted; cannot route to BT SCO")
            return false
        }

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        savedAudioMode = am.mode

        // Samsung firmware requires MODE_IN_COMMUNICATION for SCO audio to
        // actually stream — otherwise the route is "selected" but no bytes flow.
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        // Two parallel "ready" signals: either is sufficient.
        val ready = kotlinx.coroutines.CompletableDeferred<Boolean>()

        // Legacy SCO state broadcast — definitive signal that the SCO audio
        // link is up and carrying samples.
        @Suppress("DEPRECATION")
        val scoIntent = AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                @Suppress("DEPRECATION")
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED) ?: -1
                @Suppress("DEPRECATION")
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && !ready.isCompleted) {
                    android.util.Log.i(TAG, "SCO_AUDIO_STATE_CONNECTED received")
                    ready.complete(true)
                }
            }
        }
        scoStateReceiver = receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, android.content.IntentFilter(scoIntent), android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, android.content.IntentFilter(scoIntent))
        }

        // Modern API 31+ comm-device-changed listener.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetDevice = am.availableCommunicationDevices
                .firstOrNull { it.id == btDevice.id }
                ?: am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: btDevice
            val listener = AudioManager.OnCommunicationDeviceChangedListener { active ->
                if (active != null && active.id == targetDevice.id && !ready.isCompleted) {
                    android.util.Log.i(TAG, "OnCommunicationDeviceChanged → ${active.productName}")
                    ready.complete(true)
                }
            }
            am.addOnCommunicationDeviceChangedListener(java.util.concurrent.Executors.newSingleThreadExecutor(), listener)
            commDeviceListener = listener

            val current = am.communicationDevice
            if (current?.id == targetDevice.id && !ready.isCompleted) {
                android.util.Log.i(TAG, "comm device already routed to ${targetDevice.productName}")
                // Don't complete yet — still wait for SCO audio state to confirm
                // bytes actually flow. If broadcast never comes (some Samsung
                // builds skip it once already connected), the comm-device check
                // is our fallback signal.
                ready.complete(true)
            } else {
                val ok = am.setCommunicationDevice(targetDevice)
                if (!ok) {
                    android.util.Log.w(TAG, "setCommunicationDevice($targetDevice) returned false")
                }
            }
        }

        // Legacy SCO start — required on Samsung in addition to setCommunicationDevice.
        @Suppress("DEPRECATION")
        runCatching { am.startBluetoothSco() }

        val gotIt = withTimeoutOrNull(5000) { ready.await() } ?: false
        if (!gotIt) {
            android.util.Log.w(TAG, "Timed out waiting for BT SCO routing")
            tearDownBluetoothSco()
            return false
        }
        return true
    }

    private fun tearDownBluetoothSco() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        scoStateReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        scoStateReceiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commDeviceListener?.let { am.removeOnCommunicationDeviceChangedListener(it) }
            commDeviceListener = null
            runCatching { am.clearCommunicationDevice() }
        }
        @Suppress("DEPRECATION")
        runCatching { am.stopBluetoothSco() }
        runCatching { am.mode = savedAudioMode }
    }

    private fun rmsDb(frame: ShortArray): Float {
        var sumSq = 0.0
        for (s in frame) sumSq += (s.toDouble() / Short.MAX_VALUE).let { it * it }
        val rms = sqrt(sumSq / frame.size)
        return if (rms <= 0.0) -120f else (20 * log10(rms)).toFloat()
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
