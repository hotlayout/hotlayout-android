package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.hotlayout.support.Contract
import com.hotlayout.support.JsonText
import com.hotlayout.support.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The timer of a carousel on the real screen, on the clock of the test: it takes the row to the next stop after the interval, writes
 * the stop once, and stands still while a finger holds the row, a layer covers the screen, the app is not in front, a screen reader is
 * on or the row has the keyboard. The rows of the table are run in full on the model clock of `CarouselAutoAdvanceCasesTest`: a step
 * of the real row takes a moment to scroll, and the count waits until the scroll has been quiet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class CarouselAutoAdvanceWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table = Contract.json("fixtures/carousel/carousel-cases.json")
    private val nodes = table["autoAdvanceRunScreen"]?.array.orEmpty()
    private val log = ArrayList<List<JsonValue>>()
    private val hasHorizontalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
    private val clock get() = compose.mainClock

    private fun text(value: JsonValue) = JsonText.of(value)

    /** The screen of the table, with the field or the path taken away when a test asks, and a layer to open over it. */
    private fun document(field: Boolean = true, loop: Boolean = false): HotlayoutDocument {
        val children = nodes.map { node ->
            if (node["id"]?.string != "row") return@map text(node)
            val props = JsonObject((node["props"]?.obj ?: JsonObject()).map { (key, value) ->
                if (key != "carousel") return@map key to value
                key to JsonValue.Obj(JsonObject((value.obj ?: JsonObject()).filter { field || it.first != "autoAdvance" }.map { (k, v) ->
                    if (k == "autoAdvance" && loop) k to JsonValue.Obj(JsonObject((v.obj ?: JsonObject()).toList() + ("loop" to JsonValue.Bool(true)))) else k to v
                }))
            })
            text(JsonValue.Obj(JsonObject((node.obj ?: JsonObject()).map { (key, value) -> if (key == "props") key to JsonValue.Obj(props) else key to value })))
        }
        val open = """{"id":"open","type":"Button","props":{"label":{"kind":"literal","value":"Cover"},"action":{"type":"navigate","params":{"screenId":"m"}}}}"""
        return Screens.document("a" to (children + open).joinToString(","), "m" to """{"id":"m-text","type":"Text","props":{"content":{"kind":"literal","value":"Layer"}}}""", modal = setOf("m"))
    }

    private fun show(document: HotlayoutDocument) {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, onWrite = { path, value -> log.add(listOf(JsonValue.Str("write"), JsonValue.Str(path), value)) }) }
        }
        // The row puts itself on its first stop after the first frame; the count starts there.
        compose.waitForIdle()
    }

    private fun scrollRange() = compose.onNode(hasHorizontalScroll, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]

    private fun offset(): Float = scrollRange().value()

    private fun captionShown(number: Int) = compose.onAllNodesWithText("Caption $number").fetchSemanticsNodes().isNotEmpty()

    private fun pass(ms: Long) = clock.advanceTimeBy(ms)

    @Test fun afterTheIntervalTheRowGoesToTheNextStopAndWritesItOnce() {
        show(document())
        pass(2800)
        assertEquals("before the interval nothing moved", 0f, offset(), 0.5f)
        assertEquals(emptyList<List<JsonValue>>(), log)
        pass(1500)
        assertTrue("the second stop", offset() > 100f)
        assertEquals(listOf(listOf<JsonValue>(JsonValue.Str("write"), JsonValue.Str("account.cardIndex"), JsonValue.Num(1.0))), log)
        assertTrue("the caption of the second story shows", captionShown(2) && !captionShown(1))
        // The settle of the step is no move of a person: it writes nothing more.
        pass(500)
        assertEquals(1, log.size)
    }

    @Test fun aFingerOnTheRowHoldsTheCount() {
        show(document())
        pass(2000)
        compose.onNode(hasHorizontalScroll).performTouchInput { down(Offset(10f, 10f)) }
        pass(10_000)
        assertEquals("held: nothing moved", 0f, offset(), 0.5f)
        compose.onNode(hasHorizontalScroll).performTouchInput { up() }
        pass(1500)
        assertTrue("let go: the rest of the interval passes and the row moves", offset() > 100f)
    }

    @Test fun withoutTheFieldNothingMoves() {
        show(document(field = false))
        pass(10_000)
        assertEquals(0f, offset(), 0.5f)
        assertEquals(emptyList<List<JsonValue>>(), log)
    }

    @Test fun aLayerOverTheScreenHoldsTheCount() {
        show(document())
        compose.onNodeWithText("Cover").performClick()
        pass(10_000)
        assertEquals("under a layer: no step", 0f, offset(), 0.5f)
        assertEquals(emptyList<List<JsonValue>>(), log)
    }

    @Test fun anAppThatIsNotInFrontHoldsTheCountAndItResumesWhenItIsBack() {
        show(document())
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        pass(10_000)
        // The window of a paused activity has no semantics to read: look when it is back.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals("in the background: no step", emptyList<List<JsonValue>>(), log)
        assertEquals(0f, offset(), 0.5f)
        pass(4000)
        assertTrue("back in front: the count goes on", offset() > 100f)
    }

    @Test fun aScreenReaderHoldsTheCount() {
        val manager = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        org.robolectric.Shadows.shadowOf(manager).setTouchExplorationEnabled(true)
        show(document())
        pass(10_000)
        assertEquals(0f, offset(), 0.5f)
    }

    @Test fun theKeyboardInsideTheRowHoldsTheCount() {
        show(document())
        compose.onNode(hasHorizontalScroll).performSemanticsAction(SemanticsActions.RequestFocus)
        pass(10_000)
        assertEquals("the row has the focus: no step", 0f, offset(), 0.5f)
    }

    @Test fun reducedMotionMovesTheRowAtOnceAndTheTimerStillRuns() {
        android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show(document())
        pass(3300)
        assertTrue("the row is on the second stop with no animation", offset() > 100f)
        assertEquals(1, log.size)
    }

    @Test fun theLastStopWithoutLoopStopsTheTimer() {
        show(document())
        pass(25_000)
        assertEquals("the last stop", scrollRange().maxValue(), offset(), 1f)
        assertEquals(2, log.size)
    }
}
