package id.local.transfermonitor.parser

import id.local.transfermonitor.data.PatternSegment
import id.local.transfermonitor.data.SegmentRole

object SegmentSerializer {
    fun toJson(segments: List<PatternSegment>): String = buildString {
        append('[')
        segments.forEachIndexed { i, seg ->
            if (i > 0) append(',')
            append("""{"text":""")
            append('"')
            append(seg.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
            append('"')
            append(""","role":""")
            append('"')
            append(seg.role.name)
            append('"')
            append('}')
        }
        append(']')
    }

    fun fromJson(json: String): List<PatternSegment>? {
        if (json.isBlank() || !json.trimStart().startsWith("[")) return null
        return try {
            val result = mutableListOf<PatternSegment>()
            var i = json.indexOf('{')
            while (i >= 0 && i < json.length) {
                // Find object end, respecting strings
                var j = i + 1; var inStr = false
                while (j < json.length) {
                    val c = json[j]
                    if (inStr) { if (c == '\\') { j += 2; continue }; if (c == '"') inStr = false }
                    else { if (c == '"') inStr = true; if (c == '}') break }
                    j++
                }
                if (j >= json.length) break
                val obj = json.substring(i, j + 1)
                val text = extractValue(obj, "text") ?: break
                val roleName = extractValue(obj, "role") ?: break
                val role = try { SegmentRole.valueOf(roleName) } catch (_: Exception) { break }
                result.add(PatternSegment(text, role))
                i = json.indexOf('{', j + 1)
            }
            result.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    private fun extractValue(obj: String, key: String): String? {
        val ki = obj.indexOf("\"$key\""); if (ki < 0) return null
        val ci = obj.indexOf(':', ki + key.length + 2); if (ci < 0) return null
        val vs = obj.indexOf('"', ci + 1); if (vs < 0) return null
        val sb = StringBuilder(); var i = vs + 1
        while (i < obj.length) {
            val c = obj[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < obj.length) {
                i++; when (obj[i]) { '"' -> sb.append('"'); '\\' -> sb.append('\\'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); else -> { sb.append('\\'); sb.append(obj[i]) } }
            } else sb.append(c)
            i++
        }
        return null
    }
}
