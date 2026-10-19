package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.render.motion.LocalNodeMotionClockMs
import com.hotlayout.render.motion.NodeMotionFrames
import com.hotlayout.support.Screens
import com.hotlayout.support.near
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The motion of a node through the real screen: pixels, the press target, the clock and the frames it asks for. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class MotionWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val red = 0xff0000
    private val blue = 0x0000ff
    private val white = 0xffffff

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFFFFFF

    private fun box(id: String, w: Int, h: Int, color: String, extra: String = "", fields: String = "") =
        """{"id":"$id","type":"Container","props":{"direction":"column","gap":"none"$extra},"style":{"width":$w,"height":$h,"background":{"kind":"color","color":{"value":"$color"}}}$fields,"children":[]}"""

    private val floatRight = ""","motion":{"kind":"float","axis":"horizontal","amplitude":20,"duration":2000}"""

    private fun showAt(document: HotlayoutDocument, clockMs: Double?, onAction: (String) -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalNodeMotionClockMs provides clockMs) {
                Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, onAction = { a, _ -> onAction(a.type) }) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun aFloatShiftsThePaintAndNotTheNeighbours() {
        val document = Screens.document("a" to (box("a", 100, 40, "#ff0000", fields = floatRight) + "," + box("b", 100, 40, "#0000ff")))
        showAt(document, 500.0)
        val image = capture()
        // A quarter of 2000 ms: 20 px toward the end of the line.
        assertTrue("moved right", near(image.rgbAt(110, 20), red))
        assertTrue("left of the drawn box the page", near(image.rgbAt(10, 20), white))
        assertTrue("the neighbour stays where the layout put it", near(image.rgbAt(50, 60), blue) && near(image.rgbAt(50, 100), white))
    }

    @Test fun atTheStartAndBeforeTheDelayTheNodeStandsAtRest() {
        showAt(Screens.document("a" to box("a", 100, 40, "#ff0000", fields = floatRight.replace("2000", "2000,\"delay\":1000"))), 500.0)
        val image = capture()
        assertTrue("at rest before the delay ends", near(image.rgbAt(10, 20), red) && near(image.rgbAt(110, 20), white))
    }

    @Test fun reducedMotionIsRest() {
        android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        showAt(Screens.document("a" to box("a", 100, 40, "#ff0000", fields = floatRight)), 500.0)
        val image = capture()
        assertTrue("at rest", near(image.rgbAt(10, 20), red) && near(image.rgbAt(110, 20), white))
        assertEquals(0, NodeMotionFrames.requested.get() - framesBefore)
    }

    private val framesBefore = NodeMotionFrames.requested.get()

    @Test fun aPulseScalesAboutTheCentre() {
        val pulse = ""","motion":{"kind":"pulse","scale":1.5,"duration":2000}"""
        showAt(Screens.document("a" to box("a", 100, 40, "#ff0000", fields = pulse)), 1000.0)
        val image = capture()
        // Half way through the cycle the box is the peak: 150 × 60 about its centre (50, 20).
        assertTrue("grown to the right", near(image.rgbAt(120, 20), red))
        assertTrue("grown to the left of the rest box is clipped by the screen edge, the bottom grows", near(image.rgbAt(50, 45), red))
        assertTrue("not past the peak", near(image.rgbAt(50, 55), white))
    }

    @Test fun theRootNeverMoves() {
        val json = """{"schemaVersion":"9.27.0","documentId":"t","name":"T","initialScreenId":"a","screens":[{"screenId":"a","name":"a","root":{"id":"r","type":"Screen","props":{"gap":"none"},"motion":{"kind":"float","axis":"horizontal","amplitude":20,"duration":2000},
            "style":{"background":{"kind":"color","color":{"value":"#ff0000"}}},"children":[]}}]}"""
        showAt(com.hotlayout.model.loadDocument(json), 500.0)
        assertTrue("the page is red from its first pixel", near(capture().rgbAt(2, 300), red))
    }

    // The press target is where the rest box and the drawn one overlap.

    private fun tapAt(x: Float): List<String> {
        val calls = ArrayList<String>()
        val moving = box("a", 100, 40, "#ff0000", ""","action":{"type":"tap"}""", """,\"motion\":{\"kind\":\"float\",\"axis\":\"horizontal\",\"amplitude\":48,\"duration\":2000}""".replace("\\\"", "\""))
        showAt(Screens.document("a" to moving), 500.0) { calls.add(it) }
        compose.onRoot().performTouchInput { down(Offset(x, 20f)); advanceEventTime(100); up() }
        compose.waitForIdle()
        return calls
    }

    @Test fun aTouchOnlyInTheDrawnBoxPressesNothing() = assertEquals(emptyList<String>(), tapAt(130f))

    @Test fun aTouchOnlyInTheRestBoxPressesNothing() = assertEquals(emptyList<String>(), tapAt(20f))

    @Test fun aTouchWhereTheBoxesOverlapPresses() = assertEquals(listOf("tap"), tapAt(70f))

    // The clock asks for frames only while the node is inside the window.

    @Test fun aNodeBelowTheFoldAsksForNoFramesAndOneInViewDoes() {
        val clock = compose.mainClock
        clock.autoAdvance = false
        val below = """{"id":"gap","type":"Spacer","props":{"size":"none"},"style":{"height":2000}},""" + box("far", 100, 40, "#ff0000", fields = floatRight)
        showAt(Screens.document("a" to below), null)
        clock.advanceTimeBy(100)
        val start = NodeMotionFrames.requested.get()
        clock.advanceTimeBy(2000)
        assertEquals("a node outside the window asks for no frame", 0, NodeMotionFrames.requested.get() - start)
    }

    @Test fun aNodeInViewMovesOnTheRealClock() {
        val clock = compose.mainClock
        clock.autoAdvance = false
        showAt(Screens.document("a" to box("a", 100, 40, "#ff0000", fields = floatRight)), null)
        clock.advanceTimeBy(100)
        val start = NodeMotionFrames.requested.get()
        clock.advanceTimeBy(600)
        assertTrue("frames are asked for", NodeMotionFrames.requested.get() - start > 10)
        // About 700 ms into 2000: past the first quarter's start, the paint has left the rest box.
        val image = capture()
        assertTrue("the paint moved to the right", near(image.rgbAt(115, 20), red))
    }

    // A layer of a stack: the motion goes along the axes of the screen, not of the turned layer.

    @Test fun theMotionOfATurnedLayerGoesAlongTheAxesOfTheScreen() {
        val stack = """{"id":"s","type":"Stack","props":{"alignHorizontal":"start","alignVertical":"start"},"children":[
            ${box("base", 200, 100, "#ffffff")},
            ${box("dot", 40, 40, "#ff0000", fields = floatRight.replace("20", "30") + ""","layer":{"alignHorizontal":"start","alignVertical":"start","rotate":90}""")}]}"""
        showAt(Screens.document("a" to stack), 500.0)
        val image = capture()
        assertTrue("shifted to the right on the screen", near(image.rgbAt(60, 20), red))
        assertTrue("not down", near(image.rgbAt(20, 60), white))
    }
}
