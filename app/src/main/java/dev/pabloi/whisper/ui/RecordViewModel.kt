package dev.pabloi.whisper.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pabloi.whisper.WhisprApp
import dev.pabloi.whisper.engine.TranscribeEvent
import dev.pabloi.whisper.recording.Recording
import dev.pabloi.whisper.recording.RecordingService
import dev.pabloi.whisper.recording.RecordingState
import dev.pabloi.whisper.recording.RecordingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class RecordUiState(
    val state: RecordingState = RecordingState.Idle,
    val segments: List<Pair<String, String>> = emptyList(),  // (timestamps, text)
    val transcribeLive: Boolean = true,
    val needsFolder: Boolean = false,
    val needsMicPermission: Boolean = false,
    val error: String? = null,
)

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    private val whispr = app as WhisprApp

    private val _ui = MutableStateFlow(RecordUiState())
    val ui: StateFlow<RecordUiState> = _ui.asStateFlow()

    val recordings: StateFlow<List<Recording>> = run {
        val sf = MutableStateFlow<List<Recording>>(emptyList())
        viewModelScope.launch {
            RecordingsStore(app).flow.collect { sf.value = it }
        }
        sf.asStateFlow()
    }

    private var lastSegmentLastWord: String = ""

    init {
        RecordingService.state.onEach { _ui.value = _ui.value.copy(state = it) }.launchIn(viewModelScope)
        RecordingService.events.onEach { ev ->
            when (ev) {
                is TranscribeEvent.Segment -> {
                    val deduped = dedupeOverlap(ev.text)
                    if (deduped.isNotEmpty()) {
                        _ui.value = _ui.value.copy(
                            segments = _ui.value.segments + (formatTime(ev.startSec, ev.endSec) to deduped)
                        )
                    }
                    lastSegmentLastWord = deduped.trim().split(WS).lastOrNull().orEmpty()
                }
                is TranscribeEvent.Failure -> _ui.value = _ui.value.copy(
                    error = "Live transcription stopped: ${ev.cause.message ?: ev.cause::class.java.simpleName}"
                )
                else -> Unit
            }
        }.launchIn(viewModelScope)
        viewModelScope.launch {
            val s = whispr.settings.flow.first()
            _ui.value = _ui.value.copy(transcribeLive = s.recordWithTranscription)
        }
    }

    /** Returns true if everything's ready to start; false if a permission/folder prompt is needed. */
    suspend fun preflight(micGranted: Boolean): Boolean {
        if (!micGranted) {
            _ui.value = _ui.value.copy(needsMicPermission = true); return false
        }
        val s = whispr.settings.flow.first()
        if (s.recordingsFolderUri.isBlank()) {
            _ui.value = _ui.value.copy(needsFolder = true); return false
        }
        _ui.value = _ui.value.copy(needsMicPermission = false, needsFolder = false)
        return true
    }

    fun setFolder(uri: Uri) {
        viewModelScope.launch {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            whispr.settings.update { it.copy(recordingsFolderUri = uri.toString()) }
            _ui.value = _ui.value.copy(needsFolder = false)
        }
    }

    fun setTranscribeLive(on: Boolean) {
        _ui.value = _ui.value.copy(transcribeLive = on)
        viewModelScope.launch { whispr.settings.update { it.copy(recordWithTranscription = on) } }
    }

    fun start() {
        val s = runCatching {
            kotlinx.coroutines.runBlocking { whispr.settings.flow.first() }
        }.getOrNull() ?: return
        _ui.value = _ui.value.copy(segments = emptyList(), error = null)
        lastSegmentLastWord = ""
        RecordingService.start(
            context = getApplication(),
            transcribeLive = _ui.value.transcribeLive,
            source = s.audioSourcePreset,
            effectsOn = s.audioEffectsOn,
        )
    }

    fun stop() = RecordingService.stop(getApplication())

    private fun dedupeOverlap(segText: String): String {
        if (lastSegmentLastWord.isEmpty()) return segText
        val parts = segText.trim().split(WS)
        if (parts.isEmpty()) return segText
        return if (parts.first().equals(lastSegmentLastWord, ignoreCase = true)) {
            parts.drop(1).joinToString(" ")
        } else segText
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

    companion object { private val WS = Regex("\\s+") }
}
