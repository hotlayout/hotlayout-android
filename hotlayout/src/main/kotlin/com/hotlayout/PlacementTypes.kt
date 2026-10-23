package com.hotlayout

import com.hotlayout.placements.PlacementRules

/** What stands in a place of the map. */
public enum class PlacementStatus {
    /** A document stands there. */
    Document,

    /** The place is declared and empty (`documentId: null`). */
    Empty,

    /** The map has no such place, or the name is not the name of any place. */
    Unknown,

    /** No map was read, or the record of this place is broken. */
    Unreadable,
}

/** The answer to «what stands in this place»: the document id and the entry point, when there is a document. */
public data class PlacementResolution(
    val status: PlacementStatus,
    val documentId: String? = null,
    val entry: String? = null,
)

/** How the map was got. `Offline` is «the network was silent»; `Corrupt` is «it answered with something that is not a map». */
public enum class PlacementMapStatus {
    /** The network answered with a readable map, or 304 with a readable last known one. */
    Fresh,

    /** No answer: a connection error, a timeout, 5xx, 403, or 304 without a readable last known map. */
    Offline,

    /** The network answered with something that is not a map. */
    Corrupt,

    /** The network answered with a map of another major version. */
    Unsupported,

    /** The network answered 404: the project has not published placements. */
    NotPublished,
}

/** Whose map the answer is read from. */
public enum class PlacementMapOrigin { Network, LastKnown, Bundled, None }

/** Why a place draws nothing, or draws its document from the initial screen: the host is told, the person is not. */
public enum class PlacementReport {
    /** The place is declared and empty. */
    Empty,

    /** The map has no such place. */
    Unknown,

    /** No map was read, or the record of the place is broken. */
    Unreadable,

    /** The document of the place is gone (404 after the reader's own fallbacks). */
    DocumentMissing,

    /** The document could not be read. */
    DocumentUnreadable,

    /** The entry point is not in the document: the document is drawn from its initial screen. */
    EntryMissing,
}

/** The result of [loadPlacement]. A document is there when [document] is not `null`; [report] says why not, or what is wrong. */
public data class PlacementLoad(
    val name: String,
    val mapStatus: PlacementMapStatus,
    val mapOrigin: PlacementMapOrigin,
    val resolution: PlacementResolution,
    /** The document to draw, or `null` for «draw nothing». */
    val document: HotlayoutDocument?,
    /** The entry point to start on; `null` for the initial screen of the document. */
    val entry: String?,
    /** What to tell the host, or `null` when the place shows its document as asked. */
    val report: PlacementReport?,
)

/** Whether [name] can be the name of a place: lowercase Latin letters and digits, dot-separated segments, up to 64 characters. */
public fun isPlacementName(name: String): Boolean = PlacementRules.isName(name)

/** The text of a file the reader keeps from a good answer, with the `ETag` it came with. */
public class StoredText(public val text: String, public val etag: String?)

/**
 * Where the reader keeps the last known map and documents. Keys are addresses (the address of the map file, the
 * address of the document file), so a store may be shared by several projects. By default in memory: give a store of your own
 * (a file, a preference) to have them after a restart. Called off the main thread; keep it quick.
 */
public interface PlacementStore {
    /** The kept text for [key], or `null`. */
    public fun read(key: String): StoredText?

    /** Keeps [value] under [key]. */
    public fun write(key: String, value: StoredText)

    /** Forgets what is kept under [key]: a document that was taken down must not come back from here. */
    public fun remove(key: String)
}

/** A store that keeps everything in memory. */
public class MemoryPlacementStore : PlacementStore {
    private val items = HashMap<String, StoredText>()
    override fun read(key: String): StoredText? = synchronized(items) { items[key] }
    override fun write(key: String, value: StoredText) { synchronized(items) { items[key] = value } }
    override fun remove(key: String) { synchronized(items) { items.remove(key) } }
}

private fun String.lowerFirst() = replaceFirstChar { it.lowercase() }

/** The five fields the host hears about: a change of any of them is a change of the place. */
internal fun PlacementLoad.settled(): PlacementRules.Settled = PlacementRules.Settled(
    status = if (document != null) "document" else "nothing",
    report = report?.name?.lowerFirst(),
    mapStatus = mapStatus.name.lowerFirst(),
    documentId = if (document != null) resolution.documentId else null,
    entry = if (document != null) entry else null,
)
