package com.hotlayout

import com.hotlayout.actions.ActionCatalog
import com.hotlayout.actions.Flow
import com.hotlayout.actions.FlowDocument
import com.hotlayout.actions.PressOutcome
import com.hotlayout.support.Contract
import com.hotlayout.support.TableJson
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared action table (`fixtures/actions/action-cases.json`): forms, what the host receives, the flow. */
class ActionCasesTest {
    private val table get() = Contract.json("fixtures/actions/action-cases.json")

    @Test fun formCases() {
        val rows = Contract.rows(table, "formCases")
        assertEquals(108, rows.size)
        for (row in rows) {
            val value = row["repeat"]?.obj?.let(TableJson::built) ?: row["value"]?.string
            assertEquals(row.name(), row["problem"]?.string, ActionCatalog.formProblem(row["form"]?.string ?: "", value)?.word)
        }
    }

    @Test fun formsMirrorTheArtifact() {
        val forms = Contract.json("screen-schema.json")["x-actions"]?.get("forms")
        assertEquals(ActionCatalog.HTTPS_URL_PATTERN, forms?.get("httpsUrl")?.get("pattern")?.string)
        assertEquals(ActionCatalog.APP_LINK_PATTERN, forms?.get("appLink")?.get("pattern")?.string)
        assertEquals(ActionCatalog.forbiddenSchemes, forms?.get("appLink")?.get("forbiddenSchemes")?.array.orEmpty().mapNotNull { it.string }.toSet())
        assertEquals(ActionCatalog.URL_MAX_LENGTH.toDouble(), forms?.get("httpsUrl")?.get("maxLength")?.number)
    }

    @Test fun handedCases() {
        val rows = Contract.rows(table, "handedCases")
        assertEquals(55, rows.size)
        for (row in rows) {
            val action = TableJson.expand(row["action"] ?: JsonValue.Null).obj ?: JsonObject()
            val handed = ActionCatalog.handed(action, Contract.scope(row["data"]))
            assertEquals(row.name(), TableJson.expand(row["handed"] ?: JsonValue.Null), TableJson.handed(handed))
        }
    }

    /** A disabled control asks for nothing; otherwise the word of the action, when it is a known one. */
    @Test fun hapticCases() {
        val rows = Contract.rows(table, "hapticCases")
        assertEquals(7, rows.size)
        for (row in rows) {
            val action = row["action"]?.obj ?: JsonObject()
            val asked = if (row["disabled"]?.bool == true) null else ActionCatalog.haptic(action)
            assertEquals(row.name(), row["expect"]?.string, asked)
        }
    }

    @Test fun flowCases() {
        val rows = Contract.rows(table, "flowCases")
        assertEquals(39, rows.size)
        for (row in rows) {
            val name = row.name()
            val documentId = row["documentId"]?.string ?: ""
            var flow = Flow(flowDocument(row, documentId), row["entry"]?.string)
            val start = row["start"]
            assertEquals("start stack: $name", start?.get("stack")?.array.orEmpty().mapNotNull { it.string }, flow.stack.map { it.screenId })
            assertEquals("start shown: $name", start?.get("shown"), TableJson.shown(flow.shownEvent(ScreenShownCause.Entry)))
            val data = Contract.scope(row["data"])
            row["steps"]?.array.orEmpty().mapNotNull { it.obj }.forEachIndexed { index, step ->
                val label = "$name — step ${index + 1}"
                var outcome = PressOutcome()
                when (step["do"]?.string) {
                    "press" -> if (step["repeat"]?.bool != true) {
                        // An auto-repeat of a held key is not a press: nothing happens.
                        val cancelled = step["cancelled"]?.bool == true
                        val action = TableJson.expand(step["action"] ?: JsonValue.Null).obj ?: JsonObject()
                        val pressed = flow.press(action, TableJson.instances(step["instances"]), data) { _, _, _, _ -> !cancelled }
                        flow = pressed.flow
                        outcome = pressed.outcome
                    }
                    "restart" -> {
                        val moved = flow.restart(step["entry"]?.string)
                        flow = moved.flow
                        outcome = PressOutcome(shown = moved.shown)
                    }
                    "rerender" -> Unit
                    "newVersion" -> {
                        var merged = row
                        merged = merged.with("screens", step["screens"])
                        step["initial"]?.let { merged = merged.with("initial", it) }
                        step["entryPoints"]?.let { merged = merged.with("entryPoints", it) }
                        val moved = flow.update(flowDocument(merged, documentId))
                        flow = moved.flow
                        outcome = PressOutcome(shown = moved.shown)
                    }
                    else -> error("unknown step: $label")
                }
                val expect = step["expect"]
                assertEquals("stack: $label", expect?.get("stack")?.array.orEmpty().mapNotNull { it.string }, flow.stack.map { it.screenId })
                assertEquals("track: $label", expect?.get("track") ?: JsonValue.Null, TableJson.track(outcome.track))
                assertEquals("handed: $label", TableJson.expand(expect?.get("handed") ?: JsonValue.Null), TableJson.handed(outcome.handed))
                assertEquals("shown: $label", expect?.get("shown") ?: JsonValue.Null, TableJson.shown(outcome.shown))
            }
        }
    }

    companion object {
        internal fun flowDocument(row: JsonObject, documentId: String): FlowDocument {
            val placements = row["modalPlacement"]?.obj ?: JsonObject()
            val screens = (row["screens"]?.obj ?: JsonObject()).map { (id, presentation) ->
                FlowDocument.Screen(id, if (presentation.string == "modal") ScreenPresentation.Modal else ScreenPresentation.Page, placements[id]?.string)
            }
            val entryPoints = (row["entryPoints"]?.obj ?: JsonObject()).mapNotNull { (name, target) -> target.string?.let { name to it } }.toMap()
            return FlowDocument(documentId, screens, row["initial"]?.string ?: row["start"]?.string, entryPoints)
        }
    }
}
