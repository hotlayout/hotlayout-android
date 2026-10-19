package com.hotlayout.support

import com.hotlayout.actions.HotlayoutAction
import com.hotlayout.actions.ListInstance
import com.hotlayout.actions.ScreenShownEvent
import com.hotlayout.actions.StandardAction
import com.hotlayout.actions.TrackEvent
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue

/** The values of the reader written as the tables write them, to compare JSON with JSON. */
object TableJson {
    fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue = JsonValue.Obj(JsonObject(pairs.toList()))

    fun strings(map: Map<String, String>): JsonValue = JsonValue.Obj(JsonObject(map.entries.sortedBy { it.key }.map { it.key to JsonValue.Str(it.value) }))

    fun record(record: DataScope?): JsonValue = record?.let { JsonValue.Obj(JsonObject(it)) } ?: JsonValue.Null

    fun handed(action: HotlayoutAction?): JsonValue {
        if (action == null) return JsonValue.Null
        val pairs = ArrayList<Pair<String, JsonValue>>()
        pairs.add("type" to JsonValue.Str(action.type))
        action.params?.let { pairs.add("params" to strings(it)) }
        when (val standard = action.standard) {
            is StandardAction.OpenUrl -> pairs.add("standard" to obj("type" to JsonValue.Str("openUrl"), "url" to JsonValue.Str(standard.url)))
            is StandardAction.Deeplink -> pairs.add("standard" to obj("type" to JsonValue.Str("deeplink"), "url" to JsonValue.Str(standard.url), "scheme" to JsonValue.Str(standard.scheme)))
            StandardAction.Close -> pairs.add("standard" to obj("type" to JsonValue.Str("close")))
            null -> Unit
        }
        action.invalid?.let { pairs.add("invalid" to obj("param" to JsonValue.Str(it.param), "problem" to JsonValue.Str(it.problem.word))) }
        return JsonValue.Obj(JsonObject(pairs))
    }

    fun shown(event: ScreenShownEvent?): JsonValue {
        if (event == null) return JsonValue.Null
        val pairs = arrayListOf(
            "documentId" to JsonValue.Str(event.documentId),
            "screenId" to JsonValue.Str(event.screenId),
            "entry" to (event.entry?.let { JsonValue.Str(it) } ?: JsonValue.Null),
            "presentation" to JsonValue.Str(event.presentation.word),
            "cause" to JsonValue.Str(event.cause.word),
            "depth" to JsonValue.Num(event.depth.toDouble()),
            "record" to record(event.record),
        )
        event.modalPlacement?.let { pairs.add("modalPlacement" to JsonValue.Str(it)) }
        event.statusBarIcons?.let { pairs.add("statusBarIcons" to JsonValue.Str(it)) }
        return JsonValue.Obj(JsonObject(pairs))
    }

    fun track(event: TrackEvent?): JsonValue {
        if (event == null) return JsonValue.Null
        val pairs = arrayListOf<Pair<String, JsonValue>>("event" to JsonValue.Str(event.event))
        event.params?.let { pairs.add("params" to strings(it)) }
        return JsonValue.Obj(JsonObject(pairs))
    }

    fun instances(value: JsonValue?): List<ListInstance> = value?.array.orEmpty().mapNotNull { it.obj }.map {
        ListInstance(it["item"]?.string ?: "", it["fields"]?.obj ?: JsonObject(), it["source"]?.string ?: "")
    }

    /** `{ "repeat": { prefix, char, length } }` stands for a built string wherever a string may stand. */
    fun expand(value: JsonValue): JsonValue = when (value) {
        is JsonValue.Obj -> {
            val spec = value["repeat"]?.obj
            if (value.fields.size == 1 && spec != null) JsonValue.Str(built(spec)) else JsonValue.Obj(JsonObject(value.fields.map { it.first to expand(it.second) }))
        }
        is JsonValue.Arr -> JsonValue.Arr(value.items.map(::expand))
        else -> value
    }

    fun built(spec: JsonObject): String {
        val prefix = spec["prefix"]?.string ?: ""
        val char = spec["char"]?.string ?: "a"
        val length = (spec["length"]?.number ?: 0.0).toInt()
        return prefix + char.repeat(maxOf(0, length - prefix.length))
    }
}
