package com.hotlayout.data

import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.ScreenPresentation

/**
 * The four data layers of a screen, strongest first (`x-dataScope.layers`): the record of the
 * row or of the opened screen, the session (what the person chose or typed), what the host
 * addressed to this screen, and the host data.
 */
internal enum class DataLayer(internal val word: String) { Record("record"), Session("session"), Host("host"), Data("data") }

internal data class DataLayers(
    val record: DataScope? = null,
    val session: DataScope? = null,
    val host: DataScope? = null,
    val data: DataScope? = null,
) {
    internal operator fun get(layer: DataLayer): DataScope? = when (layer) {
        DataLayer.Record -> record
        DataLayer.Session -> session
        DataLayer.Host -> host
        DataLayer.Data -> data
    }
}

internal object RecordScope {
    /** Absence: a missing key or `null`. Neither shadows a weaker layer (`absentInDataLayer: 'read-next-layer'`). */
    internal fun isAbsent(value: JsonValue?): Boolean = value == null || value.isNull

    /** Reads a path through the layers: the first layer with a present value answers. */
    internal fun read(layers: DataLayers, path: String): Pair<DataLayer, JsonValue>? {
        for (layer in DataLayer.entries) {
            val value = layers[layer]?.get(path)
            if (!isAbsent(value)) return layer to value!!
        }
        return null
    }

    /** The layers flattened into one scope, strongest on top. */
    internal fun merge(layers: DataLayers): DataScope {
        val merged = LinkedHashMap<String, JsonValue>()
        for (layer in DataLayer.entries.reversed()) {
            for ((path, value) in layers[layer].orEmpty()) if (!isAbsent(value)) merged[path] = value
        }
        return merged
    }

    /**
     * The fields of one collection record under the entity prefix: `title` of a `task` record
     * becomes `task.title`. A field that already carries the prefix stays as it is.
     */
    internal fun instanceRecord(item: String, fields: JsonObject): DataScope {
        val record = LinkedHashMap<String, JsonValue>()
        for ((field, value) in fields) {
            if (isAbsent(value)) continue
            record[if (field.startsWith("$item.")) field else "$item.$field"] = value
        }
        return record
    }

    /** `inner` laid over `outer`: an absent value of the inner scope does not hide the outer one. */
    internal fun overlay(outer: DataScope?, inner: DataScope?): DataScope? {
        if (inner == null) return outer
        if (outer == null) return inner
        val merged = LinkedHashMap(outer)
        for ((path, value) in inner) if (!isAbsent(value)) merged[path] = value
        return merged
    }

    /**
     * The record a screen opened by `navigate` gets: a list row, a modal layer, or an explicit
     * `carryRecord` hands the node's record over; a page opened elsewhere gets none.
     */
    internal fun forOpenedScreen(nodeRecord: DataScope?, insideListItem: Boolean, presentation: ScreenPresentation, carryRecord: Boolean): DataScope? =
        if (carries(insideListItem, presentation, carryRecord)) nodeRecord else null

    /** Whether a screen opened by `navigate` gets the record of the node that opened it. */
    internal fun carries(insideListItem: Boolean, presentation: ScreenPresentation, carryRecord: Boolean): Boolean =
        insideListItem || presentation == ScreenPresentation.Modal || carryRecord
}

