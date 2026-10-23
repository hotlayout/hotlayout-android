package com.hotlayout

import com.hotlayout.placements.PlacementRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared table of the placement map (`fixtures/placements/placement-cases.json`): every row of every group. */
class PlacementCasesTest {
    private val table = Contract.json("fixtures/placements/placement-cases.json")

    private fun resolution(row: com.hotlayout.JsonObject): PlacementRules.Resolution =
        PlacementRules.Resolution(
            PlacementRules.Status.valueOf(row["status"]?.string!!.replaceFirstChar { it.uppercase() }),
            row["documentId"]?.string,
            row["entry"]?.string,
        )

    private fun assertResolves(name: String, map: PlacementRules.MapRead, expected: List<com.hotlayout.JsonObject>): Int {
        for (place in expected) {
            val asked = place["name"]?.string!!
            val actual = PlacementRules.resolve(map, asked)
            assertEquals("$name: «$asked»", resolution(place), actual)
        }
        return expected.size
    }

    @Test fun readCases() {
        val rows = Contract.rows(table, "readCases")
        assertEquals(36, rows.size)
        var resolutions = 0
        for (row in rows) {
            val read = row["text"]?.string?.let { PlacementRules.readMapText(it) } ?: PlacementRules.readMap(row["json"] ?: error("${row.name()}: no json and no text"))
            val kind = when (read) {
                is PlacementRules.MapRead.Map -> "map"
                PlacementRules.MapRead.Corrupt -> "corrupt"
                PlacementRules.MapRead.Unsupported -> "unsupported"
            }
            assertEquals("read: ${row.name()}", row["read"]?.string, kind)
            resolutions += assertResolves(row.name(), read, row["resolve"]?.array.orEmpty().mapNotNull { it.obj })
        }
        assertEquals(80, resolutions)
    }

    @Test fun sourceCases() {
        val rows = Contract.rows(table, "sourceCases")
        assertEquals(21, rows.size)
        var resolutions = 0
        for (row in rows) {
            val network = row["network"]!!
            val input = when (network["kind"]?.string) {
                "map" -> PlacementRules.Network.Map(network["text"]?.string!!)
                "notModified" -> PlacementRules.Network.NotModified
                "notFound" -> PlacementRules.Network.NotFound
                "offline" -> PlacementRules.Network.Offline
                else -> error("${row.name()}: unknown network kind")
            }
            val decision = PlacementRules.pickMap(input, row["lastKnown"]?.string, row["bundled"]?.string)
            val expected = row["expected"]!!
            assertEquals("source: ${row.name()}", expected["source"]?.string, decision.origin.name.replaceFirstChar { it.lowercase() })
            assertEquals("status: ${row.name()}", expected["status"]?.string, decision.status.name.replaceFirstChar { it.lowercase() })
            assertEquals("remember: ${row.name()}", expected["remember"]?.bool, decision.remember)
            resolutions += assertResolves(row.name(), decision.map, row["resolve"]?.array.orEmpty().mapNotNull { it.obj })
        }
        assertEquals(22, resolutions)
    }

    @Test fun showingCases() {
        val rows = Contract.rows(table, "showingCases")
        assertEquals(10, rows.size)
        for (row in rows) {
            val document = row["document"]?.let { doc ->
                when (doc["kind"]?.string) {
                    "ok" -> PlacementRules.DocumentOutcome.Ok(doc["entryPoints"]?.array.orEmpty().mapNotNull { it.string }.toSet())
                    "missing" -> PlacementRules.DocumentOutcome.Missing
                    "unreadable" -> PlacementRules.DocumentOutcome.Unreadable
                    else -> error("${row.name()}: unknown document kind")
                }
            }
            val showing = PlacementRules.showing(resolution(row["resolution"]?.obj!!), document)
            val expected = row["expected"]!!
            assertEquals("show: ${row.name()}", expected["show"]?.string, if (showing.show) "document" else "nothing")
            assertEquals("entry: ${row.name()}", expected["entry"]?.string, showing.entry)
            assertEquals("report: ${row.name()}", expected["report"]?.string, showing.report?.name?.replaceFirstChar { it.lowercase() })
        }
    }

    @Test fun hostileNames() {
        val map = PlacementRules.readMapText("""{"placementsVersion":"1.0.0","placements":{"constructor":{"documentId":"d1"},"home.banner":{"documentId":"d2"}}}""")
        for (name in listOf("__proto__", "toString", "hashCode", "home.banner\n", "home.banner ", "HOME.banner", "home..banner", "..", "a/b", "../home.banner", "home.banner\u0000", "ｈome", "x".repeat(65), "a.".repeat(40) + "b")) {
            assertEquals("«${name.take(20)}»", PlacementStatus.Unknown.name, PlacementRules.resolve(map, name).status.name)
            assertFalse(isPlacementName(name))
        }
        assertEquals(PlacementRules.Status.Document, PlacementRules.resolve(map, "constructor").status)
        assertTrue(isPlacementName("a".repeat(64)))
        assertFalse(isPlacementName(""))
    }

    @Test fun aMapThatWasNotReadMakesEveryNameUnreadable() {
        assertEquals(PlacementRules.Status.Unreadable, PlacementRules.resolve(PlacementRules.MapRead.Corrupt, "").status)
        assertEquals(PlacementRules.Status.Unreadable, PlacementRules.resolve(PlacementRules.MapRead.Unsupported, "__proto__").status)
    }

    @Test fun anAbsurdlyNestedMapIsCorruptNotACrash() {
        assertEquals(PlacementRules.MapRead.Corrupt, PlacementRules.readMapText("[".repeat(100_000)))
        assertEquals(PlacementRules.MapRead.Corrupt, PlacementRules.readMapText("""{"placementsVersion":"1.0.0","placements":""" + "[".repeat(100_000)))
    }
}

/** The shipped rules are the artifact `placements.json`: version, forms and the words of the reading policy. */
class PlacementMirrorTest {
    private val artifact = Contract.json("placements.json")

    @Test fun versionAndFormsAreTheArtifacts() {
        assertEquals(artifact["x-placementsVersion"]?.string, PlacementRules.CONTRACT_VERSION)
        val limits = artifact["x-limits"]!!
        assertEquals(limits["name"]?.get("pattern")?.string, PlacementRules.NAME_PATTERN)
        assertEquals(limits["name"]?.get("maxLength")?.number, PlacementRules.NAME_MAX.toDouble())
        assertEquals(limits["documentId"]?.get("pattern")?.string, PlacementRules.DOCUMENT_ID_PATTERN)
        assertEquals(limits["entry"]?.get("pattern")?.string, PlacementRules.ENTRY_PATTERN)
        assertEquals(limits["entry"]?.get("maxLength")?.number, PlacementRules.ENTRY_MAX.toDouble())
    }

    /** The words this reader implements; a new or changed word in the artifact fails here until the reader is looked at. */
    @Test fun thePolicyWordsAreTheOnesTheReaderImplements() {
        fun words(key: String) = artifact[key]?.obj?.associate { it.first to (it.second.string ?: it.second.toString()) }
        assertEquals(
            mapOf(
                "unknownFields" to "ignore", "notAMap" to "corrupt", "otherMajor" to "unsupported", "newerMinor" to "read",
                "keyLookup" to "own-keys-only", "invalidName" to "unknown", "nullDocument" to "empty", "nullEntry" to "absent",
                "brokenPlace" to "unreadable", "unreadMap" to "unreadable", "limits" to "publish-only", "publishedAt" to "informational",
            ),
            words("x-placementReading"),
        )
        assertEquals(
            mapOf(
                "notADocument" to "render-nothing-and-tell-host", "documentMissing" to "render-nothing-and-tell-host",
                "documentUnreadable" to "render-nothing-and-tell-host", "entryMissing" to "use-initial-and-tell-host",
            ),
            words("x-placementShowing"),
        )
        val source = artifact["x-placementSource"]!!
        assertEquals(listOf("lastKnown", "bundled"), source["fallbackOrder"]?.array.orEmpty().mapNotNull { it.string })
        assertEquals(true, source["rememberOnlyFreshNetwork"]?.bool)
        assertEquals(setOf("fresh", "offline", "corrupt", "unsupported", "notPublished"), source["statuses"]?.obj?.keys?.toSet())
        assertEquals("unknown", source["noMapAtAll"]?.get("notPublished")?.string)
        assertEquals("unreadable", source["noMapAtAll"]?.get("otherwise")?.string)
    }
}
