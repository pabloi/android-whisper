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
import dev.pabloi.whisper.data.TranscriptWriter
import dev.pabloi.whisper.engine.AudioSource
import dev.pabloi.whisper.engine.EngineFactory
import dev.pabloi.whisper.engine.TranscribeEvent
import dev.pabloi.whisper.engine.TranscribeOptions
import dev.pabloi.whisper.engine.local.ModelCatalog
import dev.pabloi.whisper.engine.local.ModelDownloadService
import dev.pabloi.whisper.engine.local.ModelSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
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
    val activeSpec: ModelSpec = ModelCatalog.default,
    val installed: Boolean = false,
    val inProgress: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val whispr = app as WhisprApp
    private val settingsStore = whispr.settings

    private val _transcribe = MutableStateFlow(TranscribeUiState())
    val transcribe: StateFlow<TranscribeUiState> = _transcribe.asStateFlow()

    private val _download = MutableStateFlow(DownloadUiState())
    val download: StateFlow<DownloadUiState> = _download.asStateFlow()

    val settings = settingsStore.flow

    private var job: Job? = null

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            // The active spec is derived from settings; whenever it changes we
            // rewire to that spec's per-model service flows.
            settingsStore.flow
                .map { ModelCatalog.resolve(it.localModelId) }
                .distinctUntilChanged()
                .flatMapLatest { spec -> downloadStateFor(spec) }
                .collectLatest { _download.value = it }
        }
    }

    private fun downloadStateFor(spec: ModelSpec): Flow<DownloadUiState> {
        val repo = whispr.repoFor(spec)
        return combine(
            ModelDownloadService.running(spec.id),
            ModelDownloadService.progress(spec.id),
            ModelDownloadService.error(spec.id),
        ) { running, p, err ->
            DownloadUiState(
                activeSpec = spec,
                installed = repo.isInstalled(),
                inProgress = running,
                progress = p.fraction,
                message = p.message,
                error = err,
            )
        }
    }

    fun startDownload() = startDownloadFor(_download.value.activeSpec.id)

    fun startDownloadFor(modelId: String) {
        ModelDownloadService.start(getApplication(), modelId)
    }

    fun selectLocalModel(modelId: String) {
        viewModelScope.launch {
            settingsStore.update { it.copy(localModelId = modelId) }
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
            if (current.engine == EngineChoice.LOCAL) {
                val repo = whispr.repoForId(current.localModelId)
                if (!repo.isInstalled()) {
                    _transcribe.value = TranscribeUiState(
                        error = "Model '${repo.spec.displayName}' not installed — tap Download first."
                    )
                    return@launch
                }
            }
            val engine = EngineFactory.create(getApplication(), current)
            val transcriptWriter = TranscriptWriter(getApplication(), uri).also { it.open() }
            val savedTo = transcriptWriter.displayPath.ifBlank { null }
            if (savedTo != null) {
                _transcribe.value = _transcribe.value.copy(statusMessage = "Saving to $savedTo")
            }
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
                        is TranscribeEvent.Segment -> {
                            transcriptWriter.appendSegment(ev.startSec, ev.endSec, ev.text)
                            state.copy(
                                segments = state.segments + (formatTime(ev.startSec, ev.endSec) to ev.text)
                            )
                        }
                        is TranscribeEvent.Final -> {
                            transcriptWriter.appendFooter("# Done in ${ev.durationMs} ms")
                            val tail = savedTo?.let { " · saved to $it" } ?: ""
                            state.copy(
                                busy = false, progress = 1f, statusMessage = "Done$tail",
                                finalText = ev.text, lastDurationMs = ev.durationMs
                            )
                        }
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
                transcriptWriter.close()
                engine.close()
            }
        }
    }

    fun copyToClipboard(text: String) {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Transcript", text))
    }

    fun recordingsListStateOrEmpty(): kotlinx.coroutines.flow.Flow<List<dev.pabloi.whisper.recording.Recording>> =
        dev.pabloi.whisper.recording.RecordingsStore(getApplication<Application>()).flow

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
