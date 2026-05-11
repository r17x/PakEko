package id.local.transfermonitor.protocol

import id.local.transfermonitor.data.UserPattern

sealed class AppIntent {
    data class CaptureNotification(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val bigText: String,
        val subText: String,
        val postedAt: Long,
        val rawHash: String,
        val idempotencyKey: String,
    ) : AppIntent()

    data class ParseStoredEvent(val eventId: Long) : AppIntent()

    data class SendWebhook(
        val detectionId: Long,
        val allowPendingRetry: Boolean = false,
    ) : AppIntent()

    data object RetryWebhooks : AppIntent()

    data class AddMonitoredApp(
        val packageName: String,
        val appLabel: String,
    ) : AppIntent()

    data class RemoveMonitoredApp(val packageName: String) : AppIntent()

    data class ConfigureWebhook(val url: String) : AppIntent()

    data object ReparseAll : AppIntent()

    data object DeleteAll : AppIntent()

    data object RefreshData : AppIntent()

    data class SaveUserPattern(val pattern: UserPattern) : AppIntent()
    data class DeleteUserPattern(val patternId: Long) : AppIntent()
    data class ToggleUserPattern(val patternId: Long, val enabled: Boolean) : AppIntent()
}
