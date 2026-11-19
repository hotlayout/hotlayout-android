package com.hotlayout

import com.hotlayout.model.DocumentNode
import com.hotlayout.render.motion.NodeAppear
import com.hotlayout.render.motion.NodeMotion
import com.hotlayout.render.motion.VisibilityFadeRules
import com.hotlayout.support.Contract
import com.hotlayout.support.JsonText
import com.hotlayout.support.name
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `spin` and `sway` of a node and its one-time appearance (`appear`) against `motion/motion-turn-cases.json`: the turn and the
 * pose of the appearance at a moment, the moments at which the appearance plays, and the values the reader accepts.
 * `zoneCases` is the table of the builder's validator and is not run here; `MotionTurnWiringTest` draws real nodes at the
 * moments of the rows.
 */
class MotionTurnCasesTest {
    private val table = Contract.json("fixtures/motion/motion-turn-cases.json")

    @Test fun everyGroupIsRunOrNamed() {
        val run = setOf("turnCases", "appearCases", "momentCases", "documentCases")
        val notRun = setOf("zoneCases")
        assertEquals(Contract.groups(table), run + notRun)
    }

    @Test fun theBlocksOfTheArtifact() {
        val schema = Contract.json("screen-schema.json")
        val turn = schema["x-motionTurn"]
        assertEquals(listOf("spin", "sway"), turn?.get("kinds")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(listOf("clockwise", "counterclockwise"), turn?.get("spin")?.get("directions")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(NodeMotion.Pivot.entries.map { it.word }, turn?.get("sway")?.get("pivots")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(NodeMotion.ANGLE.first.toDouble(), turn?.get("ranges")?.get("angle")?.get("min")?.number)
        assertEquals(NodeMotion.ANGLE.last.toDouble(), turn?.get("ranges")?.get("angle")?.get("max")?.number)
        assertEquals(NodeMotion.DURATION.first.toDouble(), turn?.get("ranges")?.get("duration")?.get("min")?.number)
        assertEquals(NodeMotion.DELAY.last.toDouble(), turn?.get("ranges")?.get("delay")?.get("max")?.number)
        val appear = schema["x-appear"]
        assertEquals(NodeAppear.FIELD, appear?.get("prop")?.string)
        assertEquals(listOf("fade", "rise"), appear?.get("kinds")?.array.orEmpty().mapNotNull { it.string })
        assertEquals(NodeAppear.DURATION.first.toDouble(), appear?.get("ranges")?.get("duration")?.get("min")?.number)
        assertEquals(NodeAppear.DURATION.last.toDouble(), appear?.get("ranges")?.get("duration")?.get("max")?.number)
        assertEquals(NodeAppear.DISTANCE.first.toDouble(), appear?.get("ranges")?.get("distance")?.get("min")?.number)
        assertEquals(NodeAppear.DISTANCE.last.toDouble(), appear?.get("ranges")?.get("distance")?.get("max")?.number)
        assertArrayEquals(VisibilityFadeRules.inCurve, appear?.get("curve")?.get("bezier")?.array.orEmpty().mapNotNull { it.number }.toDoubleArray(), 0.0)
        assertEquals("`x-motion` does not name the new kinds", listOf("float", "pulse"), schema["x-motion"]?.get("kinds")?.array.orEmpty().mapNotNull { it.string })
    }

    @Test fun turnFormulas() {
        val rows = Contract.rows(table, "turnCases")
        assertEquals(24, rows.size)
        for (row in rows) {
            val motion = NodeMotion.read(row["motion"])
            assertNotNull(row.name(), motion)
            val pose = motion!!.pose(row["t"]?.number ?: 0.0, row["reducedMotion"]?.bool == true, row["direction"]?.string == "rtl")
            val expected = row["expected"]
            assertEquals(row.name(), expected?.get("rotate")?.number ?: Double.NaN, pose.rotate, 1e-6)
            assertEquals(row.name(), expected?.get("pivot")?.string, pose.pivot.word)
            assertEquals(row.name(), listOf(0.0, 0.0, 1.0, 1.0), listOf(pose.x, pose.y, pose.scale, pose.opacity))
        }
    }

    @Test fun appearanceFormulas() {
        val rows = Contract.rows(table, "appearCases")
        assertEquals(13, rows.size)
        for (row in rows) {
            val appear = NodeAppear.read(row["appear"])
            assertNotNull(row.name(), appear)
            val pose = appear!!.pose(row["t"]?.number ?: 0.0, row["reducedMotion"]?.bool == true)
            assertEquals(row.name() + " y", row["expected"]?.get("y")?.number ?: Double.NaN, pose.y, 1e-6)
            assertEquals(row.name() + " opacity", row["expected"]?.get("opacity")?.number ?: Double.NaN, pose.opacity, 1e-6)
        }
    }

    @Test fun moments() {
        val rows = Contract.rows(table, "momentCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val moment = NodeAppear.Moment.entries.firstOrNull { it.word == row["moment"]?.string }
            assertNotNull(row.name(), moment)
            assertEquals(row.name(), row["plays"]?.bool, NodeAppear.plays(moment!!))
        }
    }

    /** The value of `motion` or `appear` on a node: what the reader draws and what it leaves standing; the root never moves and never appears. */
    @Test fun documents() {
        val rows = Contract.rows(table, "documentCases")
        assertEquals(34, rows.size)
        for (row in rows) {
            val field = row["field"]?.string ?: ""
            val valid = row["valid"]?.bool == true
            val read = if (field == "appear") NodeAppear.read(row["value"]) != null else NodeMotion.read(row["value"]) != null
            val node = DocumentNode("n", "Container", fields = JsonObject.of(field to row["value"]))
            val root = rootWith(field, row["value"] ?: JsonValue.Null)
            if (row["onRoot"]?.bool == true) {
                assertFalse(row.name(), NodeAppear.reads(root, root))
                continue
            }
            assertEquals(row.name(), valid, read)
            assertEquals(row.name(), valid, if (field == "appear") NodeAppear.reads(node, root) && read else read)
        }
    }

    private fun rootWith(field: String, value: JsonValue): DocumentNode {
        val root = """{"id":"r","type":"Screen","props":{},"$field":${JsonText.of(value)},"children":[]}"""
        val document = loadDocument("""{"schemaVersion":"${HotlayoutVersions.SCHEMA}","documentId":"d","name":"n","initialScreenId":"a","screens":[{"screenId":"a","name":"A","root":$root}]}""")
        return document.screens.first().root.also { assertTrue(it.widget == com.hotlayout.model.WidgetType.Screen) }
    }
}
