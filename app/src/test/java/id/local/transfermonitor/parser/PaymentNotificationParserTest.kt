package id.local.transfermonitor.parser

import id.local.transfermonitor.data.MatchField
import id.local.transfermonitor.data.PatternDirection
import id.local.transfermonitor.data.UserPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentNotificationParserTest {

    private val bcaPatterns = listOf(
        UserPattern(id = 1, packageName = "com.bca.mybca.omni.android", label = "BCA incoming (EN)", direction = PatternDirection.INCOMING, matchField = MatchField.BODY, titleText = "Financial Diary", segmentsJson = """[{"text":"You received","role":"LITERAL"},{"text":"","role":"AMOUNT"},{"text":"from","role":"LITERAL"},{"text":"","role":"SENDER"},{"text":"at Account Transfer category.","role":"LITERAL"}]""", confidence = 0.99, enabled = true, createdAt = 0, updatedAt = 0),
        UserPattern(id = 2, packageName = "com.bca.mybca.omni.android", label = "BCA incoming (ID)", direction = PatternDirection.INCOMING, matchField = MatchField.BODY, titleText = "Catatan Finansial", segmentsJson = """[{"text":"Pemasukan sebesar","role":"LITERAL"},{"text":"","role":"AMOUNT"},{"text":"dari","role":"LITERAL"},{"text":"","role":"SENDER"},{"text":"di kategori Transfer Rekening.","role":"LITERAL"}]""", confidence = 0.99, enabled = true, createdAt = 0, updatedAt = 0),
        UserPattern(id = 3, packageName = "com.bca.mybca.omni.android", label = "BCA outgoing (EN)", direction = PatternDirection.OUTGOING, matchField = MatchField.BODY, titleText = "Financial Diary", segmentsJson = """[{"text":"You sent","role":"LITERAL"},{"text":"","role":"AMOUNT"},{"text":"","role":"WILDCARD"}]""", confidence = 0.99, enabled = true, createdAt = 0, updatedAt = 0),
        UserPattern(id = 4, packageName = "com.bca.mybca.omni.android", label = "BCA outgoing (ID)", direction = PatternDirection.OUTGOING, matchField = MatchField.BODY, titleText = "Catatan Finansial", segmentsJson = """[{"text":"Pengeluaran sebesar","role":"LITERAL"},{"text":"","role":"AMOUNT"},{"text":"di kategori","role":"LITERAL"},{"text":"","role":"WILDCARD"}]""", confidence = 0.99, enabled = true, createdAt = 0, updatedAt = 0),
    )

    @Test
    fun parsesSupportedMyBcaNotification() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.bca.mybca.omni.android",
            title = "Financial Diary",
            text = "You received IDR 1,000.00 from ***SKY ****LIA *IJ at Account Transfer category.",
            bigText = "",
            subText = "",
            userPatterns = bcaPatterns,
        )

        assertEquals("com.bca.mybca.omni.android", decision.bankCode)
        assertEquals(1000L, decision.parsedAmount)
        assertEquals(0.99, decision.confidence, 0.0)
        assertNull(decision.ignoredReason)
    }

    @Test
    fun parsesIndonesianMyBcaNotificationTemplate() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.bca.mybca.omni.android",
            title = "Catatan Finansial",
            text = "Pemasukan sebesar IDR 1,231.00 dari ***SKY ****LIA *IJ di kategori Transfer Rekening.",
            bigText = "",
            subText = "",
            userPatterns = bcaPatterns,
        )

        assertEquals("com.bca.mybca.omni.android", decision.bankCode)
        assertEquals(1231L, decision.parsedAmount)
        assertEquals(0.99, decision.confidence, 0.0)
        assertNull(decision.ignoredReason)
    }

    @Test
    fun ignoresIndonesianMyBcaOutgoingTransferNotification() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.bca.mybca.omni.android",
            title = "Catatan Finansial",
            text = "Pengeluaran sebesar IDR 966,542.00 di kategori Transfer Rekening.",
            bigText = "",
            subText = "",
            userPatterns = bcaPatterns,
        )

        assertEquals("com.bca.mybca.omni.android", decision.bankCode)
        assertNull(decision.parsedAmount)
        assertEquals("outgoing_transaction", decision.ignoredReason)
    }

    @Test
    fun ignoresIndonesianMyBcaOutgoingAdminFeeNotification() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.bca.mybca.omni.android",
            title = "Catatan Finansial",
            text = "Pengeluaran sebesar IDR 6,500.00 di kategori Biaya Admin.",
            bigText = "",
            subText = "",
            userPatterns = bcaPatterns,
        )

        assertEquals("com.bca.mybca.omni.android", decision.bankCode)
        assertNull(decision.parsedAmount)
        assertEquals("outgoing_transaction", decision.ignoredReason)
    }

    @Test
    fun templateRequiresTheExpectedTitle() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.bca.mybca.omni.android",
            title = "Transfer",
            text = "Pemasukan sebesar IDR 1,231.00 dari ***SKY ****LIA *IJ di kategori Transfer Rekening.",
            bigText = "",
            subText = "",
            userPatterns = bcaPatterns,
        )

        assertEquals("com.bca.mybca.omni.android", decision.bankCode)
        assertEquals(1231L, decision.parsedAmount)
        assertEquals(0.70, decision.confidence, 0.0)
        assertNull(decision.ignoredReason)
    }

    @Test
    fun fallsBackToGenericIdrParsingForUnknownMyBcaText() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.bca.mybca.omni.android",
            title = "Financial Diary",
            text = "Incoming account activity IDR 2,345.00 from another account.",
            bigText = "",
            subText = "",
        )

        assertEquals("com.bca.mybca.omni.android", decision.bankCode)
        assertEquals(2345L, decision.parsedAmount)
        assertEquals(0.70, decision.confidence, 0.0)
        assertNull(decision.ignoredReason)
    }

    @Test
    fun parsesUnknownPackageWithIdrAmountGenericFallback() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.example.someapp",
            title = "Payment",
            text = "IDR 1.000 received",
            bigText = "",
            subText = "",
        )
        assertEquals("com.example.someapp", decision.bankCode)
        assertNotNull(decision.parsedAmount)
        assertNull(decision.ignoredReason)
    }

    @Test
    fun ignoresUnknownPackageWithNoIdrAmount() {
        val decision = PaymentNotificationParser.parse(
            packageName = "android",
            title = "USB debugging connected",
            text = "Touch to disable",
            bigText = "",
            subText = "",
        )
        assertEquals("no_idr_amount", decision.ignoredReason)
        assertNull(decision.parsedAmount)
    }

    @Test
    fun parsesWithSegmentsWhenBothSegmentsAndRegexPresent() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.bca.mybca.omni.android",
            title = "Financial Diary",
            text = "You received IDR 5,000.00 from ***TEST at Account Transfer category.",
            bigText = "",
            subText = "",
            userPatterns = bcaPatterns,
        )
        assertEquals(5000L, decision.parsedAmount)
        assertEquals(0.99, decision.confidence, 0.0)
    }
}
