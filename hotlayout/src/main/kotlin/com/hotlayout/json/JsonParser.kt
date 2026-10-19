package com.hotlayout.json

/** A syntax error of [JsonParser]. The host gets it through a load error, never the end user. */
public class JsonParseException(public val offset: Int, public val reason: String) :
    Exception("JSON: $reason at byte $offset")

/**
 * A strict JSON parser over bytes. Strict about syntax (a broken payload is a load error, not a
 * guess), lenient about nothing else: shapes are judged later, piece by piece.
 *
 * No platform parser is used: `org.json` loses the order of keys and turns big integers and
 * escapes into values of its own, and the nesting bound must hold before any recursion.
 */
public object JsonParser {
    /** The deepest nesting accepted, so that a hostile payload cannot exhaust the stack. */
    public const val MAX_PARSE_DEPTH: Int = 256

    public fun parse(bytes: ByteArray): JsonValue {
        val parser = State(bytes)
        parser.skipWhitespace()
        val value = parser.value(0)
        parser.skipWhitespace()
        if (parser.index != bytes.size) throw parser.error("unexpected trailing characters")
        return value
    }

    private class State(val bytes: ByteArray) {
        var index = 0

        fun error(reason: String) = JsonParseException(index, reason)

        fun byte(at: Int = index): Int = bytes[at].toInt() and 0xFF

        fun skipWhitespace() {
            while (index < bytes.size) {
                val b = byte()
                if (b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D) index++ else break
            }
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_PARSE_DEPTH) throw error("nesting too deep")
            if (index >= bytes.size) throw error("unexpected end")
            return when (byte().toChar()) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonValue.Str(string())
                't' -> { literal("true"); JsonValue.Bool(true) }
                'f' -> { literal("false"); JsonValue.Bool(false) }
                'n' -> { literal("null"); JsonValue.Null }
                else -> JsonValue.Num(number())
            }
        }

        fun literal(word: String) {
            val utf8 = word.toByteArray()
            if (index + utf8.size > bytes.size) throw error("unexpected character")
            for (i in utf8.indices) if (bytes[index + i] != utf8[i]) throw error("unexpected character")
            index += utf8.size
        }

        fun obj(depth: Int): JsonValue {
            index++
            val result = JsonObject()
            skipWhitespace()
            if (index < bytes.size && byte() == '}'.code) { index++; return JsonValue.Obj(result) }
            while (true) {
                skipWhitespace()
                if (index >= bytes.size || byte() != '"'.code) throw error("expected a key")
                val key = string()
                skipWhitespace()
                if (index >= bytes.size || byte() != ':'.code) throw error("expected ':'")
                index++
                skipWhitespace()
                result.putInternal(key, value(depth + 1))
                skipWhitespace()
                if (index >= bytes.size) throw error("unexpected end in an object")
                when (byte()) {
                    ','.code -> index++
                    '}'.code -> { index++; return JsonValue.Obj(result) }
                    else -> throw error("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): JsonValue {
            index++
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (index < bytes.size && byte() == ']'.code) { index++; return JsonValue.Arr(items) }
            while (true) {
                skipWhitespace()
                items.add(value(depth + 1))
                skipWhitespace()
                if (index >= bytes.size) throw error("unexpected end in an array")
                when (byte()) {
                    ','.code -> index++
                    ']'.code -> { index++; return JsonValue.Arr(items) }
                    else -> throw error("expected ',' or ']'")
                }
            }
        }

        fun digits(): Int {
            val from = index
            while (index < bytes.size && byte() in 0x30..0x39) index++
            return index - from
        }

        fun number(): Double {
            val start = index
            if (index < bytes.size && byte() == '-'.code) index++
            if (digits() == 0) throw error("malformed number")
            if (index < bytes.size && byte() == '.'.code) {
                index++
                if (digits() == 0) throw error("malformed number")
            }
            if (index < bytes.size && (byte() == 'e'.code || byte() == 'E'.code)) {
                index++
                if (index < bytes.size && (byte() == '+'.code || byte() == '-'.code)) index++
                if (digits() == 0) throw error("malformed number")
            }
            return String(bytes, start, index - start, Charsets.US_ASCII).toDoubleOrNull() ?: throw error("malformed number")
        }

        fun hex4(): Int {
            if (index + 4 > bytes.size) throw error("short unicode escape")
            var result = 0
            repeat(4) {
                val b = byte()
                val digit = when (b) {
                    in 0x30..0x39 -> b - 0x30
                    in 0x41..0x46 -> b - 0x41 + 10
                    in 0x61..0x66 -> b - 0x61 + 10
                    else -> throw error("malformed unicode escape")
                }
                result = result * 16 + digit
                index++
            }
            return result
        }

        fun string(): String {
            index++
            val out = StringBuilder()
            var runStart = index
            fun flush(end: Int) {
                if (end > runStart) out.append(String(bytes, runStart, end - runStart, Charsets.UTF_8))
            }
            while (true) {
                if (index >= bytes.size) throw error("unterminated string")
                val b = byte()
                if (b == '"'.code) { flush(index); index++; break }
                if (b < 0x20) throw error("control character in a string")
                if (b != '\\'.code) { index++; continue }
                flush(index)
                index++
                if (index >= bytes.size) throw error("unterminated escape")
                val escape = byte().toChar()
                index++
                when (escape) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        var scalar = hex4()
                        if (scalar in 0xD800..0xDBFF && index + 6 <= bytes.size && byte() == '\\'.code && byte(index + 1) == 'u'.code) {
                            val save = index
                            index += 2
                            val low = hex4()
                            if (low in 0xDC00..0xDFFF) scalar = 0x10000 + ((scalar - 0xD800) shl 10) + (low - 0xDC00) else index = save
                        }
                        // A lone surrogate is not a character: it becomes U+FFFD.
                        if (scalar in 0xD800..0xDFFF) out.append('�') else out.appendCodePoint(scalar)
                    }
                    else -> throw error("unknown escape")
                }
                runStart = index
            }
            return out.toString()
        }
    }
}
