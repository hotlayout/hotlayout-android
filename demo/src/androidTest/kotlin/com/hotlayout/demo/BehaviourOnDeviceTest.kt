package com.hotlayout.demo

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hotlayout.ScreenShownCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real touches, the real back key and real frames on a device: what the JVM cannot prove. */
@RunWith(AndroidJUnit4::class)
class BehaviourOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun motion() = setAnimatorScale(1f)

    /** The system back key: one step of the reader's stack, then the host's handler at the first screen. */
    @Test
    fun systemBackStepsTheStackThenGoesToTheHost() {
        val recorder = Recorder()
        var hostBacks = 0
        rule.setContent {
            BackHandler { hostBacks += 1 }
            FixtureHost(fixture("alignment-screens", "centered"), recorder, "alignment-screens")
        }
        rule.onNodeWithText("See both axes").performClick()
        rule.onNodeWithText("Both axes").assertIsDisplayed()
        device.pressBack()
        rule.waitForIdle()
        rule.onNodeWithText("You are all set").assertIsDisplayed()
        assertEquals(ScreenShownCause.Back, recorder.shown.last().cause)
        assertEquals(0, hostBacks)
        device.pressBack()
        rule.waitForIdle()
        assertEquals(1, hostBacks)
    }

    /** A drag across the carousel ends on a stop: the second card stands where the first stood. */
    @Test
    fun carouselSnapsToTheNearestStop() {
        rule.setContent { FixtureHost(fixture("carousel-steps-screens"), Recorder(), "carousel-steps-screens") }
        val first = rule.onNodeWithText("Spend 500 with your card").getBoundsInRoot()
        val firstLeft = first.left
        rule.onNodeWithText("Spend 500 with your card").performTouchInput {
            swipe(Offset(centerX + 40f, centerY), Offset(centerX - width * 0.9f, centerY), 400)
        }
        rule.waitForIdle()
        Thread.sleep(800)
        rule.waitForIdle()
        val second = rule.onNodeWithText("Set up a recurring payment").getBoundsInRoot()
        assertEquals("the second card lies at the first stop", firstLeft.value, second.left.value, 1.5f)
        val dots = rule.onAllNodesWithTag("carousel:dot:active", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, dots.size)
    }

    /** A tap on a swipe-to-confirm button does nothing; a drag past the threshold runs the action. */
    @Test
    fun swipeToConfirmNeedsTheDrag() {
        val recorder = Recorder()
        rule.setContent { FixtureHost(fixture("float-nav-swipe-screens", "missions"), recorder, "float-nav-swipe-screens") }
        val before = recorder.shown.size
        rule.onNodeWithTag("swipe-confirm", useUnmergedTree = true).performTouchInput { click(centerLeft + Offset(30f, 0f)) }
        rule.waitForIdle()
        assertEquals("a tap moved the stack", before, recorder.shown.size)
        rule.onNodeWithTag("swipe-confirm", useUnmergedTree = true).performTouchInput {
            swipe(centerLeft + Offset(30f, 0f), centerRight - Offset(5f, 0f), 600)
        }
        rule.waitForIdle()
        assertEquals("redeem", recorder.shown.last().screenId)
    }

    /** A hold opens the long-press screen with its haptic request; the touch after the hold does not also run the tap action. */
    @Test
    fun longPressRunsOnlyTheLongPressAction() {
        val recorder = Recorder()
        rule.setContent { FixtureHost(fixture("longpress-groups-sheet-screens"), recorder, "longpress-groups-sheet-screens") }
        rule.onNodeWithText("Hold for options", useUnmergedTree = true).performTouchInput { longClick(center, 900) }
        rule.waitForIdle()
        assertEquals(listOf("history", "peek"), recorder.shown.map { it.screenId })
        assertEquals(listOf("medium"), recorder.haptics)
    }

    /** A sheet dragged down leaves; one more step back is not taken. */
    @Test
    fun sheetDraggedDownLeaves() {
        val recorder = Recorder()
        rule.setContent { Box(Modifier.fillMaxSize()) { FixtureHost(fixture("longpress-groups-sheet-screens"), recorder, "longpress-groups-sheet-screens") } }
        rule.onNodeWithText("Quick redeem").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Redeem").assertIsDisplayed()
        rule.onNodeWithText("Redeem").performTouchInput { swipeDown(startY = top - 40f, endY = top + 900f, durationMillis = 300) }
        rule.waitForIdle()
        Thread.sleep(600)
        rule.waitForIdle()
        assertEquals("history", recorder.shown.last().screenId)
        assertEquals(1, recorder.shown.count { it.cause == ScreenShownCause.Back })
    }

    /**
     * Leaving a modal sheet by a tap on the dim: Flutter lets the sheet sink back (the same motion as a drag that
     * passed the threshold); here the layer is gone in the very next frame.
     */
    @Test
    fun sheetLeftByATapOnTheDimSinksBack() {
        val recorder = Recorder()
        rule.setContent { FixtureHost(fixture("longpress-groups-sheet-screens"), recorder, "longpress-groups-sheet-screens") }
        rule.onNodeWithText("Quick redeem").performClick()
        rule.waitForIdle()
        val redeem = SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Redeem")))
        assertTrue(rule.onAllNodes(redeem).fetchSemanticsNodes().isNotEmpty())
        // The dim over the page, read from the picture: the layer is hidden from accessibility while it sinks,
        // so its look is the only thing that tells it is still drawn.
        val dimmed = luminanceAt(0.2f)
        rule.mainClock.autoAdvance = false
        rule.onRoot().performTouchInput { click(Offset(width / 2f, height * 0.2f)) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        val sinking = luminanceAt(0.2f)
        val inTheTree = rule.onAllNodes(redeem).fetchSemanticsNodes().isNotEmpty()
        rule.mainClock.advanceTimeBy(1000)
        val plain = luminanceAt(0.2f)
        rule.mainClock.autoAdvance = true
        assertTrue("the page was not dimmed by the layer ($dimmed vs $plain)", plain > dimmed + 8)
        assertTrue("the sheet vanished at once instead of sinking back ($sinking, dimmed $dimmed, plain $plain)", sinking < (dimmed + plain) / 2)
        assertTrue("a layer that left the stack is still in the accessibility tree", !inTheTree)
    }

    private fun luminanceAt(share: Float): Int {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val px = bitmap.getPixel(bitmap.width / 2, (bitmap.height * share).toInt())
        return (android.graphics.Color.red(px) + android.graphics.Color.green(px) + android.graphics.Color.blue(px)) / 3
    }

    /** «Reduce motion» (animator scale 0): a page with endless node motion becomes idle. */
    @Test
    fun reducedMotionLetsTheScreenGoIdle() {
        setAnimatorScale(0f)
        try {
            rule.setContent { FixtureHost(fixture("motion-blur-screens"), Recorder(), "motion-blur-screens") }
            rule.waitForIdle()
            rule.onNodeWithText("Quiet sky").assertIsDisplayed()
        } finally {
            setAnimatorScale(1f)
        }
    }

    /** A node with endless motion below the fold asks for no frames: the screen goes idle with motion on. */
    @Test
    fun motionBelowTheFoldAsksForNoFrames() {
        val document = hostile(
            """{"schemaVersion":"9.27.0","documentId":"fold","initialScreenId":"s","screens":[{"screenId":"s","name":"S","root":
            {"id":"r","type":"Screen","props":{"gap":"md"},"children":[
              {"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Top"}}},
              {"id":"sp","type":"Spacer","props":{"size":"xl"},"style":{"height":4000}},
              {"id":"m","type":"Text","props":{"content":{"kind":"literal","value":"Moving"}},"motion":{"kind":"pulse","scale":1.1,"duration":1200}}
            ]}}]}""",
        )
        rule.setContent { FixtureHost(document, Recorder()) }
        rule.waitForIdle()
        rule.onNodeWithText("Top").assertIsDisplayed()
    }
}
