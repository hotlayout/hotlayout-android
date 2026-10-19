package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.hotlayout.actions.HotlayoutAction
import com.hotlayout.data.dataScopeOf
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.support.Screens
import com.hotlayout.support.Screens.literal
import com.hotlayout.support.near
import com.hotlayout.support.Screens.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Lists, rows, accordions and tabs drawn by the real screen, read back from semantics and pixels. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class ListsWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun bind(path: String) = """{"kind":"binding","path":"$path","fallback":""}"""

    private fun show(document: HotlayoutDocument, data: Map<String, JsonValue> = emptyMap(), locale: String? = null, onAction: (HotlayoutAction, ActionSource) -> Unit = { _, _ -> }) {
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data, locale = locale, onAction = onAction) } }
        compose.waitForIdle()
    }

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private val rewards = dataScopeOf(
        "rewards" to listOf(
            mapOf("id" to "r1", "name" to "Alpha", "kind" to "a", "day" to "Today"),
            mapOf("id" to "r2", "name" to "Beta", "kind" to "b", "day" to "Today"),
            mapOf("id" to "r3", "name" to "Gamma", "kind" to "a", "day" to "Mon"),
        ),
    )

    private fun repeater(extraProps: String = "", template: String = text("t", bind("reward.name")), extraChildren: String = "") =
        """{"id":"list","type":"Repeater","props":{"source":"rewards","item":"reward","gap":"none"$extraProps},"children":[$template$extraChildren]}"""

    @Test fun aRepeaterDrawsTheTemplateOncePerRecord() {
        show(Screens.document("a" to repeater()), rewards)
        for (name in listOf("Alpha", "Beta", "Gamma")) compose.onNodeWithText(name).assertExists()
        val tops = listOf("Alpha", "Beta", "Gamma").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("one under another: $tops", tops[0] < tops[1] && tops[1] < tops[2])
    }

    @Test fun thePressOfARowHandsOverTheRecordOfThatRow() {
        val card = """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","label":{"kind":"literal","value":"Open"},"action":{"type":"custom.open"}},"children":[${text("t", bind("reward.name"))}]}"""
        var source: ActionSource? = null
        show(Screens.document("a" to repeater(template = card)), rewards) { _, from -> source = from }
        compose.onNodeWithText("Beta").performClick()
        assertEquals("r2", source?.record?.get("reward.id")?.string)
        assertEquals("Beta", source?.record?.get("reward.name")?.string)
    }

    @Test fun theFilterThenTheLimitDecideWhichRecordsExist() {
        val where = ""","where":{"path":"reward.kind","op":"eq","value":"a"}"""
        show(Screens.document("a" to repeater(where + ""","limit":1""")), rewards)
        compose.onNodeWithText("Alpha").assertExists()
        compose.onAllNodesWithText("Beta").assertCountEquals(0)
        // `limit` counts the records that passed: the second one «a» is cut by it, not the first record overall.
        compose.onAllNodesWithText("Gamma").assertCountEquals(0)
    }

    @Test fun theScopeOfARecordLaysOverTheOuterOne() {
        val data = rewards + dataScopeOf("reward.name" to "Outer", "user.name" to "Maya")
        val template = """{"id":"row","type":"Column","props":{"gap":"none"},"children":[${text("t", bind("reward.name"))},${text("u", bind("user.name"))}]}"""
        show(Screens.document("a" to repeater(template = template)), data)
        compose.onNodeWithText("Alpha").assertExists()
        compose.onAllNodesWithText("Outer").assertCountEquals(0)
        compose.onAllNodesWithText("Maya").assertCountEquals(3)
    }

    @Test fun anEmptyListIsShownThroughItsNeighbourWithIsEmpty() {
        val empty = text("e", literal("Nothing here"), ""","visibleIf":{"path":"rewards","op":"isEmpty"}""")
        show(Screens.document("a" to repeater() + "," + empty), dataScopeOf("rewards" to emptyList<Any>()))
        compose.onNodeWithText("Nothing here").assertExists()
        compose.onAllNodesWithText("Alpha").assertCountEquals(0)
    }

    @Test fun columnsDrawAGridWithoutHoles() {
        show(Screens.document("a" to repeater(""","columns":2""")), rewards)
        val alpha = compose.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot
        val beta = compose.onNodeWithText("Beta").fetchSemanticsNode().boundsInRoot
        val gamma = compose.onNodeWithText("Gamma").fetchSemanticsNode().boundsInRoot
        // The default gap is `sm` (8): two cells of 176 in 360.
        assertEquals(alpha.top, beta.top, 0.5f)
        assertEquals(alpha.left, gamma.left, 0.5f)
        assertTrue("second row: ${gamma.top}", gamma.top > alpha.bottom - 1)
        assertEquals("the second column starts after one cell and a gap", 180f, beta.left - alpha.left, 1f)
    }

    @Test fun theGridWidgetKeepsTheWidthOfTheLastRowsMissingCell() {
        val grid = """{"id":"g","type":"Grid","props":{"columns":3,"gap":"none"},"children":[${text("a", literal("One"))},${text("b", literal("Two"))},${text("c", literal("Three"))},${text("d", literal("Four"))}]}"""
        show(Screens.document("a" to grid))
        val one = compose.onNodeWithText("One").fetchSemanticsNode().boundsInRoot
        val two = compose.onNodeWithText("Two").fetchSemanticsNode().boundsInRoot
        val four = compose.onNodeWithText("Four").fetchSemanticsNode().boundsInRoot
        assertEquals(120f, two.left - one.left, 1f)
        assertEquals(one.left, four.left, 0.5f)
        assertTrue(four.top > one.bottom - 1)
    }

    @Test fun aHeaderGoesBeforeEachGroupOfConsecutiveRecords() {
        val header = text("h", bind("reward.day"), ""","style":{"color":{"value":"#ff0000"}}""")
        show(Screens.document("a" to repeater(""","groupBy":"reward.day"""", extraChildren = ",$header")), rewards)
        // «Today» heads Alpha and Beta, «Mon» heads Gamma; the record texts are the bindings of the template.
        compose.onAllNodesWithText("Today").assertCountEquals(1)
        compose.onAllNodesWithText("Mon").assertCountEquals(1)
        val today = compose.onNodeWithText("Today").fetchSemanticsNode().boundsInRoot.top
        val alpha = compose.onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top
        val beta = compose.onNodeWithText("Beta").fetchSemanticsNode().boundsInRoot.top
        val mon = compose.onNodeWithText("Mon").fetchSemanticsNode().boundsInRoot.top
        val gamma = compose.onNodeWithText("Gamma").fetchSemanticsNode().boundsInRoot.top
        assertTrue("$today $alpha $beta $mon $gamma", today < alpha && alpha < beta && beta < mon && mon < gamma)
    }

    private fun tile(props: String) = """{"id":"tile","type":"ListTile","props":{"title":${literal("Title").let { it }}$props}}"""

    @Test fun aRowShowsTheTitleTheSubtitleAndTheTrailingTextWithItsCaption() {
        val row = tile(""","subtitle":${literal("Sub")},"trailing":{"kind":"text","text":${literal("+100")},"caption":${literal("today")}}""")
        show(Screens.document("a" to row))
        for (name in listOf("Title", "Sub", "+100", "today")) compose.onNodeWithText(name, useUnmergedTree = true).assertExists()
        val title = compose.onNodeWithText("Title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val trailing = compose.onNodeWithText("+100", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the trailing text stands at the end: $trailing", trailing.right > 340f && trailing.left > title.right)
    }

    @Test fun theTileUnderTheLeadingIconIsPaintedAndSquare() {
        val row = tile(""","leading":{"kind":"icon","icon":"star","tile":{"color":{"value":"#ff0000"},"size":40}}""")
        show(Screens.document("a" to row))
        val icon = compose.iconBounds("star")
        val centre = icon.center
        assertTrue("the tile is red off the glyph", near(capture().getPixel((centre.x - 17).toInt(), centre.y.toInt()) and 0xFFFFFF, 0xff0000))
    }

    @Test fun inRightToLeftTheLeadingSlotIsOnTheRightAndTheChevronLeaves() {
        val localization = ""","localization":{"defaultLocale":"en","locales":["en","ar"],"strings":{"ar":{"a/tile/title":"عنوان"}}}"""
        val row = tile(""","leading":{"kind":"icon","icon":"star"}""")
        show(Screens.document("a" to row, extra = localization), locale = "ar")
        val leading = compose.iconBounds("star")
        val chevron = compose.iconBounds("chevron")
        assertTrue("leading on the right ${leading.center}", leading.center.x > 300f)
        assertTrue("the chevron on the left ${chevron.center}", chevron.center.x < 60f)
    }

    @Test fun anAccordionOpensAndClosesAndStartsAsItWasToldTo() {
        val body = text("b", literal("Body"))
        val closed = """{"id":"acc","type":"Accordion","props":{"title":${literal("More")}},"children":[$body]}"""
        show(Screens.document("a" to closed))
        compose.onAllNodesWithText("Body").assertCountEquals(0)
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Body").assertExists()
        compose.onNodeWithText("More").performClick()
        compose.onAllNodesWithText("Body").assertCountEquals(0)
    }

    @Test fun anAccordionCanStartOpen() {
        val open = """{"id":"acc","type":"Accordion","props":{"title":${literal("More")},"initiallyExpanded":true},"children":[${text("b", literal("Body"))}]}"""
        show(Screens.document("a" to open))
        compose.onNodeWithText("Body").assertExists()
    }

    private fun tab(id: String, title: String, extra: String = "") =
        """{"id":"$id","type":"Tab","props":{"title":${literal(title)}}$extra,"children":[${text("$id-t", literal("Panel $title"))}]}"""

    private fun tabs(vararg tabs: String, props: String = "") = """{"id":"tabs","type":"Tabs","props":{$props},"children":[${tabs.joinToString(",")}]}"""

    @Test fun tabsDrawTheOpenPanelAndSwitchOnPress() {
        show(Screens.document("a" to tabs(tab("t1", "One"), tab("t2", "Two"))))
        compose.onNodeWithText("Panel One").assertExists()
        compose.onAllNodesWithText("Panel Two").assertCountEquals(0)
        compose.onNodeWithText("Two").performClick()
        compose.onNodeWithText("Panel Two").assertExists()
        compose.onAllNodesWithText("Panel One").assertCountEquals(0)
    }

    @Test fun initialTabCountsHiddenTabsAndAHiddenOneOpensTheFirstVisible() {
        val hidden = ""","visibleIf":{"path":"flag","op":"isTrue"}"""
        show(Screens.document("a" to tabs(tab("t1", "One", hidden), tab("t2", "Two"), tab("t3", "Three"), props = """"initialTab":2""")))
        compose.onNodeWithText("Panel Three").assertExists()
        compose.onAllNodesWithText("One").assertCountEquals(0)
    }

    @Test fun anInitialTabThatIsHiddenOpensTheFirstVisibleOne() {
        val hidden = ""","visibleIf":{"path":"flag","op":"isTrue"}"""
        show(Screens.document("a" to tabs(tab("t1", "One"), tab("t2", "Two", hidden), props = """"initialTab":1""")))
        compose.onNodeWithText("Panel One").assertExists()
    }

    @Test fun arrowKeysMoveBetweenTitlesAndTurnOverInRightToLeftText() {
        show(Screens.document("a" to tabs(tab("t1", "One"), tab("t2", "Two"), tab("t3", "Three"))))
        compose.onNodeWithText("One").performClick()
        compose.onNodeWithText("One").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Panel Two").assertExists()
        compose.onNodeWithText("Two").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Panel One").assertExists()
    }

    @Test fun inRightToLeftTextTheRightArrowGoesToThePreviousTab() {
        val localization = ""","localization":{"defaultLocale":"en","locales":["en","ar"],"strings":{"ar":{"a/t1/title":"واحد"}}}"""
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to tabs(tab("t1", "One"), tab("t2", "Two")), extra = localization), locale = "ar") } }
        compose.waitForIdle()
        compose.onNodeWithText("Two").performClick()
        compose.onNodeWithText("Two").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Panel One").assertExists()
        compose.onNodeWithText("واحد").assertIsSelected()
    }

    /** The bounds of the icon the screen drew under this name. */
    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.iconBounds(name: String): androidx.compose.ui.geometry.Rect =
        onNodeWithTag("icon:$name", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
}
