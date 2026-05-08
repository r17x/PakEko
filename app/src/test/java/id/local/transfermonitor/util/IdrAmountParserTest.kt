package id.local.transfermonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdrAmountParserTest {
    @Test
    fun parsesEnglishDecimalAmountFromMyBcaNotification() {
        val result = IdrAmountParser.parse(
            "You received IDR 1,000.00 from ***SKY ****LIA *IJ at Account Transfer category."
        )

        assertEquals(1000L, result?.amount)
    }

    @Test
    fun parsesIndonesianGroupedAmount() {
        val result = IdrAmountParser.parse("Dana masuk Rp 150.237 dari rekening lain.")

        assertEquals(150237L, result?.amount)
    }

    @Test
    fun parsesIndonesianGroupedAmountWithDecimalComma() {
        val result = IdrAmountParser.parse("Dana masuk Rp 1.000,00 dari rekening lain.")

        assertEquals(1000L, result?.amount)
    }

    @Test
    fun parsesEnglishGroupedAmount() {
        val result = IdrAmountParser.parse("Received IDR 150,237 from sender.")

        assertEquals(150237L, result?.amount)
    }

    @Test
    fun ignoresTextWithoutCurrencyAmount() {
        assertNull(IdrAmountParser.parse("USB debugging connected"))
    }
}
