package com.hotlayout

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.widgets.BesidePart
import com.hotlayout.render.widgets.ButtonGeometry
import com.hotlayout.render.widgets.IconBesideTextRules
import com.hotlayout.render.widgets.IconBoxState
import com.hotlayout.support.Contract
import com.hotlayout.support.name
import com.hotlayout.text.TextHeard
import com.hotlayout.theme.HotlayoutThemeSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «An icon beside a text», the button sizes and the icon button against `icon-text/icon-text-cases.json`. */
class IconTextCasesTest {
    private val theme = HotlayoutThemeSet.builtIn.theme()
    private val table get() = Contract.json("fixtures/icon-text/icon-text-cases.json")

    private fun parts(value: JsonValue?) = value?.array.orEmpty().mapNotNull { v -> BesidePart.entries.firstOrNull { it.word == v.string } }

    private fun order(widget: String, props: JsonObject, rightToLeft: Boolean, scope: Map<String, JsonValue> = emptyMap()): Triple<com.hotlayout.render.widgets.IconResolution, List<BesidePart>, List<BesidePart>> {
        val icon = IconBesideTextRules.resolve(props, "icon", scope)
        // A list row always puts the icon after the text.
        val position = if (widget == "ListTile") "end" else props["iconPosition"]?.string
        val logical = IconBesideTextRules.logicalOrder(icon, position)
        return Triple(icon, logical, IconBesideTextRules.visualOrder(logical, rightToLeft))
    }

    @Test fun orderCases() {
        val rows = Contract.rows(table, "orderCases")
        assertEquals(17, rows.size)
        for (row in rows) {
            val rtl = row["direction"]?.string == "rtl"
            val (icon, logical, visual) = order(row["widget"]?.string ?: "", row["props"]?.obj ?: JsonObject(), rtl)
            val expect = row["expect"]
            assertEquals("logical: ${row.name()}", parts(expect?.get("logical")), logical)
            assertEquals("visual: ${row.name()}", parts(expect?.get("visual")), visual)
            assertEquals("drawn: ${row.name()}", expect?.get("drawn")?.bool, icon.box == IconBoxState.Drawn)
            assertEquals("mirrored: ${row.name()}", expect?.get("mirrored")?.bool, icon.isMirrored(rtl))
        }
    }

    @Test fun glyphCases() {
        val rows = Contract.rows(table, "glyphCases")
        assertEquals(5, rows.size)
        val gap = theme.spacing[IconBesideTextRules.GAP_TOKEN] ?: -1.0
        for (row in rows) {
            val typography = theme.typography.getValue(row["typography"]?.string ?: "")
            val (glyph, inline, block) = IconBesideTextRules.glyphBox(typography)
            val expect = row["expect"]
            fun near(key: String, actual: Double) = assertEquals("$key: ${row.name()}", expect?.get(key)?.number ?: -1.0, actual, 0.01)
            near("glyph", glyph)
            near("boxInline", inline)
            near("boxBlock", block)
            near("gap", gap)
            near("addsInline", IconBesideTextRules.addsInline(typography, gap))
            near("addsBlock", maxOf(0.0, block - typography.lineBox))
        }
    }

    @Test fun buttonSizeCases() {
        val rows = Contract.rows(table, "buttonSizeCases")
        assertEquals(6, rows.size)
        for (row in rows) {
            val size = ButtonGeometry.size(row["size"]?.string, theme)
            val expect = row["expect"]
            assertEquals("paddingBlock: ${row.name()}", expect?.get("paddingBlock")?.number, size.paddingBlock)
            assertEquals("paddingInline: ${row.name()}", expect?.get("paddingInline")?.number, size.paddingInline)
            assertEquals("typography: ${row.name()}", expect?.get("typography")?.string, size.typography)
            assertEquals("block: ${row.name()}", expect?.get("block")?.number ?: -1.0, ButtonGeometry.height(size, theme, row["border"]?.number ?: 0.0), 0.01)
        }
    }

    @Test fun iconButtonCases() {
        val rows = Contract.rows(table, "iconButtonCases")
        assertEquals(5, rows.size)
        for (row in rows) {
            val geometry = ButtonGeometry.iconButton(row["size"]?.string, null, theme, row["border"]?.number ?: 0.0)
            val expect = row["expect"]
            assertEquals("side: ${row.name()}", expect?.get("side")?.number ?: -1.0, geometry.side, 0.01)
            assertEquals("glyph: ${row.name()}", expect?.get("glyph")?.number ?: -1.0, geometry.glyph, 0.01)
            assertEquals("padding: ${row.name()}", expect?.get("padding")?.number ?: -1.0, geometry.padding, 0.01)
        }
    }

    @Test fun nameCases() {
        val rows = Contract.rows(table, "nameCases")
        assertEquals(4, rows.size)
        for (row in rows) assertEquals(row.name(), row["expect"]?.string, ButtonGeometry.iconButtonName(row["label"]?.string ?: "", row["icon"]))
    }

    @Test fun silentText() {
        for (silent in listOf("", "   ", "\u200B", "\uFE0F", "\u3164", "\u2800", "\u00A0", "\u200B \u200D")) assertTrue(silent, TextHeard.isSilent(silent))
        for (heard in listOf("a", "é", "e\u0301", "👍\uFE0F", "٣")) assertFalse(heard, TextHeard.isSilent(heard))
    }

    @Test fun iconPathCases() {
        val rows = Contract.rows(table, "iconPathCases")
        assertEquals(22, rows.size)
        for (row in rows) {
            val widget = row["widget"]?.string ?: ""
            val props = row["props"]?.obj ?: JsonObject()
            val scope = Contract.scope(row["data"])
            val expect = row["expect"]
            val icon = IconBesideTextRules.resolve(props, if (widget == "Icon") "name" else "icon", scope)
            // `null` in the table is «the node has no icon at all».
            val resolved = expect?.get("resolved")?.takeUnless { it.isNull }
            assertEquals("resolved: ${row.name()}", resolved, icon.resolved)
            assertEquals("box: ${row.name()}", expect?.get("box")?.string, icon.box.word)
            assertEquals("mirrored: ${row.name()}", expect?.get("mirrored")?.bool, icon.isMirrored(true))
            expect?.get("logical")?.let { assertEquals("logical: ${row.name()}", parts(it), order(widget, props, false, scope).second) }
        }
    }
}
