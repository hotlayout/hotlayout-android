package com.hotlayout

import com.hotlayout.actions.ChoiceRules
import com.hotlayout.actions.PressRules
import com.hotlayout.actions.WriteActions
import com.hotlayout.data.Conditions
import com.hotlayout.data.DataLayers
import com.hotlayout.data.RecordScope
import com.hotlayout.support.Contract
import com.hotlayout.support.ScreenSim
import com.hotlayout.support.TableJson
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Write actions (`setValue`, `toggleValue`) and the choice made of plain nodes (`actions/…`, `inputs/choice…`, `inputs/writeFormCases`). */
class WriteCasesTest {
    private val actions get() = Contract.json("fixtures/actions/action-cases.json")
    private val inputs get() = Contract.json("fixtures/inputs/input-cases.json")

    private fun expand(value: JsonValue?): JsonValue = TableJson.expand(value ?: JsonValue.Null)

    private fun presses(row: JsonObject): List<String> = row["presses"]?.array.orEmpty().mapNotNull { it.string ?: it["node"]?.string }

    @Test fun writeSteps() {
        val rows = Contract.rows(actions, "writeCases")
        assertEquals(67, rows.size)
        for (row in rows) {
            val action = expand(row["action"]).obj ?: JsonObject()
            val data = Contract.scope(expand(row["data"]))
            val step = WriteActions.step(action) { data[it] }
            val name = row.name()
            val expected = expand(row["step"]).obj
            if (expected == null) {
                assertNull("not a write: $name", step)
                continue
            }
            when (step) {
                is WriteActions.Step.Write -> {
                    assertEquals(name, "write", expected["kind"]?.string)
                    assertEquals("path: $name", expected["path"]?.string, step.path)
                    assertEquals("value: $name", expected["value"], step.value)
                }
                is WriteActions.Step.Same -> {
                    assertEquals(name, "same-value", expected["kind"]?.string)
                    assertEquals("path: $name", expected["path"]?.string, step.path)
                    assertEquals("value: $name", expected["value"], step.value)
                }
                is WriteActions.Step.Invalid -> {
                    assertEquals(name, "invalid", expected["kind"]?.string)
                    assertEquals("problem: $name", expected["problem"]?.string, step.problem.word)
                }
                null -> error("a write action was not recognised: $name")
            }
            // What the host gets: only a write that the gate lets through.
            val permitted = (step as? WriteActions.Step.Write)?.takeIf { com.hotlayout.actions.ActionCatalog.isDataPath(it.path) }
            val host = permitted?.let { listOf(JsonValue.Str(it.path), it.value) }
            assertEquals("host: $name", expand(row["host"]).array, host)
        }
    }

    @Test fun controlOfAWriteNode() {
        val rows = Contract.rows(actions, "writeControlCases")
        assertEquals(22, rows.size)
        for (row in rows) {
            val action = expand(row["action"]).obj ?: JsonObject()
            val data = Contract.scope(expand(row["data"]))
            val control = WriteActions.control(action) { data[it] }
            val expected = row["control"]?.obj
            if (expected == null) {
                assertNull(row.name(), control)
                continue
            }
            assertEquals("role: ${row.name()}", expected["role"]?.string, control?.role?.word)
            assertEquals("path: ${row.name()}", expected["path"]?.string, control?.path)
            assertEquals("on: ${row.name()}", expected["on"]?.bool, control?.on)
        }
    }

    @Test fun hapticRequest() {
        val rows = Contract.rows(actions, "hapticCases")
        assertEquals(7, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.string, PressRules.haptic(expand(row["action"]).obj ?: JsonObject(), row["disabled"]?.bool == true))
    }

    @Test fun pressesWriteInTheOrderTheHostSeesThem() {
        val rows = Contract.rows(actions, "writePressCases")
        assertEquals(15, rows.size)
        for (row in rows) {
            val sim = ScreenSim(row["nodes"]?.array.orEmpty())
            for (id in presses(row)) sim.press(id)
            assertEquals("log: ${row.name()}", row["log"]?.array.orEmpty().map { it.array.orEmpty() }, sim.log)
            assertEquals("session: ${row.name()}", Contract.scope(row["session"]), sim.session)
        }
    }

    @Test fun placeOfAnOptionInItsGroup() {
        val rows = Contract.rows(inputs, "choicePlaceCases")
        assertEquals(11, rows.size)
        for (row in rows) {
            val siblings = row["siblings"]?.array.orEmpty().map(ScreenSim::node)
            val hidden = row["hidden"]?.array.orEmpty().mapNotNull { it.string }.toSet()
            for ((id, expected) in row["places"]?.obj ?: JsonObject()) {
                val node = siblings.first { it.id == id }
                val place = WriteActions.place(node, siblings) { it.id !in hidden }
                val want = expected.array?.mapNotNull { it.number?.toInt() }
                assertEquals("$id: ${row.name()}", want, place?.let { listOf(it.position, it.size) })
            }
        }
    }

    @Test fun controlsOnTheFinishedScreen() {
        val rows = Contract.rows(inputs, "choiceControlCases")
        assertEquals(15, rows.size)
        for (row in rows) {
            val sim = ScreenSim(row["nodes"]?.array.orEmpty(), Contract.scope(row["session"]))
            for ((id, expected) in row["expect"]?.obj ?: JsonObject()) assertEquals("before $id: ${row.name()}", expected, sim.control(id))
            if (row["presses"] == null) continue
            for (id in presses(row)) sim.press(id)
            for ((id, expected) in row["after"]?.obj ?: JsonObject()) assertEquals("after $id: ${row.name()}", expected, sim.control(id))
        }
    }

    @Test fun formIsFilledAndEmptiedByWrites() {
        val rows = Contract.rows(inputs, "writeFormCases")
        assertEquals(6, rows.size)
        for (row in rows) {
            val sim = ScreenSim(row["nodes"]?.array.orEmpty())
            for ((id, expected) in row["disabled"]?.obj ?: JsonObject()) {
                val states = expected.array.orEmpty().mapNotNull { it.bool }
                val seen = arrayListOf(sim.isDisabled(id))
                for (press in presses(row)) {
                    sim.press(press)
                    seen.add(sim.isDisabled(id))
                }
                assertEquals("$id: ${row.name()}", states, seen)
            }
        }
    }

    @Test fun choiceWrite() {
        val rows = Contract.rows(inputs, "choiceCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val value = row["value"]
            val path = value?.takeIf { it["kind"]?.string == "binding" }?.get("path")?.string?.takeIf { it.isNotEmpty() }
            val write = ChoiceRules.write(path, row["pick"]?.string ?: "", row["shown"]?.string)
            val expected = row["write"]?.obj
            assertEquals("path: ${row.name()}", expected?.get("path")?.string, write?.first)
            assertEquals("value: ${row.name()}", expected?.get("value")?.string, write?.second)
        }
    }

    @Test fun choiceReachesTheConditionsOfTheScreen() {
        val rows = Contract.rows(inputs, "choiceSessionCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            var session: DataScope = emptyMap()
            val path = row["value"]?.takeIf { it["kind"]?.string == "binding" }?.get("path")?.string
            val condition = row["condition"] ?: JsonValue.Null
            fun holds() = Conditions.holds(condition, RecordScope.merge(DataLayers(session = session)))
            assertEquals("before: ${row.name()}", row["before"]?.bool, holds())
            for (pick in listOfNotNull(row["pickFirst"]?.string, row["pick"]?.string)) {
                ChoiceRules.write(path, pick, null)?.let { (target, value) -> session = session + (target to JsonValue.Str(value)) }
            }
            assertEquals("after: ${row.name()}", row["after"]?.bool, holds())
        }
        assertTrue(true)
    }

    // The disabled button (`disabledIf`, `requiresFilled`) against the form groups of the input table.

    @Test fun filledMeansAnAnswer() {
        val rows = Contract.rows(inputs, "filledCases")
        assertEquals(32, rows.size)
        for (row in rows) assertEquals(row.name(), row["filled"]?.bool, com.hotlayout.actions.ScreenForm.isValueFilled(row["kind"]?.string ?: "", row["value"]))
    }

    @Test fun formCases() {
        val rows = Contract.rows(inputs, "formCases")
        assertEquals(23, rows.size)
        for (row in rows) {
            for (state in row["states"]?.array.orEmpty().mapNotNull { it.obj }) {
                val layers = state["layers"]
                val label = "${row.name()} — ${state.name()}"
                val data = DataLayers(
                    layers?.get("record")?.let { Contract.scope(it) }, layers?.get("session")?.let { Contract.scope(it) },
                    layers?.get("host")?.let { Contract.scope(it) }, layers?.get("data")?.let { Contract.scope(it) },
                )
                for ((screen, nodes) in row["screens"]?.obj ?: JsonObject()) {
                    val sim = ScreenSim(nodes.array.orEmpty(), data)
                    assertEquals("filled $screen: $label", state["filled"]?.get(screen)?.bool, sim.isFilled())
                    for ((id, expected) in state["disabled"]?.obj ?: JsonObject()) {
                        if (sim.find(id) != null) assertEquals("disabled $id: $label", expected.bool, sim.isDisabled(id))
                    }
                }
            }
        }
    }

    /** A press of a disabled button reaches neither the action nor the analytics event. */
    @Test fun pressOfADisabledButton() {
        val rows = Contract.rows(inputs, "pressCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val disabled = row["disabled"]?.bool == true
            val widget = row["widget"]?.string ?: "Button"
            val action = mapOf("type" to "submit", "track" to mapOf("event" to "tapped"))
            val common = mapOf("action" to action, "disabledIf" to mapOf("path" to "form.locked", "op" to "isTrue"))
            val props = if (widget == "IconButton") common + mapOf("icon" to "check", "label" to mapOf("kind" to "literal", "value" to "Done"))
            else common + mapOf("label" to mapOf("kind" to "literal", "value" to "Go"))
            val node = JsonValue.of(mapOf("id" to "b", "type" to widget, "props" to props))
            val sim = ScreenSim(listOf(node), DataLayers(data = mapOf("form.locked" to JsonValue.Bool(disabled))))
            sim.press("b")
            val log = sim.log.map { it.first().string }
            assertEquals("action: ${row.name()}", row["action"]?.number?.toInt(), log.count { it == "action" })
            assertEquals("track: ${row.name()}", row["track"]?.number?.toInt(), log.count { it == "track" })
        }
    }

    @Test fun disabledLook() {
        val rows = Contract.rows(inputs, "disabledLookCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            val disabled = row["disabled"]?.bool == true
            val opacity = (if (disabled) PressRules.DISABLED_OPACITY else 1.0) * (row["styleOpacity"]?.number ?: 1.0)
            assertEquals("opacity: ${row.name()}", row["opacity"]?.number, opacity)
            assertEquals("semantics: ${row.name()}", disabled, row["semantics"]?.get("disabled")?.bool)
            assertEquals("a disabled button stays focusable: ${row.name()}", true, row["semantics"]?.get("focusable")?.bool)
        }
    }
}
