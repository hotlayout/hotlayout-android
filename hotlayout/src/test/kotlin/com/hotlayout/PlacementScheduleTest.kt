package com.hotlayout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.hotlayout.placements.PlacementRules
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val SCHEDULE_BASE = "https://storage.example/orgs/o/projects/p"

private fun instant(text: String) = PlacementRules.instantMs(text) ?: error(text)

private fun doc(id: String) =
    """{"schemaVersion":"9.0.0","documentId":"$id","name":"$id","initialScreenId":"s1","entryPoints":{},"screens":[{"screenId":"s1","name":"S","root":{"id":"r","type":"Screen","props":{},"children":[]}}]}"""

private const val SCHEDULED_MAP =
    """{"placementsVersion":"1.1.0","placements":{"home.banner":{"documentId":"d-everyday","schedule":[""" +
        """{"documentId":"d-sale","from":"2026-11-27T22:00:00Z","until":"2026-11-30T22:00:00Z"}]}}}"""

private class ScheduleTransport : PlacementTransport {
    val asked = CopyOnWriteArrayList<String>()
    override suspend fun get(url: String, etag: String?, maxBytes: Int): PlacementResponse {
        asked.add(url)
        return when {
            url.endsWith("/placements/published.json") -> PlacementResponse.Body(SCHEDULED_MAP, null)
            url.endsWith("/documents/d-everyday/published.json") -> PlacementResponse.Body(doc("d-everyday"), null)
            url.endsWith("/documents/d-sale/published.json") -> PlacementResponse.Body(doc("d-sale"), null)
            else -> PlacementResponse.Unavailable
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class PlacementScheduleTest {
    @get:Rule val compose = createComposeRule()

    private fun source(clock: AtomicLong, transport: PlacementTransport = ScheduleTransport()) =
        PlacementSource(SCHEDULE_BASE, transport = transport, clock = { clock.get() })

    @Test fun theSourceClockPicksTheDocumentWithoutTheNetworkAnswering() = runBlocking {
        val clock = AtomicLong(instant("2026-11-27T21:59:59Z"))
        val placements = source(clock)
        assertEquals("d-everyday", loadPlacement(placements, "home.banner").document?.documentId)
        clock.set(instant("2026-11-27T22:00:00Z"))
        placements.forget()
        assertEquals("d-sale", loadPlacement(placements, "home.banner").document?.documentId)
        clock.set(instant("2026-11-30T22:00:00Z"))
        placements.forget()
        assertEquals("d-everyday", loadPlacement(placements, "home.banner").document?.documentId)
    }

    @Test fun theTimerMovesThePlaceAtTheBoundaryWithoutAskingTheMapAgain() {
        val clock = AtomicLong(instant("2026-11-27T21:59:50Z"))
        val transport = ScheduleTransport()
        val told = CopyOnWriteArrayList<String?>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(200.dp)) {
                HotlayoutPlacement(
                    "home.banner", source(clock, transport), refreshIntervalMs = null, locale = "en",
                    onPlacement = { told.add(it.resolution.documentId) },
                )
            }
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(50); told.size == 1 }
        assertEquals(listOf<String?>("d-everyday"), told.toList())
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val mapAsks = transport.asked.count { it.endsWith("/placements/published.json") }
        // The device clock reaches the boundary and the timer, set for ten seconds, fires.
        clock.set(instant("2026-11-27T22:00:00Z"))
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(50); told.size == 2 }
        assertEquals(listOf<String?>("d-everyday", "d-sale"), told.toList())
        assertEquals(mapAsks, transport.asked.count { it.endsWith("/placements/published.json") })
    }
}
