package com.hotlayout

import com.hotlayout.support.Contract
import com.hotlayout.support.name
import com.hotlayout.text.TextDirectionRule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shared table of locale resolution (`fixtures/locale/locale-cases.json`). */
@RunWith(RobolectricTestRunner::class)
class LocaleCasesTest {
    @Test fun everyRow() {
        val rows = Contract.rows(Contract.json("fixtures/locale/locale-cases.json"), "cases")
        assertEquals(38, rows.size)
        for (row in rows) {
            val name = row.name()
            val answer = resolveLocale(row["requested"]?.string, row["available"]?.array.orEmpty().mapNotNull { it.string }, row["source"]?.string)
            assertEquals("translation: $name", row["translation"]?.string, answer.translation)
            assertEquals("formatting: $name", row["formatting"]?.string, answer.formatting)
            assertEquals("directionLocale: $name", row["directionLocale"]?.string, answer.directionLocale)
            assertEquals("matched: $name", row["matched"]?.bool, answer.matched)
            row["direction"]?.string?.let { direction ->
                val rtl = TextDirectionRule.isRightToLeft(answer.directionLocale) ?: false
                assertEquals("direction: $name", direction, if (rtl) "rtl" else "ltr")
            }
        }
    }
}
