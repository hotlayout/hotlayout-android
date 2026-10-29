package com.hotlayout

import com.hotlayout.placements.PlacementRules
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val OFFLINE_BASE = "https://storage.example/orgs/o/projects/p"

private fun at(text: String) = PlacementRules.instantMs(text) ?: error(text)

private fun page(id: String) =
    """{"schemaVersion":"9.0.0","documentId":"$id","name":"$id","initialScreenId":"s1","entryPoints":{},"screens":[{"screenId":"s1","name":"S","root":{"id":"r","type":"Screen","props":{},"children":[]}}]}"""

private const val OFFLINE_MAP =
    """{"placementsVersion":"1.1.0","placements":{"home.banner":{"documentId":"d-everyday","schedule":[""" +
        """{"documentId":"d-sale","from":"2026-11-27T22:00:00Z","until":"2026-11-30T22:00:00Z"}]}}}"""

/** A network that can be cut: once cut, the transport throws for every request. */
private class CuttableTransport : PlacementTransport {
    val cut = AtomicBoolean(false)
    override suspend fun get(url: String, etag: String?, maxBytes: Int): PlacementResponse {
        if (cut.get()) throw IOException("no network")
        return when {
            url.endsWith("/placements/published.json") -> PlacementResponse.Body(OFFLINE_MAP, null)
            url.endsWith("/documents/d-everyday/published.json") -> PlacementResponse.Body(page("d-everyday"), null)
            url.endsWith("/documents/d-sale/published.json") -> PlacementResponse.Body(page("d-sale"), null)
            else -> PlacementResponse.NotFound
        }
    }
}

class PlacementOfflineTest {
    @Test fun aScheduledWindowOpensWithoutTheNetworkBecauseItsDocumentWasReadAhead() = runBlocking {
        val clock = AtomicLong(at("2026-11-27T21:00:00Z"))
        val transport = CuttableTransport()
        val source = PlacementSource(OFFLINE_BASE, transport = transport, clock = { clock.get() })
        assertEquals("d-everyday", loadPlacement(source, "home.banner").document?.documentId)
        source.prefetching?.join()
        transport.cut.set(true)

        clock.set(at("2026-11-27T22:00:00Z"))
        source.forget()
        val sale = loadPlacement(source, "home.banner")
        assertEquals("d-sale", sale.document?.documentId)
        // The same decision again, offline, keeps the document.
        source.forget()
        assertEquals("d-sale", loadPlacement(source, "home.banner").document?.documentId)

        clock.set(at("2026-11-30T22:00:00Z"))
        source.forget()
        assertEquals("d-everyday", loadPlacement(source, "home.banner").document?.documentId)
        assertNull(loadPlacement(source, "home.banner").report)
    }

    @Test fun theKeptDocumentsAreCappedAtSixtyFour() = runBlocking {
        val store = MemoryPlacementStore()
        val transport = object : PlacementTransport {
            override suspend fun get(url: String, etag: String?, maxBytes: Int): PlacementResponse =
                if (url.contains("/documents/")) PlacementResponse.Body(page("d"), null) else PlacementResponse.Unavailable
        }
        val source = PlacementSource(OFFLINE_BASE, store = store, transport = transport)
        for (i in 0 until 70) source.document("d$i")
        val kept = (0 until 70).count { store.read(source.documentKey("d$it")) != null }
        assertEquals(PlacementSource.MAX_KEPT_DOCUMENTS, kept)
        assertNull(store.read(source.documentKey("d0")))
    }
}
