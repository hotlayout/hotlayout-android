package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.name
import java.util.concurrent.CopyOnWriteArrayList
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
 * Schema 9.28.0 on the real screen: a refused picture address is a report and no request, the list budget
 * cuts what a screen draws and says it once, and a text that paints nothing is not part of the name of a card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class Contract928WiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val reports = CopyOnWriteArrayList<HotlayoutReport>()

    private fun show(document: HotlayoutDocument, data: Map<String, JsonValue> = emptyMap(), images: ImagePolicy = ImagePolicy()) {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                HotlayoutScreen(document, data = data, images = images, onReport = { reports.addAll(it) })
            }
        }
        compose.waitForIdle()
    }

    // ---------------------------------------------------------------- pictures

    private fun image(id: String, source: String) = """{"id":"$id","type":"Image","props":{"source":$source,"aspectRatio":"1:1"}}"""

    private fun urlSource(address: String) = """{"kind":"url","url":"$address"}"""

    @Test fun aRefusedAddressOfTheDocumentMakesNoRequestAndIsReportedTruncated() {
        val asked = CopyOnWriteArrayList<String>()
        val long = "https://127.0.0.1/" + "p".repeat(300)
        val document = Screens.document(
            "a" to listOf(
                image("ip", urlSource("https://127.0.0.1/a.png")), image("name", urlSource("https://localhost/a.png")),
                image("script", urlSource("javascript:alert(1)")), image("long", urlSource(long)), image("ok", urlSource("https://cdn.example.com/a.png")),
            ).joinToString(","),
        )
        show(document, images = ImagePolicy(fetch = { address, _ -> asked.add(address); null }))
        assertEquals("only the public address is asked for", listOf("https://cdn.example.com/a.png"), asked.toList())
        val said = reports.map { it.reason }.distinct()
        assertTrue(said.toString(), said.any { "https://127.0.0.1/a.png" in it })
        assertTrue(said.toString(), said.any { "javascript:alert(1)" in it })
        val ofLong = said.single { "https://127.0.0.1/ppp" in it }
        assertFalse("the address is cut at 120 characters", "p".repeat(150) in ofLong)
    }

    @Test fun anAddressFromDataIsReportedBySchemeAndHostOnly() {
        val asked = CopyOnWriteArrayList<String>()
        val document = Screens.document("a" to image("img", """{"kind":"binding","path":"offer.image","fallback":"placeholder"}"""))
        show(document, mapOf("offer.image" to JsonValue.Str("https://127.0.0.1/private/path?token=SECRET-TOKEN")), ImagePolicy(fetch = { address, _ -> asked.add(address); null }))
        assertEquals(emptyList<String>(), asked.toList())
        val said = reports.joinToString("\n") { it.reason + " " + it.location }
        assertTrue(said, "https://127.0.0.1" in said)
        assertFalse(said, "SECRET-TOKEN" in said || "private" in said)
    }

    @Test fun aScriptAddressTheHostAllowsIsStillNeverAPicture() {
        val asked = CopyOnWriteArrayList<String>()
        val document = Screens.document("a" to image("script", urlSource("JavaScript:alert(1)")) + "," + image("dev", urlSource("http://localhost:5173/a.png")))
        show(document, images = ImagePolicy(resolve = { it }, fetch = { address, _ -> asked.add(address); null }))
        assertEquals("the host's own answer is final, except a script", listOf("http://localhost:5173/a.png"), asked.toList())
    }

    // ---------------------------------------------------------------- the list budget

    private fun repeater(id: String, source: String, item: String, child: String) =
        """{"id":"$id","type":"Repeater","props":{"source":"$source","item":"$item","gap":"none"},"children":[$child]}"""

    private fun records(count: Int, label: String) = JsonValue.of((0 until count).map { mapOf("id" to it, "name" to "$label $it") })

    private fun rowsOf(prefix: String): Int = compose.onAllNodesWithText(prefix, substring = true).fetchSemanticsNodes().size

    private fun budgetReports(): List<HotlayoutReport> = reports.filter { "list budget" in it.reason }.distinct()

    @Test fun aScreenAtExactlyTheBudgetIsDrawnWhole() {
        val inner = repeater("inner", "rows", "row", Screens.text("t", """{"kind":"binding","path":"row.name"}"""))
        val document = Screens.document("a" to repeater("outer", "rows", "outer", inner))
        show(document, mapOf("rows" to records(50, "Row")))
        assertEquals(2500, rowsOf("Row "))
        assertEquals(emptyList<HotlayoutReport>(), budgetReports())
    }

    @Test fun oneInstanceOverTheBudgetIsNotDrawnAndIsReportedOnce() {
        val inner = repeater("inner", "rows", "row", Screens.text("t", """{"kind":"binding","path":"row.name"}"""))
        val extra = repeater("extra", "more", "more", Screens.text("e", """{"kind":"binding","path":"more.name"}"""))
        val document = Screens.document("a" to repeater("outer", "rows", "outer", inner) + "," + extra)
        show(document, mapOf("rows" to records(50, "Row"), "more" to records(1, "Extra")))
        assertEquals(2500, rowsOf("Row "))
        assertEquals("the list after the cut draws nothing", 0, rowsOf("Extra"))
        assertEquals(budgetReports().toString(), 1, budgetReports().size)
        assertTrue(budgetReports().single().reason, "1 instances" in budgetReports().single().reason)
    }

    @Test fun threeNestedListsStopInsideTheFirstOuterInstance() {
        val inner = repeater("inner", "rows", "row", Screens.text("t", """{"kind":"binding","path":"row.name"}"""))
        val middle = repeater("middle", "rows", "mid", inner)
        val document = Screens.document("a" to repeater("outer", "rows", "outer", middle))
        show(document, mapOf("rows" to records(15, "Row")))
        // 15 outer × 15 middle × 15 inner would be 3375 rows: the budget counts the outer and middle instances too.
        val drawn = rowsOf("Row ")
        assertTrue("drawn $drawn", drawn in 1..2550)
        assertTrue("fewer than the lists would draw: $drawn", drawn < 3375)
        assertEquals(1, budgetReports().size)
    }

    @Test fun aListHiddenByItsConditionStillCostsItsInstances() {
        val hidden = """{"id":"hidden","type":"Column","props":{},"visibleIf":{"path":"flag.on","op":"isTrue"},"children":[${repeater("h", "rows", "h", Screens.text("ht", """{"kind":"binding","path":"h.name"}"""))}]}"""
        val big = repeater("big", "rows", "big", repeater("inner", "rows", "row", Screens.text("t", """{"kind":"binding","path":"row.name"}""")))
        // The hidden list comes first in the document: it takes 50 of the 2550, so the last inner instances of the big list are cut.
        show(Screens.document("a" to "$hidden,$big"), mapOf("rows" to records(50, "Row"), "flag.on" to JsonValue.Bool(false)))
        assertEquals(2500 - 50, rowsOf("Row "))
        assertEquals(1, budgetReports().size)
    }

    // ---------------------------------------------------------------- the name of a card

    private fun nameOfCard(row: JsonObject): String {
        val data = row["data"]?.obj?.associate { (key, value) -> key to value } ?: emptyMap()
        val children = row["children"]!!.array!!.joinToString(",") { child -> render(child) }
        val card = """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","action":{"type":"navigate","params":{"screenId":"details"}}},"children":[$children]}"""
        show(Screens.document("a" to card, "details" to Screens.text("d", Screens.literal("Details"))), data)
        val config = compose.onNodeWithTag("node:card").fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
    }

    /** The row's node written back as JSON text (a node of the table is a plain document node). */
    private fun render(value: JsonValue): String = when (value) {
        is JsonValue.Obj -> value.fields.joinToString(",", "{", "}") { (key, item) -> "\"$key\":${render(item)}" }
        is JsonValue.Arr -> value.items.joinToString(",", "[", "]") { render(it) }
        is JsonValue.Str -> "\"" + value.value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is JsonValue.Num -> if (value.value == Math.rint(value.value)) value.value.toLong().toString() else value.value.toString()
        is JsonValue.Bool -> value.value.toString()
        JsonValue.Null -> "null"
    }

    @Test fun silentCases() {
        val rows = Contract.rows(Contract.json("fixtures/accessible-name/silent-text-cases.json"), "silentCases")
        assertEquals(12, rows.size)
        for (row in rows) {
            compose.activityRule.scenario.recreate()
            reports.clear()
            val name = nameOfCard(row)
            for (said in row["heard"]!!.array!!.map { it.string!! }) assertTrue("«$said» is heard: ${row.name()}: $name", said in name)
            for (quiet in row["silent"]!!.array!!.map { it.string!! }) assertFalse("«$quiet» is silent: ${row.name()}: $name", quiet in name)
        }
    }

    @Test fun aSilentTextDoesNotTakeTheNameOfTheCardAwayFromTheVisibleOne() {
        val transparent = Screens.text("b", Screens.literal("Confirm transfer"), ""","style":{"opacity":0}""")
        val card = """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","action":{"type":"none","track":{"event":"x"}}},"children":[${Screens.text("a", Screens.literal("Show offer"))},$transparent]}"""
        show(Screens.document("a" to card))
        assertEquals("Show offer", compose.onNodeWithTag("node:card").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "))
    }
}
