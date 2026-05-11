package id.local.transfermonitor.data

data class NotificationEvent(
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val postedAt: Long,
    val capturedAt: Long,
    val rawHash: String,
    val idempotencyKey: String,
    val ignoredReason: String?,
)

fun NotificationEvent.bodyPreview(): String =
    listOf(text, bigText, subText)
        .filter { it.isNotBlank() }
        .joinToString("\n")

data class PaymentDetection(
    val id: Long,
    val notificationEventId: Long,
    val idempotencyKey: String,
    val bankCode: String,
    val amount: Long,
    val currency: String,
    val confidence: Double,
    val parserVersion: String,
    val status: String,
    val createdAt: Long,
    val webhookStatus: String?,
    val webhookStatusCode: Int?,
    val webhookMessage: String?,
    val webhookAttemptCount: Int?,
)

data class CapturedNotification(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val postedAt: Long,
    val rawHash: String,
    val idempotencyKey: String,
    val ignoredReason: String?,
    val parsedAmount: Long?,
    val confidence: Double,
    val bankCode: String,
)

data class CaptureInsertResult(
    val eventId: Long,
    val detection: PaymentDetection?,
    val duplicate: Boolean,
)

data class WebhookDelivery(
    val id: Long,
    val paymentDetectionId: Long,
    val idempotencyKey: String,
    val webhookUrl: String,
    val status: String,
    val httpStatusCode: Int?,
    val message: String,
    val attemptCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SingleParseResult(
    val eventId: Long,
    val detection: PaymentDetection?,
    val ignoredReason: String?,
)

data class ReparseResult(
    val eventCount: Int,
    val detectionCount: Int,
    val ignoredCount: Int,
)

data class WebhookRetryResult(
    val attemptedCount: Int,
    val sentCount: Int,
    val failedCount: Int,
)

data class MonitoredApp(
    val packageName: String,
    val appLabel: String,
    val addedAt: Long,
)

enum class PatternDirection { INCOMING, OUTGOING }
enum class MatchField { TITLE, BODY, COMBINED }

data class UserPattern(
    val id: Long,
    val packageName: String,
    val label: String,
    val direction: PatternDirection,
    val matchField: MatchField,
    val titleText: String?,
    val segmentsJson: String,
    val confidence: Double,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class SegmentRole { LITERAL, AMOUNT, SENDER, WILDCARD }

data class PatternSegment(
    val text: String,
    val role: SegmentRole,
)

const val CURRENT_PARSER_VERSION = "idr-v2"
