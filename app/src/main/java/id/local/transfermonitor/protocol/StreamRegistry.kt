package id.local.transfermonitor.protocol

import id.local.transfermonitor.data.NotificationEvent
import id.local.transfermonitor.data.PaymentDetection
import id.local.transfermonitor.transport.StreamInfo
import org.json.JSONArray
import org.json.JSONObject

class StreamRegistry(private val runtime: AppRuntime) {

    data class StreamEntry(
        val address: StreamAddress,
        val mode: String,
        val description: String,
    )

    private val entries = listOf(
        StreamEntry(StreamAddress.EVENTS, "event", "Captured notification events"),
        StreamEntry(StreamAddress.DETECTIONS, "event", "Parsed payment detections"),
        StreamEntry(StreamAddress.MONITORED, "state", "Currently monitored app packages"),
        StreamEntry(StreamAddress.CONNECTIONS, "state", "Active transport client connections"),
        StreamEntry(StreamAddress.STREAMS, "state", "Stream catalog with metadata"),
    )

    fun catalog(): List<StreamInfo> =
        entries.map { entry ->
            StreamInfo(
                address = entry.address.address,
                mode = entry.mode,
                description = entry.description,
            )
        }

    fun snapshot(address: StreamAddress): JSONObject? =
        when (address) {
            StreamAddress.EVENTS -> {
                val events = runtime.recentEvents.value
                JSONObject().put("events", eventsToJsonArray(events))
            }
            StreamAddress.DETECTIONS -> {
                val detections = runtime.recentDetections.value
                JSONObject().put("detections", detectionsToJsonArray(detections))
            }
            StreamAddress.MONITORED -> {
                val packages = runtime.monitoredApps.value
                JSONObject().put("packages", JSONArray(packages.toList()))
            }
            StreamAddress.CONNECTIONS -> runtime.connectionInfo.value
            StreamAddress.STREAMS -> {
                val streamsArray = JSONArray()
                entries.forEach { entry ->
                    streamsArray.put(JSONObject().apply {
                        put("address", entry.address.address)
                        put("mode", entry.mode)
                        put("description", entry.description)
                    })
                }
                JSONObject().put("streams", streamsArray)
            }
            else -> null
        }

    fun isValidAddress(address: String): Boolean =
        StreamAddress.parse(address)?.let { parsed ->
            entries.any { it.address == parsed }
        } ?: false

    companion object {
        fun eventToJson(event: NotificationEvent): JSONObject =
            JSONObject().apply {
                put("id", event.id)
                put("package_name", event.packageName)
                put("app_label", event.appLabel)
                put("title", event.title)
                put("text", event.text)
                put("big_text", event.bigText)
                put("sub_text", event.subText)
                put("posted_at", event.postedAt)
                put("captured_at", event.capturedAt)
                put("idempotency_key", event.idempotencyKey)
                if (event.ignoredReason != null) put("ignored_reason", event.ignoredReason)
            }

        fun detectionToJson(detection: PaymentDetection): JSONObject =
            JSONObject().apply {
                put("id", detection.id)
                put("idempotency_key", detection.idempotencyKey)
                put("bank_code", detection.bankCode)
                put("amount", detection.amount)
                put("currency", detection.currency)
                put("confidence", detection.confidence)
                put("parser_version", detection.parserVersion)
                put("status", detection.status)
                put("created_at", detection.createdAt)
                if (detection.webhookStatus != null) put("webhook_status", detection.webhookStatus)
                if (detection.webhookStatusCode != null) put("webhook_status_code", detection.webhookStatusCode)
            }

        fun eventsToJsonArray(events: List<NotificationEvent>): JSONArray =
            JSONArray().apply { events.forEach { put(eventToJson(it)) } }

        fun detectionsToJsonArray(detections: List<PaymentDetection>): JSONArray =
            JSONArray().apply { detections.forEach { put(detectionToJson(it)) } }
    }
}
