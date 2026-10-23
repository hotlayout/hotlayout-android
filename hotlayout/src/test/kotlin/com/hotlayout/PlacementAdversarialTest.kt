package com.hotlayout

import com.hotlayout.placements.PlacementRules
import java.net.CookieHandler
import java.net.CookieManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Adversarial pass over placements: the placement map and the documents it names come from the
 * network, so they are untrusted input.
 *
 * `@Ignore("adversary: …")` tests are FINDINGS: they state what should hold and currently does not;
 * a fix removes the annotation. The other tests are GUARDS: paths around the claims that were tried
 * and did not work.
 */
class PlacementAdversarialTest {
    private val baseA = "https://storage.example/orgs/o/projects/a"
    private val baseB = "https://storage.example/orgs/o/projects/b"

    private fun map(vararg places: String) = """{"placementsVersion":"1.0.0","placements":{${places.joinToString(",")}}}"""

    private val document =
        """{"schemaVersion":"9.0.0","documentId":"d1","name":"d1","initialScreenId":"s1","entryPoints":{},"screens":[{"screenId":"s1","name":"S","root":{"id":"r","type":"Screen","props":{},"children":[]}}]}"""

    private class Transport(val answers: MutableMap<String, PlacementResponse> = HashMap()) : PlacementTransport {
        val asked = ArrayList<String>()
        override suspend fun get(url: String, etag: String?, maxBytes: Int): PlacementResponse {
            asked.add(url)
            return answers[url] ?: PlacementResponse.Unavailable
        }
    }

    /** The kind of store a host writes to keep placements over a restart: one file or preferences. */
    private class SharedStore : PlacementStore {
        val items = HashMap<String, StoredText>()
        override fun read(key: String): StoredText? = items[key]
        override fun write(key: String, value: StoredText) { items[key] = value }
    }

    // ───────────────────────────── findings ─────────────────────────────

    @Ignore("adversary: store keys are \"placements\" and \"document/<id>\", not scoped by project base")
    @Test fun aStoreSharedByTwoProjectsNeverServesProjectAsMapOrDocumentForProjectB() = runBlocking {
        val store = SharedStore()
        val a = Transport()
        a.answers["$baseA/placements/published.json"] = PlacementResponse.Body(map("\"home.banner\":{\"documentId\":\"d1\"}"), null)
        a.answers["$baseA/documents/d1/published.json"] = PlacementResponse.Body(document, null)
        loadPlacement(PlacementSource(baseA, store = store, transport = a), "home.banner")
        // Project B is offline on its first run: it must not show project A's map and document.
        val b = loadPlacement(PlacementSource(baseB, store = store, transport = Transport()), "home.banner")
        assertEquals(PlacementMapOrigin.None, b.mapOrigin)
        assertNull(b.document)
    }

    @Ignore("adversary: a 404 on the document leaves the kept copy in the store; the next failed request brings it back")
    @Test fun aDocumentTakenDownIsNotRevivedWhenTheNetworkLaterFails() = runBlocking {
        val store = SharedStore()
        val t = Transport()
        t.answers["$baseA/placements/published.json"] = PlacementResponse.Body(map("\"home.banner\":{\"documentId\":\"d1\"}"), null)
        t.answers["$baseA/documents/d1/published.json"] = PlacementResponse.Body(document, null)
        loadPlacement(PlacementSource(baseA, store = store, transport = t), "home.banner")
        t.answers["$baseA/documents/d1/published.json"] = PlacementResponse.NotFound
        val gone = loadPlacement(PlacementSource(baseA, store = store, transport = t), "home.banner")
        assertEquals(PlacementReport.DocumentMissing, gone.report)
        t.answers["$baseA/documents/d1/published.json"] = PlacementResponse.Unavailable
        val later = loadPlacement(PlacementSource(baseA, store = store, transport = t), "home.banner")
        assertNull(later.document)
    }

    @Ignore("adversary: with any process-wide CookieHandler the default transport refuses every request, so placements are silently offline forever")
    @Test fun anAppWithACookieHandlerStillReadsPlacements() = runBlocking {
        val before = CookieHandler.getDefault()
        CookieHandler.setDefault(CookieManager())
        try {
            val answer = PlacementTransport.Default.get("https://storage.invalid/placements/published.json", null, 1024)
            assertTrue(answer !is PlacementResponse.Unavailable)
        } finally {
            CookieHandler.setDefault(before)
        }
    }

    // ───────────────────────────── guards ─────────────────────────────

    @Test fun aDocumentIdThatWouldLeaveTheProjectIsNeverRequested() = runBlocking {
        val escapes = listOf(
            "..", ".", "...", "%2e%2e", "%2F", "a/b", "a\\\\b", "//evil.test", "https://evil.test/x", "d?x=1", "d#x",
            "．．", "ｄ1", "d\\n", "\\nd", " d", "d ", "d\\u0000", "d\\u200b", "", "x".repeat(65),
        )
        for (id in escapes) {
            val t = Transport()
            t.answers["$baseA/placements/published.json"] = PlacementResponse.Body(map("\"home.banner\":{\"documentId\":\"$id\"}"), null)
            val load = loadPlacement(PlacementSource(baseA, transport = t), "home.banner")
            assertEquals(id, PlacementStatus.Unreadable, load.resolution.status)
            assertEquals(id, listOf("$baseA/placements/published.json"), t.asked)
        }
    }

    @Test fun ownKeysOnly() {
        val read = PlacementRules.readMapText("""{"placementsVersion":"1.0.0","placements":{"__proto__":{"documentId":"x"},"constructor":{"documentId":"c"}}}""")
        assertEquals(PlacementRules.Status.Unknown, PlacementRules.resolve(read, "__proto__").status)
        assertEquals("c", PlacementRules.resolve(read, "constructor").documentId)
        assertEquals(PlacementRules.Status.Unknown, PlacementRules.resolve(PlacementRules.readMapText(map()), "constructor").status)
    }

    @Test fun versions() {
        for (v in listOf("01.0.0", "1.0.0-x", "1.0.0+b", "1.0", " 1.0.0", "1.0.0 ", "1.0.0\\n", "v1.0.0", "١.0.0", "")) {
            assertEquals(v, PlacementRules.MapRead.Corrupt, PlacementRules.readMapText("""{"placementsVersion":"$v","placements":{}}"""))
        }
        assertEquals(PlacementRules.MapRead.Unsupported, PlacementRules.readMapText("""{"placementsVersion":"99999999999999999999.0.0","placements":{}}"""))
        assertTrue(PlacementRules.readMapText("""{"placementsVersion":"1.99999999999999999999.0","placements":{}}""") is PlacementRules.MapRead.Map)
    }

    @Test fun duplicateKeysTakeTheLast() {
        val read = PlacementRules.readMapText("""{"placementsVersion":"1.0.0","placements":{"home.banner":{"documentId":"a"},"home.banner":{"documentId":null}}}""")
        assertEquals(PlacementRules.Status.Empty, PlacementRules.resolve(read, "home.banner").status)
    }

    @Test fun hostileEntries() {
        for (e in listOf("__proto__", "A", "-x", "x_y", "x".repeat(49), "x\\n", "ｘ")) {
            val read = PlacementRules.readMapText(map("\"home.banner\":{\"documentId\":\"d\",\"entry\":\"$e\"}"))
            assertEquals(e, PlacementRules.Status.Unreadable, PlacementRules.resolve(read, "home.banner").status)
        }
    }

    @Test fun deepJsonAndBomAreCorruptWithoutAnException() {
        // Cross-reader note: corrupt here and on iOS (depth cap 256, BOM not skipped); React and Flutter read a map.
        val depth = 100_000
        val deep = """{"placementsVersion":"1.0.0","placements":{},"x":""" + "[".repeat(depth) + "]".repeat(depth) + "}"
        assertEquals(PlacementRules.MapRead.Corrupt, PlacementRules.readMapText(deep))
        assertEquals(PlacementRules.MapRead.Corrupt, PlacementRules.readMapText("﻿" + map()))
    }

    @Test fun corruptOtherMajorAnd404NeverOverwriteTheLastKnownMap() = runBlocking {
        val good = map("\"home.banner\":{\"documentId\":null}")
        for (answer in listOf<PlacementResponse>(
            PlacementResponse.Body("<html>", null),
            PlacementResponse.Body("""{"placementsVersion":"2.0.0","placements":{}}""", null),
            PlacementResponse.TooLarge,
            PlacementResponse.NotFound,
            PlacementResponse.NotModified,
        )) {
            val store = SharedStore()
            store.write("placements", StoredText(good, "\"e\""))
            val t = Transport()
            t.answers["$baseA/placements/published.json"] = answer
            val load = loadPlacement(PlacementSource(baseA, store = store, transport = t), "home.banner")
            assertEquals(good, store.items["placements"]?.text)
            assertEquals(PlacementStatus.Empty, load.resolution.status)
        }
    }

    @Test fun theDefaultTransportRefusesPlainHttp() = runBlocking {
        assertTrue(PlacementTransport.Default.get("http://127.0.0.1:9/placements/published.json", null, 1024) is PlacementResponse.Unavailable)
    }
}
