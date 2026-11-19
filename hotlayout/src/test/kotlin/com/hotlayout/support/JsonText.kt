package com.hotlayout.support

import com.hotlayout.JsonValue

/** A JSON value written as text, to build a document out of rows of a table. */
object JsonText {
    fun of(value: JsonValue): String = when (value) {
        is JsonValue.Null -> "null"
        is JsonValue.Bool -> value.value.toString()
        is JsonValue.Num -> if (value.value == Math.rint(value.value)) value.value.toLong().toString() else value.value.toString()
        is JsonValue.Str -> "\"" + value.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is JsonValue.Arr -> value.items.joinToString(",", "[", "]") { of(it) }
        is JsonValue.Obj -> value.fields.joinToString(",", "{", "}") { (key, item) -> "\"$key\":${of(item)}" }
    }
}
