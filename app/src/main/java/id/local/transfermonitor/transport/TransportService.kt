package id.local.transfermonitor.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import id.local.transfermonitor.MainActivity
import id.local.transfermonitor.TransferMonitorApp
import id.local.transfermonitor.data.TransportSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TransportService : Service() {

    private val server: PondServer get() = (application as TransferMonitorApp).pondServer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transport Server",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val settings = TransportSettings(this)
                val port = settings.port()
                val bindAll = settings.bindAll()
                server.start(port, bindAll)

                val notification = buildNotification(port, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                notificationJob?.cancel()
                notificationJob = serviceScope.launch {
                    server.connections.collectLatest { info ->
                        val count = info.optJSONArray("clients")?.length() ?: 0
                        val updated = buildNotification(port, count)
                        val manager = getSystemService(NotificationManager::class.java)
                        manager.notify(NOTIFICATION_ID, updated)
                    }
                }
            }
            ACTION_STOP -> {
                server.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server.stop()
        notificationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(port: Int, clientCount: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PakEko Server")
            .setContentText("Port $port · $clientCount client(s)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "id.local.transfermonitor.action.START_TRANSPORT"
        const val ACTION_STOP = "id.local.transfermonitor.action.STOP_TRANSPORT"
        private const val CHANNEL_ID = "transport_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
