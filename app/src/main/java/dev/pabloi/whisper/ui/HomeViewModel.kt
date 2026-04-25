package dev.pabloi.whisper.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pabloi.whisper.WhisprApp
import dev.pabloi.whisper.data.EngineChoice
import dev.pabloi.whisper.engine.AudioSource
import dev.pabloi.whisper.engine.EngineFactory
import dev.pabloi.whisper.engine.TranscribeEvent
import dev.pabloi.whisper.engine.TranscribeOptions
import dev.pabloi.whisper.engine.local.ModelRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TranscribeUiState(
    val busy: Boolean = false,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val segments: List<Pair<String, String>> = emptyList(), // (timestamps, text)
    val finalText: String = "",
    val error: String? = null,
    val lastDurationMs: Long? = null,
)

data class DownloadUiState(
    val installed: Boolean = false,
    val inProgress: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val whispr = app as WhisprApp
    private val repo: ModelRepository = whispr.modelRepo
    private val settingsStore = whispr.settings

    private val _transcribe = MutableStateFlow(TranscribeUiState())
    val transcribe: StateFlow<TranscribeUiState> = _transcribe.asStateFlow()

    private val _download = MutableStateFlow(DownloadUiState(installed = repo.isInstalled()))
    val download: StateFlow<DownloadUiState> = _download.asStateFlow()

    val settings = settingsStore.flow

    private var job: Job? = null

    fun startDownload() {
        if (_download.value.inProgress) return
        viewModelScope.launch {
            _download.value = DownloadUiState(inProgress = true)
            try {
                repo.download().collectLatest { p ->
                    _download.value = _download.value.copy(
                        progress = p.fraction, message = p.message
                    )
                }
                _download.value = DownloadUiState(installed = repo.isInstalled(), progress = 1f, message = "Installed")
            } catch (t: Throwable) {
                _download.value = DownloadUiState(
                    installed = repo.isInstalled(), error = t.message ?: t::class.java.simpleName
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _transcribe.value = _transcribe.value.copy(busy = false)
    }

    fun transcribe(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            _transcribe.value = TranscribeUiState(busy = true, statusMessage = "Preparing")
            val current = settingsStore.flow.first()
            // If local engine chosen but assets missing, auto-pivot to download UI.
            if (current.engine == EngineChoice.LOCAL && !repo.isInstalled()) {
                _transcribe.value = TranscribeUiState(
                    error = "Model not installed — tap Download first."
                )
                return@launch
            }
            val engine = EngineFactory.create(getApplication(), current, repo)
            try {
                val opts = TranscribeOptions(
                    language = current.language.ifBlank { null },
                    timestamps = current.timestamps,
                )
                engine.transcribe(AudioSource.Uri(uri), opts).collectLatest { ev ->
                    val state = _transcribe.value
                    _transcribe.value = when (ev) {
                        is TranscribeEvent.Progress -> state.copy(
                            progress = ev.fraction, statusMessage = ev.message ?: state.statusMessage
                        )
                        is TranscribeEvent.Segment -> state.copy(
                            segments = state.segments + (formatTime(ev.startSec, ev.endSec) to ev.text)
                        )
                        is TranscribeEvent.Final -> state.copy(
                            busy = false, progress = 1f, statusMessage = "Done",
                            finalText = ev.text, lastDurationMs = ev.durationMs
                        )
                        is TranscribeEvent.Failure -> state.copy(
                            busy = false, error = ev.cause.message ?: ev.cause::class.java.simpleName
                        )
                    }
                }
            } catch (t: Throwable) {
                _transcribe.value = _transcribe.value.copy(
                    busy = false, error = t.message ?: t::class.java.simpleName
                )
            } finally {
                engine.close()
            }
        }
    }

    fun copyToClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Transcript", text))
    }

    private fun formatTime(start: Double?, end: Double?): String {
        if (start == null || end == null) return ""
        fun fmt(s: Double): String {
            val total = s.toInt()
            val h = total / 3600; val m = (total % 3600) / 60; val sec = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
        return "${fmt(start)}–${fmt(end)}"
    }
}
