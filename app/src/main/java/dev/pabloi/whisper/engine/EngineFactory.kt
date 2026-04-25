package dev.pabloi.whisper.engine

import android.content.Context
import dev.pabloi.whisper.data.AppSettings
import dev.pabloi.whisper.data.EngineChoice
import dev.pabloi.whisper.engine.local.LocalQnnWhisperEngine
import dev.pabloi.whisper.engine.local.ModelRepository
import dev.pabloi.whisper.engine.remote.RemoteOpenAIEngine

/**
 * Picks the right engine for a given settings snapshot. Engines are cheap
 * to construct (sessions are created lazily on first `transcribe`), so we
 * just build a new one per request; ViewModel may cache if needed.
 */
object EngineFactory {
    fun create(
        context: Context,
        settings: AppSettings,
        modelRepo: ModelRepository,
    ): TranscriptionEngine = when (settings.engine) {
        EngineChoice.LOCAL -> LocalQnnWhisperEngine(context, modelRepo)
        EngineChoice.REMOTE -> RemoteOpenAIEngine(
            context = context,
            baseUrl = settings.remoteBaseUrl,
            apiKey = settings.remoteApiKey.ifBlank { null },
            modelName = settings.remoteModelName,
        )
    }
}
