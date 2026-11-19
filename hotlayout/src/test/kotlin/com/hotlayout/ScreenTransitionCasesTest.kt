package com.hotlayout

import com.hotlayout.render.modal.ScreenTransition
import com.hotlayout.render.modal.ScreenTransitionPlan
import com.hotlayout.render.modal.ScreenTransitionWord
import com.hotlayout.render.modal.StackStep
import com.hotlayout.render.modal.TransitionLayerFrame
import com.hotlayout.render.modal.TransitionScreen
import com.hotlayout.render.motion.VisibilityFadeRules
import com.hotlayout.support.Contract
import com.hotlayout.support.JsonText
import com.hotlayout.support.name
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Default transitions between screens (`x-screenTransition`) against `screen-transition/screen-transition-cases.json`:
 * the three curves, which motion a step of the stack plays, where both screens stand at chosen moments, how the field is
 * read, and how a screen entry carries it. The views that play the plan are drawn in `ScreenTransitionWiringTest`.
 */
class ScreenTransitionCasesTest {
    private val table = Contract.json("fixtures/screen-transition/screen-transition-cases.json")

    private fun screen(value: JsonValue?) = TransitionScreen(
        modal = value?.get("presentation")?.string == "modal", placement = value?.get("modalPlacement"),
        transition = ScreenTransitionWord.of(value?.get("transition")),
    )

    private fun planOf(row: JsonObject): ScreenTransitionPlan {
        val step = StackStep.entries.first { it.word == row["kind"]?.string }
        val from = row["from"]?.takeIf { !it.isNull }?.let(::screen)
        return ScreenTransition.plan(
            step, from, screen(row["to"]), row["areaWidth"]?.number ?: 0.0,
            reducedMotion = row["reducedMotion"]?.bool == true, covered = row["covered"]?.bool == true, dragged = row["dragged"]?.bool == true,
        )
    }

    private fun curve(value: JsonValue?): DoubleArray = value?.array.orEmpty().mapNotNull { it.number }.toDoubleArray()

    @Test fun everyGroupIsRun() {
        assertEquals(setOf("curveCases", "planCases", "frameCases", "fieldCases", "documentCases"), Contract.groups(table))
    }

    /** The numbers of the artifact are the numbers of the rules. */
    @Test fun theBlockOfTheArtifact() {
        val block = Contract.json("screen-schema.json")["x-screenTransition"]
        assertEquals(ScreenTransition.FIELD, block?.get("field")?.string)
        assertEquals(listOf("fade", "none"), block?.get("words")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(ScreenTransition.PAGE_MS, block?.get("page")?.get("push")?.get("durationMs")?.number)
        assertEquals(ScreenTransition.PAGE_MS, block?.get("page")?.get("pop")?.get("durationMs")?.number)
        assertEquals(ScreenTransition.PAGE_PARALLAX, block?.get("page")?.get("push")?.get("parallax")?.number)
        assertArrayEquals(ScreenTransition.DECELERATE, curve(block?.get("page")?.get("push")?.get("curve")), 0.0)
        assertEquals(ScreenTransition.SHEET_OPEN_MS, block?.get("sheet")?.get("open")?.get("durationMs")?.number)
        assertEquals(ScreenTransition.SHEET_CLOSE_MS, block?.get("sheet")?.get("close")?.get("durationMs")?.number)
        assertArrayEquals(ScreenTransition.ACCELERATE, curve(block?.get("sheet")?.get("close")?.get("curve")), 0.0)
        assertEquals(ScreenTransition.WINDOW_OPEN_MS, block?.get("window")?.get("open")?.get("durationMs")?.number)
        assertEquals(ScreenTransition.WINDOW_CLOSE_MS, block?.get("window")?.get("close")?.get("durationMs")?.number)
        assertEquals(ScreenTransition.WINDOW_SCALE_FROM, block?.get("window")?.get("open")?.get("scaleFrom")?.number)
        assertEquals(ScreenTransition.FADE_MS, block?.get("fade")?.get("durationMs")?.number)
        assertArrayEquals(ScreenTransition.STANDARD_FADE, curve(block?.get("fade")?.get("curve")), 0.0)
    }

    @Test fun curves() {
        val curves = mapOf("page" to ScreenTransition.DECELERATE, "sheetClose" to ScreenTransition.ACCELERATE, "fade" to ScreenTransition.STANDARD_FADE)
        val rows = Contract.rows(table, "curveCases")
        assertEquals(12, rows.size)
        for (row in rows) {
            val curve = curves[row["curve"]?.string]
            assertNotNull(row.name(), curve)
            assertEquals(row.name(), row["y"]?.number ?: -1.0, VisibilityFadeRules.cubicBezierAt(curve!!, row["x"]?.number ?: 0.0), 0.001)
        }
    }

    @Test fun plans() {
        val rows = Contract.rows(table, "planCases")
        assertEquals(29, rows.size)
        for (row in rows) {
            val plan = planOf(row)
            val expected = row["expect"]
            assertEquals(row.name(), expected?.get("motion")?.string, plan.motion.word)
            if (expected?.get("motion")?.string == "none") {
                assertEquals(row.name(), expected.get("reason")?.string, plan.reason?.word)
                continue
            }
            assertNull(row.name(), plan.reason)
            assertEquals(row.name(), expected?.get("durationMs")?.number, plan.durationMs)
            assertArrayEquals(row.name(), curve(expected?.get("curve")), plan.curve, 0.0)
            assertEquals(row.name(), expected?.get("parallax")?.number, plan.parallax)
            assertEquals(row.name(), expected?.get("scaleFrom")?.number, plan.scaleFrom)
            assertEquals(row.name(), expected?.get("scrim")?.string, plan.scrim?.word)
        }
    }

    @Test fun frames() {
        val rows = Contract.rows(table, "frameCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val plan = planOf(row["plan"]?.obj ?: JsonObject())
            assertTrue(row.name(), plan.moves)
            for (probe in row["probes"]?.array.orEmpty()) {
                val at = probe["at"]?.number ?: 0.0
                val frame = ScreenTransition.frame(plan, at)
                fun check(side: String, layer: TransitionLayerFrame) {
                    val expected = probe[side]?.obj ?: return
                    val got = mapOf("x" to layer.x, "y" to layer.y, "opacity" to layer.opacity, "scale" to layer.scale)
                    for ((key, value) in expected) assertEquals("${row.name()} at $at ms, $side.$key", value.number ?: Double.NaN, got[key] ?: Double.NaN, 0.001)
                }
                check("incoming", frame.incoming)
                check("outgoing", frame.outgoing)
                probe["scrim"]?.number?.let { assertEquals("${row.name()} at $at ms, scrim", it, frame.scrim, 0.001) }
            }
        }
    }

    @Test fun fields() {
        val rows = Contract.rows(table, "fieldCases")
        assertEquals(5, rows.size)
        for (row in rows) assertEquals(row.name(), row["reads"]?.string, ScreenTransitionWord.of(row["value"])?.word)
    }

    /** The field on a screen entry: read when it is a word of the list, otherwise no field. */
    @Test fun screenEntriesCarryTheField() {
        val rows = Contract.rows(table, "documentCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            val entry = JsonObject((row["screen"]?.obj ?: JsonObject()).toList() + listOf(
                "screenId" to JsonValue.Str("s"), "name" to JsonValue.Str("S"),
                "root" to JsonValue.parse("""{"id":"root","type":"Screen","props":{},"children":[]}"""),
            ))
            val document = loadDocument("""{"schemaVersion":"${HotlayoutVersions.SCHEMA}","documentId":"d","name":"n","initialScreenId":"s","screens":[${JsonText.of(JsonValue.Obj(entry))}]}""")
            val expected = if (row["schema"]?.bool == true) ScreenTransitionWord.of(row["screen"]?.get("transition")) else null
            assertEquals(row.name(), expected, document.screens.first().transition)
        }
    }

    @Test fun theFramePastTheDurationIsTheFinalState() {
        val plan = ScreenTransition.plan(StackStep.Push, TransitionScreen(), TransitionScreen(), 393.0)
        val end = ScreenTransition.frame(plan, 5000.0)
        assertEquals(0.0, end.incoming.x, 0.0)
        assertEquals(-0.3, end.outgoing.x, 1e-9)
    }
}
