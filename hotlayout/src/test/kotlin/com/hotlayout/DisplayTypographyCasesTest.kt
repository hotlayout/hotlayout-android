package com.hotlayout

import com.hotlayout.support.Contract
import com.hotlayout.support.name
import com.hotlayout.theme.DisplayTypography
import com.hotlayout.theme.StyleResolver
import com.hotlayout.theme.ThemeBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Display typography against the shared table (`display-typography/display-typography-cases.json`): the effective font size after
 * the system text scale, tracking in shares of the font size, the line block, and what the reader does with a typography override.
 */
@RunWith(RobolectricTestRunner::class)
class DisplayTypographyCasesTest {
    private val table get() = Contract.json("fixtures/display-typography/display-typography-cases.json")
    private val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme())

    private fun rows(group: String, count: Int): List<JsonObject> = Contract.rows(table, group).also { assertEquals(group, count, it.size) }

    @Test fun everyGroupIsRun() {
        assertEquals(setOf("scaleCases", "trackingCases", "lineCases", "documentCases"), Contract.groups(table))
    }

    @Test fun effectiveFontSize() {
        for (row in rows("scaleCases", 44)) {
            assertEquals(row.name(), row["effective"]?.number ?: Double.NaN, DisplayTypography.scaledFontSize(row["fontSize"]?.number ?: Double.NaN, row["scale"]?.number ?: Double.NaN), 1e-6)
        }
    }

    @Test fun tracking() {
        for (row in rows("trackingCases", 10)) {
            val effective = DisplayTypography.scaledFontSize(row["fontSize"]?.number ?: Double.NaN, row["scale"]?.number ?: Double.NaN)
            assertEquals(row.name(), row["effective"]?.number ?: Double.NaN, effective, 1e-6)
            val tracking = DisplayTypography.trackingPx(row["letterSpacing"]?.number, row["letterSpacingEm"]?.number, effective)
            assertEquals(row.name(), row["trackingPx"]?.number ?: Double.NaN, tracking, 1e-6)
        }
    }

    @Test fun lineBlock() {
        for (row in rows("lineCases", 6)) {
            val block = DisplayTypography.lineBlock(row["fontSize"]?.number ?: Double.NaN, row["lineHeight"]?.number ?: Double.NaN, row["scale"]?.number ?: Double.NaN)
            assertEquals(row.name(), row["lineBlock"]?.number ?: Double.NaN, block, 1e-6)
        }
    }

    /**
     * The reader is tolerant: a size past 400 or below 8 is brought to the bound, tracking in shares to its own bounds, and a document
     * that names both kinds of tracking is read with the shares. Every row reads.
     */
    @Test fun overrideAsTheReaderTakesIt() {
        for (row in rows("documentCases", 12)) {
            val override = row["typography"]!!
            val style = resolver.typography(override)
            assertNotNull(row.name(), style)
            val token = resolver.theme.typography[override["token"]?.string ?: ""]!!
            val size = override["fontSize"]?.number
            assertEquals(row.name(), size?.coerceIn(8.0, 400.0) ?: token.fontSize, style!!.fontSize, 0.0)
            val share = override["letterSpacingEm"]?.number
            if (share != null) {
                assertEquals(row.name(), share.coerceIn(-0.1, 0.5) * style.fontSize, style.letterSpacing, 1e-9)
            } else if (override["letterSpacingEm"] == null) {
                assertEquals(row.name(), override["letterSpacing"]?.number ?: token.letterSpacing, style.letterSpacing, 0.0)
            }
        }
    }

    /** Size 240 with tracking -0.06 is -14.4 pixels, and the bound of a size in a THEME stays 96 (the theme names roles of text). */
    @Test fun posterSizeAndTheBoundOfTheTheme() {
        val poster = resolver.typography(JsonValue.parse("""{"token":"displayL","fontSize":240,"letterSpacingEm":-0.06}"""))!!
        assertEquals(240.0, poster.fontSize, 0.0)
        assertEquals(-14.4, poster.letterSpacing, 1e-9)
        assertEquals(96.0, ThemeBounds.FONT_SIZE.endInclusive, 0.0)
        val both = resolver.typography(JsonValue.parse("""{"token":"bodyS","letterSpacing":1,"letterSpacingEm":0.1}"""))!!
        assertEquals(0.1 * both.fontSize, both.letterSpacing, 1e-9)
    }
}
