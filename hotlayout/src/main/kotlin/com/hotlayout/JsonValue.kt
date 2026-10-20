package com.hotlayout

import com.hotlayout.json.JsonParser

/** A syntax error of the JSON parser: [JsonValue.parse] throws it for text that is not JSON. */
public class JsonParseException(public val offset: Int, public val reason: String) :
    Exception("JSON: $reason at byte $offset")

/**
 * A JSON value as the document, the theme and the host data carry it.
 *
 * The reader keeps documents in this form instead of decoding them into strict types: a
 * document arrives over the network and may be newer than the reader, or produced by something
 * other than the builder. A piece of the wrong shape must be skipped and reported, never crash
 * the screen, and a strict decoder fails the whole document on the first surprise.
 */
public sealed class JsonValue {
    /** The JSON `null`. */
    public object Null : JsonValue() {
        override fun toString(): String = "null"
    }

    /** A JSON boolean. */
    public data class Bool(val value: Boolean) : JsonValue()

    /** A JSON number. */
    public data class Num(val value: Double) : JsonValue()

    /** A JSON string. */
    public data class Str(val value: String) : JsonValue()

    /** A JSON array. */
    public data class Arr(val items: List<JsonValue>) : JsonValue()

    /** A JSON object. */
    public data class Obj(val fields: JsonObject) : JsonValue()

    /** The string of a [Str], else `null`. */
    public val string: String? get() = (this as? Str)?.value

    /** A finite number. `NaN` and infinity cannot come from JSON, but a host may build one in code. */
    public val number: Double? get() = (this as? Num)?.value?.takeIf { it.isFinite() }

    /** The boolean of a [Bool], else `null`. */
    public val bool: Boolean? get() = (this as? Bool)?.value

    /** The items of an [Arr], else `null`. */
    public val array: List<JsonValue>? get() = (this as? Arr)?.items

    /** The fields of an [Obj], else `null`. */
    public val obj: JsonObject? get() = (this as? Obj)?.fields

    /** Whether this is the JSON `null`. */
    public val isNull: Boolean get() = this === Null

    /** Own key of an object; `null` for anything that is not an object. */
    public operator fun get(key: String): JsonValue? = obj?.get(key)

    public companion object {
        /**
         * A value from plain Kotlin values: strings, numbers, booleans, `null`, lists and maps
         * with string keys. Anything else becomes `null`.
         */
        public fun of(value: Any?): JsonValue = of(value, 0)

        /** Host values nested deeper than the parser accepts (or cyclic ones) end in `null`, so the walk never overflows the stack. */
        private fun of(value: Any?, depth: Int): JsonValue = when {
            depth > JsonParser.MAX_PARSE_DEPTH -> Null
            value == null -> Null
            value is JsonValue -> value
            value is String -> Str(value)
            value is Boolean -> Bool(value)
            value is Number -> Num(value.toDouble())
            value is Map<*, *> -> Obj(JsonObject(value.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to of(v, depth + 1) } }))
            value is List<*> -> Arr(value.map { of(it, depth + 1) })
            value is Array<*> -> Arr(value.map { of(it, depth + 1) })
            else -> Null
        }

        /** Parses strict JSON text; throws [JsonParseException] for anything else. */
        public fun parse(text: String): JsonValue = JsonParser.parse(text.toByteArray(Charsets.UTF_8))

        /** Parses strict UTF-8 JSON bytes; throws [JsonParseException] for anything else. */
        public fun parse(bytes: ByteArray): JsonValue = JsonParser.parse(bytes)
    }
}

/**
 * A JSON object that remembers the order of its keys.
 *
 * Order matters in a few places of the contract (the first entry wins, a document is walked in
 * its own order). A repeated key keeps its first position and takes the last value, as
 * `JSON.parse` does. Equality ignores the order.
 */
public class JsonObject(pairs: List<Pair<String, JsonValue>> = emptyList()) : Iterable<Pair<String, JsonValue>> {
    private val storage = LinkedHashMap<String, JsonValue>()

    init {
        for ((key, value) in pairs) storage[key] = value
    }

    /** An object from a map, in the map's order. */
    public constructor(map: Map<String, JsonValue>) : this(map.entries.map { it.key to it.value })

    /** The keys in document order. */
    public val keys: List<String> get() = storage.keys.toList()
    /** The number of keys. */
    public val size: Int get() = storage.size
    /** Whether the object has no keys. */
    public fun isEmpty(): Boolean = storage.isEmpty()

    public operator fun get(key: String): JsonValue? = storage[key]

    /** Whether the object has the key. */
    public fun containsKey(key: String): Boolean = storage.containsKey(key)

    /** A copy with `key` set (or removed for `null`). */
    public fun with(key: String, value: JsonValue?): JsonObject {
        val copy = JsonObject(toList())
        if (value == null) copy.storage.remove(key) else copy.storage[key] = value
        return copy
    }

    /** A copy as a map, in the object's order. */
    public fun toMap(): Map<String, JsonValue> = LinkedHashMap(storage)

    override fun iterator(): Iterator<Pair<String, JsonValue>> = storage.entries.map { it.key to it.value }.iterator()

    override fun equals(other: Any?): Boolean = other is JsonObject && other.storage == storage

    override fun hashCode(): Int = storage.hashCode()

    override fun toString(): String = storage.toString()

    internal fun putInternal(key: String, value: JsonValue) {
        storage[key] = value
    }

    public companion object {
        public fun of(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.map { it.first to JsonValue.of(it.second) })
    }
}

/** Shorthand used across the reader: an object value from pairs of plain values. */
internal fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonValue = JsonValue.Obj(JsonObject.of(*pairs))
