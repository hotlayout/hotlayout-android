package com.hotlayout

import com.hotlayout.model.DocumentNode
import com.hotlayout.render.motion.FadeFrame
import com.hotlayout.render.motion.FadePhase
import com.hotlayout.render.motion.FadeState
import com.hotlayout.render.motion.VisibilityFadeRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fade of a node when its condition changes against `visibility-fade/visibility-fade-cases.json`: the two curves,
 * the timeline of one node, two neighbours that swap, the changes that are not transitions, how the field is read, and
 * the data of the artifact. `VisibilityFadeWiringTest` runs the real screen through `screenCases`.
 */
class VisibilityFadeCasesTest {
    private val table = Contract.json("fixtures/visibility-fade/visibility-fade-cases.json")
    private val schema get() = Contract.json("screen-schema.json")

    private fun same(label: String, expect: JsonObject, got: FadeFrame) {
        assertEquals("$label: phase", expect["phase"]?.string, got.phase.word)
        assertEquals("$label: in layout", expect["inLayout"]?.bool, got.inLayout)
        assertEquals("$label: opacity", expect["opacity"]?.number ?: 0.0, got.opacity, 0.001)
        assertEquals("$label: interactive", expect["interactive"]?.bool ?: got.interactive, got.interactive)
    }

    @Test fun everyGroupIsRunOrNamed() {
        val run = setOf("curveCases", "timelineCases", "pairCases", "animateCases", "fieldCases", "screenCases")
        assertEquals(run + "documentCases", Contract.groups(table))
    }

    @Test fun curves() {
        val rows = Contract.rows(table, "curveCases")
        assertEquals(12, rows.size)
        for (row in rows) {
            val curve = if (row["curve"]?.string == "out") VisibilityFadeRules.outCurve else VisibilityFadeRules.inCurve
            assertEquals(row.name(), row["y"]?.number ?: 0.0, VisibilityFadeRules.cubicBezierAt(curve, row["x"]?.number ?: 0.0), 0.001)
        }
    }

    @Test fun timelinesOfOneNode() {
        val rows = Contract.rows(table, "timelineCases")
        assertEquals(9, rows.size)
        for (row in rows) {
            var state = VisibilityFadeRules.rest(row["shown"]?.bool == true)
            val changes = row["changes"]?.array.orEmpty().mapNotNull { it.obj }
            var applied = 0
            for (probe in row["probes"]?.array.orEmpty().mapNotNull { it.obj }) {
                val at = probe["at"]?.number ?: 0.0
                while (applied < changes.size && (changes[applied]["at"]?.number ?: 0.0) <= at) {
                    val change = changes[applied++]
                    state = VisibilityFadeRules.change(state, change["shown"]?.bool == true, change["at"]?.number ?: 0.0, animate = true)
                }
                same("${row.name()} at $at", probe, VisibilityFadeRules.at(state, at))
            }
        }
    }

    @Test fun twoNeighboursThatSwap() {
        val rows = Contract.rows(table, "pairCases")
        assertEquals(2, rows.size)
        for (row in rows) {
            val at = row["at"]?.number ?: 0.0
            val back = row["back"]?.number
            var leaving = VisibilityFadeRules.rest(row["leaving"]?.get("shown")?.bool == true)
            var appearing = VisibilityFadeRules.rest(row["appearing"]?.get("shown")?.bool == true)
            leaving = VisibilityFadeRules.change(leaving, false, at, true)
            appearing = VisibilityFadeRules.change(appearing, true, at, true)
            var backed = false
            var first: Pair<Boolean, Boolean>? = null
            var changesAt: Double? = null
            for (probe in row["probes"]?.array.orEmpty().mapNotNull { it.number }) {
                if (back != null && !backed && probe >= back) {
                    backed = true
                    leaving = VisibilityFadeRules.change(leaving, true, back, true)
                    appearing = VisibilityFadeRules.change(appearing, false, back, true)
                }
                val pair = VisibilityFadeRules.at(leaving, probe).inLayout to VisibilityFadeRules.at(appearing, probe).inLayout
                assertFalse("${row.name()} at $probe: both in the layout", pair.first && pair.second)
                if (first == null) first = pair else if (pair != first && changesAt == null) changesAt = probe
            }
            assertEquals("${row.name()}: where the layout changes", row["layoutChangesAt"]?.number, changesAt)
        }
    }

    @Test fun changesThatAreNotTransitions() {
        val rows = Contract.rows(table, "animateCases")
        assertEquals(4, rows.size)
        for (row in rows) {
            var state = VisibilityFadeRules.rest(row["shown"]?.bool == true)
            val midway = row["midway"]?.obj
            midway?.let { state = VisibilityFadeRules.change(state, it["shown"]?.bool == true, it["at"]?.number ?: 0.0, true) }
            val now = midway?.get("at")?.number ?: 0.0
            state = VisibilityFadeRules.change(state, row["to"]?.bool == true, now, animate = false)
            val got = VisibilityFadeRules.at(state, now)
            assertEquals(row.name(), row["phase"]?.string, got.phase.word)
            assertEquals(row.name(), row["inLayout"]?.bool, got.inLayout)
            assertEquals(row.name(), row["opacity"]?.number ?: 0.0, got.opacity, 1e-9)
        }
    }

    private fun node(type: String, fields: List<Pair<String, JsonValue>> = emptyList(), id: String = type.lowercase(), children: List<DocumentNode> = emptyList()) =
        DocumentNode(id, type, JsonObject(), JsonObject(fields), children)

    @Test fun readingTheField() {
        val rows = Contract.rows(table, "fieldCases")
        assertTrue(rows.size >= 5)
        for (row in rows) {
            val fields = listOfNotNull(row["value"]?.let { VisibilityFadeRules.KEY to it })
            assertEquals(row.name(), row["reads"]?.string, if (VisibilityFadeRules.fieldOf(node("Text", fields))) "fade" else null)
        }
    }

    private val fade = listOf(VisibilityFadeRules.KEY to JsonValue.Str("fade"), "visibleIf" to JsonValue.Obj(JsonObject(listOf("path" to JsonValue.Str("a.b"), "op" to JsonValue.Str("eq"), "value" to JsonValue.Num(1.0)))))

    @Test fun zonesOfTheScreenFollowTheConditionAtOnce() {
        val first = node("AppBar", fade, "bar-first")
        val late = node("AppBar", fade, "bar-late")
        val root = node("Screen", children = listOf(first, node("Text", id = "body"), late))
        assertFalse("a node without the condition is not read", VisibilityFadeRules.reads(node("Text", fade.take(1)), root))
        assertTrue("an ordinary node is read", VisibilityFadeRules.reads(node("Text", fade), root))
        assertFalse("the first child of the root that is an app bar is a zone", VisibilityFadeRules.reads(first, root))
        assertTrue("an app bar that is not the first child fades", VisibilityFadeRules.reads(late, root))
        assertFalse("a pinned node (bottom) is a zone", VisibilityFadeRules.reads(node("Container", fade + ("pin" to JsonValue.Str("bottom"))), root))
        assertFalse("a pinned node (top) is a zone", VisibilityFadeRules.reads(node("Container", fade + ("pin" to JsonValue.Str("top"))), root))
        assertFalse("any value of pin makes a zone", VisibilityFadeRules.reads(node("Container", fade + ("pin" to JsonValue.Str("other"))), root))
        assertFalse(VisibilityFadeRules.reads(node("NavBar", fade), root))
        assertFalse(VisibilityFadeRules.reads(node("NavItem", fade), root))
        assertFalse("the panels of Tabs show the open tab only", VisibilityFadeRules.reads(node("Tab", fade), root))
        assertTrue("a child of a stack fades", VisibilityFadeRules.reads(node("Text", fade, "in-stack"), node("Stack", children = listOf(node("Text", fade, "in-stack")))))
    }

    @Test fun theDataOfTheArtifact() {
        val block = schema["x-visibilityFade"]!!
        assertEquals(VisibilityFadeRules.KEY, block["field"]?.string)
        assertEquals(listOf(VisibilityFadeRules.WORD), block["words"]?.array.orEmpty().mapNotNull { it.string })
        assertEquals(VisibilityFadeRules.OUT_MS, block["out"]?.get("durationMs")?.number)
        assertEquals(VisibilityFadeRules.IN_MS, block["in"]?.get("durationMs")?.number)
        assertEquals(VisibilityFadeRules.IN_DELAY_MS, block["in"]?.get("delayMs")?.number)
        assertEquals(VisibilityFadeRules.outCurve.toList(), block["out"]?.get("curve")?.array.orEmpty().mapNotNull { it.number })
        assertEquals(VisibilityFadeRules.inCurve.toList(), block["in"]?.get("curve")?.array.orEmpty().mapNotNull { it.number })
        assertEquals("at once", block["olderReader"]?.string)
    }

    @Test fun phasesHaveTheWordsOfTheTable() {
        assertEquals(listOf("shown", "leaving", "hidden", "waiting", "entering"), FadePhase.values().map { it.word })
    }
}
