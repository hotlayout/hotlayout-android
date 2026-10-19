package com.hotlayout.json

/**
 * A JSON value as the document, the theme and the host data carry it.
 *
 * The reader keeps documents in this form instead of decoding them into strict types: a
 * document arrives over the network and may be newer than the reader, or produced by something
 * other than the builder. A piece of the wrong shape must be skipped and reported, never crash
 * the screen, and a strict decoder fails the whole document on the first surprise.
 */
public sealed class JsonValue {
    public object Null : JsonValue() {
        override fun toString(): String = "null"
    }

    public data class Bool(val value: Boolean) : JsonValue()

    public data class Num(val value: Double) : JsonValue()

    public data class Str(val value: String) : JsonValue()

    public data class Arr(val items: List<JsonValue>) : JsonValue()

    public data class Obj(val fields: JsonObject) : JsonValue()

    public val string: String? get() = (this as? Str)?.value

    /** A finite number. `NaN` and infinity cannot come from JSON, but a host may build one in code. */
    public val number: Double? get() = (this as? Num)?.value?.takeIf { it.isFinite() }

    public val bool: Boolean? get() = (this as? Bool)?.value

    public val array: List<JsonValue>? get() = (this as? Arr)?.items

    public val obj: JsonObject? get() = (this as? Obj)?.fields

    public val isNull: Boolean get() = this === Null

    /** Own key of an object; `null` for anything that is not an object. */
    public operator fun get(key: String): JsonValue? = obj?.get(key)

    public companion object {
        /**
         * A value from plain Kotlin values: strings, numbers, booleans, `null`, lists and maps
         * with string keys. Anything else becomes `null`.
         */
        public fun of(value: Any?): JsonValue = when (value) {
            null -> Null
            is JsonValue -> value
            is String -> Str(value)
            is Boolean -> Bool(value)
            is Number -> Num(value.toDouble())
            is Map<*, *> -> Obj(JsonObject(value.entries.mapNotNull { (k, v) -> (k as? String)?.let { it to of(v) } }))
            is List<*> -> Arr(value.map(::of))
            is Array<*> -> Arr(value.map(::of))
            else -> Null
        }

        /** Parses JSON text; see [JsonParser]. */
        public fun parse(text: String): JsonValue = JsonParser.parse(text.toByteArray(Charsets.UTF_8))

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

    public constructor(map: Map<String, JsonValue>) : this(map.entries.map { it.key to it.value })

    public val keys: List<String> get() = storage.keys.toList()
    public val size: Int get() = storage.size
    public fun isEmpty(): Boolean = storage.isEmpty()

    public operator fun get(key: String): JsonValue? = storage[key]

    public fun containsKey(key: String): Boolean = storage.containsKey(key)

    /** A copy with `key` set (or removed for `null`). */
    public fun with(key: String, value: JsonValue?): JsonObject {
        val copy = JsonObject(toList())
        if (value == null) copy.storage.remove(key) else copy.storage[key] = value
        return copy
    }

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
public fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonValue = JsonValue.Obj(JsonObject.of(*pairs))
