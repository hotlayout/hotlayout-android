package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.hotlayout.data.dataScopeOf
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.support.Screens
import com.hotlayout.support.Screens.literal
import com.hotlayout.support.Screens.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The carousel drawn by the real screen: card width, snapping, dots, the memory of the stack frame. Read back from semantics. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class CarouselWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(document: HotlayoutDocument, data: Map<String, JsonValue> = emptyMap(), locale: String? = null) {
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data, locale = locale) } }
        compose.waitForIdle()
    }

    private fun card(id: String, title: String) =
        """{"id":"$id","type":"Container","props":{"direction":"column","gap":"none"},"style":{"height":60,"background":{"kind":"color","color":{"value":"#336699"}}},"children":[${text("$id-t", literal(title))}]}"""

    private fun row(carousel: String, cards: Int = 3, extra: String = "") =
        """{"id":"strip","type":"Row","props":{"gap":"md","scrollable":true,"carousel":$carousel},"style":{"padding":{"start":"lg","end":"lg"}}$extra,"children":[${(1..cards).joinToString(",") { card("c$it", "Card $it") }}]}"""

    private val hasHorizontalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    private val isCard = SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionItemInfo)

    private fun cards() = compose.onAllNodes(isCard).fetchSemanticsNodes().map { androidx.compose.ui.geometry.Rect(it.positionInRoot, androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }

    private fun activeDot(): Int {
        val dots = compose.onAllNodesWithTag("carousel:dot", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.left } +
            compose.onAllNodesWithTag("carousel:dot:active", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.left }
        val active = compose.onAllNodesWithTag("carousel:dot:active", useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot.left
        return dots.sorted().indexOf(active)
    }

    private fun dotCount() = compose.onAllNodesWithTag("carousel:dot", useUnmergedTree = true).fetchSemanticsNodes().size +
        compose.onAllNodesWithTag("carousel:dot:active", useUnmergedTree = true).fetchSemanticsNodes().size

    private fun swipe() = compose.onNode(hasHorizontalScroll).performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 300) }

    @Test fun aCardIsPerViewWideAndTheNextOneShowsByThePeek() {
        // 360 − padding 16 − 1 × gap 12 − peek 24 = 308 (never rounded).
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl"}""")))
        val bounds = cards()
        assertEquals(3, bounds.size)
        for (card in bounds) assertEquals(308f, card.width, 0.6f)
        assertEquals(16f, bounds[0].left, 0.6f)
        assertEquals("the second card shows by the peek", 360f - 24f + 12f, bounds[1].left + 0f, 0.6f + 12f)
    }

    @Test fun threeCardsMakeThreeStopsAndThreeDots() {
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl"}""")))
        assertEquals(3, dotCount())
        assertEquals(0, activeDot())
    }

    @Test fun aSwipeSnapsToTheNextCardAndTheActiveDotFollows() {
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl"}""")))
        swipe()
        compose.waitForIdle()
        val bounds = cards()
        assertEquals("the second card stands at the padding line", 16f, bounds[1].left, 1f)
        assertEquals(1, activeDot())
    }

    @Test fun theLastCardStopsAtTheEndOfTheScrollNotAtItsOwnStart() {
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl"}""")))
        swipe(); compose.waitForIdle(); swipe(); compose.waitForIdle(); swipe(); compose.waitForIdle()
        assertEquals(2, activeDot())
        // The end padding is the last card's: it ends one padding from the edge.
        assertEquals(360f - 16f, cards()[2].right, 1f)
    }

    @Test fun noDotsForIndicatorNoneAndNoRoomTaken() {
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl","indicator":"none"}""")))
        assertEquals(0, dotCount())
        val heightWithout = compose.onAllNodes(hasHorizontalScroll).fetchSemanticsNodes().single().boundsInRoot.height
        val withDots = Screens.document("a" to row("""{"perView":1,"peek":"xl"}"""))
        show2(withDots)
        val strip = compose.onAllNodes(hasHorizontalScroll).fetchSemanticsNodes().single().boundsInRoot
        assertEquals("the scroll area is the same with and without dots", heightWithout, strip.height, 0.6f)
    }

    private fun show2(document: HotlayoutDocument) {
        compose.activityRule.scenario.recreate()
        show(document)
    }

    @Test fun oneStopMeansNoDots() {
        // Two narrow cards that fit the place: one stop.
        show(Screens.document("a" to row("""{}""", cards = 2)))
        assertEquals(0, dotCount())
    }

    @Test fun moreThanTenStopsMeansNoDots() {
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl"}""", cards = 11)))
        assertEquals(0, dotCount())
    }

    @Test fun theCardsAreAListOfItemsWithAPosition() {
        show(Screens.document("a" to row("""{"perView":1}""")))
        val info = compose.onAllNodes(isCard).fetchSemanticsNodes().map { it.config[SemanticsProperties.CollectionItemInfo].columnIndex }
        assertEquals(listOf(0, 1, 2), info)
        assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionInfo)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun aFilteredListCountsTheStopsOfTheCardsDrawn() {
        val list = """{"id":"strip","type":"Repeater","props":{"source":"rewards","item":"reward","direction":"row","gap":"md","carousel":{"perView":1,"peek":"xl"},
            "where":{"path":"reward.kind","op":"eq","value":"a"}},"style":{"padding":{"start":"lg","end":"lg"}},"children":[${text("t", """{"kind":"binding","path":"reward.name","fallback":""}""")}]}"""
        val rewards = dataScopeOf("rewards" to listOf(
            mapOf("name" to "One", "kind" to "a"), mapOf("name" to "Two", "kind" to "b"), mapOf("name" to "Three", "kind" to "a"), mapOf("name" to "Four", "kind" to "b"),
        ))
        show(Screens.document("a" to list), rewards)
        assertEquals("two records pass the filter: two cards, two stops", 2, cards().size)
        assertEquals(2, dotCount())
    }

    @Test fun rowsOfAListFromDataKeepTheirOwnStop() {
        val inner = """{"id":"strip","type":"Row","props":{"gap":"md","scrollable":true,"carousel":{"perView":1,"peek":"xl"}},"style":{"padding":{"start":"lg","end":"lg"}},"children":[
            ${(1..3).joinToString(",") { card("c$it", "C$it") }}]}"""
        val outer = """{"id":"list","type":"Repeater","props":{"source":"rows","item":"row","gap":"md"},"children":[$inner]}"""
        show(Screens.document("a" to outer), dataScopeOf("rows" to listOf(mapOf("id" to "x"), mapOf("id" to "y"))))
        assertEquals(2, compose.onAllNodes(hasHorizontalScroll).fetchSemanticsNodes().size)
        compose.onAllNodes(hasHorizontalScroll)[0].performTouchInput { swipeLeft(startX = right - 10f, endX = left + 10f, durationMillis = 300) }
        compose.waitForIdle()
        val active = compose.onAllNodesWithTag("carousel:dot:active", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.left }
        assertEquals(2, active.size)
        assertTrue("the rows do not share one memory: $active", active[0] != active[1])
    }

    @Test fun theSameCardIsShownAfterBack() {
        val forward = """{"id":"next","type":"Button","props":{"label":{"kind":"literal","value":"Next"},"action":{"type":"navigate","params":{"screenId":"b"}}}}"""
        val back = """{"id":"back","type":"Button","props":{"label":{"kind":"literal","value":"Back"},"action":{"type":"navigate.back"}}}"""
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl"}""") + "," + forward, "b" to back))
        swipe()
        compose.waitForIdle()
        assertEquals(1, activeDot())
        compose.onNodeWithText("Next").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        assertEquals("the carousel stands on the stop it was left on", 1, activeDot())
        assertEquals(16f, cards()[1].left, 1f)
    }

    @Test fun inRightToLeftTheFirstCardIsOnTheRight() {
        val localization = ""","localization":{"defaultLocale":"en","locales":["en","ar"],"strings":{"ar":{"a/c1-t/content":"أ"}}}"""
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl"}"""), extra = localization), locale = "ar")
        val bounds = cards()
        assertEquals("the first card stands a padding from the right edge", 360f - 16f, bounds[0].right, 1f)
        assertTrue(bounds[1].right < bounds[0].left + 0.6f)
    }

    @Test fun aCarouselInANaturalColumnDoesNotChaseItsOwnWidth() {
        // The column is as wide as its widest child; the carousel's place is not known, so its cards keep their natural widths and no dots are drawn.
        val natural = """{"id":"wrap","type":"Row","props":{"gap":"none","justify":"start"},"children":[${row("""{"perView":1,"peek":"xl"}""")}]}"""
        show(Screens.document("a" to natural))
        val first = cards()
        compose.waitForIdle()
        assertEquals("the layout settles: nothing moves on the next frame", first, cards())
        assertEquals(3, first.size)
    }

    @Test fun theInactiveDotIsTheActiveColourAtThirtyPercent() {
        show(Screens.document("a" to row("""{"perView":1,"peek":"xl"}""")))
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        fun centre(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot.center
        val active = centre("carousel:dot:active")
        val inactive = centre("carousel:dot")
        val a = bitmap.getPixel(active.x.toInt(), active.y.toInt()) and 0xFFFFFF
        val i = bitmap.getPixel(inactive.x.toInt(), inactive.y.toInt()) and 0xFFFFFF
        assertEquals("the active dot is the primary colour", 0x4f46e5, a)
        assertTrue("the inactive one is lighter: ${Integer.toHexString(i)}", (i shr 16) > 0xA0 && i != a)
    }
}
