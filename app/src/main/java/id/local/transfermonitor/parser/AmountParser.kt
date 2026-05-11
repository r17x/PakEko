package id.local.transfermonitor.parser

object AmountParser {
    fun parse(text: String): AmountParseResult? {
        var pos = 0
        while (pos < text.length) {
            val prefixEnd = matchCurrencyPrefix(text, pos, requireWordBoundary = true)
            if (prefixEnd != null) {
                var amountStart = prefixEnd
                while (amountStart < text.length && text[amountStart].isWhitespace()) amountStart++

                val amountResult = consumeAmountDigits(text, amountStart)
                if (amountResult != null) {
                    val amount = normalizeAmount(amountResult.first)
                    if (amount != null) {
                        return AmountParseResult(amount = amount, confidence = 0.95)
                    }
                }
            }
            pos++
        }
        return null
    }

    fun parseAmount(amountText: String): Long? = normalizeAmount(amountText)

    private fun normalizeAmount(amountText: String): Long? {
        val compact = amountText.trim()
            .replace(" ", "")
            .replace("\u00A0", "")
            .trimEnd('.', ',')

        if (compact.isBlank()) return null

        val separatorPositions = compact
            .mapIndexedNotNull { index, char ->
                if (char == '.' || char == ',') index else null
            }

        val integerPart = when {
            separatorPositions.isEmpty() -> compact
            else -> {
                val lastSeparator = separatorPositions.last()
                val trailingDigits = compact
                    .substring(lastSeparator + 1)
                    .count { it.isDigit() }

                if (trailingDigits in 1..2) {
                    compact.substring(0, lastSeparator)
                } else {
                    compact
                }
            }
        }

        return integerPart
            .filter { it.isDigit() }
            .takeIf { it.isNotBlank() }
            ?.toLongOrNull()
    }
}
