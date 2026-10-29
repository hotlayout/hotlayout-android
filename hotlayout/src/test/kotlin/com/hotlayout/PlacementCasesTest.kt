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

    /** The bytes of a table body: parts of `text` (UTF-8) or `hex`, each repeated `times`. */
    private fun bytesOf(parts: List<com.hotlayout.JsonObject>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) {
            val piece = part["text"]?.string?.toByteArray(Charsets.UTF_8)
                ?: part["hex"]?.string!!.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            repeat(part["times"]?.number?.toInt() ?: 1) { out.write(piece) }
        }
        return out.toByteArray()
    }

    @Test fun bodyCases() {
        val rows = Contract.rows(table, "bodyCases")
        assertEquals(26, rows.size)
        for (row in rows) {
            val read = PlacementRules.readMapBytes(bytesOf(row["body"]?.array.orEmpty().mapNotNull { it.obj }))
            val kind = when (read) {
                is PlacementRules.MapRead.Map -> "map"
                PlacementRules.MapRead.Corrupt -> "corrupt"
                PlacementRules.MapRead.Unsupported -> "unsupported"
            }
            assertEquals("read: ${row.name()}", row["read"]?.string, kind)
            assertResolves(row.name(), read, row["resolve"]?.array.orEmpty().mapNotNull { it.obj })
        }
    }

    @Test fun transportCases() {
        val rows = Contract.rows(table, "transportCases")
        assertEquals(26, rows.size)
        for (row in rows) {
            val answer = row["answer"]!!
            val given = PlacementRules.Answer(
                answer["status"]?.number!!.toInt(),
                answer["completedAtMs"]?.number?.toLong(),
                bytesOf(answer["body"]?.array.orEmpty().mapNotNull { it.obj }),
            )
            val actual = if (row["request"]?.string == "map") {
                when (val network = PlacementRules.mapAnswer(given)) {
                    is PlacementRules.Network.Map ->
                        if (PlacementRules.readMapBytes(network.bytes) is PlacementRules.MapRead.Corrupt) "corrupt" else "map"
                    PlacementRules.Network.NotModified -> "notModified"
                    PlacementRules.Network.NotFound -> "notFound"
                    PlacementRules.Network.Offline -> "offline"
                }
            } else {
                PlacementRules.documentAnswer(given).name.replaceFirstChar { it.lowercase() }
            }
            assertEquals("transport: ${row.name()}", row["expected"]?.string, actual)
        }
    }

    @Test fun eventCases() {
        val rows = Contract.rows(table, "eventCases")
        assertEquals(8, rows.size)
        fun settled(o: com.hotlayout.JsonObject) = PlacementRules.Settled(
            o["status"]?.string!!, o["report"]?.string, o["mapStatus"]?.string!!, o["documentId"]?.string, o["entry"]?.string,
        )
        for (row in rows) {
            val told = ArrayList<PlacementRules.Settled>()
            var previous: PlacementRules.Settled? = null
            for (state in row["states"]?.array.orEmpty().mapNotNull { it.obj }) {
                val next = settled(state)
                PlacementRules.event(previous, next)?.let { told.add(it) }
                previous = next
            }
            val expected = row["events"]?.array.orEmpty().mapNotNull { it.obj }.map { settled(it) }
            assertEquals("events: ${row.name()}", expected, told)
        }
    }

    @Test fun cacheCases() {
        val rows = Contract.rows(table, "cacheCases")
        assertEquals(3, rows.size)
        val document =
            """{"schemaVersion":"9.0.0","documentId":"d1","name":"d1","initialScreenId":"s1","entryPoints":{},"screens":[{"screenId":"s1","name":"S","root":{"id":"r","type":"Screen","props":{},"children":[]}}]}"""
        for (row in rows) {
            val store = MemoryPlacementStore()
            for (step in row["steps"]?.array.orEmpty().mapNotNull { it.obj }) {
                val base = "https://storage.example/orgs/o/projects/${step["project"]?.string}"
                val transport = object : PlacementTransport {
                    override suspend fun get(url: String, etag: String?, maxBytes: Int): PlacementResponse = when {
                        url.endsWith("/placements/published.json") ->
                            if (step["map"]?.get("kind")?.string == "map") PlacementResponse.Body(step["map"]?.get("text")?.string!!, null) else PlacementResponse.Unavailable
                        else -> when (step["document"]?.string) {
                            "ok" -> PlacementResponse.Body(document, null)
                            "notFound" -> PlacementResponse.NotFound
                            else -> PlacementResponse.Unavailable
                        }
                    }
                }
                val load = kotlinx.coroutines.runBlocking { loadPlacement(PlacementSource(base, store = store, transport = transport), "home.banner") }
                val expected = step["expected"]!!
                val actual = load.settled()
                assertEquals("status: ${row.name()}", expected["status"]?.string, actual.status)
                assertEquals("report: ${row.name()}", expected["report"]?.string, actual.report)
                assertEquals("mapStatus: ${row.name()}", expected["mapStatus"]?.string, actual.mapStatus)
            }
        }
    }

    @Test fun scheduleCases() {
        val rows = Contract.rows(table, "scheduleCases")
        assertEquals(37, rows.size)
        var checks = 0
        for (row in rows) {
            val read = row["text"]?.string?.let { PlacementRules.readMapText(it) }
                ?: PlacementRules.readMap(row["json"] ?: error("${row.name()}: no json and no text"))
            for (check in row["checks"]?.array.orEmpty().mapNotNull { it.obj }) {
                val place = check["place"]?.string!!
                val nowText = check["now"]?.string!!
                val now = PlacementRules.instantMs(nowText) ?: error("${row.name()}: «$nowText» is no instant")
                val label = "${row.name()} @ $nowText"
                val actual = PlacementRules.resolveAt(read, place, now)
                assertEquals("status: $label", check["status"]?.string, actual.status.name.replaceFirstChar { it.lowercase() })
                assertEquals("documentId: $label", check["documentId"]?.string, actual.documentId)
                assertEquals("entry: $label", check["entry"]?.string, actual.entry)
                val next = check["next"]?.string?.let { PlacementRules.instantMs(it) ?: error("${row.name()}: «$it» is no instant") }
                assertEquals("next: $label", next, PlacementRules.nextBoundary(read, place, now))
                checks++
            }
        }
        assertEquals(89, checks)
    }

    @Test fun anInstantIsTheContractFormOnly() {
        assertEquals(1_798_761_600_000L, PlacementRules.instantMs("2027-01-01T00:00:00Z"))
        assertEquals(951_868_800_000L, PlacementRules.instantMs("2000-03-01T00:00:00Z"))
        for (bad in listOf("2026-02-29T00:00:00Z", "2026-06-31T00:00:00Z", "2026-13-01T00:00:00Z", "2026-01-01T24:00:00Z", "2026-01-01T00:00:00z",
            "2026-01-01T00:00:00+02:00", "2026-01-01T00:00:00.5Z", "2026-01-01 00:00:00Z", "2100-01-01T00:00:00Z", "1999-12-31T23:59:59Z", " 2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z\n")) {
            assertEquals("«$bad»", null, PlacementRules.instantMs(bad))
        }
        assertTrue(PlacementRules.instantMs("2028-02-29T12:00:00Z") != null)
        assertEquals(null, PlacementRules.instantMs("2100-02-29T00:00:00Z"))
    }

    @Test fun aTimerIsNeverNegativeAndNeverAboveWhatItCanHold() {
        assertEquals(0L, PlacementRules.timerDelayMs(1_000, 5_000))
        assertEquals(4_000L, PlacementRules.timerDelayMs(5_000, 1_000))
        assertEquals(PlacementRules.MAX_TIMER_MS, PlacementRules.timerDelayMs(Long.MAX_VALUE, 0))
        assertEquals(PlacementRules.MAX_TIMER_MS, PlacementRules.timerDelayMs(PlacementRules.MAX_TIMER_MS + 1, 0))
        assertEquals(PlacementRules.MAX_TIMER_MS - 1, PlacementRules.timerDelayMs(PlacementRules.MAX_TIMER_MS, 1))
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

    @Test fun theScheduleBlockIsTheOneTheReaderImplements() {
        val schedule = artifact["x-placementSchedule"]!!
        assertEquals(PlacementRules.INSTANT_PATTERN, schedule["instant"]?.get("pattern")?.string)
        assertEquals("UTC", schedule["instant"]?.get("zone")?.string)
        assertEquals("second", schedule["instant"]?.get("precision")?.string)
        assertEquals("included", schedule["window"]?.get("from")?.string)
        assertEquals("excluded", schedule["window"]?.get("until")?.string)
        assertEquals(
            mapOf(
                "notAnArray" to "no-schedule", "brokenItem" to "skip", "emptyWindow" to "skip", "nullDocument" to "pause-empty",
                "pauseEntry" to "ignore", "nullEntry" to "absent", "brokenPermanent" to "unreadable",
                "overlap" to "latest-from-then-lowest-index", "unknownFields" to "ignore",
            ),
            schedule["reading"]?.obj?.associate { it.first to it.second.string },
        )
        assertEquals("active-scheduled-else-permanent", schedule["resolve"]?.string)
        assertEquals("device", schedule["clock"]?.string)
        assertEquals("earliest-edge-after-now", schedule["nextBoundary"]?.string)
        assertEquals(listOf("next-boundary", "map-read", "app-foreground"), schedule["recompute"]?.array.orEmpty().mapNotNull { it.string })
        assertEquals(PlacementRules.MAX_TIMER_MS.toDouble(), schedule["maxTimerMs"]?.number)
        assertEquals("permanent-only", schedule["olderReaders"]?.string)
    }

    /** The words this reader implements; a new or changed word in the artifact fails here until the reader is looked at. */
    @Test fun thePolicyWordsAreTheOnesTheReaderImplements() {
        fun words(key: String) = artifact[key]?.obj?.associate { it.first to (it.second.string ?: it.second.toString()) }
        assertEquals(
            mapOf(
                "unknownFields" to "ignore", "notAMap" to "corrupt", "otherMajor" to "unsupported", "newerMinor" to "read",
                "keyLookup" to "own-keys-only", "invalidName" to "unknown", "nullDocument" to "empty", "nullEntry" to "absent",
                "brokenPlace" to "unreadable", "unreadMap" to "unreadable", "limits" to "publish-only", "publishedAt" to "informational",
                "majorComparison" to "digit-string",
            ),
            words("x-placementReading")?.minus("body"),
        )
        val body = artifact["x-placementReading"]?.get("body")!!
        assertEquals("skip", body["leadingBom"]?.string)
        assertEquals("utf-8-strict", body["encoding"]?.string)
        assertEquals(PlacementRules.MAX_BODY_BYTES.toDouble(), body["maxBytes"]?.number)
        assertEquals(PlacementRules.MAX_DEPTH.toDouble(), body["maxDepth"]?.number)
        val deadline = artifact["x-placementSource"]?.get("deadline")!!
        assertEquals(PlacementRules.REQUEST_DEADLINE_MS.toDouble(), deadline["ms"]?.number)
        assertEquals(false, artifact["x-placementSource"]?.get("redirects")?.get("follow")?.bool)
        assertEquals("map-address", artifact["x-placementSource"]?.get("lastKnownKey")?.string)
        assertEquals("drop", artifact["x-placementSource"]?.get("documentCache")?.get("onNotFound")?.string)
        assertEquals("every-change", artifact["x-placementEvents"]?.get("when")?.string)
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
