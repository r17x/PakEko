package id.local.transfermonitor.util

object MyBcaNotificationTemplates {
    private data class NotificationTemplate(
        val titlePattern: Regex,
        val bodyPattern: Regex,
    )

    private val incomingTransferTemplates = listOf(
        NotificationTemplate(
            titlePattern = Regex("""(?i)^\s*Financial Diary\s*$"""),
            bodyPattern = Regex(
                pattern = """(?is)^\s*You received\s+IDR\s+([0-9][0-9.,\s]*)\s+from\s+.+?\s+at\s+Account Transfer\s+category\.?\s*$""",
            ),
        ),
        NotificationTemplate(
            titlePattern = Regex("""(?i)^\s*Catatan Finansial\s*$"""),
            bodyPattern = Regex(
                pattern = """(?is)^\s*Pemasukan sebesar\s+IDR\s+([0-9][0-9.,\s]*)\s+dari\s+.+?\s+di kategori\s+Transfer Rekening\.?\s*$""",
            ),
        ),
    )

    private val outgoingTransferTemplates = listOf(
        NotificationTemplate(
            titlePattern = Regex("""(?i)^\s*Financial Diary\s*$"""),
            bodyPattern = Regex(
                pattern = """(?is)^\s*You sent\s+IDR\s+[0-9][0-9.,\s]*\s+.+$""",
            ),
        ),
        NotificationTemplate(
            titlePattern = Regex("""(?i)^\s*Catatan Finansial\s*$"""),
            bodyPattern = Regex(
                pattern = """(?is)^\s*Pengeluaran sebesar\s+IDR\s+[0-9][0-9.,\s]*\s+di kategori\s+.+?\.?\s*$""",
            ),
        ),
    )

    fun parse(
        title: String,
        text: String,
        bigText: String,
        subText: String,
    ): IdrParseResult? {
        val bodyCandidates = listOf(text, bigText, subText).filter { it.isNotBlank() }
        val match = incomingTransferTemplates.firstNotNullOfOrNull { template ->
            if (!template.titlePattern.matches(title)) return@firstNotNullOfOrNull null
            bodyCandidates.firstNotNullOfOrNull { body -> template.bodyPattern.matchEntire(body) }
        } ?: return null

        val amount = IdrAmountParser.parseAmount(match.groupValues[1]) ?: return null
        return IdrParseResult(amount = amount, confidence = 0.99)
    }

    fun isOutgoing(
        title: String,
        text: String,
        bigText: String,
        subText: String,
    ): Boolean {
        val bodyCandidates = listOf(text, bigText, subText).filter { it.isNotBlank() }
        return outgoingTransferTemplates.any { template ->
            template.titlePattern.matches(title) &&
                bodyCandidates.any { body -> template.bodyPattern.matches(body) }
        }
    }
}
