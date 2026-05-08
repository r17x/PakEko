package id.local.transfermonitor.util

import android.content.Context
import id.local.transfermonitor.data.PaymentDetection
import id.local.transfermonitor.data.TransferDatabase
import id.local.transfermonitor.data.WebhookRetryResult
import id.local.transfermonitor.data.WebhookSettings

object WebhookDispatcher {
    fun sendConfigured(
        context: Context,
        database: TransferDatabase,
        detection: PaymentDetection,
        allowPendingRetry: Boolean = false,
    ): WebhookSendResult {
        val webhookUrl = WebhookSettings(context).url()
        val delivery = database.createWebhookDelivery(
            detection = detection,
            webhookUrl = webhookUrl,
            allowPendingRetry = allowPendingRetry,
        )
            ?: return WebhookSendResult(
                sent = false,
                statusCode = null,
                message = "Delivery already recorded",
            )

        if (webhookUrl.isBlank()) {
            return WebhookSendResult(
                sent = false,
                statusCode = null,
                message = "No webhook URL configured",
            )
        }

        val result = WebhookClient.sendDetection(webhookUrl, detection)
        database.updateWebhookDelivery(delivery, result)
        return result
    }

    fun retryRecordedDeliveries(
        context: Context,
        database: TransferDatabase,
    ): WebhookRetryResult {
        if (WebhookSettings(context).url().isBlank()) {
            return WebhookRetryResult(
                attemptedCount = 0,
                sentCount = 0,
                failedCount = 0,
            )
        }

        var attempted = 0
        var sent = 0
        var failed = 0

        database.retryableWebhookDetections().forEach { detection ->
            val result = sendConfigured(
                context = context,
                database = database,
                detection = detection,
                allowPendingRetry = true,
            )
            attempted += 1
            if (result.sent) {
                sent += 1
            } else {
                failed += 1
            }
        }

        return WebhookRetryResult(
            attemptedCount = attempted,
            sentCount = sent,
            failedCount = failed,
        )
    }
}
