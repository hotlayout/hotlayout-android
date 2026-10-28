package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The fade of the real finished screen of `visibility-fade-screens.json`: a press on the dot of the second card writes
 * 1, the heading and the details of the first card fade out in place, those of the second wait out of the layout and
 * fade in. The phases are read from the tags the nodes carry while they change and from the text that is on the screen;
 * the clock is the one of the test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class VisibilityFadeWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table = Contract.json("fixtures/visibility-fade/visibility-fade-cases.json")
    private val frame = Contract.json("fixtures/visibility-fade-screens.json")
    private val document = loadDocument(String(Contract.bytes("fixtures/visibility-fade-screens.json")))

    /** The text that tells a node of the frame: the last text inside it (a heading, a note). */
    private fun voice(id: String): String {
        fun find(node: JsonValue): JsonValue? = if (node["id"]?.string == id) node else node["children"]?.array.orEmpty().firstNotNullOfOrNull(::find)
        fun last(node: JsonValue): String? = node["children"]?.array.orEmpty().reversed().firstNotNullOfOrNull(::last) ?: node["props"]?.get("content")?.get("value")?.string
        return last(frame["screens"]!!.array!!.first()["root"]!!.let { find(it)!! })!!
    }

    private fun show(reduced: Boolean = false) {
        android.provider.Settings.Global.putFloat(compose.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, if (reduced) 0f else 1f)
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = mapOf("account.cardIndex" to JsonValue.Num(0.0))) }
        }
        compose.waitForIdle()
    }

    private fun press(dot: String) {
        compose.onNode(hasContentDescription(dot) or hasText(dot)).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun tagged(phase: String, id: String) = compose.onAllNodesWithTag("fade:$phase:$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun onScreen(id: String) = compose.onAllNodesWithText(voice(id)).fetchSemanticsNodes().isNotEmpty()

    private fun phaseOf(id: String): String = when {
        tagged("leaving", id) -> "leaving"
        tagged("entering", id) -> "entering"
        onScreen(id) -> "shown"
        else -> "out"
    }

    private fun advance(ms: Long) { compose.mainClock.advanceTimeBy(ms) }

    /** The press, then frames until the first node starts to leave: that frame is the moment 0 of the table. */
    private fun pressAndStart(dot: String) {
        compose.mainClock.autoAdvance = false
        press(dot)
        repeat(6) { if (!tagged("leaving", "heading-debit")) compose.mainClock.advanceTimeByFrame() }
        assertTrue("the first card started to leave", tagged("leaving", "heading-debit"))
    }

    @Test fun theSecondCard() {
        val rows = Contract.rows(table, "screenCases")
        assertEquals(1, rows.size)
        val row = rows.single()
        show()
        pressAndStart(row["press"]?.string?.let { "Card ${it.removePrefix("dot-").toInt() + 1}" } ?: "")
        var elapsed = 0L
        for (probe in row["probes"]?.array.orEmpty().mapNotNull { it.obj }) {
            val at = probe["at"]?.number?.toLong() ?: 0L
            if (at > elapsed) advance(at - elapsed)
            elapsed = maxOf(elapsed, at)
            for ((word, ids) in probe.filter { it.first != "at" }) {
                for (id in ids.array.orEmpty().mapNotNull { it.string }) {
                    val got = phaseOf(id)
                    val expected = when (word) {
                        "leaving", "entering", "shown" -> word
                        else -> "out"
                    }
                    assertEquals("${row.name()} at $at: $id", expected, got)
                }
            }
        }
    }

    @Test fun theLayoutKeepsThePlaceWhileTheNodeFades() {
        show()
        val strip = androidx.compose.ui.test.hasScrollAction()
        val before = compose.onAllNodes(strip).fetchSemanticsNodes().first().boundsInRoot.top
        pressAndStart("Card 2")
        assertEquals("what lies below a leaving node stays where it was", before, compose.onAllNodes(strip).fetchSemanticsNodes().first().boundsInRoot.top, 0.5f)
        advance(400)
        assertEquals("shown", phaseOf("heading-credit"))
        assertEquals("out", phaseOf("heading-debit"))
    }

    @Test fun reducedMotionIsAtOnce() {
        show(reduced = true)
        press("Card 2")
        compose.waitForIdle()
        assertEquals("shown", phaseOf("heading-credit"))
        assertEquals("out", phaseOf("heading-debit"))
    }

    @Test fun theFirstShowIsAtOnce() {
        show()
        assertEquals("shown", phaseOf("heading-debit"))
        assertEquals("out", phaseOf("heading-credit"))
    }

    private fun condition(value: Int) = """{"path":"a.b","op":"eq","value":$value}"""

    private fun fading(id: String, value: Int, word: String) =
        """{"id":"$id","type":"Text","props":{"content":{"kind":"literal","value":"$word"}},"visibleIf":${condition(value)},"visibilityTransition":"fade"}"""

    /** A container that holds two children with exclusive conditions; the value of `a.b` goes from 0 to 1 at the press of the button. */
    private fun swap(container: String) {
        val button = """{"id":"go","type":"Button","props":{"label":{"kind":"literal","value":"Go"},"action":{"type":"setValue","params":{"path":"a.b","value":"1","valueType":"number"}}}}"""
        val document = Screens.document("a" to "$container,$button")
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = mapOf("a.b" to JsonValue.Num(0.0))) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNode(hasText("Go")).performSemanticsAction(SemanticsActions.OnClick)
        repeat(6) { if (!tagged("leaving", "first")) compose.mainClock.advanceTimeByFrame() }
        assertTrue("the first child started to leave", tagged("leaving", "first"))
        assertEquals("out", if (compose.onAllNodesWithText("Second").fetchSemanticsNodes().isEmpty()) "out" else "in")
        advance(130)
        assertEquals("the second one enters after the wait", true, tagged("entering", "second"))
        assertEquals("the first one has left", false, tagged("leaving", "first") || compose.onAllNodesWithText("First").fetchSemanticsNodes().isNotEmpty())
        advance(300)
        assertEquals("the second one is shown", true, compose.onAllNodesWithText("Second").fetchSemanticsNodes().isNotEmpty() && !tagged("entering", "second"))
    }

    @Test fun aChildOfAStackFades() = swap("""{"id":"stack","type":"Stack","props":{},"children":[${fading("first", 0, "First")},${fading("second", 1, "Second")}]}""")

    @Test fun aChildOfAGridFades() = swap("""{"id":"grid","type":"Grid","props":{"columns":2},"children":[${fading("first", 0, "First")},${fading("second", 1, "Second")}]}""")

    @Test fun aChildOfAColumnFades() = swap("""{"id":"column","type":"Column","props":{},"children":[${fading("first", 0, "First")},${fading("second", 1, "Second")}]}""")

    @Test fun aRowOfAListTemplateFadesAndItsNeighboursDoNot() {
        fun records(second: Int) = JsonValue.Arr(listOf("A" to 1, "B" to second, "C" to 1).map { (name, on) ->
            JsonValue.Obj(JsonObject(listOf("name" to JsonValue.Str(name), "on" to JsonValue.Num(on.toDouble()))))
        })
        val template = """{"id":"row","type":"Text","props":{"content":{"kind":"binding","path":"item.name","fallback":"-"}},"visibleIf":{"path":"item.on","op":"eq","value":1},"visibilityTransition":"fade"}"""
        val document = Screens.document("a" to """{"id":"list","type":"Repeater","props":{"source":"items","item":"item"},"children":[$template]}""")
        var data by androidx.compose.runtime.mutableStateOf<DataScope>(mapOf("items" to records(1)))
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data) } }
        compose.waitForIdle()
        fun there(name: String) = compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        assertTrue(there("A") && there("B") && there("C"))
        compose.mainClock.autoAdvance = false
        data = mapOf("items" to records(0))
        repeat(6) { if (!tagged("leaving", "row")) compose.mainClock.advanceTimeByFrame() }
        assertTrue("the row of B started to leave", tagged("leaving", "row"))
        assertEquals("only one row fades", 1, compose.onAllNodesWithTag("fade:leaving:row", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue("the neighbours stay", there("A") && there("C"))
        advance(130)
        assertTrue("B has left", !there("B") && !tagged("leaving", "row"))
        assertTrue("the neighbours stay", there("A") && there("C"))
        data = mapOf("items" to records(1))
        repeat(12) { advance(50); compose.waitForIdle() }
        assertTrue("B comes back after the wait", there("B"))
    }
}
