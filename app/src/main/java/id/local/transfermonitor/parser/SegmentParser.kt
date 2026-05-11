package id.local.transfermonitor.parser

import id.local.transfermonitor.data.PatternSegment
import id.local.transfermonitor.data.SegmentRole

object SegmentParser {
    fun parse(segments: List<PatternSegment>, input: String): Map<String, String>? {
        if (segments.isEmpty()) return emptyMap()

        var pos = 0
        val allCaptures = mutableMapOf<String, String>()

        for ((index, segment) in segments.withIndex()) {
            val nextLiteral = findNextLiteral(segments, index + 1)
            val matcher = matcherFor(segment.role, segment.text)

            when (val result = matcher.match(input, pos, nextLiteral)) {
                is ParseResult.Success -> {
                    pos = result.endPos
                    allCaptures.putAll(result.captures)
                }
                is ParseResult.Failure -> return null
            }
        }

        return allCaptures
    }

    private fun findNextLiteral(segments: List<PatternSegment>, fromIndex: Int): String? {
        for (i in fromIndex until segments.size) {
            if (segments[i].role == SegmentRole.LITERAL) {
                return segments[i].text
            }
        }
        return null
    }

    private fun matcherFor(role: SegmentRole, text: String): SegmentMatcher =
        when (role) {
            SegmentRole.LITERAL -> LiteralMatcher(text)
            SegmentRole.AMOUNT -> AmountMatcher
            SegmentRole.SENDER -> GreedyMatcher("sender")
            SegmentRole.WILDCARD -> GreedyMatcher(null)
        }
}
