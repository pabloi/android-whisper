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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    fun open() {
        check(record == null) { "AudioCapture already open" }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        val device = pickActiveInputDevice()
        val nativeRate = device?.sampleRates?.firstOrNull() ?: 48_000
        configureAndStart(device, nativeRate)
        registerRouteCallback()
    }

    private fun configureAndStart(device: AudioDeviceInfo?, nativeRate: Int) {
        val source = when (sourcePreset) {
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

        // Bring up the BT SCO link if we picked a BT mic. Without this,
        // AudioRecord falls back to the built-in mic on most devices.
        if (device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            ensureBluetoothSco(start = true, device = device)
        }

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
                    val device = pickActiveInputDevice()
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
        ensureBluetoothSco(start = false, device = null)
        runCatching { record?.stop() }
        runCatching { record?.release() }; record = null
        runCatching { ns?.release() }; ns = null
        runCatching { agc?.release() }; agc = null
    }

    /**
     * Bring the Bluetooth SCO link up (or down) when we want to capture from a
     * BT headset mic. Without this, AudioRecord with preferredDevice = BT SCO
     * silently routes to the built-in mic on most Android builds.
     *
     * On Android 12+ we use AudioManager.setCommunicationDevice (the modern
     * API). On older devices we use the legacy startBluetoothSco / stopBluetoothSco
     * pair, which is deprecated as of API 31 but still works through API 35.
     */
    private fun ensureBluetoothSco(start: Boolean, device: AudioDeviceInfo?) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (start) {
                if (device != null) am.setCommunicationDevice(device)
            } else {
                runCatching { am.clearCommunicationDevice() }
            }
        } else {
            @Suppress("DEPRECATION")
            if (start) {
                runCatching {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    am.startBluetoothSco()
                }
            } else {
                runCatching {
                    am.stopBluetoothSco()
                    am.mode = AudioManager.MODE_NORMAL
                }
            }
        }
    }

    private fun rmsDb(frame: ShortArray): Float {
        var sumSq = 0.0
        for (s in frame) sumSq += (s.toDouble() / Short.MAX_VALUE).let { it * it }
        val rms = sqrt(sumSq / frame.size)
        return if (rms <= 0.0) -120f else (20 * log10(rms)).toFloat()
    }
}
