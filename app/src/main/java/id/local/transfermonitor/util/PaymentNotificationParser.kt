package id.local.transfermonitor.util

import id.local.transfermonitor.data.MonitorSettings

data class PaymentParseDecision(
    val bankCode: String,
    val parsedAmount: Long?,
    val confidence: Double,
    val ignoredReason: String?,
)

object PaymentNotificationParser {
    fun parse(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
    ): PaymentParseDecision {
        if (packageName !in MonitorSettings.SUPPORTED_BANK_PACKAGES) {
            return PaymentParseDecision(
                bankCode = packageName.toBankCode(),
                parsedAmount = null,
                confidence = 0.0,
                ignoredReason = "unsupported_package",
            )
        }

        val combinedText = listOf(title, text, bigText, subText)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")

        if (looksSensitive(combinedText)) {
            return PaymentParseDecision(
                bankCode = packageName.toBankCode(),
                parsedAmount = null,
                confidence = 0.0,
                ignoredReason = "sensitive_notification",
            )
        }

        if (isKnownOutgoing(packageName, title, text, bigText, subText)) {
            return PaymentParseDecision(
                bankCode = packageName.toBankCode(),
                parsedAmount = null,
                confidence = 0.0,
                ignoredReason = "outgoing_transaction",
            )
        }

        val templateResult = parseKnownTemplate(
            packageName = packageName,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
        )
        if (templateResult != null) {
            return PaymentParseDecision(
                bankCode = packageName.toBankCode(),
                parsedAmount = templateResult.amount,
                confidence = templateResult.confidence,
                ignoredReason = null,
            )
        }

        val parseResult = IdrAmountParser.parse(combinedText)
        return PaymentParseDecision(
            bankCode = packageName.toBankCode(),
            parsedAmount = parseResult?.amount,
            confidence = parseResult?.confidence?.coerceAtMost(0.70) ?: 0.0,
            ignoredReason = if (parseResult == null) "no_idr_amount" else null,
        )
    }

    private fun parseKnownTemplate(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
    ): IdrParseResult? =
        when (packageName) {
            "com.bca.mybca.omni.android" -> MyBcaNotificationTemplates.parse(
                title = title,
                text = text,
                bigText = bigText,
                subText = subText,
            )
            else -> null
        }

    private fun isKnownOutgoing(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
    ): Boolean =
        when (packageName) {
            "com.bca.mybca.omni.android" -> MyBcaNotificationTemplates.isOutgoing(
                title = title,
                text = text,
                bigText = bigText,
                subText = subText,
            )
            else -> false
        }

    private fun String.toBankCode(): String =
        when (this) {
            "com.bca.mybca.omni.android" -> "bca"
            else -> this
        }

    private fun looksSensitive(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("otp", "kode rahasia", "password", "pin", "login").any { it in lower }
    }
}
