package com.hotlayout

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hotlayout.actions.Flow
import com.hotlayout.actions.FlowDocument
import com.hotlayout.model.ReportSink
import com.hotlayout.render.interaction.pressInput
import com.hotlayout.render.widgets.ImageStore
import com.hotlayout.support.Screens
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the screen does with state, effects and the picture cache: nothing is written while composing, nothing sticks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ComposeHygieneTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var now = 0L

    @Before fun freshStore() {
        ImageStore.reset()
        ImageStore.clock = { now }
    }

    @After fun realClock() {
        ImageStore.reset()
        ImageStore.clock = { System.nanoTime() / 1_000_000 }
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    // ---------------------------------------------------------------- reports

    @Test fun aReportWritesNoSnapshotState() {
        val sink = ReportSink()
        var writes = 0
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { writes++ })
        try {
            snapshot.enter { sink.add(HotlayoutReport.Kind.IgnoredInput, "here", "why") }
        } finally {
            snapshot.dispose()
        }
        assertEquals("a widget may report while it is composed", 0, writes)
        assertEquals(1, sink.changes.value)
    }

    @Test fun aReportReachesTheHostOnceAndARecompositionSendsNothingNew() {
        val refused = """{"id":"pic","type":"Image","props":{"source":{"kind":"url","url":"http://127.0.0.1/a.png"},"aspectRatio":"1:1"}}"""
        val document = Screens.document("a" to refused)
        val deliveries = ArrayList<List<HotlayoutReport>>()
        var data by mutableStateOf<DataScope>(emptyMap())
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data, locale = "en", onReport = { deliveries.add(it) }) }
        }
        compose.waitForIdle()
        val refusals = deliveries.last().filter { it.location == "image" }
        assertEquals("the refused address is one report: ${deliveries.last()}", 1, refusals.size)
        val count = deliveries.size
        repeat(3) { index ->
            data = mapOf("user.name" to JsonValue.Str("n$index"))
            compose.waitForIdle()
        }
        assertEquals("new data recomposes the screen and reports nothing new", count, deliveries.size)
    }

    // ---------------------------------------------------------------- press pose

    @Test fun aGestureCutShortLetsGoOfThePressedPose() {
        var pressing = false
        var long by mutableStateOf(false)
        compose.setContent {
            Box(Modifier.size(100.dp).testTag("card").pressInput(true, long, {}, {}) { pressing = it })
        }
        compose.onNodeWithTag("card").performTouchInput { down(center) }
        compose.waitForIdle()
        assertTrue("pressed while the finger is down", pressing)
        // A change of what the node does restarts its pointer input under the finger: the gesture in progress is cancelled.
        long = true
        compose.waitForIdle()
        assertFalse("the pose does not stick after the gesture was cut short", pressing)
        compose.onNodeWithTag("card").performTouchInput { up() }
    }

    // ---------------------------------------------------------------- picture cache

    @Test fun aFailedPictureIsAskedForAgainOnceTheFailureExpires() {
        val calls = AtomicInteger()
        val bytes = png()
        val policy = ImagePolicy(fetch = { _, _ -> if (calls.incrementAndGet() == 1) null else bytes })
        val address = "https://cdn.example.com/a.png"
        assertNull(runBlocking { ImageStore.load(address, policy.maxDownloadBytes, policy) })
        assertNull("not asked again at once", runBlocking { ImageStore.load(address, policy.maxDownloadBytes, policy) })
        assertEquals(1, calls.get())
        now += ImageStore.FAILURE_RETRY_MS
        assertNotNull("asked again after the failure expired", runBlocking { ImageStore.load(address, policy.maxDownloadBytes, policy) })
        assertEquals(2, calls.get())
    }

    @Test fun aCancelledLoadIsNoFailure() {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val bytes = png()
        val policy = ImagePolicy(
            fetch = { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    started.complete(Unit)
                    awaitCancellation()
                }
                bytes
            },
        )
        val address = "https://cdn.example.com/b.png"
        runBlocking {
            val first = async { ImageStore.load(address, policy.maxDownloadBytes, policy) }
            started.await()
            first.cancel()
            yield()
        }
        assertNotNull("the node that comes next loads the picture", runBlocking { ImageStore.load(address, policy.maxDownloadBytes, policy) })
        assertEquals(2, calls.get())
    }

    @Test fun thePictureCacheKeepsOneDecodePerLongestSide() {
        val bytes = png()
        val address = "https://cdn.example.com/c.png"
        val small = ImagePolicy(maxPixelSide = 2, fetch = { _, _ -> bytes })
        assertNotNull(runBlocking { ImageStore.load(address, small.maxDownloadBytes, small) })
        assertNotNull(ImageStore.cached(address, 2))
        assertNull("another side is another picture", ImageStore.cached(address, ImagePolicy.DEFAULT_MAX_PIXEL_SIDE))
    }

    // ---------------------------------------------------------------- flow

    @Test fun aPressLeavesTheFlowItStartedFrom() {
        val document = FlowDocument(
            "d", listOf(FlowDocument.Screen("a", ScreenPresentation.Page), FlowDocument.Screen("b", ScreenPresentation.Page)), "a", emptyMap(),
        )
        val start = Flow(document, null)
        val (next, outcome) = start.press(JsonObject.of("type" to "navigate", "params" to mapOf("screenId" to "b")))
        assertEquals(listOf("a"), start.stack.map { it.screenId })
        assertEquals(listOf("a", "b"), next.stack.map { it.screenId })
        assertEquals(ScreenShownCause.Forward, outcome.shown?.cause)
    }
}
