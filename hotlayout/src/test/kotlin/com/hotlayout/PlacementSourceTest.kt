package com.hotlayout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val BASE = "https://storage.example/orgs/o/projects/p"

private fun map(vararg places: String) = """{"placementsVersion":"1.0.0","placements":{${places.joinToString(",")}}}"""

private fun document(id: String, vararg entries: String): String {
    val points = entries.joinToString(",") { "\"$it\":\"s1\"" }
    return """{"schemaVersion":"9.0.0","documentId":"$id","name":"$id","initialScreenId":"s1","entryPoints":{$points},"screens":[{"screenId":"s1","name":"S","root":{"id":"r","type":"Screen","props":{},"children":[]}}]}"""
}

/** A transport that answers from a table and remembers what it was asked. */
private class FakeTransport(val answers: MutableMap<String, PlacementResponse> = HashMap()) : PlacementTransport {
    val asked = ArrayList<Pair<String, String?>>()
    override suspend fun get(url: String, etag: String?, maxBytes: Int): PlacementResponse {
        asked.add(url to etag)
        return answers[url] ?: PlacementResponse.Unavailable
    }
}

@RunWith(RobolectricTestRunner::class)
class PlacementSourceTest {
    @get:Rule val compose = createComposeRule()

    private val mapUrl = "$BASE/placements/published.json"
    private fun docUrl(id: String) = "$BASE/documents/$id/published.json"

    @Test fun aPlacementLoadsItsDocumentAndEntry() = runBlocking {
        val transport = FakeTransport()
        transport.answers[mapUrl] = PlacementResponse.Body(map("\"home.banner\":{\"documentId\":\"d1\",\"entry\":\"raffle\"}"), "\"v1\"")
        transport.answers[docUrl("d1")] = PlacementResponse.Body(document("d1", "raffle"), null)
        val load = loadPlacement(PlacementSource(BASE, transport = transport), "home.banner")
        assertEquals(PlacementMapStatus.Fresh, load.mapStatus)
        assertEquals("d1", load.document?.documentId)
        assertEquals("raffle", load.entry)
        assertNull(load.report)
    }

    @Test fun anEntryTheDocumentLostDrawsTheInitialScreenAndTellsTheHost() = runBlocking {
        val transport = FakeTransport()
        transport.answers[mapUrl] = PlacementResponse.Body(map("\"home.banner\":{\"documentId\":\"d1\",\"entry\":\"raffle\"}"), null)
        transport.answers[docUrl("d1")] = PlacementResponse.Body(document("d1", "rules"), null)
        val load = loadPlacement(PlacementSource(BASE, transport = transport), "home.banner")
        assertNotNull(load.document)
        assertNull(load.entry)
        assertEquals(PlacementReport.EntryMissing, load.report)
    }

    @Test fun theMapIsAskedWithItsEtagAndTheLastKnownOneAnswersA304() = runBlocking {
        val transport = FakeTransport()
        val source = PlacementSource(BASE, transport = transport)
        transport.answers[mapUrl] = PlacementResponse.Body(map("\"home.banner\":{\"documentId\":\"d1\"}"), "\"v1\"")
        transport.answers[docUrl("d1")] = PlacementResponse.Body(document("d1"), "\"d\"")
        loadPlacement(source, "home.banner")
        source.forget()
        transport.answers[mapUrl] = PlacementResponse.NotModified
        transport.answers[docUrl("d1")] = PlacementResponse.NotModified
        val again = loadPlacement(source, "home.banner")
        assertEquals("\"v1\"", transport.asked.last { it.first == mapUrl }.second)
        assertEquals("\"d\"", transport.asked.last { it.first == docUrl("d1") }.second)
        assertEquals(PlacementMapStatus.Fresh, again.mapStatus)
        assertEquals(PlacementMapOrigin.LastKnown, again.mapOrigin)
        assertEquals("d1", again.document?.documentId)
    }

    @Test fun aSilentNetworkIsNotACorruptMap() = runBlocking {
        val transport = FakeTransport()
        val source = PlacementSource(BASE, bundledMap = map("\"home.banner\":{\"documentId\":\"d1\"}"), bundledDocuments = { document(it) }, transport = transport)
        val silent = loadPlacement(source, "home.banner")
        assertEquals(PlacementMapStatus.Offline, silent.mapStatus)
        assertEquals(PlacementMapOrigin.Bundled, silent.mapOrigin)
        assertEquals("d1", silent.document?.documentId)

        source.forget()
        transport.answers[mapUrl] = PlacementResponse.Body("<html>Sign in</html>", null)
        assertEquals(PlacementMapStatus.Corrupt, loadPlacement(source, "home.banner").mapStatus)
    }

    @Test fun aMapAboveTheSizeLimitIsNotAMapAndIsNotRemembered() = runBlocking {
        val transport = FakeTransport()
        val store = MemoryPlacementStore()
        transport.answers[mapUrl] = PlacementResponse.TooLarge
        val load = loadPlacement(PlacementSource(BASE, store = store, transport = transport), "home.banner")
        assertEquals(PlacementMapStatus.Corrupt, load.mapStatus)
        assertEquals(PlacementStatus.Unreadable, load.resolution.status)
        assertEquals(PlacementReport.Unreadable, load.report)
        assertNull(store.read("map:$mapUrl"))
    }

    @Test fun onlyAFreshReadableMapBecomesTheLastKnownOne() = runBlocking {
        val store = MemoryPlacementStore()
        val transport = FakeTransport()
        val source = PlacementSource(BASE, store = store, transport = transport)
        transport.answers[mapUrl] = PlacementResponse.Body("""{"placementsVersion":"2.0.0","placements":{}}""", "\"x\"")
        assertEquals(PlacementMapStatus.Unsupported, loadPlacement(source, "a").mapStatus)
        assertNull(store.read("map:$mapUrl"))
        source.forget()
        transport.answers[mapUrl] = PlacementResponse.Body(map(), "\"y\"")
        loadPlacement(source, "a")
        assertEquals("\"y\"", store.read("map:$mapUrl")?.etag)
    }

    @Test fun neverPublishedMakesEveryPlaceUnknown() = runBlocking {
        val transport = FakeTransport(mutableMapOf(mapUrl to PlacementResponse.NotFound))
        val load = loadPlacement(PlacementSource(BASE, transport = transport), "home.banner")
        assertEquals(PlacementMapStatus.NotPublished, load.mapStatus)
        assertEquals(PlacementReport.Unknown, load.report)
    }

    @Test fun aDocumentThatIsGoneOrBrokenDrawsNothing() = runBlocking {
        val transport = FakeTransport()
        transport.answers[mapUrl] = PlacementResponse.Body(map("\"a.gone\":{\"documentId\":\"gone\"}", "\"a.broken\":{\"documentId\":\"broken\"}", "\"a.off\":{\"documentId\":\"off\"}"), null)
        transport.answers[docUrl("gone")] = PlacementResponse.NotFound
        transport.answers[docUrl("broken")] = PlacementResponse.Body("not json", null)
        val source = PlacementSource(BASE, transport = transport)
        assertEquals(PlacementReport.DocumentMissing, loadPlacement(source, "a.gone").report)
        assertEquals(PlacementReport.DocumentUnreadable, loadPlacement(source, "a.broken").report)
        assertEquals(PlacementReport.DocumentUnreadable, loadPlacement(source, "a.off").report)
        assertNull(loadPlacement(source, "a.gone").document)
    }

    @Test fun aDocumentIdThatLeavesTheFolderIsNeverRequested() = runBlocking {
        val transport = FakeTransport()
        transport.answers[mapUrl] = PlacementResponse.Body(map("\"a.up\":{\"documentId\":\"../theme\"}", "\"a.slash\":{\"documentId\":\"a/b\"}"), null)
        val source = PlacementSource(BASE, transport = transport)
        assertEquals(PlacementReport.Unreadable, loadPlacement(source, "a.up").report)
        assertEquals(PlacementReport.Unreadable, loadPlacement(source, "a.slash").report)
        assertTrue(transport.asked.none { "documents" in it.first })
    }

    @Test fun aThrowingTransportIsASilentNetwork() = runBlocking {
        val load = loadPlacement(PlacementSource(BASE, transport = { _, _, _ -> error("boom") }), "home.banner")
        assertEquals(PlacementMapStatus.Offline, load.mapStatus)
        assertEquals(PlacementReport.Unreadable, load.report)
    }

    @Test fun theComposablesDrawsTheDocumentOfThePlace() {
        val transport = FakeTransport()
        transport.answers[mapUrl] = PlacementResponse.Body(map("\"home.banner\":{\"documentId\":\"d1\"}", "\"home.empty\":{\"documentId\":null}"), null)
        transport.answers[docUrl("d1")] = PlacementResponse.Body(document("d1"), null)
        val source = PlacementSource(BASE, transport = transport)
        val loads = AtomicReference<PlacementLoad>()
        compose.setContent {
            Box(Modifier.size(200.dp)) {
                HotlayoutPlacement("home.banner", source, refreshIntervalMs = null, locale = "en", onPlacement = { loads.set(it) })
            }
        }
        compose.waitUntil(10_000) { loads.get() != null }
        compose.waitForIdle()
        compose.onNodeWithTag("screen:s1").assertExists()
        assertNull(loads.get().report)
    }

    @Test fun theComposableDrawsNothingForAnEmptyPlaceAndTellsTheHost() {
        val transport = FakeTransport()
        transport.answers[mapUrl] = PlacementResponse.Body(map("\"home.empty\":{\"documentId\":null}"), null)
        val source = PlacementSource(BASE, transport = transport)
        val loads = AtomicReference<PlacementLoad>()
        compose.setContent {
            Box(Modifier.size(200.dp)) {
                HotlayoutPlacement("home.empty", source, refreshIntervalMs = null, locale = "en", onPlacement = { loads.set(it) })
            }
        }
        compose.waitUntil(10_000) { loads.get() != null }
        compose.waitForIdle()
        compose.onNodeWithTag("screen:s1").assertDoesNotExist()
        assertEquals(PlacementReport.Empty, loads.get().report)
        assertNull(loads.get().document)
    }
}
