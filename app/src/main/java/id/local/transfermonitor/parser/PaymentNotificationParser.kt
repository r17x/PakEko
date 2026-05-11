package id.local.transfermonitor.parser

import id.local.transfermonitor.data.MatchField
import id.local.transfermonitor.data.PatternDirection
import id.local.transfermonitor.data.UserPattern

data class PaymentParseDecision(
    val bankCode: String,
    val parsedAmount: Long?,
    val confidence: Double,
    val ignoredReason: String?,
) {
    companion object {
        fun notMonitored(packageName: String) = PaymentParseDecision(
            bankCode = packageName,
            parsedAmount = null,
            confidence = 0.0,
            ignoredReason = "not_monitored",
        )
    }
}

object PaymentNotificationParser {
    fun parse(
        packageName: String,
        title: String,
        text: String,
        bigText: String,
        subText: String,
        userPatterns: List<UserPattern> = emptyList(),
    ): PaymentParseDecision {
        val combinedText = listOf(title, text, bigText, subText)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n")

        if (looksSensitive(combinedText)) {
            return PaymentParseDecision(
                bankCode = packageName,
                parsedAmount = null,
                confidence = 0.0,
                ignoredReason = "sensitive_notification",
            )
        }

        val packagePatterns = userPatterns.filter { it.packageName == packageName && it.enabled }

        if (findFirstMatch(packagePatterns, PatternDirection.OUTGOING, title, text, bigText, subText) != null) {
            return PaymentParseDecision(
                bankCode = packageName,
                parsedAmount = null,
                confidence = 0.0,
                ignoredReason = "outgoing_transaction",
            )
        }

        val userResult = parseUserPattern(packagePatterns, title, text, bigText, subText)
        if (userResult != null) {
            return PaymentParseDecision(
                bankCode = packageName,
                parsedAmount = userResult.amount,
                confidence = userResult.confidence,
                ignoredReason = null,
            )
        }

        val parseResult = AmountParser.parse(combinedText)
        return PaymentParseDecision(
            bankCode = packageName,
            parsedAmount = parseResult?.amount,
            confidence = parseResult?.confidence?.coerceAtMost(0.70) ?: 0.0,
            ignoredReason = if (parseResult == null) "no_idr_amount" else null,
        )
    }

    private fun findMatchingBody(
        pattern: UserPattern,
        candidates: List<String>,
    ): Map<String, String>? {
        val segments = SegmentSerializer.fromJson(pattern.segmentsJson) ?: return null
        for (body in candidates) {
            val captures = SegmentParser.parse(segments, body)
            if (captures != null) return captures
        }
        return null
    }

    private fun findFirstMatch(
        patterns: List<UserPattern>,
        direction: PatternDirection,
        title: String,
        text: String,
        bigText: String,
        subText: String,
    ): Pair<UserPattern, Map<String, String>>? {
        val filtered = patterns.filter { it.direction == direction }
        for (pattern in filtered) {
            if (!matchesTitle(pattern.titleText, title)) continue
            val candidates = bodyCandidates(pattern.matchField, title, text, bigText, subText)
            val captures = findMatchingBody(pattern, candidates) ?: continue
            return pattern to captures
        }
        return null
    }

    private fun parseUserPattern(
        patterns: List<UserPattern>,
        title: String,
        text: String,
        bigText: String,
        subText: String,
    ): AmountParseResult? {
        val (pattern, captures) = findFirstMatch(patterns, PatternDirection.INCOMING, title, text, bigText, subText) ?: return null
        val amountText = captures["amount"] ?: return null
        val amount = AmountParser.parseAmount(amountText) ?: return null
        return AmountParseResult(amount = amount, confidence = pattern.confidence)
    }

    private fun matchesTitle(titleText: String?, title: String): Boolean {
        if (titleText == null) return true
        return titleText.trim().equals(title.trim(), ignoreCase = true)
    }

    private fun bodyCandidates(
        matchField: MatchField,
        title: String,
        text: String,
        bigText: String,
        subText: String,
    ): List<String> = when (matchField) {
        MatchField.TITLE -> listOf(title)
        MatchField.COMBINED -> listOf(
            listOf(title, text, bigText, subText)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        )
        MatchField.BODY -> listOf(text, bigText, subText).filter { it.isNotBlank() }
    }

    private fun looksSensitive(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("otp", "kode rahasia", "password", "pin", "login").any { it in lower }
    }
}
