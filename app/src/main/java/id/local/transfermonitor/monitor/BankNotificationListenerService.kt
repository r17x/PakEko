package id.local.transfermonitor.monitor

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import id.local.transfermonitor.TransferMonitorApp
import id.local.transfermonitor.protocol.AppIntent
import id.local.transfermonitor.util.notificationIdempotencyKey
import id.local.transfermonitor.util.sha256Hex
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class BankNotificationListenerService : NotificationListenerService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val appLabelCache = ConcurrentHashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        val app = (application as? TransferMonitorApp) ?: return
        if (packageName !in app.runtime.monitoredApps.value) return

        val notification = sbn.notification ?: return
        val title = notification.readString(Notification.EXTRA_TITLE)
        val text = notification.readString(Notification.EXTRA_TEXT)
        val bigText = notification.readString(Notification.EXTRA_BIG_TEXT)
        val subText = notification.readString(Notification.EXTRA_SUB_TEXT)
        val lines = notification.readLines()
        val storedText = if (text.isBlank()) lines else text
        val rawHash = sha256Hex(
            listOf(packageName, title, text, bigText, subText, lines, sbn.postTime.toString())
                .joinToString(separator = "|")
        )
        val idempotencyKey = notificationIdempotencyKey(title = title, message = storedText)
        val postedAt = sbn.postTime

        executor.execute {
            app.runtime.send(
                AppIntent.CaptureNotification(
                    packageName = packageName,
                    appLabel = packageName.appLabel(),
                    title = title,
                    text = storedText,
                    bigText = bigText,
                    subText = subText,
                    postedAt = postedAt,
                    rawHash = rawHash,
                    idempotencyKey = idempotencyKey,
                )
            )
        }
    }

    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, BankNotificationListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun Notification.readString(key: String): String =
        extras.getCharSequence(key)?.toString().orEmpty()

    private fun Notification.readLines(): String =
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(separator = "\n") { it.toString() }
            .orEmpty()

    private fun String.appLabel(): String =
        appLabelCache.getOrPut(this) {
            try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(
                        this,
                        PackageManager.ApplicationInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(this, 0)
                }
                packageManager.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                this
            }
        }

}
