package com.hotlayout.placements

import com.hotlayout.JsonObject
import com.hotlayout.JsonParseException
import com.hotlayout.JsonValue

/**
 * The placement map (`placements.json` of the contract): which published document each named place of the
 * app shows. This is the tolerant reading of a reader, as the shared table `placements/placement-cases.json`
 * runs it; the strict form a builder publishes is not checked here.
 */
internal object PlacementRules {
    /** The version of the placement contract this reader is written against. */
    const val CONTRACT_VERSION = "1.0.0"

    /** `MAJOR.MINOR.PATCH` without leading zeros, as the contract writes it; the major stays text so no number overflows. */
    private val VERSION = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
    private const val READER_MAJOR = "1"
    const val NAME_PATTERN = "^[a-z][a-z0-9]*(\\.[a-z0-9-]+)*$"
    const val DOCUMENT_ID_PATTERN = "^(?!\\.+$)[A-Za-z0-9._-]{1,64}$"
    const val ENTRY_PATTERN = "^[a-z0-9][a-z0-9-]*$"
    private val NAME = Regex(NAME_PATTERN)
    private val DOCUMENT_ID = Regex(DOCUMENT_ID_PATTERN)
    private val ENTRY = Regex(ENTRY_PATTERN)
    const val NAME_MAX = 64
    const val ENTRY_MAX = 48

    /** A name that can be a placement of some map. Anything else is `unknown` without a look at the map. */
    fun isName(name: String): Boolean = name.length <= NAME_MAX && NAME.matches(name)

    /** A document id that is safe as one segment of an address. */
    fun isDocumentId(id: String): Boolean = DOCUMENT_ID.matches(id)

    /** The map file as read: places are kept raw so one broken place spoils only itself. */
    sealed class MapRead {
        class Map(val places: JsonObject) : MapRead()
        data object Corrupt : MapRead()
        data object Unsupported : MapRead()
    }

    fun readMap(raw: JsonValue): MapRead {
        val root = raw.obj ?: return MapRead.Corrupt
        val version = root["placementsVersion"]?.string ?: return MapRead.Corrupt
        val major = VERSION.matchEntire(version)?.groupValues?.get(1) ?: return MapRead.Corrupt
        if (major != READER_MAJOR) return MapRead.Unsupported
        val places = root["placements"]?.obj ?: return MapRead.Corrupt
        return MapRead.Map(places)
    }

    /** A text that is not JSON is `Corrupt`, not an exception: «the map is broken» takes the same road as any other state. */
    fun readMapText(text: String): MapRead = try {
        readMap(JsonValue.parse(text.toByteArray(Charsets.UTF_8)))
    } catch (_: JsonParseException) {
        MapRead.Corrupt
    }

    enum class Status { Document, Empty, Unknown, Unreadable }

    data class Resolution(val status: Status, val documentId: String? = null, val entry: String? = null)

    private val UNREADABLE = Resolution(Status.Unreadable)
    private val UNKNOWN = Resolution(Status.Unknown)

    /**
     * What stands in the place [name]. The order is part of the rule: a map that was not read makes every name
     * `unreadable`; a name of the wrong form is `unknown`; then only an OWN key of the map counts.
     */
    fun resolve(map: MapRead, name: String): Resolution {
        if (map !is MapRead.Map) return UNREADABLE
        if (!isName(name) || !map.places.containsKey(name)) return UNKNOWN
        return readPlace(map.places[name])
    }

    private fun readPlace(value: JsonValue?): Resolution {
        val place = value?.obj ?: return UNREADABLE
        val id = place["documentId"] ?: return UNREADABLE
        if (id.isNull) return Resolution(Status.Empty)
        val documentId = id.string?.takeIf { isDocumentId(it) } ?: return UNREADABLE
        val entry = place["entry"]
        if (entry == null || entry.isNull) return Resolution(Status.Document, documentId)
        val name = entry.string?.takeIf { it.length <= ENTRY_MAX && ENTRY.matches(it) } ?: return UNREADABLE
        return Resolution(Status.Document, documentId, name)
    }

    /** What the network said about the map file. */
    sealed class Network {
        class Map(val text: String) : Network()
        data object NotModified : Network()
        data object NotFound : Network()
        data object Offline : Network()
    }

    enum class SourceStatus { Fresh, Offline, Corrupt, Unsupported, NotPublished }
    enum class Origin { Network, LastKnown, Bundled, None }

    class Decision(val origin: Origin, val status: SourceStatus, val remember: Boolean, val map: MapRead)

    /** With no map at all: «never published» is the server's truth, so every place is unknown; otherwise unreadable. */
    private val NO_MAP_PUBLISHED = MapRead.Map(JsonObject())

    private fun fallback(lastKnown: String?, bundled: String?, status: SourceStatus): Decision {
        lastKnown?.let { readMapText(it) }?.let { if (it is MapRead.Map) return Decision(Origin.LastKnown, status, false, it) }
        bundled?.let { readMapText(it) }?.let { if (it is MapRead.Map) return Decision(Origin.Bundled, status, false, it) }
        return Decision(Origin.None, status, false, if (status == SourceStatus.NotPublished) NO_MAP_PUBLISHED else MapRead.Corrupt)
    }

    /** Which map to use. The order of fallbacks is the same for every cause: the last known map, then the bundled one. */
    fun pickMap(network: Network, lastKnown: String?, bundled: String?): Decision = when (network) {
        is Network.Map -> when (val read = readMapText(network.text)) {
            is MapRead.Map -> Decision(Origin.Network, SourceStatus.Fresh, true, read)
            MapRead.Corrupt -> fallback(lastKnown, bundled, SourceStatus.Corrupt)
            MapRead.Unsupported -> fallback(lastKnown, bundled, SourceStatus.Unsupported)
        }
        Network.NotModified -> {
            val last = lastKnown?.let { readMapText(it) }
            // A 304 without a readable last known map is the same as no answer.
            if (last is MapRead.Map) Decision(Origin.LastKnown, SourceStatus.Fresh, false, last)
            else fallback(null, bundled, SourceStatus.Offline)
        }
        Network.NotFound -> fallback(lastKnown, bundled, SourceStatus.NotPublished)
        Network.Offline -> fallback(lastKnown, bundled, SourceStatus.Offline)
    }

    /** What happened to the document of a place, after the reader's own fallbacks for documents. */
    sealed class DocumentOutcome {
        class Ok(val entryPoints: Set<String>) : DocumentOutcome()
        data object Missing : DocumentOutcome()
        data object Unreadable : DocumentOutcome()
    }

    enum class Report { Empty, Unknown, Unreadable, DocumentMissing, DocumentUnreadable, EntryMissing }

    class Showing(val show: Boolean, val entry: String?, val report: Report?)

    /**
     * What a place draws. An entry point the document does not have is the initial screen
     * (`unknownEntryPoint: use-initial`), and the host hears about it: it is a setup mistake somebody must fix.
     */
    fun showing(resolution: Resolution, document: DocumentOutcome?): Showing {
        if (resolution.status != Status.Document) {
            val report = when (resolution.status) {
                Status.Empty -> Report.Empty
                Status.Unknown -> Report.Unknown
                else -> Report.Unreadable
            }
            return Showing(false, null, report)
        }
        return when (document) {
            null, DocumentOutcome.Unreadable -> Showing(false, null, Report.DocumentUnreadable)
            DocumentOutcome.Missing -> Showing(false, null, Report.DocumentMissing)
            is DocumentOutcome.Ok -> when {
                resolution.entry == null -> Showing(true, null, null)
                resolution.entry in document.entryPoints -> Showing(true, resolution.entry, null)
                else -> Showing(true, null, Report.EntryMissing)
            }
        }
    }

}
