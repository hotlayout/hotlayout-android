package com.hotlayout.data

import com.hotlayout.actions.ListInstance
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode

/**
 * One link of the record a screen holds: the `Repeater` instance a record came from. The
 * collection the list read (`source`), the entity (`item`, the list's prefix) and the fields of
 * the record as the host gave them.
 */
public data class RecordLink(val source: String, val item: String, val fields: JsonObject)

/** The collections of each entity, as the DOCUMENT names them (`entity` → `source` of every `Repeater`). */
public typealias EntitySources = Map<String, List<String>>

/**
 * The live record of a page (`x-liveRecord`).
 *
 * The record a frame of the stack holds is a CHAIN of links, outer first; the record itself is the
 * overlay of the links, the inner one stronger. When the host gives new collections, every link is
 * searched for by its key (`<item>.id`): first in its own collection, then in the other collections
 * of the same entity; a link found takes the new record whole, a link not found keeps the last
 * record it had. Nothing here is a show of a screen: the stack does not move.
 */
public object LiveRecord {
    public const val KEY_FIELD: String = "id"

    /** The record of a chain: the links laid over each other, the inner one stronger. */
    public fun record(chain: List<RecordLink>): DataScope? {
        var record: DataScope? = null
        for (link in chain) record = RecordScope.overlay(record, RecordScope.instanceRecord(link.item, link.fields))
        return record
    }

    /** The chain of a node: the links of its frame, then the instances it is drawn inside. */
    public fun chain(frame: List<RecordLink>, instances: List<ListInstance>): List<RecordLink> =
        frame + instances.map { RecordLink(it.source, it.item, it.fields) }

    /**
     * The value of the key field the way `instanceRecord` would write it: the short name and the
     * full path are one field, an absent one overrides nothing, and when both are present the one
     * later in the order of keys wins.
     */
    internal fun keyFieldValue(item: String, fields: JsonObject): JsonValue? {
        val full = "$item.$KEY_FIELD"
        val short = fields[KEY_FIELD]?.takeUnless(RecordScope::isAbsent)
        val long = fields[full]?.takeUnless(RecordScope::isAbsent)
        if (short == null) return long
        if (long == null) return short
        val keys = fields.keys
        return if (keys.indexOf(KEY_FIELD) > keys.indexOf(full)) short else long
    }

    /** The key of a record: a non-empty string or a finite number; anything else and the record is not live. */
    public fun key(item: String, fields: JsonObject): JsonValue? = when (val value = keyFieldValue(item, fields)) {
        is JsonValue.Str -> value.takeIf { it.value.isNotEmpty() }
        is JsonValue.Num -> value.takeIf { it.value.isFinite() }
        else -> null
    }

    /** The collections of each entity named by the document, from the roots of its screens. */
    public fun entitySources(roots: List<DocumentNode>): EntitySources {
        val found = LinkedHashMap<String, MutableList<String>>()
        fun walk(node: DocumentNode, depth: Int) {
            if (depth > 64) return
            val source = node.props["source"]?.string
            val item = node.props["item"]?.string
            if (node.type == "Repeater" && !source.isNullOrEmpty() && !item.isNullOrEmpty()) {
                val list = found.getOrPut(item) { ArrayList() }
                if (source !in list) list.add(source)
            }
            for (child in node.children) walk(child, depth + 1)
        }
        for (root in roots) walk(root, 0)
        return found
    }

    /** The same from the plain list of `Repeater`s a table gives (document order). */
    public fun entitySourcesOfLists(lists: List<Pair<String, String>>): EntitySources {
        val found = LinkedHashMap<String, MutableList<String>>()
        for ((source, item) in lists) {
            if (source.isEmpty() || item.isEmpty()) continue
            val list = found.getOrPut(item) { ArrayList() }
            if (source !in list) list.add(source)
        }
        return found
    }

    /** Where to look for a link: its own collection first, then the other collections of the same entity. */
    public fun searchOrder(link: RecordLink, sources: EntitySources): List<String> =
        listOf(link.source) + sources[link.item].orEmpty().filter { it != link.source }

    /** The record of a link in the collections the host gives now; the same JSON type and equal without conversion. */
    public fun find(link: RecordLink, collections: (String) -> JsonValue?, sources: EntitySources): JsonObject? {
        val wanted = key(link.item, link.fields) ?: return null
        for (source in searchOrder(link, sources)) {
            val listed = collections(source)?.array ?: continue
            for (candidate in listed) {
                val fields = candidate.obj ?: continue
                if (key(link.item, fields) == wanted) return fields
            }
        }
        return null
    }

    /** The chain after the host gave new collections; nothing changed — an equal chain. */
    public fun refresh(chain: List<RecordLink>, collections: (String) -> JsonValue?, sources: EntitySources): List<RecordLink> = chain.map { link ->
        val found = find(link, collections, sources)
        if (found == null || RecordScope.instanceRecord(link.item, found) == RecordScope.instanceRecord(link.item, link.fields)) {
            link
        } else {
            RecordLink(link.source, link.item, found)
        }
    }
}
