package com.hotlayout.render.adaptive

import androidx.compose.runtime.saveable.Saver
import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.actions.Flow
import com.hotlayout.actions.FlowDocument
import com.hotlayout.actions.FlowFrame
import com.hotlayout.data.RecordLink
import java.math.BigDecimal

/**
 * What survives a configuration change (a rotation, a fold, a change of the window) when the host's activity is recreated:
 * the stack of screens with the records they were opened with, and the values the person entered. They are saved as JSON
 * text and read back with the same tolerant reader as a document; a stack that names a screen the document no longer has
 * is dropped for a fresh flow. The width class is not saved: it is read from the box the screen is given, so a rotation
 * resolves the tree again by itself.
 */
internal object ConfigChangeState {
    fun flowSaver(document: FlowDocument, entry: String?): Saver<Flow, String> = Saver(
        save = { flow -> encode(JsonValue.of(mapOf("stack" to flow.stack.map(::frame)))) },
        restore = { text -> restoreFlow(document, entry, text) },
    )

    fun sessionSaver(): Saver<DataScope, String> = Saver(
        save = { session -> encode(JsonValue.Obj(JsonObject(session.entries.map { it.key to it.value }))) },
        restore = { text -> runCatching { JsonValue.parse(text).obj?.toMap().orEmpty() }.getOrDefault(emptyMap()) },
    )

    private fun frame(frame: FlowFrame): JsonValue = JsonValue.of(
        mapOf(
            "screenId" to frame.screenId,
            "chain" to frame.chain.map { link -> mapOf("source" to link.source, "item" to link.item, "fields" to JsonValue.Obj(link.fields)) },
        ),
    )

    private fun restoreFlow(document: FlowDocument, entry: String?, text: String): Flow? {
        val fresh = Flow(document, entry)
        val frames = runCatching {
            JsonValue.parse(text)["stack"]?.array.orEmpty().mapNotNull { item ->
                val screenId = item["screenId"]?.string ?: return@mapNotNull null
                val chain = item["chain"]?.array.orEmpty().mapNotNull { link ->
                    val fields = link["fields"]?.obj ?: return@mapNotNull null
                    RecordLink(link["source"]?.string ?: "", link["item"]?.string ?: return@mapNotNull null, fields)
                }
                FlowFrame(screenId, chain)
            }
        }.getOrNull().orEmpty()
        if (frames.isEmpty() || frames.any { document.screens.none { screen -> screen.screenId == it.screenId } }) return fresh
        return fresh.withStack(frames)
    }

    /** JSON text of a value: strings escaped, numbers as the shortest decimal; non-finite numbers are not JSON and are written as null. */
    fun encode(value: JsonValue): String = StringBuilder().also { write(value, it) }.toString()

    private fun write(value: JsonValue, out: StringBuilder) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(value.value)
            is JsonValue.Num -> out.append(if (value.value.isFinite()) number(value.value) else "null")
            is JsonValue.Str -> string(value.value, out)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item -> if (i > 0) out.append(','); write(item, out) }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    string(key, out)
                    out.append(':')
                    write(item, out)
                }
                out.append('}')
            }
        }
    }

    private fun number(value: Double): String =
        if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else BigDecimal(value.toString()).toPlainString()

    private fun string(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}
