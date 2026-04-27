package dev.pabloi.whisper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.pabloi.whisper.data.SettingsStore
import dev.pabloi.whisper.engine.local.ModelCatalog
import dev.pabloi.whisper.engine.local.ModelDownloadService
import dev.pabloi.whisper.engine.local.ModelRepository
import dev.pabloi.whisper.engine.local.ModelSpec
import java.io.File

class WhisprApp : Application() {
    lateinit var settings: SettingsStore
        private set

    /** One [ModelRepository] per [ModelSpec]; shares a single OkHttp client / disk root. */
    lateinit var modelRepos: Map<String, ModelRepository>
        private set

    /** The repo for the legacy default (Whisper Large v3 Turbo). Kept for callers that haven't
     *  yet migrated to per-spec lookup. */
    val modelRepo: ModelRepository get() = modelRepos.getValue(ModelCatalog.default.id)

    fun repoFor(spec: ModelSpec): ModelRepository = modelRepos.getValue(spec.id)
    fun repoForId(id: String): ModelRepository = modelRepos[id] ?: modelRepo

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        migrateLegacyModelDir()
        modelRepos = ModelCatalog.specs.associate { it.id to ModelRepository(this, it) }
        registerNotificationChannels()
    }

    /** Earlier builds wrote large-v3-turbo assets into `models/whisper_large_v3_turbo_qnn/`.
     *  Per-model layout uses `models/whisper_large_v3_turbo/` (matching spec.id). Move once. */
    private fun migrateLegacyModelDir() {
        val old = File(filesDir, "models/whisper_large_v3_turbo_qnn")
        val new = File(filesDir, "models/whisper_large_v3_turbo")
        if (old.exists() && !new.exists()) old.renameTo(new)
    }

    private fun registerNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            ModelDownloadService.CHANNEL_ID,
            "Model download",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress while a Whisper model downloads."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(NotificationChannel(
            dev.pabloi.whisper.recording.RecordingService.CHANNEL_ID,
            "Live recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Whispr is recording audio."
            setShowBadge(false)
        })
    }
}
