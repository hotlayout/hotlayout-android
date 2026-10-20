package com.hotlayout

import com.hotlayout.render.paint.DashedBorderRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.ShadowTint
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colour of a shadow taken from the background (`shadow-color/…`) and the dashed border (`border-dash/…`). */
class PaintCasesTest {
    private val shadow get() = Contract.json("fixtures/shadow-color/shadow-color-cases.json")
    private val dash get() = Contract.json("fixtures/border-dash/border-dash-cases.json")

    @Test fun middleStop() {
        val rows = Contract.rows(shadow, "middleStopCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            val positions = row["positions"]?.array.orEmpty().mapNotNull { it.number }
            assertEquals(row.name(), row["expected"]?.number?.toInt(), ShadowTint.middleStop(positions))
        }
    }

    @Test fun tint() {
        val themes = JsonValue.Obj(JsonObject.of("themes" to mapOf("light" to (shadow["theme"] ?: JsonValue.Null))))
        val resolver = StyleResolver(HotlayoutThemeSet.read(themes)!!.theme("light"))
        val rows = Contract.rows(shadow, "tintCases")
        assertEquals(7, rows.size)
        for (row in rows) {
            val style = row["style"]?.obj ?: JsonObject()
            val layers = ShadowTint.apply(resolver.shadow(style["shadow"]).orEmpty(), style, resolver)
            val expected = row["expect"]?.array
            if (expected == null) {
                assertTrue(row.name(), layers.isEmpty())
                continue
            }
            assertEquals(row.name(), expected.size, layers.size)
            for ((layer, want) in layers.zip(expected)) {
                assertEquals(row.name(), want["offsetX"]?.number, layer.offsetX)
                assertEquals(row.name(), want["offsetY"]?.number, layer.offsetY)
                assertEquals(row.name(), want["blur"]?.number, layer.blur)
                assertEquals(row.name(), want["spread"]?.number, layer.spread)
                val color = Rgba.hex(want["color"]?.string ?: "")!!
                assertEquals(row.name(), color.red, layer.color.red, 1e-9)
                assertEquals(row.name(), color.green, layer.color.green, 1e-9)
                assertEquals(row.name(), color.blue, layer.color.blue, 1e-9)
                assertEquals(row.name(), want["opacity"]?.number ?: Double.NaN, layer.color.alpha, 1e-9)
            }
        }
    }

    @Test fun shadowRules() {
        for (row in Contract.rows(shadow, "ruleCases")) {
            when (row["rule"]?.string) {
                "words" -> assertEquals(listOf(ShadowTint.WORD), row["expect"]?.array.orEmpty().mapNotNull { it.string })
                "inherited" -> assertEquals(false, row["expect"]?.bool)
                else -> error(row.name())
            }
        }
        assertEquals(listOf(ShadowTint.WORD), Contract.json("screen-schema.json")["x-shadowColor"]?.get("words")?.array.orEmpty().mapNotNull { it.string })
    }

    private fun box(value: JsonValue?): DashedBorderRules.Box {
        val r = value?.get("radii")
        return DashedBorderRules.Box(
            value?.get("width")?.number ?: 0.0, value?.get("height")?.number ?: 0.0, value?.get("border")?.number ?: 0.0,
            DashedBorderRules.Radii(r?.get("topStart")?.number ?: 0.0, r?.get("topEnd")?.number ?: 0.0, r?.get("bottomEnd")?.number ?: 0.0, r?.get("bottomStart")?.number ?: 0.0),
        )
    }

    @Test fun lines() {
        val rows = Contract.rows(dash, "lineCases")
        assertEquals(6, rows.size)
        for (row in rows) {
            val line = DashedBorderRules.line(box(row["box"]))
            val expect = row["expect"]
            assertEquals(row.name(), expect?.get("perimeter")?.number ?: Double.NaN, line.perimeter, 1e-5)
            assertEquals(row.name(), expect?.get("count")?.number?.toInt(), line.count)
            assertEquals(row.name(), expect?.get("dash")?.number ?: Double.NaN, line.dash, 1e-5)
            assertEquals(row.name(), expect?.get("gap")?.number ?: Double.NaN, line.gap, 1e-5)
            val want = expect?.get("radii")
            assertEquals(row.name(), want?.get("topStart")?.number ?: Double.NaN, line.radii.topStart, 1e-5)
            assertEquals(row.name(), want?.get("topEnd")?.number ?: Double.NaN, line.radii.topEnd, 1e-5)
            assertEquals(row.name(), want?.get("bottomEnd")?.number ?: Double.NaN, line.radii.bottomEnd, 1e-5)
            assertEquals(row.name(), want?.get("bottomStart")?.number ?: Double.NaN, line.radii.bottomStart, 1e-5)
        }
    }

    @Test fun paths() {
        val rows = Contract.rows(dash, "pathCases")
        assertEquals(2, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.string, DashedBorderRules.pathString(box(row["box"])))
    }

    @Test fun styleWords() {
        val rows = Contract.rows(dash, "styleCases")
        assertEquals(3, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.string == "dashed", DashedBorderRules.isDashed(row["border"]))
    }

    @Test fun dashRules() {
        for (row in Contract.rows(dash, "ruleCases")) {
            when (row["rule"]?.string) {
                "dash" -> assertEquals(row["expect"]?.number, DashedBorderRules.DASH)
                "gap" -> assertEquals(row["expect"]?.number, DashedBorderRules.GAP)
                else -> assertTrue(row.name(), row["expect"] != null)
            }
        }
        val block = Contract.json("screen-schema.json")["x-style"]?.get("layout")?.get("borderDash")
        assertEquals(block?.get("dash")?.number, DashedBorderRules.DASH)
        assertEquals(block?.get("gap")?.number, DashedBorderRules.GAP)
        assertEquals(listOf(DashedBorderRules.WORD), block?.get("words")?.array.orEmpty().mapNotNull { it.string })
    }
}
