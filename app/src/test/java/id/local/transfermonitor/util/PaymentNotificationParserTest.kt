package id.local.transfermonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentNotificationParserTest {
    @Test
    fun parsesSupportedMyBcaNotification() {
        val decision = PaymentNotificationParser.parse(
            packageName = "com.bca.mybca.omni.android",
            title = "Financial Diary",
            text = "You received IDR 1,000.00 from ***SKY ****LIA *IJ at Account Transfer category.",
            bigText = "",
            subText = "",
        )

        assertEquals("bca", decision.bankCode)
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
        )

        assertEquals("bca", decision.bankCode)
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
        )

        assertEquals("bca", decision.bankCode)
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
        )

        assertEquals("bca", decision.bankCode)
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
        )

        assertEquals("bca", decision.bankCode)
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

        assertEquals("bca", decision.bankCode)
        assertEquals(2345L, decision.parsedAmount)
        assertEquals(0.70, decision.confidence, 0.0)
        assertNull(decision.ignoredReason)
    }

    @Test
    fun ignoresUnsupportedPackage() {
        val decision = PaymentNotificationParser.parse(
            packageName = "android",
            title = "USB debugging connected",
            text = "IDR 1,000.00",
            bigText = "",
            subText = "",
        )

        assertEquals("unsupported_package", decision.ignoredReason)
        assertNull(decision.parsedAmount)
    }
}
