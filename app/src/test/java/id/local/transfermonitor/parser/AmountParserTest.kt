package id.local.transfermonitor.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {

    @Test
    fun parsesEnglishDecimalAmount() {
        val result = AmountParser.parse("IDR 1,000.00")
        assertNotNull(result)
        assertEquals(1000L, result!!.amount)
    }

    @Test
    fun parsesIndonesianGroupedAmount() {
        val result = AmountParser.parse("Rp 150.237")
        assertNotNull(result)
        assertEquals(150237L, result!!.amount)
    }

    @Test
    fun parsesIndonesianDecimalComma() {
        val result = AmountParser.parse("Rp 1.000,00")
        assertNotNull(result)
        assertEquals(1000L, result!!.amount)
    }

    @Test
    fun parsesEnglishGroupedAmount() {
        val result = AmountParser.parse("IDR 150,237")
        assertNotNull(result)
        assertEquals(150237L, result!!.amount)
    }

    @Test
    fun parsesRpDotPrefix() {
        val result = AmountParser.parse("Rp.50.000")
        assertNotNull(result)
        assertEquals(50000L, result!!.amount)
    }

    @Test
    fun parsesSingleDigit() {
        val result = AmountParser.parse("Rp 5")
        assertNotNull(result)
        assertEquals(5L, result!!.amount)
    }

    @Test
    fun parsesNoSpaceAfterPrefix() {
        val result = AmountParser.parse("IDR50000")
        assertNotNull(result)
        assertEquals(50000L, result!!.amount)
    }

    @Test
    fun returnsNullForNoCurrency() {
        assertNull(AmountParser.parse("USB debugging connected"))
    }

    @Test
    fun parsesAmountSurroundedByText() {
        val result = AmountParser.parse("Dana masuk Rp 150.237 dari rekening lain.")
        assertNotNull(result)
        assertEquals(150237L, result!!.amount)
    }

    @Test
    fun parseAmountEnglishDecimal() {
        assertEquals(1000L, AmountParser.parseAmount("1,000.00"))
    }

    @Test
    fun parseAmountPlainNumber() {
        assertEquals(50000L, AmountParser.parseAmount("50000"))
    }

    @Test
    fun parseAmountEmptyString() {
        assertNull(AmountParser.parseAmount(""))
    }
}
