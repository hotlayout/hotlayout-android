package com.hotlayout.data

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.ScreenPresentation

/**
 * Values available to a node: a flat map from a data PATH to a value.
 *
 * Flat on purpose. A path in the contract is a string (`task.title`), not a walk through nested
 * objects; splitting it would raise questions the contract does not answer.
 */
public typealias DataScope = Map<String, JsonValue>

/** A data scope from plain Kotlin values (see [JsonValue.of]). */
public fun dataScopeOf(vararg pairs: Pair<String, Any?>): DataScope = pairs.associate { it.first to JsonValue.of(it.second) }

/**
 * The four data layers of a screen, strongest first (`x-dataScope.layers`): the record of the
 * row or of the opened screen, the session (what the person chose or typed), what the host
 * addressed to this screen, and the host data.
 */
public enum class DataLayer(public val word: String) { Record("record"), Session("session"), Host("host"), Data("data") }

public data class DataLayers(
    val record: DataScope? = null,
    val session: DataScope? = null,
    val host: DataScope? = null,
    val data: DataScope? = null,
) {
    public operator fun get(layer: DataLayer): DataScope? = when (layer) {
        DataLayer.Record -> record
        DataLayer.Session -> session
        DataLayer.Host -> host
        DataLayer.Data -> data
    }
}

public object RecordScope {
    /** Absence: a missing key or `null`. Neither shadows a weaker layer (`absentInDataLayer: 'read-next-layer'`). */
    public fun isAbsent(value: JsonValue?): Boolean = value == null || value.isNull

    /** Reads a path through the layers: the first layer with a present value answers. */
    public fun read(layers: DataLayers, path: String): Pair<DataLayer, JsonValue>? {
        for (layer in DataLayer.entries) {
            val value = layers[layer]?.get(path)
            if (!isAbsent(value)) return layer to value!!
        }
        return null
    }

    /** The layers flattened into one scope, strongest on top. */
    public fun merge(layers: DataLayers): DataScope {
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
    public fun instanceRecord(item: String, fields: JsonObject): DataScope {
        val record = LinkedHashMap<String, JsonValue>()
        for ((field, value) in fields) {
            if (isAbsent(value)) continue
            record[if (field.startsWith("$item.")) field else "$item.$field"] = value
        }
        return record
    }

    /** `inner` laid over `outer`: an absent value of the inner scope does not hide the outer one. */
    public fun overlay(outer: DataScope?, inner: DataScope?): DataScope? {
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
    public fun forOpenedScreen(nodeRecord: DataScope?, insideListItem: Boolean, presentation: ScreenPresentation, carryRecord: Boolean): DataScope? =
        if (insideListItem || presentation == ScreenPresentation.Modal || carryRecord) nodeRecord else null
}

/**
 * Which data paths the reader may write to. The document names the path of a write, and a
 * document arrives over the network: the host says what it expects. By default every path of the
 * shape of a data path is allowed (a forbidden segment such as `__proto__` never is).
 */
public class WritablePaths private constructor(private val allows: (String) -> Boolean) {
    public constructor(paths: Set<String>) : this({ it in paths })

    internal fun permits(path: String): Boolean = allows(path)

    public companion object {
        public fun where(predicate: (String) -> Boolean): WritablePaths = WritablePaths(predicate)

        /** The gate of the contract (the shape of a data path), then the host's list when there is one. */
        internal fun permits(gate: WritablePaths?, path: String): Boolean =
            com.hotlayout.actions.ActionCatalog.isDataPath(path) && (gate?.permits(path) ?: true)
    }
}
