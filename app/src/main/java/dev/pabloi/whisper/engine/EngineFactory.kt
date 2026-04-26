package dev.pabloi.whisper.engine

import android.content.Context
import dev.pabloi.whisper.WhisprApp
import dev.pabloi.whisper.data.AppSettings
import dev.pabloi.whisper.data.EngineChoice
import dev.pabloi.whisper.engine.local.LocalQnnWhisperEngine
import dev.pabloi.whisper.engine.remote.RemoteOpenAIEngine

/**
 * Picks the right engine for a given settings snapshot. For LOCAL we look up
 * the per-model [dev.pabloi.whisper.engine.local.ModelRepository] from the
 * [WhisprApp] map keyed by `settings.localModelId`. Engines are cheap to
 * construct (sessions are created lazily on first `transcribe`), so we just
 * build a new one per request; ViewModel may cache if needed.
 */
object EngineFactory {
    fun create(
        context: Context,
        settings: AppSettings,
    ): TranscriptionEngine = when (settings.engine) {
        EngineChoice.LOCAL -> {
            val app = context.applicationContext as WhisprApp
            val repo = app.repoForId(settings.localModelId)
            LocalQnnWhisperEngine(context, repo)
        }
        EngineChoice.REMOTE -> RemoteOpenAIEngine(
            context = context,
            baseUrl = settings.remoteBaseUrl,
            apiKey = settings.remoteApiKey.ifBlank { null },
            modelName = settings.remoteModelName,
        )
    }
}
