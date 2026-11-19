package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hotlayout.render.modal.ScreenTransition
import com.hotlayout.render.modal.StackStep
import com.hotlayout.render.modal.TransitionScreen
import com.hotlayout.support.Contract
import com.hotlayout.support.channels
import com.hotlayout.support.isColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The plan of a step of the stack, drawn by the real screen: where the pages stand while a page comes and goes, how a
 * dissolve and a step with no motion look, how the dim under a layer moves, and the route of the shared frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ScreenTransitionWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val clock get() = compose.mainClock
    private val red = 0xff0000
    private val blue = 0x0000ff

    private fun capture(): android.graphics.Bitmap {
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun button(label: String, action: String) =
        """{"id":"b-$label","type":"Button","props":{"label":{"kind":"literal","value":"$label"},"variant":"primary","action":$action}}"""

    private fun page(id: String, color: String, children: String, extra: String = "") =
        """{"screenId":"$id","name":"$id"$extra,"root":{"id":"$id-root","type":"Screen","props":{"gap":"none"},"style":{"background":{"kind":"color","color":{"value":"$color"}}},"children":[$children]}}"""

    private fun push(to: String) = """{"type":"navigate","params":{"screenId":"$to"}}"""

    /** A red page `a`, a blue page `b` on top of it, and the layers and the pages with a word of `transition` that a test names. */
    private fun document(bExtra: String = "", mExtra: String = ""): HotlayoutDocument = loadDocument(
        """{"schemaVersion":"9.42.0","documentId":"t","name":"T","initialScreenId":"a","screens":[
            ${page("a", "#ff0000", button("Next", push("b")) + "," + button("Layer", push("m")))},
            ${page("b", "#0000ff", button("Back", """{"type":"navigate.back"}"""), bExtra)},
            ${page("m", "#0000ff", button("Close", """{"type":"navigate.back"}"""), ""","presentation":"modal"$mExtra""")}
        ]}""",
    )

    private fun show(document: HotlayoutDocument) {
        clock.autoAdvance = false
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, locale = "en") } }
        clock.advanceTimeBy(16)
    }

    /** Presses a button and lets the screen change; the motion of the step starts on the next frame, so a probe reads the plan at `advanced − 16`. */
    private fun press(label: String) {
        compose.onNodeWithText(label).performClick()
        clock.advanceTimeBy(16)
    }

    @Test fun aPageComesInFromTheEndEdgeOverTheOneThatDriftsBack() {
        show(document())
        press("Next")
        clock.advanceTimeBy(75)
        val image = capture()
        // At 75 ms the new page stands about 0.39 of the width from the start: the page that goes drifts back beneath it.
        assertTrue("the page beneath is still seen at the start edge", image.isColor(40, 300, red))
        assertTrue("the new page covers the end side", image.isColor(330, 300, blue))
        clock.advanceTimeBy(400)
        assertTrue("then the new page stands", capture().isColor(30, 300, blue))
    }

    @Test fun aStepBackPlaysThePushTheOtherWay() {
        show(document())
        press("Next")
        clock.advanceTimeBy(400)
        press("Back")
        clock.advanceTimeBy(75)
        val image = capture()
        // The page that goes slides to the end edge OVER the one that comes back from its parallax.
        assertTrue("the page that comes back is seen at the start edge", image.isColor(40, 300, red))
        assertTrue("the page that goes covers the end side", image.isColor(340, 300, blue))
        clock.advanceTimeBy(400)
        assertTrue("then only the first page", capture().isColor(340, 300, red))
    }

    @Test fun aPageThatSaysFadeDissolvesInAndOneThatSaysNoneAppearsAtOnce() {
        show(document(bExtra = ""","transition":"fade""""))
        press("Next")
        clock.advanceTimeBy(116)
        val (r, _, b) = capture().channels(180, 300)
        assertTrue("the old page fades out over the new one: both are in the pixel ($r, $b)", r in 40..215 && b in 40..215)
        clock.advanceTimeBy(300)
        assertTrue(capture().isColor(180, 300, blue))
    }

    @Test fun aPageThatSaysNoneAppearsAtOnce() {
        show(document(bExtra = ""","transition":"none""""))
        press("Next")
        clock.advanceTimeBy(16)
        assertTrue("no motion: the new page is there on the first frames", capture().isColor(30, 300, blue))
    }

    @Test fun reducedMotionAppearsAtOnce() {
        android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(document())
        press("Next")
        clock.advanceTimeBy(16)
        assertTrue(capture().isColor(30, 300, blue))
    }

    @Test fun aNewStepFinishesTheOneBeforeIt() {
        show(document())
        press("Next")
        clock.advanceTimeBy(60)
        press("Back")
        clock.advanceTimeBy(400)
        assertTrue("the first step ended at once and the second one ran to the first page", capture().isColor(340, 300, red))
        compose.onNodeWithText("Next").assertExists()
    }

    @Test fun theDimUnderASheetComesWithTheSheetOnTheCurveOfThePlan() {
        show(document())
        press("Layer")
        // 70 ms of the 280 ms: the dim stands at the share of its resting opacity (0.45) the plan gives.
        clock.advanceTimeBy(86)
        val early = capture().channels(180, 100).first
        val share = ScreenTransition.frame(ScreenTransition.plan(StackStep.Push, TransitionScreen(), TransitionScreen(modal = true), 360.0), 70.0).scrim
        clock.advanceTimeBy(400)
        val rest = capture().channels(180, 100).first
        assertEquals("at rest the dim is 0.45 of black over the red page", 255 * (1 - 0.45), rest.toDouble(), 6.0)
        assertEquals("a quarter in: 0.607 of the resting dim", 255 * (1 - 0.45 * share), early.toDouble(), 12.0)
        assertEquals(0.6072, share, 0.001)
    }

    @Test fun theDimGoesWithTheSheetWhenItCloses() {
        show(document())
        press("Layer")
        clock.advanceTimeBy(400)
        press("Close")
        // Half of the 220 ms of the closing: the dim is 0.675 of its resting opacity.
        clock.advanceTimeBy(126)
        val half = capture().channels(180, 100).first
        assertEquals(255 * (1 - 0.45 * 0.6752), half.toDouble(), 14.0)
        clock.advanceTimeBy(400)
        assertTrue("the layer is gone ${capture().channels(180, 100)}", capture().isColor(180, 100, red))
    }

    /** The route of the frame the contract ships: every kind of step, walked by pressing its buttons. */
    @Test fun theRouteOfTheSharedFrameIsWalked() {
        show(loadDocument(Contract.bytes("fixtures/screen-transition-screens.json")))
        fun go(label: String, expect: String) {
            press(label)
            clock.advanceTimeBy(400)
            compose.onNodeWithText(expect).assertExists()
        }
        go("Next", "Step 2 of 3")
        go("Next", "Step 3 of 3")
        go("Finish", "All set")
        go("Close", "Step 3 of 3")
        go("Read the terms", "Terms")
        go("Got it", "Step 3 of 3")
        go("Back", "Step 2 of 3")
        go("Back", "Step 1 of 3")
        go("What's new", "A calmer look")
        go("Back", "Step 1 of 3")
        go("Skip", "Skipped")
        go("Back", "Step 1 of 3")
    }
}
