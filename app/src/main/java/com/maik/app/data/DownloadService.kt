package com.maik.app.data

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
import com.maik.app.*
import com.maik.app.R
import com.maik.app.engine.*
import com.maik.app.ui.chat.*
import com.maik.app.ui.components.*
import com.maik.app.ui.list.*
import com.maik.app.ui.settings.*
import com.maik.app.ui.setup.*
import com.maik.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Progress from the download service to whoever is watching.
 *
 * A plain singleton rather than a bound service: the download outlives the UI, and
 * the UI needs to be able to attach to it at any point without a connection dance.
 */
object DownloadBus {
    val running = MutableStateFlow(false)

    /** Which model is downloading, so a recreated screen can show the right one. */
    val modelId = MutableStateFlow<String?>(null)

    /** Latest progress. State, because a screen arriving late should see it. */
    val progress = MutableStateFlow<Download.Progress?>(null)

    /**
     * Finished and failed downloads. Events, not state: replaying a stale "done" to
     * every new screen is what used to start a second, duplicate model load.
     */
    val events = MutableSharedFlow<Download>(extraBufferCapacity = 8)
}

/**
 * Downloads the model as a foreground service, so it survives the screen locking,
 * the app being backgrounded, and the process being trimmed for memory. A 2.5 GB
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
        val requested = intent?.getStringExtra(EXTRA_MODEL_ID)
        if (requested == null || Models.ALL.none { it.id == requested }) {
            if (job?.isActive != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (job?.isActive == true) {
            // One download at a time; say so instead of silently ignoring the second.
            if (requested != DownloadBus.modelId.value) {
                DownloadBus.events.tryEmit(Download.Failed(requested, getString(R.string.download_another_running)))
            }
            return START_NOT_STICKY
        }

        val store = ModelStore(applicationContext)
        val spec = Models.byId(requested)

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(spec.label, 0, 0, indeterminate = true))
        DownloadBus.modelId.value = spec.id
        DownloadBus.running.value = true

        job = scope.launch {
            store.download(spec).collect { event ->
                when (event) {
                    is Download.Progress -> {
                        DownloadBus.progress.value = event
                        notify(buildNotification(spec.label, event.bytes, event.total, false))
                    }

                    is Download.Done, is Download.Failed -> {
                        DownloadBus.running.value = false
                        DownloadBus.progress.value = null
                        DownloadBus.events.tryEmit(event)
                        stopSelf()
                    }
                }
            }
        }
        // If the system kills the process mid-download, it restarts the service with
        // this same intent — the same model — and the download resumes.
        return START_REDELIVER_INTENT
    }

    private fun stopEverything() {
        job?.cancel()
        DownloadBus.running.value = false
        DownloadBus.progress.value = null
        DownloadBus.events.tryEmit(Download.Failed(DownloadBus.modelId.value ?: "", getString(R.string.download_cancelled), cancelled = true))
        stopSelf()
    }

    /** Android 15 caps dataSync services; stop cleanly and let the user continue later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        DownloadBus.running.value = false
        DownloadBus.progress.value = null
        DownloadBus.events.tryEmit(
            Download.Failed(DownloadBus.modelId.value ?: "", getString(R.string.download_android_paused))
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
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
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.download_channel_detail) }
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
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOAD),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mb = if (total > 0) {
            getString(R.string.download_notification_progress, bytes / 1024 / 1024, total / 1024 / 1024)
        } else {
            getString(R.string.download_notification_starting)
        }
        val percent = if (total > 0) ((bytes * 100) / total).toInt() else 0

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.download_notification_title, label))
            .setContentText(mb)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, percent, indeterminate)
            .addAction(0, getString(R.string.download_notification_cancel), cancel)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 42
        const val ACTION_CANCEL = "com.maik.app.CANCEL_DOWNLOAD"
        private const val EXTRA_MODEL_ID = "model_id"

        fun start(context: Context, modelId: String) {
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_MODEL_ID, modelId)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException.
                DownloadBus.events.tryEmit(
                    Download.Failed(modelId, context.getString(R.string.download_not_allowed_now))
                )
            }
        }
    }
}
