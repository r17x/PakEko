package id.local.transfermonitor.util

data class IdrParseResult(
    val amount: Long,
    val confidence: Double,
)

object IdrAmountParser {
    private val currencyPattern =
        Regex("""(?i)\b(?:rp|idr)\s*([0-9][0-9.,\s]{2,})""")

    fun parse(text: String): IdrParseResult? {
        val match = currencyPattern.find(text) ?: return null
        val amount = parseAmount(match.groupValues[1]) ?: return null
        return IdrParseResult(amount = amount, confidence = 0.95)
    }

    fun parseAmount(amountText: String): Long? {
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
