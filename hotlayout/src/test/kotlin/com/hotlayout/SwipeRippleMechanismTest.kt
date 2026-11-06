package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import com.hotlayout.render.widgets.LAYER_TAG
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.hotlayout.render.widgets.SwipeRippleSpec
import com.hotlayout.render.widgets.SwipeRippleStore
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.near
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The fill of a swipe button on the real screen: the thumb is let go as a finger would, the circle grows over the screen
 * first, the action runs under it, the fill fades away over what the action put on the screen. The shared frame is
 * `swipe-motion-screens`; its button «Slide to pay» goes to the screen `paid` with `confirmTransition: 'ripple'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class SwipeRippleMechanismTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val primary = 0x4f46e5
    private val document get() = loadDocument(String(Contract.bytes("fixtures/swipe-motion-screens.json")))

    private fun capture(): android.graphics.Bitmap {
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun rgbAt(x: Int, y: Int): Int = capture().getPixel(x, y) and 0xFFFFFF

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { content() } }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun swipePay() {
        compose.onNodeWithContentDescription("Slide to pay", useUnmergedTree = true).performTouchInput {
            down(Offset(20f, height / 2f))
            moveTo(Offset(width - 20f, height / 2f))
            up()
        }
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms)

    @Test fun theActionWaitsForTheFillAndThenTheScreenChanges() {
        val log = ArrayList<String>()
        show {
            HotlayoutScreen(document, onTrack = { e, _ -> log.add("track:${e.event}") }, onScreenChange = { log.add("shown:${it.screenId}") })
        }
        log.clear()
        swipePay()
        // The thumb glides (200 ms) and the button collapses (600 ms): nothing has run yet.
        advance(700)
        assertEquals(emptyList<String>(), log)
        // The circle grows for 800 ms; the action goes only after it.
        advance(700)
        assertEquals("still growing", emptyList<String>(), log)
        advance(200)
        assertEquals(listOf("shown:paid"), log)
        // The fill fades over the next screen and is gone after its 200 ms: nothing else runs.
        advance(300)
        assertEquals(listOf("shown:paid"), log)
    }

    @Test fun theFillCoversTheWholeScreenBeforeTheAction() {
        show { HotlayoutScreen(document) }
        val corners = listOf(5 to 5, 355 to 5, 5 to 635, 355 to 635)
        val before = corners.map { (x, y) -> rgbAt(x, y) }
        swipePay()
        advance(800 + 799)
        val after = corners.map { (x, y) -> rgbAt(x, y) }
        // The circle has reached every corner (the farthest one only with its halo, a hair before the end); the screen under it has not changed yet.
        assertTrue("every corner is covered by the fill: $before -> $after", after.indices.all { !near(after[it], before[it], 40) })
        assertTrue("the corners nearest to the button are the colour of the thumb: $after", near(after[0], primary, 40) && near(after[1], primary, 40))
    }

    @Test fun theKeyboardAndAssistiveTechnologyActAtOnce() {
        val log = ArrayList<String>()
        show { HotlayoutScreen(document, onScreenChange = { log.add("shown:${it.screenId}") }) }
        log.clear()
        compose.onNodeWithContentDescription("Slide to pay", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        advance(50)
        assertEquals(listOf("shown:paid"), log)
    }

    @Test fun enterActsAtOnce() {
        val log = ArrayList<String>()
        show { HotlayoutScreen(document, onScreenChange = { log.add("shown:${it.screenId}") }) }
        log.clear()
        val node = compose.onNodeWithContentDescription("Slide to pay", useUnmergedTree = true)
        node.requestFocus()
        node.performKeyInput { keyDown(androidx.compose.ui.input.key.Key.Enter); keyUp(androidx.compose.ui.input.key.Key.Enter) }
        advance(50)
        assertEquals(listOf("shown:paid"), log)
    }

    @Test fun aButtonThatLeavesBeforeTheCoverTakesTheFillWithIt() {
        val log = ArrayList<String>()
        var entry by mutableStateOf<String?>(null)
        show { HotlayoutScreen(document, entry = entry, onScreenChange = { log.add("shown:${it.screenId}") }, onTrack = { e, _ -> log.add("track:${e.event}") }) }
        swipePay()
        advance(900)
        log.clear()
        // The host restarts the flow (a name the document does not know starts it again) while the circle is on its way.
        entry = "elsewhere"
        advance(100)
        log.clear()
        advance(2000)
        assertEquals("nothing runs: the fill went with the button", emptyList<String>(), log.filter { it == "shown:paid" })
        assertTrue("the fill is gone", !near(rgbAt(5, 5), primary, 20))
    }

    @Test fun aHostThatKeepsItsStackHearsTheCoverBeforeTheAction() {
        val log = ArrayList<String>()
        show {
            HotlayoutOverlayHost {
                HotlayoutScreen(
                    document,
                    onNavigate = { log.add("navigate:${it.toScreenId}"); false },
                    onScreenCovered = { log.add("covered:$it") },
                )
            }
        }
        swipePay()
        advance(1500)
        assertEquals(listOf("covered:true"), log)
        advance(200)
        assertEquals(listOf("covered:true", "navigate:paid"), log)
        advance(400)
        assertEquals(listOf("covered:true", "navigate:paid", "covered:false"), log)
    }

    /** A host whose routes drop the screen the moment it is told to navigate. */
    private fun hostThatRemovesTheScreen(log: MutableList<String>, hostModifier: Modifier = Modifier) {
        var visible by mutableStateOf(true)
        show {
            HotlayoutOverlayHost(hostModifier) {
                if (visible) {
                    HotlayoutScreen(
                        document,
                        onNavigate = { log.add("navigate:${it.toScreenId}"); visible = false; false },
                        onScreenCovered = { log.add("covered:$it") },
                    )
                }
            }
        }
    }

    @Test fun hostRemovesScreenOnNavigate() {
        val log = ArrayList<String>()
        hostThatRemovesTheScreen(log)
        swipePay()
        advance(1700)
        assertEquals(listOf("covered:true", "navigate:paid"), log)
        advance(400)
        assertEquals(listOf("covered:true", "navigate:paid", "covered:false"), log)
    }

    @Test fun hostRemovesScreenBeforeCover() {
        val log = ArrayList<String>()
        var visible by mutableStateOf(true)
        show {
            HotlayoutOverlayHost {
                if (visible) HotlayoutScreen(document, onScreenCovered = { log.add("covered:$it") })
            }
        }
        swipePay()
        advance(1100)
        assertEquals(listOf("covered:true"), log)
        // The host takes the screen away while the circle is still growing: the fill goes with the button, nothing runs.
        visible = false
        advance(300)
        assertEquals(listOf("covered:true", "covered:false"), log)
        advance(2000)
        assertEquals(listOf("covered:true", "covered:false"), log)
    }

    @Test fun theFillFadesAwayOverTheNextScreenOfTheHost() {
        val log = ArrayList<String>()
        hostThatRemovesTheScreen(log)
        swipePay()
        advance(1500)
        while ("navigate:paid" !in log) advance(10)
        // The screen is gone from the host's routes; the fill is still there, over what stands in its place.
        compose.onNodeWithTag(LAYER_TAG).assertExists()
        assertTrue("the corner is still the colour of the fill: ${rgbAt(5, 5)}", !near(rgbAt(5, 5), 0xFFFFFF, 40))
        advance(100)
        compose.onNodeWithTag(LAYER_TAG).assertExists()
        advance(150)
        compose.onNodeWithTag(LAYER_TAG).assertDoesNotExist()
    }

    @Test fun theFillLiesOverTheWholeBoxOfTheOverlayHost() {
        hostThatRemovesTheScreen(ArrayList(), Modifier.testTag("host").size(320.dp, 600.dp))
        swipePay()
        advance(1000)
        val host = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val layer = compose.onNodeWithTag(LAYER_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(host, layer)
        assertEquals(600f, layer.height, 1f)
    }

    @Test fun aHostWithoutAnOverlayHostGetsNoFillAndTheActionRunsAtOnce() {
        val log = ArrayList<String>()
        show {
            HotlayoutScreen(
                document,
                onNavigate = { log.add("navigate:${it.toScreenId}"); false },
                onScreenCovered = { log.add("covered:$it") },
            )
        }
        swipePay()
        advance(700)
        compose.onNodeWithTag(LAYER_TAG).assertDoesNotExist()
        advance(100)
        assertEquals(listOf("navigate:paid"), log)
        advance(2000)
        assertEquals(listOf("navigate:paid"), log)
    }

    @Test fun aButtonThatLeavesBeforeTheCoverTakesTheHostFillWithIt() {
        val log = ArrayList<String>()
        var entry by mutableStateOf<String?>(null)
        show { HotlayoutOverlayHost { HotlayoutScreen(document, entry = entry, onNavigate = { log.add("navigate"); false }, onScreenCovered = { log.add("covered:$it") }) } }
        swipePay()
        advance(900)
        entry = "elsewhere"
        advance(2000)
        assertEquals(listOf("covered:true", "covered:false"), log)
        compose.onNodeWithTag(LAYER_TAG).assertDoesNotExist()
    }

    @Test fun theHostsAnswerExpandsTheCircleBackBeforeTheThumbReturns() {
        val task = kotlinx.coroutines.Job() as kotlinx.coroutines.CompletableJob
        val calls = ArrayList<String>()
        show { HotlayoutScreen(document, onAction = { a, _ -> calls.add(a.type) }, onActionTask = { _, _ -> task }) }
        compose.onNodeWithContentDescription("Slide to add a tip", useUnmergedTree = true).performTouchInput {
            down(Offset(20f, height / 2f)); moveTo(Offset(width - 20f, height / 2f)); up()
        }
        advance(1000)
        assertEquals(listOf("submit"), calls)
        // Busy while the task runs: another swipe does nothing.
        compose.onNodeWithContentDescription("Slide to add a tip", useUnmergedTree = true).performTouchInput {
            down(Offset(20f, height / 2f)); moveTo(Offset(width - 20f, height / 2f)); up()
        }
        advance(500)
        assertEquals(1, calls.size)
        task.complete()
        // The circle expands back (600 ms) and the thumb returns (250 ms); only then does the button act again.
        advance(300)
        compose.onNodeWithContentDescription("Slide to add a tip", useUnmergedTree = true).performTouchInput {
            down(Offset(20f, height / 2f)); moveTo(Offset(width - 20f, height / 2f)); up()
        }
        advance(100)
        assertEquals("still expanding", 1, calls.size)
        advance(1000)
        compose.onNodeWithContentDescription("Slide to add a tip", useUnmergedTree = true).performTouchInput {
            down(Offset(20f, height / 2f)); moveTo(Offset(width - 20f, height / 2f)); up()
        }
        advance(100)
        assertEquals(2, calls.size)
    }
}

/** The store of the fills with no screen: the order of the report and the action, the cancel. */
@OptIn(ExperimentalCoroutinesApi::class)
class SwipeRippleStoreTest {
    private fun spec(log: MutableList<String>) =
        SwipeRippleSpec(Offset(10f, 10f), 40f, 30f, androidx.compose.ui.graphics.Color.Red, androidx.compose.ui.graphics.Color.White, 16.dp) { log.add("act") }

    @Test fun theReportComesBeforeTheActionAndTheLastFillEndsIt() = runTest {
        val log = ArrayList<String>()
        val store = SwipeRippleStore(this)
        store.start(spec(log), { log.add("report:$it") })
        assertEquals(listOf("report:true"), log)
        assertTrue(store.covers)
        advanceTimeBy(801)
        assertEquals(listOf("report:true", "act"), log)
        advanceTimeBy(250)
        testScheduler.runCurrent()
        assertEquals(listOf("report:true", "act", "report:false"), log)
        assertFalse(store.covers)
    }

    @Test fun aFillCancelledBeforeTheCoverRunsNothing() = runTest {
        val log = ArrayList<String>()
        val store = SwipeRippleStore(this)
        store.start(spec(log), { log.add("report:$it") }).cancel()
        assertFalse(store.covers)
        advanceTimeBy(2000)
        assertEquals(listOf("report:true", "report:false"), log)
    }

    @Test fun aFillThatHasActedGoesOnToItsEnd() = runTest {
        val log = ArrayList<String>()
        val store = SwipeRippleStore(this)
        val handle = store.start(spec(log), null)
        advanceTimeBy(801)
        handle.cancel()
        assertTrue("it belongs to the screen now", store.covers)
        advanceTimeBy(250)
        assertFalse(store.covers)
        assertEquals(listOf("act"), log)
    }
}
