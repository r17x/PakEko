package id.local.transfermonitor.parser

sealed interface ParseResult {
    data class Success(
        val endPos: Int,
        val captures: Map<String, String> = emptyMap(),
    ) : ParseResult

    data class Failure(
        val expected: String,
        val pos: Int,
    ) : ParseResult
}

data class AmountParseResult(
    val amount: Long,
    val confidence: Double,
)

fun interface SegmentMatcher {
    fun match(input: String, pos: Int, nextLiteral: String?): ParseResult
}

class LiteralMatcher(private val text: String) : SegmentMatcher {
    private val words = splitOnWhitespace(text)

    override fun match(input: String, pos: Int, nextLiteral: String?): ParseResult {
        var p = skipWhitespace(input, pos)
        if (words.isEmpty()) return ParseResult.Success(p)

        for ((wordIndex, word) in words.withIndex()) {
            if (wordIndex > 0) {
                if (p >= input.length || !input[p].isWhitespace()) {
                    return ParseResult.Failure("literal: $text", pos)
                }
                p = skipWhitespace(input, p)
            }
            if (p + word.length > input.length) {
                return ParseResult.Failure("literal: $text", pos)
            }
            if (!input.regionMatches(p, word, 0, word.length, ignoreCase = true)) {
                return ParseResult.Failure("literal: $text", pos)
            }
            p += word.length
        }
        return ParseResult.Success(p)
    }
}

object AmountMatcher : SegmentMatcher {
    override fun match(input: String, pos: Int, nextLiteral: String?): ParseResult {
        var p = skipWhitespace(input, pos)
        val prefixEnd = matchCurrencyPrefix(input, p)
        if (prefixEnd != null) p = prefixEnd
        p = skipWhitespace(input, p)

        val result = consumeAmountDigits(input, p)
            ?: return ParseResult.Failure("amount", pos)

        return ParseResult.Success(result.second, mapOf("amount" to result.first))
    }
}

class GreedyMatcher(private val captureName: String?) : SegmentMatcher {
    override fun match(input: String, pos: Int, nextLiteral: String?): ParseResult {
        val p = skipWhitespace(input, pos)

        val endPos = if (nextLiteral != null) {
            findLiteralStart(input, p, nextLiteral) ?: input.length
        } else {
            input.length
        }

        if (captureName != null) {
            val captured = input.substring(p, endPos).trim()
            if (captured.isEmpty()) {
                return ParseResult.Failure(captureName, pos)
            }
            return ParseResult.Success(endPos, mapOf(captureName to captured))
        }

        return ParseResult.Success(endPos)
    }
}

fun consumeAmountDigits(input: String, pos: Int): Pair<String, Int>? {
    if (pos >= input.length || !input[pos].isDigit()) return null

    var p = pos
    while (p < input.length) {
        val c = input[p]
        if (c.isDigit() || c == '.' || c == ',' || c == ' ' || c == '\u00A0') {
            p++
        } else {
            break
        }
    }

    while (p > pos) {
        val c = input[p - 1]
        if (c == '.' || c == ',' || c == ' ' || c == '\u00A0') {
            p--
        } else {
            break
        }
    }

    val captured = input.substring(pos, p)
    if (captured.isEmpty() || captured.none { it.isDigit() }) return null
    return Pair(captured, p)
}

fun matchCurrencyPrefix(input: String, pos: Int, requireWordBoundary: Boolean = false): Int? {
    if (requireWordBoundary && pos > 0 && input[pos - 1].isLetterOrDigit()) return null

    if (pos + 2 <= input.length && input.regionMatches(pos, "Rp", 0, 2, ignoreCase = true)) {
        var p = pos + 2
        if (p < input.length && input[p] == '.') p++
        return p
    }
    if (pos + 3 <= input.length && input.regionMatches(pos, "IDR", 0, 3, ignoreCase = true)) {
        return pos + 3
    }
    return null
}

private fun skipWhitespace(input: String, pos: Int): Int {
    var p = pos
    while (p < input.length && (input[p].isWhitespace() || input[p] == '\u00A0')) p++
    return p
}

private fun splitOnWhitespace(s: String): List<String> {
    val result = mutableListOf<String>()
    var i = 0
    while (i < s.length) {
        if (s[i].isWhitespace()) {
            i++
            continue
        }
        val start = i
        while (i < s.length && !s[i].isWhitespace()) i++
        result.add(s.substring(start, i))
    }
    return result
}

private fun findLiteralStart(input: String, fromPos: Int, literal: String): Int? {
    val words = splitOnWhitespace(literal.trim())
    if (words.isEmpty()) return null
    val firstWord = words[0]

    var i = fromPos
    while (i + firstWord.length <= input.length) {
        if (input.regionMatches(i, firstWord, 0, firstWord.length, ignoreCase = true)) {
            return i
        }
        i++
    }
    return null
}
