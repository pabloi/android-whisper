package dev.pabloi.whisper.engine.local

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.pabloi.whisper.MainActivity
import dev.pabloi.whisper.R
import dev.pabloi.whisper.WhisprApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Foreground service that runs `ModelRepository.download()` for the model id
 * passed in as an intent extra. Holds a partial wake lock and posts a sticky
 * progress notification so the download survives screen-off, Doze, and
 * Samsung Freecess.
 *
 * Per-model state is exposed as a singleton `Map<modelId, StateFlow<…>>` so
 * the UI can observe without binding. The service stops itself once download
 * completes or fails.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private var activeModelId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: ModelCatalog.default.id
        // If a download is already in flight, ignore — caller can observe its state.
        if (job?.isActive == true) return START_NOT_STICKY
        activeModelId = modelId
        markRunning(modelId, true)
        markError(modelId, null)

        val app = applicationContext as WhisprApp
        val repo = app.repoForId(modelId)

        startInForeground(buildNotification(repo.spec.displayName, "Starting download…", indeterminate = true))
        acquireWakeLock(modelId)

        job = scope.launch {
            try {
                repo.download().collect { p ->
                    progressFlowFor(modelId).value = p
                    val pct = (p.fraction.coerceIn(0f, 1f) * 100).toInt()
                    updateNotification(repo.spec.displayName, p.message, pct, indeterminate = pct == 0 && p.fraction == 0f)
                }
            } catch (t: Throwable) {
                markError(modelId, t.message ?: t::class.java.simpleName)
            } finally {
                markRunning(modelId, false)
                activeModelId = null
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        scope.cancel()
        releaseWakeLock()
        activeModelId?.let { markRunning(it, false) }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireWakeLock(modelId: String) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Whispr:download:$modelId").apply {
            setReferenceCounted(false)
            acquire(TimeUnit.HOURS.toMillis(2))
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(
        modelName: String,
        text: String,
        percent: Int = 0,
        indeterminate: Boolean = false,
    ): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Whispr — $modelName")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setProgress(if (indeterminate) 0 else 100, percent, indeterminate)
            .build()
    }

    private fun updateNotification(modelName: String, text: String, percent: Int, indeterminate: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(modelName, text, percent, indeterminate))
    }

    companion object {
        const val CHANNEL_ID = "whispr_download"
        const val EXTRA_MODEL_ID = "model_id"
        private const val NOTIF_ID = 1001

        // Per-model singleton flows. Created lazily as model ids are seen.
        private val progressFlows = mutableMapOf<String, MutableStateFlow<ModelRepository.Progress>>()
        private val runningFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()
        private val errorFlows = mutableMapOf<String, MutableStateFlow<String?>>()

        @Synchronized
        private fun progressFlowFor(modelId: String): MutableStateFlow<ModelRepository.Progress> =
            progressFlows.getOrPut(modelId) { MutableStateFlow(ModelRepository.Progress(0f, "Idle")) }

        @Synchronized
        private fun runningFlowFor(modelId: String): MutableStateFlow<Boolean> =
            runningFlows.getOrPut(modelId) { MutableStateFlow(false) }

        @Synchronized
        private fun errorFlowFor(modelId: String): MutableStateFlow<String?> =
            errorFlows.getOrPut(modelId) { MutableStateFlow(null) }

        fun progress(modelId: String): StateFlow<ModelRepository.Progress> = progressFlowFor(modelId).asStateFlow()
        fun running(modelId: String): StateFlow<Boolean> = runningFlowFor(modelId).asStateFlow()
        fun error(modelId: String): StateFlow<String?> = errorFlowFor(modelId).asStateFlow()

        fun isAnyRunning(): Boolean = runningFlows.values.any { it.value }

        private fun markRunning(modelId: String, value: Boolean) {
            runningFlowFor(modelId).value = value
        }

        private fun markError(modelId: String, value: String?) {
            errorFlowFor(modelId).value = value
        }

        fun start(context: Context, modelId: String) {
            if (runningFlowFor(modelId).value) return
            val intent = Intent(context, ModelDownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            context.startForegroundService(intent)
        }
    }
}
