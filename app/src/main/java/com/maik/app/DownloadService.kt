package com.maik.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Progress from the download service to whoever is watching.
 *
 * A plain singleton rather than a bound service: the download outlives the UI, and
 * the UI needs to be able to attach to it at any point without a connection dance.
 */
object DownloadBus {
    val state = MutableStateFlow<Download?>(null)
    val running = MutableStateFlow(false)
}

/**
 * Downloads the model as a foreground service, so it survives the screen locking,
 * the app being backgrounded, and the process being trimmed for memory. A 1.5 GB
 * fetch is far too long to hang off an Activity's lifecycle.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_STICKY

        val store = ModelStore(applicationContext)
        val spec = Models.byId(intent?.getStringExtra(EXTRA_MODEL_ID) ?: store.spec.id)

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(spec.label, 0, 0, indeterminate = true))
        DownloadBus.running.value = true

        job = scope.launch {
            store.download(spec).collect { event ->
                DownloadBus.state.value = event
                when (event) {
                    is Download.Progress -> notify(
                        buildNotification(spec.label, event.bytes, event.total, false)
                    )

                    is Download.Done, is Download.Failed -> {
                        DownloadBus.running.value = false
                        stopSelf()
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun stopEverything() {
        job?.cancel()
        DownloadBus.running.value = false
        DownloadBus.state.value = Download.Failed("Cancelled")
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadBus.running.value = false
        scope.cancel()
    }

    /* ---------- notification ---------- */

    private fun manager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model download",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows progress while maik fetches its model." }
        manager().createNotificationChannel(channel)
    }

    private fun notify(notification: Notification) {
        // Silently ignored if the user denied notifications; the download continues.
        runCatching { manager().notify(NOTIFICATION_ID, notification) }
    }

    private fun buildNotification(
        label: String,
        bytes: Long,
        total: Long,
        indeterminate: Boolean
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mb = if (total > 0) "${bytes / 1024 / 1024} / ${total / 1024 / 1024} MB" else "Starting…"
        val percent = if (total > 0) ((bytes * 100) / total).toInt() else 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading $label")
            .setContentText(mb)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, percent, indeterminate)
            .addAction(0, "Cancel", cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 42
        const val ACTION_CANCEL = "com.maik.app.CANCEL_DOWNLOAD"
        private const val EXTRA_MODEL_ID = "model_id"

        fun start(context: Context, modelId: String? = null) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
