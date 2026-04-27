package dev.pabloi.whisper.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class EngineChoice { LOCAL, REMOTE }

enum class AudioSourcePreset { MIC, VOICE_RECOGNITION, CAMCORDER }

data class AppSettings(
    val engine: EngineChoice = EngineChoice.LOCAL,
    val remoteBaseUrl: String = "",
    val remoteApiKey: String = "",
    val remoteModelName: String = "whisper-1",
    val language: String = "",          // empty = auto-detect
    val timestamps: Boolean = false,
    /** Active local-engine model id (matches a [dev.pabloi.whisper.engine.local.ModelSpec.id]). */
    val localModelId: String = "whisper_large_v3_turbo",
    val recordingsFolderUri: String = "",
    val recordWithTranscription: Boolean = true,
    val audioSourcePreset: AudioSourcePreset = AudioSourcePreset.VOICE_RECOGNITION,
    val audioEffectsOn: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "whispr_settings")

class SettingsStore(private val context: Context) {
    private val engineKey = stringPreferencesKey("engine")
    private val urlKey = stringPreferencesKey("remote_url")
    private val apiKey = stringPreferencesKey("remote_api_key")
    private val modelKey = stringPreferencesKey("remote_model")
    private val langKey = stringPreferencesKey("language")
    private val tsKey = booleanPreferencesKey("timestamps")
    private val localModelKey = stringPreferencesKey("local_model_id")
    private val recFolderKey = stringPreferencesKey("recordings_folder_uri")
    private val recTranscribeKey = booleanPreferencesKey("record_with_transcription")
    private val audioSourceKey = stringPreferencesKey("audio_source_preset")
    private val audioEffectsKey = booleanPreferencesKey("audio_effects_on")

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs -> prefs.toSettings() }

    suspend fun update(updater: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs.toSettings()
            val next = updater(current)
            prefs[engineKey] = next.engine.name
            prefs[urlKey] = next.remoteBaseUrl
            prefs[apiKey] = next.remoteApiKey
            prefs[modelKey] = next.remoteModelName
            prefs[langKey] = next.language
            prefs[tsKey] = next.timestamps
            prefs[localModelKey] = next.localModelId
            prefs[recFolderKey] = next.recordingsFolderUri
            prefs[recTranscribeKey] = next.recordWithTranscription
            prefs[audioSourceKey] = next.audioSourcePreset.name
            prefs[audioEffectsKey] = next.audioEffectsOn
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        engine = this[engineKey]?.let { runCatching { EngineChoice.valueOf(it) }.getOrNull() } ?: EngineChoice.LOCAL,
        remoteBaseUrl = this[urlKey].orEmpty(),
        remoteApiKey = this[apiKey].orEmpty(),
        remoteModelName = this[modelKey].orEmpty().ifEmpty { "whisper-1" },
        language = this[langKey].orEmpty(),
        timestamps = this[tsKey] == true,
        localModelId = this[localModelKey].orEmpty().ifEmpty { "whisper_large_v3_turbo" },
        recordingsFolderUri = this[recFolderKey].orEmpty(),
        recordWithTranscription = this[recTranscribeKey] ?: true,
        audioSourcePreset = this[audioSourceKey]
            ?.let { runCatching { AudioSourcePreset.valueOf(it) }.getOrNull() }
            ?: AudioSourcePreset.VOICE_RECOGNITION,
        audioEffectsOn = this[audioEffectsKey] ?: true,
    )
}
