package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.hotlayout.data.dataScopeOf
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.loadDocument
import com.hotlayout.render.widgets.ProvideKeyboardInset
import com.hotlayout.support.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the real screen does with its anatomy: the zones stand where the rules put them, the app bar
 * and the bottom zone are fixed while the body scrolls, the bar of navigation switches sections,
 * the insets and the keyboard move the zones. The tables prove the numbers; these prove the screen calls them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ScreenAnatomyWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun lit(text: String) = """{"kind":"literal","value":"$text"}"""

    private fun text(id: String, content: String, extra: String = "") = """{"id":"$id","type":"Text","props":{"content":${lit(content)}}$extra}"""

    private fun long(prefix: String, count: Int = 30) = (1..count).joinToString(",") { text("$prefix$it", "$prefix line $it", ""","style":{"padding":{"top":"md","bottom":"md"}}""") }

    private fun bar(extra: String = "", props: String = "") =
        """{"id":"bar","type":"AppBar","props":{"title":${lit("Mission")},"showBack":false$props}$extra}"""

    private fun nav(current: String = "home", extra: String = "", item: String = "") =
        """{"id":"nav","type":"NavBar","props":{$extra},"children":[""" + listOf("home", "rewards").joinToString(",") { id ->
            """{"id":"n-$id","type":"NavItem","props":{"icon":"house","label":${lit(id.replaceFirstChar { it.uppercase() })},"action":{"type":"navigate.section","params":{"screenId":"$id"}}$item}}"""
        } + "]}"

    private fun screen(id: String, rootProps: String, children: String, style: String = """{"background":{"kind":"color","color":{"value":"#ffffff"}},"padding":{"top":16,"bottom":16,"start":16,"end":16}}""", modal: Boolean = false) =
        """{"screenId":"$id","name":"$id"${if (modal) ""","presentation":"modal"""" else ""},"root":{"id":"$id-root","type":"Screen","props":{$rootProps},"style":$style,"children":[$children]}}"""

    private fun document(vararg screens: String) =
        loadDocument("""{"schemaVersion":"9.27.0","documentId":"t","name":"T","initialScreenId":"home","screens":[${screens.joinToString(",")}]}""")

    private fun show(document: HotlayoutDocument, top: Float? = null, bottom: Float? = null, keyboard: Float? = null, data: com.hotlayout.data.DataScope = emptyMap()) {
        compose.setContent {
            ProvideKeyboardInset(keyboard?.dp) { HotlayoutScreen(document, data = data, safeAreaTop = top?.dp, safeAreaBottom = bottom?.dp) }
        }
        compose.waitForIdle()
    }

    private fun bounds(content: String): Rect = compose.onNodeWithText(content).fetchSemanticsNode().boundsInRoot

    private fun scrollBody(screenId: String = "home") {
        compose.onNodeWithTag("screen:$screenId").performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.2f) }
        compose.waitForIdle()
    }

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun near(expected: Float, actual: Float, message: String) = assertEquals(message, expected, actual, 1.5f)

    @Test fun theAppBarAndTheBottomZoneStayWhileOnlyTheBodyScrolls() {
        show(document(screen("home", "\"gap\":\"none\"", bar() + "," + long("A") + "," + nav())))
        val title = bounds("Mission")
        val home = bounds("Home")
        val first = bounds("A line 1")
        scrollBody()
        near(title.top, bounds("Mission").top, "the app bar title is fixed")
        near(home.top, bounds("Home").top, "the navigation bar is fixed")
        assertTrue("the body moved up", bounds("A line 1").top < first.top - 20f)
    }

    @Test fun theBottomZoneStandsAtTheBottomOfAShortScreen() {
        show(document(screen("home", "\"gap\":\"none\"", bar() + "," + text("t", "Short") + "," + nav())))
        // Root padding 16 top, a bar, the gap of the screen: a short body leaves the bar of navigation at the bottom edge.
        assertTrue(bounds("Home").bottom > 560f)
        near(640f, bounds("Home").bottom, "the item of the bar ends at the bottom edge")
    }

    @Test fun theZonesAgreeWithTheRulesForTheMeasuredBar() {
        show(document(screen("home", "\"gap\":\"none\"", bar(""","style":{"height":56}""") + "," + text("t", "Body") + "," + nav())))
        // Root padding 16, a bar of 56, the screen gap 'none' (0): the first line starts at 72.
        near(72f, bounds("Body").top, "body starts under the bar")
    }

    @Test fun aPinnedBlockStaysAtTheBottomAndTheBodyEndsAboveIt() {
        val pinned = """{"id":"pin","type":"Button","props":{"label":${lit("Pay")},"variant":"primary","action":{"type":"none"}},"pin":"bottom"}"""
        show(document(screen("home", "\"gap\":\"sm\"", long("A") + "," + pinned)))
        val pay = bounds("Pay")
        near(640f, pay.bottom + 16f, "the pinned block lies above the bottom padding")
        scrollBody()
        near(pay.top, bounds("Pay").top, "the pinned block does not scroll")
    }

    @Test fun theScreenKeepsAFloatingBarOverTheBodyAndTheEndOfTheBodyIsNotHiddenByIt() {
        show(document(screen("home", "\"gap\":\"none\"", bar() + "," + long("A", 12) + "," + nav(extra = "\"float\":true"))))
        val label = bounds("Home")
        repeat(4) { scrollBody() }
        near(label.top, bounds("Home").top, "the floating bar is fixed")
        val last = bounds("A line 12")
        assertTrue("the last line comes out above the bar", last.bottom <= label.top)
    }

    @Test fun theInsetsOfTheHostMoveTheBarAndTheBottomZone() {
        show(document(screen("home", "\"gap\":\"none\"", bar() + "," + text("t", "Body") + "," + nav())), top = 24f, bottom = 34f)
        val title = bounds("Mission")
        assertTrue("the bar holds the top inset inside its box: the title is below it", title.top >= 24f + 16f)
        near(640f - 34f, bounds("Home").bottom, "the bottom inset lies under the items of the bar")
    }

    @Test fun noInsetsWithoutAHost() {
        show(document(screen("home", "\"gap\":\"none\"", bar() + "," + nav())), top = 0f, bottom = 0f)
        val flat = bounds("Mission").top
        assertTrue(flat < 40f)
    }

    @Test fun theKeyboardRaisesThePinnedBlockAndLeavesTheNavigationUnderIt() {
        val pinned = """{"id":"pin","type":"Button","props":{"label":${lit("Pay")},"variant":"primary","action":{"type":"none"}},"pin":"bottom"}"""
        show(document(screen("home", "\"gap\":\"sm\"", text("t", "Body") + "," + pinned + "," + nav())), keyboard = 300f)
        assertTrue("the pinned block rides above the keyboard", bounds("Pay").bottom <= 640f - 300f)
        assertTrue("the bar of navigation stays under the keyboard", bounds("Home").top >= 640f - 300f)
    }

    @Test fun anItemOfTheBarIsActiveWhenItSwitchesToTheShownScreen() {
        show(document(screen("home", "\"gap\":\"none\"", text("t", "Home body") + "," + nav()), screen("rewards", "\"gap\":\"none\"", text("r", "Rewards body") + "," + nav())))
        assertEquals(true, compose.onNodeWithContentDescription("Home").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, compose.onNodeWithContentDescription("Rewards").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        compose.onNodeWithContentDescription("Rewards").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rewards body").assertExists()
        assertEquals(true, compose.onNodeWithContentDescription("Rewards").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
    }

    @Test fun aSectionSwitchResetsTheStackAndTheSameSectionDoesNothing() {
        val events = ArrayList<String>()
        val doc = document(
            screen("home", "\"gap\":\"none\"", text("t", "Home body") + "," + nav()),
            screen("rewards", "\"gap\":\"none\"", text("r", "Rewards body") + "," + nav()),
        )
        compose.setContent { HotlayoutScreen(doc, onScreenChange = { events.add("${it.screenId}:${it.cause.word}") }) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Home").performClick()
        compose.waitForIdle()
        assertEquals(listOf("home:entry"), events)
        compose.onNodeWithContentDescription("Rewards").performClick()
        compose.waitForIdle()
        assertEquals(listOf("home:entry", "rewards:switch"), events)
    }

    @Test fun theBackArrowStepsBackAndTwoActionsAreDrawn() {
        val actions = (1..3).joinToString(",") { """{"id":"act$it","type":"IconButton","props":{"icon":"search","label":${lit("Action $it")},"action":{"type":"none"}}}""" }
        val second = """{"id":"bar","type":"AppBar","props":{"title":${lit("Details")},"showBack":true},"children":[$actions]}"""
        val doc = document(
            screen("home", "\"gap\":\"none\"", text("t", "Home body") + "," + """{"id":"go","type":"Button","props":{"label":${lit("Open")},"action":{"type":"navigate","params":{"screenId":"two"}}}}"""),
            screen("two", "\"gap\":\"none\"", second + "," + text("x", "Second body")),
        )
        show(doc)
        compose.onNodeWithText("Open").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Second body").assertExists()
        compose.onAllNodes(hasContentDescription("Action 3")).assertCountEquals(0)
        compose.onNodeWithContentDescription("Action 2").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Home body").assertExists()
    }

    @Test fun theScrolledStyleOfTheBarComesWithTheScroll() {
        val scrolled = ""","style":{"height":56},"styleScrolled":{"background":{"kind":"color","color":{"value":"#ff0000"}}}"""
        show(document(screen("home", "\"gap\":\"none\"", bar(scrolled) + "," + long("A"))))
        // The root padding is 16: the bar box stands at 16…72; a pixel inside it, away from the title.
        assertNotEquals("the bar is plain at rest", 0xff0000, capture().getPixel(340, 60) and 0xFFFFFF)
        scrollBody()
        assertEquals("the layer lies over the bar once the body is past the threshold", 0xff0000, capture().getPixel(340, 60) and 0xFFFFFF)
    }

    @Test fun theBodyCanStartAtTheTopEdgeUnderTheBar() {
        val first = text("t", "Under", ""","style":{"height":200}""")
        show(document(screen("home", "\"gap\":\"none\",\"bodyTop\":\"screenTop\"", bar(""","style":{"height":56}""") + "," + first, style = """{"background":{"kind":"color","color":{"value":"#ffffff"}}}""")))
        assertTrue("the body starts at the top edge, under the bar", bounds("Under").top < 56f)
    }

    @Test fun theDotOnAnItemFollowsItsCondition() {
        val badge = ""","badgeWhen":{"path":"user.unread","op":"gt","value":0}"""
        val doc = document(screen("home", "\"gap\":\"none\"", text("t", "Body") + "," + nav(item = badge)))
        show(doc, data = dataScopeOf("user.unread" to 3))
        compose.onAllNodesWithTag("nav-badge-dot", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun noDotWhenTheConditionDoesNotHold() {
        val badge = ""","badgeWhen":{"path":"user.unread","op":"gt","value":0}"""
        show(document(screen("home", "\"gap\":\"none\"", text("t", "Body") + "," + nav(item = badge))), data = dataScopeOf("user.unread" to 0))
        compose.onAllNodesWithTag("nav-badge-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aModalLayerKeepsItsOwnAppBarWhileOnlyItsBodyScrolls() {
        val layer = screen("sheet", "\"gap\":\"none\"", """{"id":"lbar","type":"AppBar","props":{"title":${lit("Sheet title")},"showBack":false}},""" + long("L", 40), modal = true)
        val home = screen("home", "\"gap\":\"none\"", """{"id":"go","type":"Button","props":{"label":${lit("Open sheet")},"action":{"type":"navigate","params":{"screenId":"sheet"}}}}""")
        show(document(home, layer))
        compose.onNodeWithText("Open sheet").performClick()
        compose.waitForIdle()
        val title = bounds("Sheet title")
        val first = bounds("L line 1")
        compose.onNodeWithTag("screen:sheet").performTouchInput { swipeUp(startY = height * 0.8f, endY = height * 0.2f) }
        compose.waitForIdle()
        near(title.top, bounds("Sheet title").top, "the app bar of the layer is fixed")
        assertTrue("its body scrolled", bounds("L line 1").top < first.top - 20f)
    }

    @Test fun theSameAnatomyDrawsTheFixturesWithoutReports() {
        for (name in listOf("bottom-zone-screens", "float-nav-swipe-screens", "app-bar-screens", "hero-screens", "rewards-showcase-screens")) {
            val doc = loadDocument(Contract.bytes("fixtures/$name.json"))
            assertTrue(name, doc.screens.isNotEmpty())
        }
    }
}
