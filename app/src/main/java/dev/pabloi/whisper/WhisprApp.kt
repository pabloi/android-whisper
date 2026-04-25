package dev.pabloi.whisper

import android.app.Application
import dev.pabloi.whisper.data.SettingsStore
import dev.pabloi.whisper.engine.local.ModelRepository

class WhisprApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var modelRepo: ModelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        modelRepo = ModelRepository(this)
    }
}
