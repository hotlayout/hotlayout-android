package com.hotlayout

import com.hotlayout.placements.PlacementRules
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where the placements of one project come from: the map `placements/published.json` and the published
 * documents it names, both under [projectBase]. Read without a token.
 *
 * The map is asked for with its `ETag` (a 304 costs no body). The answer of the network wins; when it is silent,
 * broken or of another major version, the last known map is used, then [bundledMap]. Only a fresh, readable map
 * from the network becomes the last known one. A document goes the same way: the network, the last known copy,
 * [bundledDocuments].
 *
 * One source serves every placement of the app: keep it in your application object, not in a composition.
 *
 * @param projectBase the address of the project in the storage, `https://…/orgs/{org}/projects/{project}`.
 * @param bundledMap the map shipped with the app release, as JSON text; used when no other map can be read.
 * @param bundledDocuments the documents shipped with the app release: a document id to its JSON text.
 * @param store where the last known map and documents are kept; in memory by default.
 * @param transport how the requests go; `https` without cookies by default.
 */
public class PlacementSource(
    projectBase: String,
    internal val bundledMap: String? = null,
    internal val bundledDocuments: (documentId: String) -> String? = { null },
    internal val store: PlacementStore = MemoryPlacementStore(),
    internal val transport: PlacementTransport = PlacementTransport.Default,
) {
    internal val base: String = projectBase.trim().trimEnd('/')

    /** Where the last known map is kept: its address, so a store shared by several projects keeps them apart. */
    internal val mapAddress: String = "$base/placements/published.json"
    internal val mapKey: String = "map:$mapAddress"
    internal fun documentAddress(id: String): String = "$base/documents/$id/published.json"
    internal fun documentKey(id: String): String = "document:${documentAddress(id)}"
    private val gate = Mutex()
    private var recent: Recent? = null

    private class Recent(val at: Long, val decision: PlacementRules.Decision)

    /** The map to use right now. Concurrent callers share one request: an answer younger than [SHARE_MS] is reused. */
    internal suspend fun map(): PlacementRules.Decision = gate.withLock {
        recent?.takeIf { System.nanoTime() - it.at < SHARE_MS * 1_000_000 }?.let { return it.decision }
        val decision = fetchMap()
        recent = Recent(System.nanoTime(), decision)
        decision
    }

    /** Drops the shared answer: the next [map] asks the network. */
    internal suspend fun forget() = gate.withLock { recent = null }

    private suspend fun fetchMap(): PlacementRules.Decision {
        val lastKnown = store.read(mapKey)
        val sendTag = lastKnown?.takeIf { PlacementRules.readMapText(it.text) is PlacementRules.MapRead.Map }?.etag
        val answer = safeGet(mapAddress, sendTag, MAP_MAX_BYTES)
        val network = when (answer) {
            is PlacementResponse.Body -> PlacementRules.Network.Map(answer.bytes)
            // A body above the limit is something that is not a map.
            PlacementResponse.TooLarge -> PlacementRules.Network.Map(ByteArray(0))
            PlacementResponse.NotModified -> PlacementRules.Network.NotModified
            PlacementResponse.NotFound -> PlacementRules.Network.NotFound
            PlacementResponse.Unavailable -> PlacementRules.Network.Offline
        }
        val decision = PlacementRules.pickMap(network, lastKnown?.text, bundledMap)
        if (decision.remember && answer is PlacementResponse.Body) {
            // The text that was read: strict UTF-8, without the byte order mark.
            val text = PlacementRules.decodeBody(answer.bytes)
            if (text != null) runCatching { store.write(mapKey, StoredText(text, answer.etag)) }
        }
        return decision
    }

    internal suspend fun safeGet(url: String, etag: String?, maxBytes: Int): PlacementResponse = try {
        transport.get(url, etag, maxBytes)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        PlacementResponse.Unavailable
    }

    /** The document [id], through the network, the last known copy and the bundled one. */
    internal suspend fun document(id: String): DocumentResult {
        val key = documentKey(id)
        val kept = store.read(key)
        val keptDocument = kept?.let { readDocument(it.text.toByteArray(Charsets.UTF_8)) }
        val answer = safeGet(documentAddress(id), kept?.takeIf { keptDocument != null }?.etag, DOCUMENT_MAX_BYTES)
        when (answer) {
            is PlacementResponse.Body -> {
                val fresh = readDocument(answer.bytes)
                if (fresh != null) {
                    runCatching { store.write(key, StoredText(answer.text, answer.etag)) }
                    return outcome(fresh)
                }
            }
            PlacementResponse.NotModified -> if (keptDocument != null) return outcome(keptDocument)
            // A document that was taken down must not live on from the cache; only the copy of the release stands in.
            PlacementResponse.NotFound -> {
                runCatching { store.remove(key) }
                return bundledDocuments(id)?.let { readDocument(it.toByteArray(Charsets.UTF_8)) }?.let { outcome(it) }
                    ?: DocumentResult(PlacementRules.DocumentOutcome.Missing)
            }
            else -> Unit
        }
        val fallback = keptDocument ?: bundledDocuments(id)?.let { readDocument(it.toByteArray(Charsets.UTF_8)) }
        return fallback?.let { outcome(it) } ?: DocumentResult(PlacementRules.DocumentOutcome.Unreadable)
    }

    private suspend fun readDocument(bytes: ByteArray): HotlayoutDocument? = withContext(Dispatchers.Default) {
        try {
            loadDocument(bytes)
        } catch (_: HotlayoutLoadException) {
            null
        }
    }

    private fun outcome(document: HotlayoutDocument) =
        DocumentResult(PlacementRules.DocumentOutcome.Ok(document.entryPoints.keys.toSet()), document)

    internal class DocumentResult(val outcome: PlacementRules.DocumentOutcome, val document: HotlayoutDocument? = null)

    public companion object {
        /** The default pause between two asks of the map in [HotlayoutPlacement]. */
        public const val DEFAULT_REFRESH_MS: Long = 60_000L
        internal const val MAP_MAX_BYTES = PlacementRules.MAX_BODY_BYTES
        internal const val DOCUMENT_MAX_BYTES = 4 * 1024 * 1024
        internal const val SHARE_MS = 2_000L
    }
}

/**
 * What the place [name] shows: asks the map (see [PlacementSource]), then the document it names. Runs off the main
 * thread and never throws on a bad answer — a bad answer is a [PlacementLoad] with a [PlacementLoad.report].
 */
public suspend fun loadPlacement(source: PlacementSource, name: String): PlacementLoad {
    val decision = source.map()
    val resolution = PlacementRules.resolve(decision.map, name)
    var outcome: PlacementRules.DocumentOutcome? = null
    var document: HotlayoutDocument? = null
    if (resolution.status == PlacementRules.Status.Document) {
        val result = source.document(requireNotNull(resolution.documentId))
        outcome = result.outcome
        document = result.document
    }
    val showing = PlacementRules.showing(resolution, outcome)
    return PlacementLoad(
        name = name,
        mapStatus = decision.status.toPublic(),
        mapOrigin = decision.origin.toPublic(),
        resolution = PlacementResolution(resolution.status.toPublic(), resolution.documentId, resolution.entry),
        document = if (showing.show) document else null,
        entry = showing.entry,
        report = showing.report?.toPublic(),
    )
}

private fun PlacementRules.Status.toPublic() = when (this) {
    PlacementRules.Status.Document -> PlacementStatus.Document
    PlacementRules.Status.Empty -> PlacementStatus.Empty
    PlacementRules.Status.Unknown -> PlacementStatus.Unknown
    PlacementRules.Status.Unreadable -> PlacementStatus.Unreadable
}

private fun PlacementRules.SourceStatus.toPublic() = when (this) {
    PlacementRules.SourceStatus.Fresh -> PlacementMapStatus.Fresh
    PlacementRules.SourceStatus.Offline -> PlacementMapStatus.Offline
    PlacementRules.SourceStatus.Corrupt -> PlacementMapStatus.Corrupt
    PlacementRules.SourceStatus.Unsupported -> PlacementMapStatus.Unsupported
    PlacementRules.SourceStatus.NotPublished -> PlacementMapStatus.NotPublished
}

private fun PlacementRules.Origin.toPublic() = when (this) {
    PlacementRules.Origin.Network -> PlacementMapOrigin.Network
    PlacementRules.Origin.LastKnown -> PlacementMapOrigin.LastKnown
    PlacementRules.Origin.Bundled -> PlacementMapOrigin.Bundled
    PlacementRules.Origin.None -> PlacementMapOrigin.None
}

private fun PlacementRules.Report.toPublic() = when (this) {
    PlacementRules.Report.Empty -> PlacementReport.Empty
    PlacementRules.Report.Unknown -> PlacementReport.Unknown
    PlacementRules.Report.Unreadable -> PlacementReport.Unreadable
    PlacementRules.Report.DocumentMissing -> PlacementReport.DocumentMissing
    PlacementRules.Report.DocumentUnreadable -> PlacementReport.DocumentUnreadable
    PlacementRules.Report.EntryMissing -> PlacementReport.EntryMissing
}
