package com.hotlayout

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.widgets.CompositionTracker
import com.hotlayout.render.widgets.InputPartsRules
import com.hotlayout.render.widgets.InputRules
import com.hotlayout.support.Contract
import com.hotlayout.support.Mismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fields of the shared input table (`inputs/input-cases.json`): the value, the typed text, the
 * switch and the checkbox, the number field, the keyboard, the geometry and the parts of a text
 * field. The rows run against the pure rules the views call; `InputsWiringTest` runs the same
 * things through the real screen.
 */
class InputCasesTest {
    private val table get() = Contract.json("fixtures/inputs/input-cases.json")

    private fun label(row: JsonObject): String = row["name"]?.string ?: row["note"]?.string ?: row["text"]?.string ?: "?"

    private fun rows(group: String): List<JsonObject> = Contract.rows(table, group)

    private fun visible(text: String) = Mismatch.visible(text)

    @Test fun pathOfAField() {
        val rows = rows("pathCases")
        assertEquals(10, rows.size)
        for (row in rows) assertEquals(label(row), row["path"]?.string, InputRules.path(row["value"]))
    }

    @Test fun readValuesThroughTheLayers() {
        val rows = rows("readCases")
        assertEquals(20, rows.size)
        for (row in rows) {
            val layers = row["layers"]
            val scope = com.hotlayout.data.RecordScope.merge(
                com.hotlayout.data.DataLayers(
                    record = layers?.get("record")?.let { Contract.scope(it) }, session = layers?.get("session")?.let { Contract.scope(it) },
                    host = layers?.get("host")?.let { Contract.scope(it) }, data = layers?.get("data")?.let { Contract.scope(it) },
                ),
            )
            val value = scope[row["path"]?.string ?: ""]
            if (row["type"]?.string == "string") {
                assertEquals(label(row), visible(row["text"]?.string ?: ""), visible(InputRules.text(value)))
            } else {
                assertEquals(label(row), row["on"]?.bool, InputRules.flag(value))
            }
        }
    }

    private fun event(step: JsonObject): InputRules.Event? = when (step["type"]?.string) {
        "focus" -> InputRules.Event.Focus
        "blur" -> InputRules.Event.Blur
        "input" -> InputRules.Event.Input(step["text"]?.string ?: "")
        "compositionStart" -> InputRules.Event.CompositionStart
        "compositionEnd" -> InputRules.Event.CompositionEnd(step["text"]?.string ?: "")
        "platformFill" -> InputRules.Event.PlatformFill(step["text"]?.string ?: "")
        else -> null
    }

    @Test fun textFieldSequences() {
        val rows = rows("textCases")
        assertEquals(27, rows.size)
        for (row in rows) {
            var bound = row["bound"]?.string ?: ""
            var state = InputRules.TextState()
            val writes = ArrayList<String>()
            for (step in row["steps"]?.array.orEmpty().mapNotNull { it.obj }) {
                if (step["type"]?.string == "data") {
                    bound = step["bound"]?.string ?: ""
                    state = InputRules.sync(state, bound)
                } else {
                    val result = InputRules.step(state, event(step) ?: continue, bound, row["maxLength"], row["disabled"]?.bool == true, row["hasPath"]?.bool != false)
                    state = result.state
                    result.write?.let(writes::add)
                }
            }
            assertEquals("writes: ${label(row)}", row["writes"]?.array.orEmpty().mapNotNull { it.string }.map(::visible), writes.map(::visible))
            assertEquals("shown: ${label(row)}", visible(row["shown"]?.string ?: ""), visible(InputRules.shownText(state, bound)))
        }
    }

    @Test fun toggleSequences() {
        val rows = rows("toggleCases")
        assertEquals(9, rows.size)
        for (row in rows) {
            var bound = row["bound"]?.bool ?: false
            var state = InputRules.ToggleState()
            val writes = ArrayList<Boolean>()
            for (step in row["steps"]?.array.orEmpty()) {
                if (step.string == "press") {
                    val result = InputRules.pressToggle(state, bound, row["disabled"]?.bool == true, row["hasPath"]?.bool != false)
                    state = result.state
                    result.write?.let(writes::add)
                } else step["data"]?.bool?.let {
                    bound = it
                    state = InputRules.syncToggle(state, bound)
                }
            }
            assertEquals("writes: ${label(row)}", row["writes"]?.array.orEmpty().mapNotNull { it.bool }, writes)
            assertEquals("shown: ${label(row)}", row["shown"]?.bool, InputRules.shownToggle(state, bound))
        }
    }

    @Test fun lengthLimitInCodePoints() {
        val rows = rows("clampCases")
        assertEquals(13, rows.size)
        for (row in rows) {
            assertEquals(label(row), visible(row["expect"]?.string ?: ""), visible(InputRules.clamp(row["text"]?.string ?: "", row["maxLength"])))
        }
    }

    @Test fun lines() {
        val rows = rows("linesCases")
        assertEquals(9, rows.size)
        for (row in rows) assertEquals("lines: ${row["lines"]}", row["expect"]?.number?.toInt(), InputRules.lines(row["lines"]))
    }

    @Test fun keyboardIntent() {
        val rows = rows("keyboardCases")
        assertEquals(13, rows.size)
        for (row in rows) {
            val effective = InputRules.effectiveKeyboard(row["keyboard"], row["lines"], false)
            assertEquals(label(row), row["expect"]?.string, effective?.word ?: "multiline")
        }
    }

    @Test fun keyboardKey() {
        val rows = rows("keyActionCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val fields = row["fields"]?.array.orEmpty().mapNotNull { it.obj }.map { InputRules.Focusable(it["multiline"]?.bool == true, it["reachable"]?.bool == true) }
            val index = row["index"]?.number?.toInt() ?: 0
            assertEquals("action: ${label(row)}", row["expect"]?.string, InputRules.keyAction(fields, index).word)
            assertEquals("next: ${label(row)}", row["next"]?.number?.toInt(), InputRules.nextIndex(fields, index))
        }
    }

    @Test fun errorStateIsAHeardText() {
        val rows = rows("errorCases")
        assertEquals(8, rows.size)
        for (row in rows) assertEquals(label(row), row["expect"]?.string?.let(::visible), InputRules.error(row["error"]?.string)?.let(::visible))
    }

    @Test fun geometry() {
        val metrics = InputRules.Metrics(labelLine = 16.0, textLine = 20.0, noteLine = 16.0, gap = 4.0, paddingBlock = 12.0, border = 1.0)
        val rows = rows("geometryCases")
        assertEquals(17, rows.size)
        for (row in rows) {
            val name = label(row)
            when (row["widget"]?.string) {
                "TextField" -> {
                    assertEquals("box: $name", row["box"]?.number, InputRules.boxHeight(metrics, row["lines"]))
                    assertEquals("height: $name", row["height"]?.number, InputRules.fieldHeight(metrics, row["lines"], row["labelLines"]?.number?.toInt() ?: 1, row["noteLines"]?.number?.toInt() ?: 0))
                }
                "Switch" ->
                    if (row["thumbOffset"] != null) {
                        assertEquals(name, row["thumbOffset"]?.number, InputRules.thumbOffset(row["on"]?.bool == true))
                    } else {
                        assertEquals("label: $name", row["labelWidth"]?.number, InputRules.switchLabelWidth(row["rowWidth"]?.number ?: 0.0, 12.0))
                        assertEquals("height: $name", row["height"]?.number, InputRules.switchHeight(20.0, row["labelLines"]?.number?.toInt() ?: 1))
                    }
                else -> {
                    val line = row["labelLine"]?.number ?: 20.0
                    assertEquals("label: $name", row["labelWidth"]?.number, InputRules.checkboxLabelWidth(row["rowWidth"]?.number ?: 0.0, 8.0))
                    assertEquals("height: $name", row["height"]?.number, InputRules.checkboxHeight(line, row["labelLines"]?.number?.toInt() ?: 1))
                    assertEquals("offset: $name", row["boxOffset"]?.number, InputRules.checkboxBoxOffset(line))
                }
            }
        }
    }

    @Test fun directionOfTheContent() {
        val rows = rows("directionCases").filter { it["widget"]?.string == "TextField" }
        assertEquals(8, rows.size)
        for (row in rows) {
            val leftToRight = InputRules.contentIsLeftToRight(row["keyboard"], row["lines"], false)
            assertEquals(label(row), row["content"]?.string, if (leftToRight) "left-to-right" else "screen")
        }
    }

    // The number field

    @Test fun parseTypedNumbers() {
        val rows = rows("numberParseCases")
        assertEquals(65, rows.size)
        for (row in rows) {
            val text = row["text"]?.string ?: ""
            assertEquals("«${visible(text)}» ${row["note"]?.string ?: ""}", row["number"]?.number, InputRules.parseNumber(text))
        }
    }

    @Test fun readNumbersFromTheLayers() {
        val rows = rows("numberReadCases")
        assertEquals(21, rows.size)
        for (row in rows) {
            assertEquals("number: ${label(row)}", row["number"]?.number, InputRules.readNumber(row["value"]))
            assertEquals("text: ${label(row)}", row["text"]?.string, InputRules.numberText(row["value"]))
        }
    }

    @Test fun numberFieldSequences() {
        val rows = rows("numberFieldCases")
        assertEquals(25, rows.size)
        for (row in rows) {
            var bound = row["bound"]
            var state = InputRules.TextState()
            val writes = ArrayList<JsonValue>()
            for (step in row["steps"]?.array.orEmpty().mapNotNull { it.obj }) {
                if (step["type"]?.string == "data") {
                    bound = step["bound"]
                } else {
                    val result = InputRules.stepNumber(state, event(step) ?: continue, bound, row["maxLength"], row["disabled"]?.bool == true, row["hasPath"]?.bool != false)
                    state = result.state
                    result.write?.let(writes::add)
                }
            }
            assertEquals("writes: ${label(row)}", row["writes"]?.array.orEmpty(), writes)
            assertEquals("shown: ${label(row)}", visible(row["shown"]?.string ?: ""), visible(InputRules.shownNumber(state, bound)))
        }
    }

    @Test fun aNonNumberIsAnErrorStateAfterLeaving() {
        val rows = rows("numberInvalidCases")
        assertEquals(11, rows.size)
        for (row in rows) {
            var bound = row["bound"]
            var state = InputRules.TextState()
            val states = ArrayList<Boolean>()
            for (step in row["steps"]?.array.orEmpty().mapNotNull { it.obj }) {
                if (step["type"]?.string == "data") bound = step["bound"]
                else state = InputRules.stepNumber(state, event(step) ?: continue, bound, null, row["disabled"]?.bool == true).state
                states.add(InputRules.numberInvalid(state, bound))
            }
            assertEquals(label(row), row["states"]?.array.orEmpty().mapNotNull { it.bool }, states)
        }
    }

    @Test fun numberFieldKeyboard() {
        val rows = rows("numberKeyboardCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val given = row["given"]?.obj ?: JsonObject()
            val numeric = InputRules.isNumber(row["valueType"])
            val effective = InputRules.effectiveKeyboard(given["keyboard"], given["lines"], numeric)
            assertEquals("keyboard: ${label(row)}", row["keyboard"]?.string, effective?.word ?: "multiline")
            assertEquals("lines: ${label(row)}", row["lines"]?.number?.toInt(), InputRules.effectiveLines(given["lines"], numeric))
            val leftToRight = InputRules.contentIsLeftToRight(given["keyboard"], given["lines"], numeric)
            assertEquals("content: ${label(row)}", row["content"]?.string, if (leftToRight) "left-to-right" else "screen")
        }
    }

    // The parts of a text field

    @Test fun boxOfTheField() {
        val rows = rows("fieldBoxCases")
        assertEquals(16, rows.size)
        for (row in rows) {
            val metrics = row["metrics"]
            val height = InputPartsRules.boxHeight(
                metrics?.get("textLine")?.number ?: 0.0, metrics?.get("paddingBlock")?.number ?: 0.0,
                row["lines"]?.number?.toInt() ?: 1, row["height"], row["borderWidth"],
            )
            assertEquals(label(row), row["expect"]?.number, height)
        }
    }

    @Test fun rowInsideTheBox() {
        val rows = rows("affixRowCases")
        assertEquals(7, rows.size)
        for (row in rows) {
            val name = label(row)
            val box = row["boxInline"]?.number ?: 0.0
            val result = InputPartsRules.affixRow(box, row["border"]?.number ?: 0.0, row["paddingInline"]?.number ?: 0.0, row["gap"]?.number ?: 0.0, row["prefix"]?.number, row["suffix"]?.number)
            val expect = row["expect"]
            assertEquals(name, expect?.get("prefixStart")?.number, result.prefixStart)
            assertEquals(name, expect?.get("textStart")?.number, result.textStart)
            assertEquals(name, expect?.get("textInline")?.number, result.textInline)
            assertEquals(name, expect?.get("suffixStart")?.number, result.suffixStart)
            // Right to left: the left edge is «box − start − width».
            row["rtl"]?.let { rtl ->
                assertEquals("rtl prefix: $name", rtl["prefixX"]?.number, box - (result.prefixStart ?: 0.0) - (row["prefix"]?.number ?: 0.0))
                assertEquals("rtl text: $name", rtl["textX"]?.number, box - result.textStart - result.textInline)
                assertEquals("rtl suffix: $name", rtl["suffixX"]?.number, box - (result.suffixStart ?: 0.0) - (row["suffix"]?.number ?: 0.0))
            }
        }
    }

    @Test fun counterText() {
        val rows = rows("counterCases")
        assertEquals(16, rows.size)
        for (row in rows) {
            val props = row["props"]
            assertEquals(label(row), row["expect"]?.string, InputPartsRules.counterText(props?.get("counter"), props?.get("maxLength"), row["shown"]?.string ?: ""))
        }
    }

    @Test fun rowUnderTheBox() {
        val rows = rows("noteRowCases")
        assertEquals(4, rows.size)
        for (row in rows) {
            val name = label(row)
            val width = row["rowInline"]?.number ?: 0.0
            val result = InputPartsRules.noteRow(width, row["gap"]?.number ?: 0.0, row["counter"]?.number)
            assertEquals(name, row["expect"]?.get("noteInline")?.number, result.noteInline)
            assertEquals(name, row["expect"]?.get("counterStart")?.number, result.counterStart)
            row["rtl"]?.let { assertEquals("rtl: $name", it["counterX"]?.number, width - (result.counterStart ?: 0.0) - (row["counter"]?.number ?: 0.0)) }
        }
    }

    @Test fun aMarkIsCutToEightCodePoints() {
        assertEquals("12345678", InputPartsRules.affixText("123456789"))
        assertEquals("😀".repeat(8), InputPartsRules.affixText("😀".repeat(9)))
        assertNull(InputPartsRules.affix(JsonValue.parse("""{"kind":"text"}""")))
        assertNull(InputPartsRules.affix(JsonValue.Str("₴")))
        assertEquals(InputPartsRules.Affix.Icon("mail"), InputPartsRules.affix(JsonValue.parse("""{"kind":"icon","icon":"mail"}""")))
    }

    // The composition of an IME

    @Test fun compositionIsOneStartTheIntermediateTextsAndOneEnd() {
        val tracker = CompositionTracker()
        assertEquals(listOf(InputRules.Event.CompositionStart, InputRules.Event.Input("k")), tracker.changed("k", true))
        assertEquals(listOf<InputRules.Event>(InputRules.Event.Input("ka")), tracker.changed("ka", true))
        assertEquals(listOf<InputRules.Event>(InputRules.Event.CompositionEnd("か")), tracker.changed("か", false))
        assertEquals(listOf<InputRules.Event>(InputRules.Event.Input("かa")), tracker.changed("かa", false))
        // A composition still open when the focus is lost is committed by the platform.
        tracker.changed("k", true)
        assertEquals(listOf<InputRules.Event>(InputRules.Event.CompositionEnd("k")), tracker.ended("k"))
        assertEquals(emptyList<InputRules.Event>(), tracker.ended("k"))
    }
}
