package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hotlayout.support.AdaptiveRender
import com.hotlayout.support.Contract
import com.hotlayout.support.ScreenStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wide places through the REAL screen (`adaptive-screens`, drawn by `HotlayoutScreen`): where the bar of navigation stands on
 * 393, 768 and 1280 wide boxes in both writing directions, what the labels do, what a sheet becomes, and that a change of the
 * box resolves the tree again while the stack stays.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w2000dp-h900dp-mdpi")
class AdaptiveWideWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stage by lazy { ScreenStage(compose) }
    private val document get() = loadDocument(Contract.bytes("fixtures/adaptive-screens.json"))

    private fun show(width: Double, rtl: Boolean = false, height: Double = 900.0) = stage.draw(AdaptiveRender.Spec(document, width, height, rtl))

    private fun tabs(): List<Rect> = compose.onAllNodes(SemanticsMatcher("tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun tab(name: String): Rect = compose.onNode(hasContentDescription(name) and hasClickAction()).fetchSemanticsNode().boundsInRoot

    private fun bounds(text: String): Rect = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    @Test fun onACompactBoxTheBarStaysAtTheBottom() {
        show(393.0)
        assertEquals(4, tabs().size)
        for (rect in tabs()) assertTrue("an item at the bottom: ${rect.top}", rect.top > 780f)
    }

    @Test fun onAMediumBoxTheItemsStandInTheAppBarAtItsEnd() {
        show(768.0)
        val items = tabs()
        assertEquals(4, items.size)
        for (rect in items) assertTrue("an item in the app bar: ${rect.top}", rect.bottom < 120f)
        // In one row, from the start of the block to its end; the block is at the end of the bar (the content column is 1120 wide, the screen 768).
        assertTrue(items.map { it.left }.zip(items.drop(1).map { it.left }).all { (a, b) -> a < b })
        assertTrue("the block ends at the end of the bar", items.maxOf { it.right } > 700f)
        // The actions of the bar stand after the items.
        val bell = compose.onNodeWithContentDescription("Notifications").fetchSemanticsNode().boundsInRoot
        assertTrue(bell.left >= items.maxOf { it.right } - 1f)
    }

    @Test fun inRightToLeftTextTheBlockStandsAtTheLeftEnd() {
        show(768.0, rtl = true)
        val items = tabs()
        assertEquals(4, items.size)
        assertTrue("the block ends at the left end", items.minOf { it.left } < 80f)
        // The first item (reading order, the document's order) is the rightmost of the block, and the action stands after the block, at the very left.
        assertTrue(items[0].left > items[1].left && items[1].left > items[2].left && items[2].left > items[3].left)
        val bell = compose.onNodeWithTag("node:home-bell").fetchSemanticsNode().boundsInRoot
        assertTrue(bell.right <= items.minOf { it.left } + 1f)
    }

    @Test fun aScreenWithoutAnAppBarGetsABarOfItsOwnAtTheTop() {
        show(1280.0)
        compose.onNode(hasContentDescription("Rewards") and hasClickAction()).performClick()
        compose.waitForIdle()
        val items = tabs()
        assertEquals(4, items.size)
        // At the top of the screen, from the start of the content column (1120 wide in 1280), with the labels (they fit): the items are wider than a glyph box.
        for (rect in items) assertTrue("an item of the own bar: ${rect.top}", rect.top < 60f)
        assertEquals(80f, items.minOf { it.left }, 1.5f)
        assertTrue(items.all { it.width > 60f })
        assertTrue(items.map { it.left }.zip(items.drop(1).map { it.left }).all { (a, b) -> a < b })
        // Nothing stands at the bottom any more: the body starts under the bar.
        assertTrue(bounds("Spend your points on something nice").top >= items.maxOf { it.bottom } - 1f)
    }

    @Test fun theOwnBarStartsAtTheRightEdgeInRightToLeftText() {
        show(1280.0, rtl = true)
        // The third item of the bar is «Rewards»; it is the third from the right in right-to-left text.
        compose.onAllNodes(SemanticsMatcher("tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab })[2].performClick()
        compose.waitForIdle()
        val items = tabs()
        assertEquals(4, items.size)
        for (rect in items) assertTrue(rect.top < 60f)
        // The column of 1120 stands in the middle: the first item at its right edge (1200).
        assertEquals(1200f, items.maxOf { it.right }, 1.5f)
        assertTrue(items[0].left > items[1].left && items[1].left > items[2].left)
    }

    @Test fun widePlacementBottomKeepsTheBarDown() {
        val json = """{"schemaVersion":"9.27.0","documentId":"w","name":"w","initialScreenId":"a","screens":[{"screenId":"a","name":"a","root":{"id":"r","type":"Screen","props":{"gap":"none"},"children":[
            {"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Body"}}},
            {"id":"n","type":"NavBar","props":{"widePlacement":"bottom"},"children":[
              {"id":"i1","type":"NavItem","props":{"icon":"house","label":{"kind":"literal","value":"One"},"action":{"type":"navigate.section","params":{"screenId":"a"}}}},
              {"id":"i2","type":"NavItem","props":{"icon":"gift","label":{"kind":"literal","value":"Two"},"action":{"type":"navigate.section","params":{"screenId":"a"}}}}]}]}}]}"""
        stage.draw(AdaptiveRender.Spec(loadDocument(json), 1280.0, 900.0))
        for (rect in tabs()) assertTrue(rect.top > 800f)
    }

    /** The labels hide ALL together, decided by the natural widths of the row; a roomy bar keeps them. */
    @Test fun labelsHideTogetherWhenTheyDoNotFit() {
        fun doc(extra: String, third: String = "Another long label two that is long") = loadDocument(
            """{"schemaVersion":"9.27.0","documentId":"w","name":"w","initialScreenId":"a","screens":[{"screenId":"a","name":"a","root":{"id":"r","type":"Screen","props":{"gap":"none"},"children":[
            {"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Body"}}},
            {"id":"n","type":"NavBar","props":{},"children":[
              {"id":"i1","type":"NavItem","props":{"icon":"house","label":{"kind":"literal","value":"A very long label number one"},"action":{"type":"navigate.section","params":{"screenId":"a"}}}},
              {"id":"i2","type":"NavItem","props":{"icon":"gift","label":{"kind":"literal","value":"$extra"},"action":{"type":"navigate.section","params":{"screenId":"a"}}}},
              {"id":"i3","type":"NavItem","props":{"icon":"star","label":{"kind":"literal","value":"${third}"},"action":{"type":"navigate.section","params":{"screenId":"a"}}}}]}]}}]}""",
        )
        // 610 wide (a medium box): three labels of the longest kind (206 each, with the gaps 626) do not stand in a row; icons alone (a box of 12 + 18 + 12 = 42 each).
        stage.draw(AdaptiveRender.Spec(doc("Yet another label that is long"), 610.0, 400.0))
        assertTrue("icons only: ${tabs().map { it.width }}", tabs().all { it.width in 41f..43f })
        // A wide bar keeps every label: widths differ with their labels.
        stage.draw(AdaptiveRender.Spec(doc("Short", "Another long label two"), 1500.0, 400.0))
        assertTrue("labels: ${tabs().map { it.width }}", tabs().all { it.width > 60f })
        // The first label is longer than the second: one and the same view for all.
        val widths = tabs().map { it.width }
        assertTrue(widths[0] > widths[1])
    }

    // A sheet in a wide place

    private fun openBonus() {
        compose.onNodeWithText("Claim +20 pts").performClick()
        compose.waitForIdle()
    }

    @Test fun aSheetStaysASheetOnACompactBox() {
        show(393.0)
        openBonus()
        val layer = compose.onNodeWithTag("screen:bonus").fetchSemanticsNode().boundsInRoot
        assertEquals(393f, layer.width, 1.5f)
        assertEquals(0f, layer.left, 1.5f)
        assertEquals(900f, layer.bottom, 1.5f)
    }

    @Test fun aSheetBecomesACentreWindowOnAWideBox() {
        for (width in listOf(768.0, 1280.0)) {
            show(width)
            openBonus()
            val layer = compose.onNodeWithTag("screen:bonus").fetchSemanticsNode().boundsInRoot
            assertEquals("$width: at most 560 wide", 560f, layer.width, 1.5f)
            assertEquals("$width: centred", ((width - 560.0) / 2).toFloat(), layer.left, 1.5f)
            assertTrue("$width: in the middle of the height", layer.top > 20f && layer.bottom < 880f)
        }
    }

    /** The content of a layer is always compact: a node for the compact class is drawn in it and a wide-only one is not, on any box. */
    @Test fun theContentOfALayerIsAlwaysCompact() {
        val json = """{"schemaVersion":"9.27.0","documentId":"m","name":"m","initialScreenId":"a","screens":[
          {"screenId":"a","name":"a","root":{"id":"r","type":"Screen","props":{"gap":"none"},"children":[
            {"id":"open","type":"Button","props":{"label":{"kind":"literal","value":"Open"},"action":{"type":"navigate","params":{"screenId":"m"}}}},
            {"id":"wide","type":"Text","showOn":["expanded"],"props":{"content":{"kind":"literal","value":"Page wide only"}}}]}},
          {"screenId":"m","name":"m","presentation":"modal","root":{"id":"mr","type":"Screen","props":{"gap":"none"},"children":[
            {"id":"p","type":"Text","showOn":["compact"],"props":{"content":{"kind":"literal","value":"Layer phone"}}},
            {"id":"w","type":"Text","showOn":["expanded"],"props":{"content":{"kind":"literal","value":"Layer wide"}}}]}}]}"""
        stage.draw(AdaptiveRender.Spec(loadDocument(json), 1280.0, 900.0))
        compose.onNodeWithText("Page wide only").assertIsDisplayed()
        compose.onNodeWithText("Open").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Layer phone").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTextExactly("Layer wide")).fetchSemanticsNodes().size)
    }

    private fun hasTextExactly(text: String) = SemanticsMatcher("text $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }

    /** The class is read from the box again when it changes (a rotation of a tablet, a fold): the tree is resolved again and the stack stays. */
    @Test fun aChangeOfTheBoxResolvesTheTreeAgainAndKeepsTheStack() {
        val width = mutableStateOf(393.0)
        compose.setContent {
            Box(Modifier.size(width.value.dp, 900.dp)) { HotlayoutScreen(document, locale = "en") }
        }
        compose.waitForIdle()
        // Compact: the bar is at the bottom. Go to «Layout lab» (the stack changes).
        assertTrue(tabs().all { it.top > 780f })
        compose.onNode(hasContentDescription("Layout lab") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Phone only").assertIsDisplayed()
        // The box becomes expanded: the phone-only block is gone, the wide-only one is there, the bar moved to the top, and we are still on the same screen.
        width.value = 1280.0
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTextExactly("Phone only")).fetchSemanticsNodes().size)
        compose.onNodeWithText("Wide only").assertIsDisplayed()
        assertTrue(tabs().all { it.top < 60f })
        compose.onNodeWithTag("screen:lab").assertIsDisplayed()
        // And back.
        width.value = 393.0
        compose.waitForIdle()
        compose.onNodeWithText("Phone only").assertIsDisplayed()
        assertTrue(tabs().all { it.top > 780f })
    }

    /** The host's activity is recreated by a configuration change: the stack and the screen it was on come back. */
    @Test fun theStackSurvivesTheRecreationOfTheActivity() {
        val doc = document
        // The same content again after the recreation, as an activity's onCreate would run it (the saved state is keyed by the place in the composition).
        val content: @androidx.compose.runtime.Composable () -> Unit = { Box(Modifier.size(393.dp, 900.dp)) { HotlayoutScreen(doc, locale = "en") } }
        compose.setContent(content)
        compose.waitForIdle()
        compose.onNode(hasContentDescription("Layout lab") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("screen:lab").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.setContent(content)
        compose.waitForIdle()
        compose.onNodeWithTag("screen:lab").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag("screen:home").fetchSemanticsNodes().size)
    }
}
