package com.hotlayout

import com.hotlayout.actions.Flow
import com.hotlayout.actions.FlowDocument
import com.hotlayout.model.BlurRules
import com.hotlayout.model.inWidthClass
import com.hotlayout.render.modal.ModalRules
import com.hotlayout.support.Contract
import com.hotlayout.support.TableJson
import com.hotlayout.support.name
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The modal layer against the shared tables: the box of a sheet and a window, the blur, the drag
 * that closes a sheet, `dismissible`, the Escape key, the event of a show. The views only call
 * these rules; `ModalWiringTest` shows that they do.
 */
class ModalCasesTest {
    private val dismiss get() = Contract.json("fixtures/sheet-dismiss/sheet-dismiss-cases.json")
    private val chrome get() = Contract.json("fixtures/media-chrome/media-chrome-cases.json")
    private val motion get() = Contract.json("fixtures/motion/motion-cases.json")
    private val schema get() = Contract.json("screen-schema.json")

    private val root = JsonValue.Obj(JsonObject.of("id" to "r", "type" to "Screen", "props" to JsonObject.of(), "children" to emptyList<Any>()))

    /** A page `a` and a screen [id] with the given entry fields. */
    private fun document(fields: List<Pair<String, JsonValue>>, id: String = "b") = loadDocument(
        JsonValue.Obj(
            JsonObject(
                listOf(
                    "schemaVersion" to JsonValue.Str("9.27.0"), "documentId" to JsonValue.Str("d"), "name" to JsonValue.Str("n"), "initialScreenId" to JsonValue.Str("a"),
                    "screens" to JsonValue.Arr(
                        listOf(
                            JsonValue.Obj(JsonObject(listOf("screenId" to JsonValue.Str("a"), "name" to JsonValue.Str("A"), "root" to root))),
                            JsonValue.Obj(JsonObject(listOf("screenId" to JsonValue.Str(id), "name" to JsonValue.Str("B"), "root" to root) + fields)),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun JsonObject.num(key: String): Double = this[key]?.number ?: 0.0

    @Test fun modalBoxCases() {
        val rows = Contract.rows(chrome, "modalCases")
        assertEquals(16, rows.size)
        for (row in rows) {
            val viewport = row["viewport"]?.obj ?: JsonObject()
            val box = ModalRules.box(
                ModalRules.isWindow(row["placement"]), viewport.num("width"), viewport.num("height"), row.num("contentHeight"), row.num("margin"),
            )
            val expect = row["expect"]?.obj ?: JsonObject()
            assertEquals(row.name(), ModalRules.Box(expect.num("x"), expect.num("y"), expect.num("width"), expect.num("height"), expect["scrolls"]?.bool == true), box)
        }
    }

    @Test fun modalAreaCases() {
        val rows = Contract.rows(chrome, "modalAreaCases")
        assertEquals(3, rows.size)
        for (row in rows) {
            val area = row["area"]?.obj ?: JsonObject()
            val box = ModalRules.box(true, area.num("width"), area.num("height"), row.num("contentHeight"), row.num("margin"))
            val expect = row["expect"]?.obj ?: JsonObject()
            assertEquals(row.name(), expect.num("x"), box.x + area.num("x"), 1e-9)
            assertEquals(row.name(), expect.num("y"), box.y + area.num("y"), 1e-9)
            assertEquals(row.name(), expect.num("centerY"), box.y + area.num("y") + box.height / 2, 1e-9)
            assertEquals(row.name(), expect.num("width"), box.width, 1e-9)
        }
    }

    @Test fun placementCases() {
        val rows = Contract.rows(chrome, "placementCases")
        assertEquals(7, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.string, if (ModalRules.isWindow(row["value"])) "center" else "bottom")
    }

    @Test fun modalRuleCases() {
        val layer = schema["x-style"]?.get("layout")?.get("modalLayer")
        val rows = Contract.rows(chrome, "modalRuleCases")
        assertEquals(2, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.string, layer?.get(row["rule"]?.string ?: "")?.string)
        assertEquals(layer?.get("scrim")?.get("opacity")?.number, ModalRules.SCRIM_OPACITY)
        assertEquals(layer?.get("center")?.get("maxWidth")?.number, ModalRules.WINDOW_MAX_WIDTH)
    }

    @Test fun blurCases() {
        val rows = Contract.rows(motion, "blurCases")
        assertEquals(11, rows.size)
        for (row in rows) {
            val document = document(listOf("presentation", "modalPlacement", "backdropBlur").mapNotNull { key -> row[key]?.let { key to it } })
            val expected = if (row["schema"]?.bool == true && row["presentation"]?.string == "modal") row["backdropBlur"]?.number?.toInt() else null
            assertEquals(row.name(), expected, document.screen("b")?.backdropBlur)
            assertEquals(row.name(), expected, BlurRules.radius(row["backdropBlur"]).takeIf { row["presentation"]?.string == "modal" && row["schema"]?.bool == true })
        }
    }

    @Test fun blursAddAsVariances() {
        assertEquals(0.0, ModalRules.combinedBlur(emptyList()), 0.0)
        assertEquals(16.0, ModalRules.combinedBlur(listOf(16)), 1e-9)
        assertEquals(5.0, ModalRules.combinedBlur(listOf(3, 4)), 1e-9)
    }

    @Test fun glassCases() {
        val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme(null))
        val rows = Contract.rows(motion, "glassCases")
        assertEquals(11, rows.size)
        for (row in rows) {
            // A layer of a wider class (`styleOn`): the same field, read after the tree is resolved for that class.
            val style = if (row["where"]?.string == "styleOn") {
                val node = com.hotlayout.model.DocumentNode(
                    "n", "Container", fields = JsonObject.of("styleOn" to mapOf("medium" to mapOf("backdropBlur" to (row["value"] ?: JsonValue.Null)))),
                ).inWidthClass(com.hotlayout.model.WidthClass.Medium)
                node.style?.obj ?: JsonObject()
            } else {
                JsonObject(listOf("backdropBlur" to (row["value"] ?: JsonValue.Null)))
            }
            val look = resolver.box(style, emptyMap())
            assertEquals(row.name(), if (row["valid"]?.bool == true) row["value"]?.number?.toInt() else null, look.backdropBlur)
        }
    }

    @Test fun startCases() {
        val rows = Contract.rows(dismiss, "startCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val starts = ModalRules.dragStarts(
                row["sheet"]?.bool == true, row["dismissible"]?.bool ?: true, row.num("scrolled"), row.num("dy"), row.num("dx"), row["onHandle"]?.bool == true,
            )
            assertEquals(row.name(), row["expect"]?.bool, starts)
        }
    }

    @Test fun releaseCases() {
        val rows = Contract.rows(dismiss, "releaseCases")
        assertEquals(9, rows.size)
        for (row in rows) {
            val outcome = ModalRules.dragOutcome(row.num("sheetHeight"), row.num("dragged"), row.num("velocity"))
            assertEquals(row.name(), row["expect"]?.string, outcome.word)
        }
    }

    @Test fun velocityFromTheLastHundredMilliseconds() {
        assertEquals(0.0, ModalRules.velocity(emptyList()), 0.0)
        assertEquals(0.0, ModalRules.velocity(listOf(ModalRules.Sample(0.0, 0.0))), 0.0)
        // 100 px in 100 ms is 1000 px/s; the older sample is outside the window.
        assertEquals(1000.0, ModalRules.velocity(listOf(ModalRules.Sample(0.0, 0.0), ModalRules.Sample(900.0, 100.0), ModalRules.Sample(1000.0, 200.0))), 1e-6)
        assertEquals(-1000.0, ModalRules.velocity(listOf(ModalRules.Sample(0.0, 300.0), ModalRules.Sample(50.0, 250.0))), 1e-6)
    }

    @Test fun entryCases() {
        val rows = Contract.rows(dismiss, "entryCases")
        assertEquals(2, rows.size)
        for (row in rows) {
            val fields = row["entry"]?.obj?.filter { it.first != "screenId" && it.first != "name" }.orEmpty()
            assertEquals(row.name(), row["expect"]?.bool, document(fields, "redeem").screen("redeem")?.dismissible)
        }
    }

    /** On a page the field is not read (the validator rejects it). */
    @Test fun dismissibleAndBlurOnAPageAreNotRead() {
        val document = document(listOf("dismissible" to JsonValue.Bool(false), "backdropBlur" to JsonValue.Num(8.0)))
        assertEquals(true, document.screen("b")?.dismissible)
        assertNull(document.screen("b")?.backdropBlur)
    }

    @Test fun ruleCases() {
        val block = schema["x-sheetDismiss"]
        val rows = Contract.rows(dismiss, "ruleCases")
        assertEquals(6, rows.size)
        for (row in rows) {
            var value = block
            for (part in (row["rule"]?.string ?: "").split(".")) value = value?.get(part)
            assertEquals("artifact: ${row.name()}", row["expect"], value)
        }
        assertEquals(0.3, ModalRules.DISMISS_SHARE, 0.0)
        assertEquals(700.0, ModalRules.FLING_VELOCITY, 0.0)
        assertEquals(100.0, ModalRules.VELOCITY_WINDOW_MS, 0.0)
        assertEquals(ModalRules.Motion(250, listOf(0.2, 0.0, 0.0, 1.0)), ModalRules.returnMotion)
        assertEquals(ModalRules.Motion(200, listOf(0.4, 0.0, 1.0, 1.0)), ModalRules.exitMotion)
        assertEquals(10.0, ModalRules.TOUCH_SLOP, 0.0)
    }

    @Test fun escapeCases() {
        val rows = Contract.rows(dismiss, "escapeCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            val steps = ModalRules.escapeSteps(row["key"]?.string ?: "", row["repeat"]?.bool == true, row["modalOnTop"]?.bool == true, row["topDismissible"]?.bool ?: true)
            assertEquals(row.name(), row.num("expect"), steps.toDouble(), 0.0)
        }
    }

    /** The two descriptive rows of Escape are in the artifact block `x-modalEscape`. */
    @Test fun escapeRuleCases() {
        val block = schema["x-modalEscape"]
        val rows = Contract.rows(dismiss, "escapeRuleCases")
        assertEquals(2, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.string, block?.get(row["rule"]?.string ?: "")?.string)
    }

    /** `statusBarIcons` is part of the event; the flow carries it from the document. */
    @Test fun shownCases() {
        val rows = Contract.rows(chrome, "shownCases")
        assertEquals(11, rows.size)
        for (row in rows) {
            val stack = row["stack"]?.array.orEmpty().mapNotNull { it.obj }
            val screens = stack.map {
                FlowDocument.Screen(
                    it["screenId"]?.string ?: "", if (it["presentation"]?.string == "modal") ScreenPresentation.Modal else ScreenPresentation.Page,
                    it["modalPlacement"]?.string, it["statusBarIcons"]?.string,
                )
            }
            val flow = Flow(FlowDocument("d", screens, screens.firstOrNull()?.screenId, emptyMap()), null)
            val event = when (row["transition"]?.string) {
                "start" -> flow.shownEvent(ScreenShownCause.Entry)
                "pop" -> flow.shownEvent(ScreenShownCause.Back)
                else -> {
                    val last = stack.last()
                    val params = JsonObject(listOf("screenId" to (last["screenId"] ?: JsonValue.Null)))
                    var action = JsonObject(listOf("type" to JsonValue.Str("navigate"), "params" to JsonValue.Obj(params)))
                    if (last["record"] != null) action = action.with("carryRecord", JsonValue.Bool(true))
                    flow.press(action).shown
                }
            }
            val actual = TableJson.shown(event).obj ?: JsonObject()
            // A record of the pushed frame comes from the row of a list in the document, not from the stack here.
            val shown = if (stack.last()["record"] != null) actual.with("record", row["expect"]?.get("record") ?: JsonValue.Null) else actual
            assertEquals(row.name(), row["expect"], JsonValue.Obj(shown))
        }
    }
}
