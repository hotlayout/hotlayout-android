package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hotlayout.render.motion.LocalNodeAppearClockMs
import com.hotlayout.render.motion.LocalNodeMotionClockMs
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.channels
import com.hotlayout.support.isColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** `spin`, `sway` and `appear` through the real screen: the turn painted about its pivot, the appearance on its clock, and the window of the show. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class MotionTurnWiringTest {
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

    private fun box(id: String, w: Int, h: Int, color: String, fields: String = "") =
        """{"id":"$id","type":"Container","props":{"direction":"column","gap":"none"},"style":{"width":$w,"height":$h,"background":{"kind":"color","color":{"value":"$color"}}}$fields,"children":[]}"""

    private fun spacer(width: Int, height: Int = 1) = """{"id":"sp$width$height","type":"Spacer","props":{"size":"none"},"style":{"width":$width,"height":$height}}"""

    private fun row(vararg children: String, fields: String = "") =
        """{"id":"row","type":"Container","props":{"direction":"row","gap":"none"}$fields,"children":[${children.joinToString(",")}]}"""

    private fun show(document: HotlayoutDocument, motionMs: Double? = null, appearMs: Double? = null, locale: String = "en", data: DataScope = emptyMap()) {
        compose.setContent {
            CompositionLocalProvider(LocalNodeMotionClockMs provides motionMs, LocalNodeAppearClockMs provides appearMs) {
                Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, locale = locale, data = data) }
            }
        }
        compose.waitForIdle()
    }

    private fun spin(duration: Int, direction: String = "clockwise") = ""","motion":{"kind":"spin","direction":"$direction","duration":$duration}"""

    private fun sway(angle: Int, pivot: String, duration: Int) = ""","motion":{"kind":"sway","angle":$angle,"pivot":"$pivot","duration":$duration}"""

    /** The mean place of the pixels of a colour, or `null` when there are none. */
    private fun android.graphics.Bitmap.centroid(rgb: Int): Pair<Double, Double>? {
        var count = 0
        var sx = 0.0
        var sy = 0.0
        for (y in 0 until height) for (x in 0 until width) if (isColor(x, y, rgb, 40)) { count++; sx += x; sy += y }
        return if (count == 0) null else sx / count to sy / count
    }

    @Test fun aSpinTurnsTheBoxAboutItsCentre() {
        // A bar of 8 by 60 at the corner; a quarter of the cycle is a quarter turn: it lies down about its centre (4, 30).
        show(Screens.document("a" to box("bar", 8, 60, "#ff0000", spin(4000))), motionMs = 1000.0)
        val image = capture()
        assertTrue("lies down along the middle", image.isColor(20, 30, red) && image.isColor(30, 30, red))
        assertTrue("and has left its place", image.isColor(4, 5, white) && image.isColor(4, 55, white))
    }

    @Test fun aSwayTurnsTheBoxAboutTheMiddleOfItsTopEdge() {
        // 45 degrees clockwise at a quarter of the cycle: the bottom end swings to the start side (left).
        show(Screens.document("a" to row(spacer(100), box("bar", 8, 60, "#ff0000", sway(45, "top", 4000)))), motionMs = 1000.0)
        val image = capture()
        // The bar hangs from (104, 0); its axis now runs to (62, 42): at 30 points down it is at x = 74.
        assertTrue("the bar swung left", image.isColor(74, 30, red))
        assertTrue("and left its place", image.isColor(104, 50, white))
    }

    @Test fun aSwayIsMirroredInRightToLeftWritingAndASpinIsNot() {
        // Right to left the row starts at the end edge: the bar stands at its right and hangs from (256, 0).
        show(Screens.document("a" to row(spacer(100), box("bar", 8, 60, "#ff0000", sway(45, "top", 4000)))), motionMs = 1000.0, locale = "ar")
        val image = capture()
        assertTrue("the bottom swings toward the end: right, mirrored", image.isColor(286, 30, red))
        assertTrue("and left its place", image.isColor(256, 50, white))
    }

    /** How far the bottom half of a red bar stands to the right of its top half: negative when the bar leans like a clockwise turn. */
    private fun lean(image: android.graphics.Bitmap, centreY: Int): Double {
        fun half(range: IntRange): Double {
            var count = 0
            var sx = 0.0
            for (y in range) for (x in 0 until image.width) if (image.isColor(x, y, red, 40)) { count++; sx += x }
            return if (count == 0) Double.NaN else sx / count
        }
        return half(centreY until centreY + 28) - half(centreY - 28 until centreY)
    }

    @Test fun aSpinTurnsClockwiseInLeftToRightWriting() {
        // An eighth of the cycle: 45 degrees clockwise about the centre; the top end goes to the right, the bottom end to the left.
        show(Screens.document("a" to box("bar", 8, 60, "#ff0000", spin(4000))), motionMs = 500.0)
        assertTrue("the top is to the right of the bottom", lean(capture(), 30) < -2)
    }

    @Test fun aSpinKeepsItsDirectionInRightToLeftWriting() {
        // The same turn right to left: a spin has no direction of the line, so it is not mirrored (the bar stands at the end edge).
        show(Screens.document("a" to box("bar", 8, 60, "#ff0000", spin(4000))), motionMs = 500.0, locale = "ar")
        assertTrue("the same lean", lean(capture(), 30) < -2)
    }

    @Test fun aCounterclockwiseSpinLeansTheOtherWay() {
        show(Screens.document("a" to box("bar", 8, 60, "#ff0000", spin(4000, "counterclockwise"))), motionMs = 500.0)
        assertTrue(lean(capture(), 30) > 2)
    }

    // The appearance.

    private fun appear(json: String) = ""","appear":$json"""

    @Test fun anAppearingNodeIsInvisibleAtTheShowAndFullAtTheEnd() {
        val fade = appear("""{"kind":"fade","duration":400}""")
        show(Screens.document("a" to box("sq", 100, 40, "#ff0000", fade)), appearMs = 0.0)
        assertTrue("invisible at the show", capture().isColor(50, 20, white))
    }

    @Test fun anAppearingNodeHasTheOpacityOfTheTableAtAMoment() {
        val fade = appear("""{"kind":"fade","duration":400}""")
        show(Screens.document("a" to box("sq", 100, 40, "#ff0000", fade)), appearMs = 100.0)
        // 0.5776 of red over white: the green and blue channels are what is left of the white.
        val (r, g, b) = capture().channels(50, 20)
        assertEquals(255, r)
        assertEquals(255 * (1 - 0.577572928), g.toDouble(), 6.0)
        assertEquals(g, b)
    }

    @Test fun aRisingNodeStandsLowerByTheRestOfTheDistance() {
        val rise = appear("""{"kind":"rise","distance":16,"duration":400}""")
        show(Screens.document("a" to box("sq", 100, 40, "#ff0000", rise)), appearMs = 100.0)
        val image = capture()
        // 16 · (1 − 0.5776) = 6.8 points down: the top rows are the page, the rest is the tinted box.
        assertTrue("the top rows stay empty", image.isColor(50, 3, white))
        assertTrue("the box is lower, lighter than a full red", image.channels(50, 25).second in 60..160)
    }

    @Test fun theDelayKeepsTheStartPose() {
        val rise = appear("""{"kind":"fade","duration":400,"delay":300}""")
        show(Screens.document("a" to box("sq", 100, 40, "#ff0000", rise)), appearMs = 250.0)
        assertTrue("still invisible during the delay", capture().isColor(50, 20, white))
    }

    @Test fun theAppearanceEndsAtFullOpacityOnTheRealClock() {
        val clock = compose.mainClock
        clock.autoAdvance = false
        val fade = appear("""{"kind":"fade","duration":400}""")
        show(Screens.document("a" to box("sq", 100, 40, "#ff0000", fade)))
        clock.advanceTimeBy(16)
        assertTrue("not yet", !capture().isColor(50, 20, red))
        clock.advanceTimeBy(900)
        assertTrue("then full", capture().isColor(50, 20, red))
    }

    @Test fun reducedMotionStandsAtOnce() {
        android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(Screens.document("a" to box("sq", 100, 40, "#ff0000", appear("""{"kind":"fade","duration":400}"""))), appearMs = 0.0)
        assertTrue(capture().isColor(50, 20, red))
    }

    @Test fun aNodeThatComesLaterOnAShownScreenStandsAtOnce() {
        val fade = appear("""{"kind":"fade","duration":400}""")
        val later = box("later", 100, 40, "#0000ff", fade).replace(""""children":[]}""", """"children":[]${""","visibleIf":{"path":"ui.show","op":"eq","value":true}"""}}""")
        val document = Screens.document("a" to (box("first", 100, 40, "#ff0000", fade) + "," + later))
        var data by mutableStateOf<DataScope>(emptyMap())
        compose.setContent {
            CompositionLocalProvider(LocalNodeAppearClockMs provides 0.0) { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data) } }
        }
        compose.waitForIdle()
        assertTrue("the node of the first build starts invisible", capture().isColor(50, 20, white))
        data = mapOf("ui.show" to JsonValue.Bool(true))
        compose.waitForIdle()
        assertTrue("the node that came later does not play its appearance", capture().isColor(50, 60, blue))
        assertTrue("and the first one stays on its clock", capture().isColor(50, 20, white))
    }

    @Test fun thePageThatGoesDoesNotAppearASecondTime() {
        val fade = appear("""{"kind":"fade","duration":400}""")
        val button = """{"id":"go","type":"Button","props":{"label":{"kind":"literal","value":"Go"},"variant":"primary","action":{"type":"navigate","params":{"screenId":"b"}}}}"""
        val document = Screens.document("a" to (box("sq", 100, 40, "#ff0000", fade).let { it } + "," + button), "b" to box("blue", 10, 10, "#0000ff"))
        val clock = compose.mainClock
        clock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalNodeAppearClockMs provides 0.0) { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document) } }
        }
        clock.advanceTimeBy(16)
        assertTrue("the box starts invisible", capture().isColor(50, 20, white))
        compose.onNodeWithText("Go").performClick()
        clock.advanceTimeBy(16)
        clock.advanceTimeBy(90)
        // The copy of the page that goes drifts back; a node that appeared again would be invisible in it.
        assertFalse("the box of the page that goes is not hidden again", capture().isColor(40, 20, white) && capture().isColor(20, 20, white))
    }

    // The shared frames read without a report.

    @Test fun theFramesOfTheMinorsReadWithoutAReport() {
        for (name in listOf("motion-turn-screens", "appear-swipe-dot-screens", "carousel-auto-advance-screens", "screen-transition-screens")) {
            val document = loadDocument(Contract.bytes("fixtures/$name.json"))
            assertTrue("$name: ${document.reports}", document.reports.isEmpty())
        }
    }
}
