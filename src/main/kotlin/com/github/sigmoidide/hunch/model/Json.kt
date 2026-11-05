package com.github.sigmoidide.hunch.model

/**
 * A small JSON reader and writer, so the plugin bundles no JSON library.
 *
 * Hunch's files are written by Python's json module, which writes non-finite
 * floats as the bare tokens NaN, Infinity and -Infinity; the reader accepts them.
 * Objects become Map<String, Any?>, arrays List<Any?>, integers Long, other numbers Double.
 */
object Json {
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        if (!parser.atEnd()) parser.fail("unexpected text after the value")
        return value
    }

    fun write(value: Any?): String = StringBuilder().also { writeValue(it, value) }.toString()

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Double -> out.append(
                when {
                    value.isNaN() -> "NaN"
                    value == Double.POSITIVE_INFINITY -> "Infinity"
                    value == Double.NEGATIVE_INFINITY -> "-Infinity"
                    else -> value.toString()
                }
            )
            is Number -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, (key, item) ->
                    if (index > 0) out.append(", ")
                    writeString(out, key.toString())
                    out.append(": ")
                    writeValue(out, item)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(", ")
                    writeValue(out, item)
                }
                out.append(']')
            }
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                // Control characters, and the separators some readers split lines on.
                c < ' ' || c == ' ' || c == ' ' || c == '\u0085' ->
                    out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(message: String): Nothing = throw IllegalArgumentException("invalid JSON at $i: $message")

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWhitespace()
            if (atEnd()) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                'N' -> literal("NaN", Double.NaN)
                'I' -> literal("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' && s.startsWith("-Infinity", i)) literal("-Infinity", Double.NEGATIVE_INFINITY) else number()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            i++
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return result
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[i] != '"') fail("expected a key")
                val key = string()
                skipWhitespace()
                if (atEnd() || s[i] != ':') fail("expected ':'")
                i++
                result[key] = value()
                skipWhitespace()
                if (atEnd()) fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return result
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(): List<Any?> {
            val result = ArrayList<Any?>()
            i++
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return result
            }
            while (true) {
                result.add(value())
                skipWhitespace()
                if (atEnd()) fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return result
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            val out = StringBuilder()
            i++
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("short \\u escape")
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> fail("unknown escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            if (text.isEmpty() || text == "-") fail("expected a value")
            val isInteger = text.none { it in ".eE" }
            return if (isInteger) text.toLongOrNull() ?: text.toDouble() else text.toDouble()
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()
