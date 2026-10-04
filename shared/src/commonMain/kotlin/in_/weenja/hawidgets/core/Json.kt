package in_.weenja.hawidgets.core

/**
 * A small JSON tree, parser and writer. Shared by Android and iOS so the Home Assistant logic
 * needs no platform JSON classes and no third-party library.
 */
sealed class Json {
    object Null : Json()
    class Bool(val value: Boolean) : Json()
    class Num(val value: Double) : Json()
    class Str(val value: String) : Json()
    class Arr(val items: List<Json>) : Json()
    class Obj(val map: Map<String, Json>) : Json()

    // ------------------------------------------------------------ reading

    /** Member of an object, element of an array ("0", "1", …), else null. */
    operator fun get(key: String): Json? = when (this) {
        is Obj -> map[key]
        is Arr -> key.toIntOrNull()?.let { items.getOrNull(it) }
        else -> null
    }

    fun at(index: Int): Json? = (this as? Arr)?.items?.getOrNull(index)

    /** Text of a string, number or boolean; null for null, objects and arrays. */
    val string: String?
        get() = when (this) {
            is Str -> value
            is Num -> if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
            is Bool -> value.toString()
            else -> null
        }

    /** Numeric value of a number, or of a string that holds one. */
    val double: Double?
        get() = when (this) {
            is Num -> value
            is Str -> value.trim().toDoubleOrNull()
            is Bool -> if (value) 1.0 else 0.0
            else -> null
        }

    val bool: Boolean?
        get() = when (this) {
            is Bool -> value
            is Str -> when (value.lowercase()) { "true", "on", "yes" -> true; "false", "off", "no" -> false; else -> null }
            is Num -> value != 0.0
            else -> null
        }

    val list: List<Json> get() = (this as? Arr)?.items ?: emptyList()
    val obj: Map<String, Json> get() = (this as? Obj)?.map ?: emptyMap()
    val isNull: Boolean get() = this is Null

    fun str(key: String): String? = get(key)?.string
    fun num(key: String): Double? = get(key)?.double
    fun flag(key: String): Boolean? = get(key)?.bool
    fun arr(key: String): List<Json> = get(key)?.list ?: emptyList()
    /** Strings of an array member (non-strings are skipped). */
    fun strings(key: String): List<String> = arr(key).mapNotNull { (it as? Str)?.value }

    // ------------------------------------------------------------ writing

    override fun toString(): String = StringBuilder().also { write(it) }.toString()

    fun write(sb: StringBuilder) {
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(if (value) "true" else "false")
            is Num -> if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(string)
            is Str -> quote(value, sb)
            is Arr -> { sb.append('['); items.forEachIndexed { i, it -> if (i > 0) sb.append(','); it.write(sb) }; sb.append(']') }
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in map) { if (!first) sb.append(','); first = false; quote(k, sb); sb.append(':'); v.write(sb) }
                sb.append('}')
            }
        }
    }

    companion object {
        fun parse(text: String): Json = Parser(text).parseDocument()

        /** Parse, or null when [text] is not JSON. */
        fun parseOrNull(text: String?): Json? = if (text.isNullOrBlank()) null else try { parse(text) } catch (e: JsonException) { null }

        fun of(value: Any?): Json = when (value) {
            null -> Null
            is Json -> value
            is Boolean -> Bool(value)
            is Int -> Num(value.toDouble())
            is Long -> Num(value.toDouble())
            is Float -> Num(value.toDouble())
            is Double -> Num(value)
            is Number -> Num(value.toDouble())
            is String -> Str(value)
            is Map<*, *> -> Obj(LinkedHashMap<String, Json>().also { m -> value.forEach { (k, v) -> m[k.toString()] = of(v) } })
            is Iterable<*> -> Arr(value.map { of(it) })
            is Array<*> -> Arr(value.map { of(it) })
            else -> Str(value.toString())
        }

        fun obj(vararg pairs: Pair<String, Any?>): Obj = Obj(LinkedHashMap<String, Json>().also { m -> pairs.forEach { (k, v) -> m[k] = of(v) } })
        fun arr(items: List<Any?>): Arr = Arr(items.map { of(it) })

        fun quote(s: String, sb: StringBuilder) {
            sb.append('"')
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c < ' ') { sb.append("\\u"); sb.append(c.code.toString(16).padStart(4, '0')) } else sb.append(c)
                }
            }
            sb.append('"')
        }
    }
}

class JsonException(message: String) : Exception(message)

private class Parser(private val s: String) {
    private var i = 0

    fun parseDocument(): Json {
        skipWs()
        val v = value()
        skipWs()
        if (i != s.length) fail("trailing characters")
        return v
    }

    private fun fail(msg: String): Nothing = throw JsonException("$msg at $i")

    private fun skipWs() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

    private fun value(): Json {
        if (i >= s.length) fail("unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(string())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
        }
    }

    private fun literal(word: String, v: Json): Json {
        if (!s.startsWith(word, i)) fail("bad literal")
        i += word.length
        return v
    }

    private fun obj(): Json {
        i++ // {
        val m = LinkedHashMap<String, Json>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return Json.Obj(m) }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') fail("expected key")
            val k = string()
            skipWs()
            if (i >= s.length || s[i] != ':') fail("expected ':'")
            i++
            skipWs()
            m[k] = value()
            skipWs()
            if (i >= s.length) fail("unterminated object")
            when (s[i]) { ',' -> i++; '}' -> { i++; return Json.Obj(m) }; else -> fail("expected ',' or '}'") }
        }
    }

    private fun arr(): Json {
        i++ // [
        val l = ArrayList<Json>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return Json.Arr(l) }
        while (true) {
            skipWs()
            l.add(value())
            skipWs()
            if (i >= s.length) fail("unterminated array")
            when (s[i]) { ',' -> i++; ']' -> { i++; return Json.Arr(l) }; else -> fail("expected ',' or ']'") }
        }
    }

    private fun string(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i++]) {
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                        'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("bad unicode escape")
                            sb.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("bad unicode escape"))
                            i += 4
                        }
                        else -> fail("bad escape '$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun number(): Json {
        val start = i
        if (s[i] == '-') i++
        while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return Json.Num(s.substring(start, i).toDoubleOrNull() ?: fail("bad number"))
    }
}
