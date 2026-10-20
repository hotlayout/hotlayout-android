package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
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

/**
 * What the real screen does with the progress scales, read back from pixels and from semantics:
 * the rule tables prove the numbers, these prove that `HotlayoutScreen` draws them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ProgressWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val fill = 0x4f46e5
    private val track = 0xeef0f6
    private val white = 0xffffff

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFFFFFF

    private fun number(value: Double) = """{"kind":"literal","value":$value}"""

    private fun bar(props: String = "", id: String = "b") =
        """{"id":"$id","type":"ProgressBar","props":{"value":${number(50.0)},"max":${number(100.0)},"color":"primary","thickness":8,"labelFormat":"none"$props}}"""

    private fun ring(props: String = "", style: String = """{"width":100,"height":100}""") =
        """{"id":"r","type":"CircularProgress","props":{"value":${number(25.0)},"max":${number(100.0)},"color":"primary","thickness":10$props},"style":$style}"""

    private fun show(document: HotlayoutDocument, locale: String? = null, data: com.hotlayout.DataScope = emptyMap()) {
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data, locale = locale ?: "en") } }
        compose.waitForIdle()
    }

    @Test fun aRingStartsAtTheTopAndRunsClockwise() {
        show(Screens.document("a" to ring()))
        val image = capture()
        assertTrue("just above three o'clock is filled", near(image.rgbAt(95, 45), fill))
        assertTrue("just after twelve is filled", near(image.rgbAt(55, 5), fill))
        assertTrue("just before twelve is the track", near(image.rgbAt(45, 5), track))
        assertTrue("six o'clock is the track", near(image.rgbAt(50, 95), track))
        assertTrue("the middle is empty", near(image.rgbAt(50, 50), white))
    }

    @Test fun theArcLiesInsideTheCircleAndTheNamedBoxFixesTheDiameter() {
        show(Screens.document("a" to ring(style = """{"width":200,"height":120}""", props = ""","value":${number(100.0)}""")))
        val image = capture()
        // The diameter is the smaller side (120); the ring stands in the middle of the 200 wide box: 40 free on each side.
        assertTrue("left edge of the circle", near(image.rgbAt(40 + 4, 60), fill))
        assertTrue("outside the circle on the left", near(image.rgbAt(30, 60), white))
        assertTrue("right edge of the circle", near(image.rgbAt(160 - 4, 60), fill))
    }

    @Test fun aRingGradientRunsAlongTheFilledArcOnly() {
        val gradient = ""","gradient":{"angle":90,"stops":[{"color":{"value":"#ff0000"},"position":0},{"color":{"value":"#0000ff"},"position":1}]}"""
        show(Screens.document("a" to ring(props = ""","value":${number(50.0)}$gradient""")))
        val image = capture()
        assertTrue("starts red", near(image.rgbAt(55, 5), 0xff0000, 60))
        assertTrue("ends blue just before six o'clock", near(image.rgbAt(52, 94), 0x0000ff, 60))
    }

    @Test fun roundEndsAddHalfCirclesAndTheCornerIsRoundedOff() {
        show(Screens.document("a" to ring(props = ""","value":${number(50.0)},"cap":"round"""")))
        val round = capture()
        assertTrue("the corner at twelve is rounded off", !near(round.rgbAt(50, 0), fill, 20))
        assertTrue("past the start the arc is filled", near(round.rgbAt(56, 5), fill))
    }

    @Test fun theLabelIsOneLineInTheMiddleThatDoesNotSizeTheRing() {
        show(Screens.document("a" to ring(props = ""","labelFormat":"valueOfMax","labelTypography":"displayL"""")))
        compose.onNodeWithText("25 / 100").assertExists()
        val bounds = compose.onNodeWithText("25 / 100").fetchSemanticsNode().boundsInRoot
        assertEquals("centred in the ring", 50f, (bounds.left + bounds.right) / 2, 2f)
    }

    @Test fun aBarGrowsFromTheLogicalStartInBothDirections() {
        val ltr = Screens.document("a" to bar())
        show(ltr)
        val left = capture()
        assertTrue(near(left.rgbAt(10, 4), fill))
        assertTrue(near(left.rgbAt(350, 4), track))
    }

    @Test fun aBarGrowsFromTheRightInArabic() {
        val localization = ""","localization":{"defaultLocale":"en","locales":["en","ar"],"strings":{"ar":{"a/b/label":"مستوى"}}}"""
        show(Screens.document("a" to bar(""","label":${Screens.literal("Level")}"""), extra = localization), locale = "ar")
        val image = capture()
        assertTrue(near(image.rgbAt(350, 4), fill))
        assertTrue(near(image.rgbAt(10, 4), track))
    }

    @Test fun stepsLeaveGapsWithNothingUnderThem() {
        show(Screens.document("a" to bar(""","segments":6""")))
        val image = capture()
        // Six steps on 360: (360 − 5×4) / 6 = 56.67 each; the first is filled, the gap after it shows the page.
        assertTrue(near(image.rgbAt(25, 4), fill))
        assertTrue("a gap shows what is behind", near(image.rgbAt(58, 4), white))
        assertTrue("the current step looks unfilled", near(image.rgbAt(25 + 3 * 61, 4), track))
    }

    @Test fun theCurrentStepTakesItsColour() {
        show(Screens.document("a" to bar(""","segments":6,"currentColor":"accent"""")))
        assertTrue(near(capture().rgbAt(25 + 3 * 61, 4), 0x0369a1))
    }

    @Test fun aGlowIsAGhostUnderTheScaleThatSpreadsOutsideIt() {
        show(Screens.document("a" to bar(""","glow":"strong""""), rootStyle = """{"background":{"kind":"color","color":{"value":"#ffffff"}},"padding":"lg"}"""))
        val image = capture()
        // The bar stands 16 px from the top and is 8 tall; two pixels above it the page is tinted by the glow.
        val above = image.rgbAt(50, 16 - 3)
        assertTrue("the glow tints the page above the bar: ${"%06x".format(above)}", !near(above, white, 3))
        assertTrue("the bar itself is drawn over its glow", near(image.rgbAt(50, 20), fill))
    }

    @Test fun aBarWithoutGlowLeavesThePageWhite() {
        show(Screens.document("a" to bar(), rootStyle = """{"background":{"kind":"color","color":{"value":"#ffffff"}},"padding":"lg"}"""))
        assertTrue(near(capture().rgbAt(50, 16 - 3), white, 1))
    }

    @Test fun theScaleIsAProgressBarForTheReaderOfTheScreen() {
        show(Screens.document("a" to bar(""","label":${Screens.literal("Level")}""")))
        val node = compose.onRoot().fetchSemanticsNode()
        val bar = collect(node).first { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
        assertEquals(0.5f, bar.config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
    }

    private fun collect(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        listOf(node) + node.children.flatMap(::collect)

    @Test fun aBarLabelStandsAtTheEndOfTheRow() {
        show(Screens.document("a" to bar().replace(""""labelFormat":"none"""", """"labelFormat":"percent"""")))
        val bounds = compose.onNodeWithText("50%").fetchSemanticsNode().boundsInRoot
        assertTrue("the label ends the row on the right: $bounds", bounds.right > 340f)
    }

    // region Motion

    private val clock get() = compose.mainClock

    @Test fun animateInGrowsTheFillFromZero() {
        clock.autoAdvance = false
        show(Screens.document("a" to bar(""","animateIn":true""")))
        clock.advanceTimeBy(16)
        assertTrue("the first frame is empty", near(capture().rgbAt(10, 4), track))
        clock.advanceTimeBy(500)
        val mid = capture()
        // e(0.7) = 0.973: the fill is at 0.5 × 0.973 × 360 = 175 px.
        assertTrue("grown by now", near(mid.rgbAt(150, 4), fill))
        assertTrue("not past the target", near(mid.rgbAt(200, 4), track))
        clock.advanceTimeBy(500)
        assertTrue("done", near(capture().rgbAt(170, 4), fill))
    }

    @Test fun aChangeOfTheDataLeadsFromTheOldValueWithAnimateChanges() {
        clock.autoAdvance = false
        val document = Screens.document("a" to """{"id":"b","type":"ProgressBar","props":{"value":{"kind":"binding","path":"user.xp","fallback":0},"max":${number(100.0)},"color":"primary","thickness":8,"labelFormat":"none","animateChanges":true}}""")
        var data by mutableStateOf(dataScopeOf("user.xp" to 0))
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data) } }
        clock.advanceTimeBy(100)
        assertTrue("shown at once the first time", near(capture().rgbAt(10, 4), track))
        data = dataScopeOf("user.xp" to 100)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        clock.advanceTimeBy(16)
        clock.advanceTimeBy(100)
        val early = capture()
        assertTrue("moving: the left is filled", near(early.rgbAt(10, 4), fill))
        assertTrue("moving: the right is not yet", near(early.rgbAt(350, 4), track))
        clock.advanceTimeBy(800)
        assertTrue("arrived", near(capture().rgbAt(350, 4), fill))
    }

    @Test fun stepsFillOneByOneDuringATransitionAndTheLabelCountsInWholeNumbers() {
        clock.autoAdvance = false
        val document = Screens.document("a" to """{"id":"b","type":"ProgressBar","props":{"value":{"kind":"binding","path":"user.xp","fallback":0},"max":${number(6.0)},"color":"primary","thickness":8,"segments":6,"animateChanges":true,"labelFormat":"valueOfMax"}}""")
        var data by mutableStateOf(dataScopeOf("user.xp" to 0))
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data) } }
        clock.advanceTimeBy(100)
        data = dataScopeOf("user.xp" to 6)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        clock.advanceTimeBy(16)
        clock.advanceTimeBy(300)
        val label = shownTexts().first { it.endsWith("/ 6") }
        assertTrue("the label is a whole number of the way: $label", label.matches(Regex("[0-6] / 6")))
        clock.advanceTimeBy(800)
        compose.onNodeWithText("6 / 6").assertExists()
    }

    private fun shownTexts(): List<String> =
        collect(compose.onRoot().fetchSemanticsNode()).mapNotNull { (if (it.config.contains(SemanticsProperties.Text)) it.config[SemanticsProperties.Text].firstOrNull()?.text else null) }

    // endregion
}
