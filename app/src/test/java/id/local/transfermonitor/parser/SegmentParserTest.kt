package id.local.transfermonitor.parser

import id.local.transfermonitor.data.PatternSegment
import id.local.transfermonitor.data.SegmentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentParserTest {

    @Test
    fun literalOnly() {
        val segments = listOf(PatternSegment("Hello world", SegmentRole.LITERAL))
        val result = SegmentParser.parse(segments, "Hello world")
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun literalCaseInsensitive() {
        val segments = listOf(PatternSegment("HELLO", SegmentRole.LITERAL))
        val result = SegmentParser.parse(segments, "hello")
        assertNotNull(result)
    }

    @Test
    fun amountExtraction() {
        val segments = listOf(
            PatternSegment("Rp", SegmentRole.LITERAL),
            PatternSegment("", SegmentRole.AMOUNT),
        )
        val result = SegmentParser.parse(segments, "Rp 1.000,00")
        assertNotNull(result)
        assertEquals("1.000,00", result!!["amount"])
    }

    @Test
    fun senderExtraction() {
        val segments = listOf(
            PatternSegment("from", SegmentRole.LITERAL),
            PatternSegment("", SegmentRole.SENDER),
            PatternSegment("at", SegmentRole.LITERAL),
        )
        val result = SegmentParser.parse(segments, "from John Doe at home")
        assertNotNull(result)
        assertEquals("John Doe", result!!["sender"])
    }

    @Test
    fun wildcardSkip() {
        val segments = listOf(
            PatternSegment("hello", SegmentRole.LITERAL),
            PatternSegment("", SegmentRole.WILDCARD),
            PatternSegment("world", SegmentRole.LITERAL),
        )
        val result = SegmentParser.parse(segments, "hello foo bar world")
        assertNotNull(result)
    }

    @Test
    fun fullBcaEnTemplate() {
        val segments = listOf(
            PatternSegment("You received", SegmentRole.LITERAL),
            PatternSegment("", SegmentRole.AMOUNT),
            PatternSegment("from", SegmentRole.LITERAL),
            PatternSegment("", SegmentRole.SENDER),
            PatternSegment("at Account Transfer category.", SegmentRole.LITERAL),
        )
        val result = SegmentParser.parse(
            segments,
            "You received IDR 1,000.00 from ***SKY ****LIA *IJ at Account Transfer category.",
        )
        assertNotNull(result)
        assertTrue(result!!["amount"]!!.contains("1,000.00"))
        assertTrue(result["sender"]!!.contains("***SKY ****LIA *IJ"))
    }

    @Test
    fun fullBcaIdTemplate() {
        val segments = listOf(
            PatternSegment("Pemasukan sebesar", SegmentRole.LITERAL),
            PatternSegment("", SegmentRole.AMOUNT),
            PatternSegment("dari", SegmentRole.LITERAL),
            PatternSegment("", SegmentRole.SENDER),
            PatternSegment("di kategori Transfer Rekening.", SegmentRole.LITERAL),
        )
        val result = SegmentParser.parse(
            segments,
            "Pemasukan sebesar IDR 1,231.00 dari ***SKY ****LIA *IJ di kategori Transfer Rekening.",
        )
        assertNotNull(result)
        assertNotNull(result!!["amount"])
        assertNotNull(result["sender"])
    }

    @Test
    fun failureCase() {
        val segments = listOf(PatternSegment("Hello", SegmentRole.LITERAL))
        val result = SegmentParser.parse(segments, "Goodbye")
        assertNull(result)
    }

    @Test
    fun emptySegments() {
        val result = SegmentParser.parse(emptyList(), "anything")
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun extraWhitespace() {
        val segments = listOf(PatternSegment("You received", SegmentRole.LITERAL))
        val result = SegmentParser.parse(segments, "You  received")
        assertNotNull(result)
    }

    @Test
    fun bcaOutgoingEnWithAmount() {
        val segments = listOf(
            PatternSegment("You sent", SegmentRole.LITERAL),
            PatternSegment("", SegmentRole.AMOUNT),
            PatternSegment("", SegmentRole.WILDCARD),
        )
        val result = SegmentParser.parse(
            segments,
            "You sent IDR 966,542.00 to someone Account Transfer category.",
        )
        assertNotNull(result)
        assertNotNull(result!!["amount"])
    }
}
