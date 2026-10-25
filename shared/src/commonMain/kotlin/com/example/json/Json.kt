package com.example.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KxArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KxObject
import kotlinx.serialization.json.JsonPrimitive

// org.json's JSONObject and JSONArray, in plain Kotlin.
//
// The app was written against org.json, which Android ships and iOS does not.
// Rather than rewrite every reader of the seed, the sync feed, the NCAA
// scoreboard and the Claude API, this is a drop-in for the part of the API
// the app uses, so moving a file over is an import change and nothing else.
//
// It keeps Android's behaviour exactly, quirks included, because the code
// that calls it was written and tested against those quirks:
// - optString of a JSON null is the string "null", not the fallback;
// - the numeric getters read numbers sent as strings ("12" -> 12);
// - put(name, null) removes the name rather than storing a null;
// - toString escapes '/' as "\/" and writes 2.0 as 2.
// JsonCompatTest runs the same inputs through this and through Android's own
// org.json and requires the same answers.
//
// kotlinx.serialization does the parsing; the value model, the accessors and
// the writer are here.

class JSONException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Android's JSONObject.NULL: equal to itself and to null, written as null. */
private object NullValue {
    override fun equals(other: Any?) = other === this || other == null
    override fun hashCode() = 0
    override fun toString() = "null"
}

class JSONObject() {
    private val nameValuePairs = LinkedHashMap<String, Any?>()

    constructor(json: String) : this() {
        val value = Parser.parse(json)
        if (value !is JSONObject) throw Coerce.typeMismatch(value, "JSONObject")
        nameValuePairs.putAll(value.nameValuePairs)
    }

    constructor(copyFrom: Map<*, *>) : this() {
        for ((key, value) in copyFrom) nameValuePairs[key as String] = wrap(value)
    }

    fun length(): Int = nameValuePairs.size

    fun put(name: String, value: Boolean): JSONObject = apply { nameValuePairs[name] = value }
    fun put(name: String, value: Double): JSONObject = apply { nameValuePairs[name] = Coerce.checkDouble(value) }
    fun put(name: String, value: Int): JSONObject = apply { nameValuePairs[name] = value }
    fun put(name: String, value: Long): JSONObject = apply { nameValuePairs[name] = value }

    /** Stores [value], or removes [name] when [value] is null - as Android does. */
    fun put(name: String, value: Any?): JSONObject = apply {
        if (value == null) {
            nameValuePairs.remove(name)
        } else {
            if (value is Number) Coerce.checkDouble(value.toDouble())
            nameValuePairs[name] = value
        }
    }

    fun putOpt(name: String?, value: Any?): JSONObject =
        if (name == null || value == null) this else put(name, value)

    fun remove(name: String): Any? = nameValuePairs.remove(name)

    fun isNull(name: String): Boolean {
        val value = nameValuePairs[name]
        return value == null || value === NULL
    }

    fun has(name: String): Boolean = nameValuePairs.containsKey(name)

    fun get(name: String): Any = nameValuePairs[name] ?: throw JSONException("No value for $name")

    fun opt(name: String): Any? = nameValuePairs[name]

    fun getBoolean(name: String): Boolean =
        get(name).let { Coerce.toBoolean(it) ?: throw Coerce.typeMismatch(name, it, "boolean") }

    fun optBoolean(name: String, fallback: Boolean = false): Boolean = Coerce.toBoolean(opt(name)) ?: fallback

    fun getDouble(name: String): Double =
        get(name).let { Coerce.toDouble(it) ?: throw Coerce.typeMismatch(name, it, "double") }

    fun optDouble(name: String, fallback: Double = Double.NaN): Double = Coerce.toDouble(opt(name)) ?: fallback

    fun getInt(name: String): Int =
        get(name).let { Coerce.toInteger(it) ?: throw Coerce.typeMismatch(name, it, "int") }

    fun optInt(name: String, fallback: Int = 0): Int = Coerce.toInteger(opt(name)) ?: fallback

    fun getLong(name: String): Long =
        get(name).let { Coerce.toLong(it) ?: throw Coerce.typeMismatch(name, it, "long") }

    fun optLong(name: String, fallback: Long = 0L): Long = Coerce.toLong(opt(name)) ?: fallback

    fun getString(name: String): String =
        get(name).let { Coerce.toStr(it) ?: throw Coerce.typeMismatch(name, it, "String") }

    fun optString(name: String, fallback: String = ""): String = Coerce.toStr(opt(name)) ?: fallback

    fun getJSONArray(name: String): JSONArray =
        get(name).let { it as? JSONArray ?: throw Coerce.typeMismatch(name, it, "JSONArray") }

    fun optJSONArray(name: String): JSONArray? = opt(name) as? JSONArray

    fun getJSONObject(name: String): JSONObject =
        get(name).let { it as? JSONObject ?: throw Coerce.typeMismatch(name, it, "JSONObject") }

    fun optJSONObject(name: String): JSONObject? = opt(name) as? JSONObject

    fun keys(): Iterator<String> = nameValuePairs.keys.iterator()

    fun keySet(): Set<String> = nameValuePairs.keys

    fun names(): JSONArray? =
        if (nameValuePairs.isEmpty()) null else JSONArray(ArrayList<Any?>(nameValuePairs.keys))

    override fun toString(): String = StringBuilder().also { Writer.write(it, this) }.toString()

    internal fun entries(): Set<Map.Entry<String, Any?>> = nameValuePairs.entries

    internal fun putParsed(name: String, value: Any?) {
        nameValuePairs[name] = value
    }

    companion object {
        val NULL: Any = NullValue

        fun quote(data: String?): String =
            if (data == null) "\"\"" else StringBuilder().also { Writer.string(it, data) }.toString()

        /** Android's JSONObject.wrap, for the value types this app produces. */
        fun wrap(o: Any?): Any? = when {
            o == null -> NULL
            o is JSONArray || o is JSONObject || o === NULL -> o
            o is Collection<*> -> JSONArray(o)
            o is Array<*> -> JSONArray(o.toList())
            o is Map<*, *> -> JSONObject(o)
            o is Boolean || o is Byte || o is Char || o is Double || o is Float ||
                o is Int || o is Long || o is Short || o is String -> o
            else -> null
        }

        fun numberToString(number: Number): String {
            val doubleValue = Coerce.checkDouble(number.toDouble())
            // org.json writes negative zero as "-0" rather than "-0.0".
            if (number is Double && number.toRawBits() == (-0.0).toRawBits()) return "-0"
            val longValue = number.toLong()
            if (doubleValue == longValue.toDouble()) return longValue.toString()
            return number.toString()
        }
    }
}

class JSONArray() {
    private val values = ArrayList<Any?>()

    constructor(json: String) : this() {
        val value = Parser.parse(json)
        if (value !is JSONArray) throw Coerce.typeMismatch(value, "JSONArray")
        values.addAll(value.values)
    }

    constructor(copyFrom: Collection<*>) : this() {
        for (o in copyFrom) values.add(JSONObject.wrap(o))
    }

    fun length(): Int = values.size

    fun put(value: Boolean): JSONArray = apply { values.add(value) }
    fun put(value: Double): JSONArray = apply { values.add(Coerce.checkDouble(value)) }
    fun put(value: Int): JSONArray = apply { values.add(value) }
    fun put(value: Long): JSONArray = apply { values.add(value) }
    fun put(value: Any?): JSONArray = apply { values.add(value) }

    fun put(index: Int, value: Any?): JSONArray = apply {
        if (value is Number) Coerce.checkDouble(value.toDouble())
        while (values.size <= index) values.add(null)
        values[index] = value
    }

    fun isNull(index: Int): Boolean {
        val value = opt(index)
        return value == null || value === JSONObject.NULL
    }

    fun get(index: Int): Any {
        if (index < 0 || index >= values.size) {
            throw JSONException("Index $index out of range [0..${values.size})")
        }
        return values[index] ?: throw JSONException("Value at $index is null.")
    }

    fun opt(index: Int): Any? = if (index < 0 || index >= values.size) null else values[index]

    fun remove(index: Int): Any? = if (index < 0 || index >= values.size) null else values.removeAt(index)

    fun getBoolean(index: Int): Boolean =
        get(index).let { Coerce.toBoolean(it) ?: throw Coerce.typeMismatch(index, it, "boolean") }

    fun optBoolean(index: Int, fallback: Boolean = false): Boolean = Coerce.toBoolean(opt(index)) ?: fallback

    fun getDouble(index: Int): Double =
        get(index).let { Coerce.toDouble(it) ?: throw Coerce.typeMismatch(index, it, "double") }

    fun optDouble(index: Int, fallback: Double = Double.NaN): Double = Coerce.toDouble(opt(index)) ?: fallback

    fun getInt(index: Int): Int =
        get(index).let { Coerce.toInteger(it) ?: throw Coerce.typeMismatch(index, it, "int") }

    fun optInt(index: Int, fallback: Int = 0): Int = Coerce.toInteger(opt(index)) ?: fallback

    fun getLong(index: Int): Long =
        get(index).let { Coerce.toLong(it) ?: throw Coerce.typeMismatch(index, it, "long") }

    fun optLong(index: Int, fallback: Long = 0L): Long = Coerce.toLong(opt(index)) ?: fallback

    fun getString(index: Int): String =
        get(index).let { Coerce.toStr(it) ?: throw Coerce.typeMismatch(index, it, "String") }

    fun optString(index: Int, fallback: String = ""): String = Coerce.toStr(opt(index)) ?: fallback

    fun getJSONArray(index: Int): JSONArray =
        get(index).let { it as? JSONArray ?: throw Coerce.typeMismatch(index, it, "JSONArray") }

    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    fun getJSONObject(index: Int): JSONObject =
        get(index).let { it as? JSONObject ?: throw Coerce.typeMismatch(index, it, "JSONObject") }

    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject

    override fun toString(): String = StringBuilder().also { Writer.write(it, this) }.toString()

    override fun equals(other: Any?): Boolean = other is JSONArray && other.values == values

    override fun hashCode(): Int = values.hashCode()

    internal fun items(): List<Any?> = values

    internal fun addParsed(value: Any?) {
        values.add(value)
    }
}

/** The conversions behind the typed getters, as in Android's org.json.JSON. */
private object Coerce {
    fun checkDouble(d: Double): Double {
        if (d.isInfinite() || d.isNaN()) throw JSONException("Forbidden numeric value: $d")
        return d
    }

    fun toBoolean(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> null
        }
        else -> null
    }

    fun toDouble(value: Any?): Double? = when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> parseDouble(value)
        else -> null
    }

    fun toInteger(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> parseDouble(value)?.toInt()
        else -> null
    }

    fun toLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> parseDouble(value)?.toLong()
        else -> null
    }

    /** null stays null; JSONObject.NULL becomes "null", as on Android. */
    fun toStr(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> value.toString()
    }

    // Java's Double.parseDouble ignores leading and trailing characters up to
    // and including the space.
    private fun parseDouble(s: String): Double? = s.trim { it <= ' ' }.toDoubleOrNull()

    fun typeMismatch(indexOrName: Any, actual: Any?, requiredType: String): JSONException =
        if (actual == null) {
            JSONException("Value at $indexOrName is null.")
        } else {
            JSONException("Value $actual at $indexOrName of type ${actual::class.simpleName} cannot be converted to $requiredType")
        }

    fun typeMismatch(actual: Any?, requiredType: String): JSONException =
        if (actual == null) {
            JSONException("Value is null.")
        } else {
            JSONException("Value $actual of type ${actual::class.simpleName} cannot be converted to $requiredType")
        }
}

private object Parser {
    fun parse(json: String): Any? {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw JSONException(e.message ?: "Malformed JSON", e)
        }
        return convert(element)
    }

    private fun convert(element: JsonElement): Any? = when (element) {
        is KxObject -> JSONObject().also { o -> element.forEach { (k, v) -> o.putParsed(k, convert(v)) } }
        is KxArray -> JSONArray().also { a -> element.forEach { a.addParsed(convert(it)) } }
        JsonNull -> JSONObject.NULL
        is JsonPrimitive -> if (element.isString) element.content else literal(element.content)
    }

    // Android's JSONTokener.readLiteral: whole numbers become Int when they fit
    // and Long when they do not; anything with a point or an exponent, or too
    // big for a Long, becomes Double.
    private fun literal(text: String): Any = when {
        text.equals("true", ignoreCase = true) -> true
        text.equals("false", ignoreCase = true) -> false
        else -> {
            val whole = if ('.' !in text) text.toLongOrNull() else null
            when {
                whole == null -> text.toDoubleOrNull() ?: text
                whole in Int.MIN_VALUE..Int.MAX_VALUE -> whole.toInt()
                else -> whole
            }
        }
    }
}

internal object Writer {
    fun write(out: StringBuilder, value: Any?) {
        when {
            value == null || value === JSONObject.NULL -> out.append("null")
            value is JSONObject -> {
                out.append('{')
                var first = true
                for ((name, v) in value.entries()) {
                    if (!first) out.append(',')
                    first = false
                    string(out, name)
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            value is JSONArray -> {
                out.append('[')
                value.items().forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            value is Boolean -> out.append(if (value) "true" else "false")
            value is Number -> out.append(JSONObject.numberToString(value))
            else -> string(out, value.toString())
        }
    }

    // Android's JSONStringer.string: RFC 4627's required escapes, plus '/'.
    fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"', '\\', '/' -> out.append('\\').append(c)
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\u000C' -> out.append("\\f")
                else -> if (c.code <= 0x1F) unicodeEscape(out, c) else out.append(c)
            }
        }
        out.append('"')
    }

    private fun unicodeEscape(out: StringBuilder, c: Char) {
        out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
    }
}
