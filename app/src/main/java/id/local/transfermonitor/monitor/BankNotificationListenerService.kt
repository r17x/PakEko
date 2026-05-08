package id.local.transfermonitor.monitor

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import id.local.transfermonitor.TransferMonitorApp
import id.local.transfermonitor.data.CapturedNotification
import id.local.transfermonitor.data.MonitorSettings
import id.local.transfermonitor.util.PaymentNotificationParser
import id.local.transfermonitor.util.WebhookDispatcher
import id.local.transfermonitor.util.notificationIdempotencyKey
import id.local.transfermonitor.util.sha256Hex
import java.util.concurrent.Executors

class BankNotificationListenerService : NotificationListenerService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return

        if (packageName !in MonitorSettings.SUPPORTED_BANK_PACKAGES) return

        val notification = sbn.notification ?: return
        val title = notification.readString(Notification.EXTRA_TITLE)
        val text = notification.readString(Notification.EXTRA_TEXT)
        val bigText = notification.readString(Notification.EXTRA_BIG_TEXT)
        val subText = notification.readString(Notification.EXTRA_SUB_TEXT)
        val lines = notification.readLines()
        val storedText = if (text.isBlank()) lines else text
        val parseDecision = PaymentNotificationParser.parse(
            packageName = packageName,
            title = title,
            text = storedText,
            bigText = bigText,
            subText = subText,
        )

        val rawHash = sha256Hex(
            listOf(packageName, title, text, bigText, subText, lines, sbn.postTime.toString())
                .joinToString(separator = "|")
        )
        val idempotencyKey = notificationIdempotencyKey(title = title, message = storedText)

        val captured = CapturedNotification(
            packageName = packageName,
            appLabel = packageName.appLabel(),
            title = title,
            text = storedText,
            bigText = bigText,
            subText = subText,
            postedAt = sbn.postTime,
            rawHash = rawHash,
            idempotencyKey = idempotencyKey,
            ignoredReason = parseDecision.ignoredReason,
            parsedAmount = parseDecision.parsedAmount,
            confidence = parseDecision.confidence,
            bankCode = parseDecision.bankCode,
        )

        executor.execute {
            val database = (application as TransferMonitorApp).database
            val result = database.insertCapturedNotification(captured)
            result.detection?.let { detection ->
                WebhookDispatcher.sendConfigured(applicationContext, database, detection)
            }
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
