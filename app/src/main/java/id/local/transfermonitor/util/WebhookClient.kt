package id.local.transfermonitor.util

import id.local.transfermonitor.data.PaymentDetection
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class WebhookSendResult(
    val sent: Boolean,
    val statusCode: Int?,
    val message: String,
)

object WebhookClient {
    fun sendDetection(webhookUrl: String, detection: PaymentDetection): WebhookSendResult {
        val cleanUrl = webhookUrl.trim()
        if (cleanUrl.isBlank()) {
            return WebhookSendResult(
                sent = false,
                statusCode = null,
                message = "No webhook URL configured",
            )
        }

        return try {
            val payload = detection.toWebhookPayload().toString()
            val connection = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Idempotency-Key", detection.idempotencyKey)
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }

            val code = connection.responseCode
            connection.disconnect()

            WebhookSendResult(
                sent = code in 200..299,
                statusCode = code,
                message = "HTTP $code",
            )
        } catch (error: Exception) {
            WebhookSendResult(
                sent = false,
                statusCode = null,
                message = error.message ?: error::class.java.simpleName,
            )
        }
    }

    fun payloadForDetection(detection: PaymentDetection): JSONObject =
        detection.toWebhookPayload()

    private fun PaymentDetection.toWebhookPayload(): JSONObject =
        JSONObject()
            .put("event", "payment.detected")
            .put("idempotency_key", idempotencyKey)
            .put("detection_id", id)
            .put("notification_event_id", notificationEventId)
            .put("bank", bankCode)
            .put("amount", amount)
            .put("currency", currency)
            .put("confidence", confidence)
            .put("parser_version", parserVersion)
            .put("status", status)
            .put("detected_at", Instant.ofEpochMilli(createdAt).toString())
}
