package com.hotlayout

import com.hotlayout.render.widgets.ListTileRules
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The parts of a list row against `list-tile/list-tile-cases.json`. `documentCases` are verdicts of the validator. */
class ListTileCasesTest {
    private val table get() = Contract.json("fixtures/list-tile/list-tile-cases.json")

    private fun same(left: JsonValue?, right: JsonValue?): Boolean = when {
        left is JsonValue.Num && right is JsonValue.Num -> Math.abs(left.value - right.value) < 1e-9
        left is JsonValue.Obj && right is JsonValue.Obj ->
            left.fields.keys.toSet() == right.fields.keys.toSet() && left.fields.keys.all { same(left.fields[it], right.fields[it]) }
        else -> left == right
    }

    private fun number(value: JsonValue?): Double = value?.number ?: Double.NaN

    @Test fun lineLimits() {
        val rows = Contract.rows(table, "lineCases")
        assertEquals(10, rows.size)
        for (row in rows) {
            val shown = ListTileRules.shownLines(number(row["natural"]).toInt(), row["limit"])
            assertEquals(row.name(), number(row["expected"]?.get("lines")).toInt(), shown.first)
            assertEquals(row.name(), row["expected"]?.get("cut")?.bool, shown.second)
        }
    }

    @Test fun tileUnderTheLeadingIcon() {
        val rows = Contract.rows(table, "tileCases")
        assertEquals(13, rows.size)
        for (row in rows) {
            val tile = ListTileRules.tile(row["leading"])
            val expected = row["expected"]
            if (expected == null || expected.isNull) {
                assertNull(row.name(), tile)
                continue
            }
            assertTrue("tile: ${row.name()}", tile != null)
            assertTrue("color: ${row.name()}", same(tile!!.color, expected["color"]))
            assertTrue("radius: ${row.name()}", same(tile.radius, expected["radius"]))
            assertEquals("size: ${row.name()}", number(expected["size"]), tile.size, 1e-9)
        }
    }

    @Test fun boxOfTheLeadingIcon() {
        val rows = Contract.rows(table, "leadingBoxCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val box = ListTileRules.leadingBox(row["leading"], number(row["glyph"]?.get("inline")), number(row["glyph"]?.get("block")))
            val expected = row["expected"]
            assertEquals(row.name(), number(expected?.get("inline")), box.inline, 1e-9)
            assertEquals(row.name(), number(expected?.get("block")), box.block, 1e-9)
            assertEquals(row.name(), number(expected?.get("glyphInline")), box.glyphInline, 1e-9)
            assertEquals(row.name(), number(expected?.get("glyphBlock")), box.glyphBlock, 1e-9)
        }
    }

    @Test fun trailingColumn() {
        val rows = Contract.rows(table, "trailingColumnCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            val caption = row["caption"]?.takeIf { !it.isNull }?.let { number(it["inline"]) to number(it["line"]) }
            val column = ListTileRules.trailingColumn(number(row["textInline"]), number(row["textLine"]), caption)
            val expected = row["expected"]
            assertEquals(row.name(), number(expected?.get("inline")), column.inline, 1e-9)
            assertEquals(row.name(), number(expected?.get("block")), column.block, 1e-9)
            assertEquals(row.name(), number(expected?.get("textStart")), column.textStart, 1e-9)
            if (expected?.get("captionStart")?.isNull == true) assertNull(row.name(), column.captionStart)
            else assertEquals(row.name(), number(expected?.get("captionStart")), column.captionStart ?: Double.NaN, 1e-9)
        }
    }

    @Test fun threeColumnsOfTheRow() {
        val rows = Contract.rows(table, "rowCases")
        assertEquals(11, rows.size)
        fun column(value: JsonValue?) = value?.takeIf { !it.isNull }?.let { number(it["inline"]) to number(it["block"]) }
        fun check(rect: ListTileRules.Rect?, expected: JsonValue?, label: String) {
            if (expected == null || expected.isNull) return assertNull(label, rect)
            assertEquals(label, number(expected["x"]), rect!!.x, 1e-9)
            assertEquals(label, number(expected["y"]), rect.y, 1e-9)
            assertEquals(label, number(expected["width"]), rect.width, 1e-9)
            assertEquals(label, number(expected["height"]), rect.height, 1e-9)
        }
        for (row in rows) {
            val rects = ListTileRules.rowRects(
                number(row["rowInline"]), number(row["gap"]), column(row["leading"]), column(row["trailing"]),
                number(row["bodyBlock"]), row["direction"]?.string == "rtl",
            )
            val expected = row["expected"]
            assertEquals(row.name(), number(expected?.get("block")), rects.block, 1e-9)
            check(rects.leading, expected?.get("leading"), "leading: ${row.name()}")
            check(rects.body, expected?.get("body"), "body: ${row.name()}")
            check(rects.trailing, expected?.get("trailing"), "trailing: ${row.name()}")
        }
    }

    private fun named(context: JsonValue?) = ListTileRules.NamedColor(context?.get("color") ?: JsonValue.Null, context?.get("colorNamed")?.bool == true)

    @Test fun looksOfTheParts() {
        val rows = Contract.rows(table, "lookCases")
        assertEquals(17, rows.size)
        for (row in rows) {
            val met = row["met"]?.array.orEmpty().mapNotNull { it.string }.toSet()
            val isMet: (JsonValue) -> Boolean = { (it["path"]?.string ?: "") in met }
            val context = named(row["context"])
            val text = ListTileRules.trailingTextLook(row["trailing"], context, isMet)
            val caption = ListTileRules.captionLook(row["trailing"], context, isMet)
            val expected = row["expected"]
            assertTrue("text colour: ${row.name()}: ${text.color}", same(text.color, expected?.get("text")?.get("color")))
            assertTrue("text typography: ${row.name()}", same(text.typography, expected?.get("text")?.get("typography")))
            assertTrue("caption colour: ${row.name()}: ${caption.color}", same(caption.color, expected?.get("caption")?.get("color")))
            assertTrue("caption typography: ${row.name()}", same(caption.typography, expected?.get("caption")?.get("typography")))
        }
    }

    @Test fun colourOfTheLeadingIcon() {
        val rows = Contract.rows(table, "leadingColorCases")
        assertEquals(6, rows.size)
        for (row in rows) assertTrue(row.name(), same(ListTileRules.leadingIconColor(row["leading"], named(row["context"])), row["expected"]))
    }

    @Test fun colourOfTheTrailingText() {
        val rows = Contract.rows(Contract.json("fixtures/icon-text/icon-text-cases.json"), "colorCases")
        assertEquals(2, rows.size)
        for (row in rows) {
            val ref = row["named"]
            val context = ListTileRules.NamedColor(ref ?: JsonValue.Null, ref != null && !ref.isNull)
            val look = ListTileRules.trailingTextLook(JsonValue.of(mapOf("kind" to "text")), context) { false }
            // The icon beside the text takes the colour of the text: one value for both.
            assertEquals(row.name(), row["expect"]?.get("text"), look.color)
            assertEquals(row.name(), row["expect"]?.get("icon"), look.color)
        }
    }

    @Test fun documentVerdictsAreNotAReadersConcern() {
        assertEquals(21, Contract.rows(table, "documentCases").size)
    }
}
