package com.hotlayout

import com.hotlayout.model.BlurRules
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.motion.NodeMotion
import com.hotlayout.render.paint.GlassRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import com.hotlayout.theme.NodeStyle
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared motion table: the pose of a moving node at a moment, and the glass numbers. */
class MotionCasesTest {
    private val table get() = Contract.json("fixtures/motion/motion-cases.json")

    @Test fun everyGroupIsKnown() {
        assertEquals(setOf("poseCases", "documentCases", "blurCases", "glassCases"), Contract.groups(table))
    }

    @Test fun poses() {
        val rows = Contract.rows(table, "poseCases")
        assertEquals(29, rows.size)
        for (row in rows) {
            val motion = NodeMotion.read(row["motion"])
            assertNotNull(row.name(), motion)
            val pose = motion!!.pose(row["t"]?.number ?: 0.0, row["reducedMotion"]?.bool == true, row["direction"]?.string == "rtl")
            val expected = row["expected"]?.obj
            assertEquals(row.name() + " x", expected?.get("x")?.number ?: 0.0, pose.x, 1e-6)
            assertEquals(row.name() + " y", expected?.get("y")?.number ?: 0.0, pose.y, 1e-6)
            assertEquals(row.name() + " scale", expected?.get("scale")?.number ?: 1.0, pose.scale, 1e-6)
            assertEquals(row.name() + " opacity", expected?.get("opacity")?.number ?: 1.0, pose.opacity, 1e-6)
        }
    }

    /** A value the table calls valid is read; one it calls invalid is not drawn (the root is the exception: its value is fine, the root never moves). */
    @Test fun aMotionThatTheTableCallsInvalidIsNotDrawn() {
        val rows = Contract.rows(table, "documentCases")
        assertEquals(28, rows.size)
        for (row in rows) {
            val read = NodeMotion.read(row["motion"])
            if (row["onRoot"]?.bool == true) continue
            assertEquals(row.name(), row["valid"]?.bool == true, read != null)
        }
    }

    private val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme())

    @Test fun glass() {
        val rows = Contract.rows(table, "glassCases")
        assertEquals(11, rows.size)
        for (row in rows) {
            val value = row["value"]!!
            val read = BlurRules.radius(value)
            assertEquals(row.name(), row["valid"]?.bool == true, read != null)
            if (row["valid"]?.bool != true) continue
            assertEquals(row.name(), value.number?.toInt(), read)
            val style = JsonObject.of("backdropBlur" to value)
            val node = when (row["where"]?.string) {
                "style" -> DocumentNode("n", "Container", fields = JsonObject.of("style" to JsonValue.Obj(style)))
                "styleWhen" -> DocumentNode("n", "Container", fields = JsonObject.of("styleWhen" to JsonValue.Arr(listOf(JsonValue.Obj(JsonObject.of("when" to JsonValue.Obj(JsonObject.of("path" to JsonValue.Str("a.b"), "op" to JsonValue.Str("isEmpty"))), "style" to JsonValue.Obj(style)))))))
                else -> null
            }
            if (node != null) assertTrue(row.name(), GlassRules.uses(node))
            if (row["where"]?.string == "style") {
                assertEquals(row.name(), read, resolver.box(NodeStyle.effective(node!!, emptyMap()), emptyMap()).backdropBlur)
            }
        }
    }

    @Test fun aGlassOfAnotherFormIsNotRead() {
        assertNull(BlurRules.radius(JsonValue.Str("12px")))
        assertNull(BlurRules.radius(JsonValue.Num(Double.NaN)))
        assertEquals(40, BlurRules.radius(JsonValue.Num(40.0)))
    }
}
