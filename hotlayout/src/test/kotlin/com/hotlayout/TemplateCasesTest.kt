package com.hotlayout

import com.hotlayout.json.JsonObject
import com.hotlayout.support.Contract
import com.hotlayout.support.Mismatch
import com.hotlayout.support.assertOnlyKnownDifferences
import com.hotlayout.support.name
import com.hotlayout.text.BindableText
import com.hotlayout.text.DateText
import com.hotlayout.text.NumberText
import com.hotlayout.text.ShownText
import com.hotlayout.text.TemplateLanguage
import com.hotlayout.text.TextEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.TimeZone

/**
 * The shared table of text templates, number formats, money, dates and plurals
 * (`fixtures/template/template-cases.json`), run through the rule of this reader.
 *
 * Rows marked `mayDiffer` depend on the CLDR data of the platform. Where Android's ICU data gives
 * other characters than the table records, the row is listed in [platformDiffers] with what this
 * reader shows; every other row must match character for character.
 */
@RunWith(RobolectricTestRunner::class)
class TemplateCasesTest {
    companion object {
        val table get() = Contract.json("fixtures/template/template-cases.json")

        /** Rows where the platform's CLDR data writes other characters than the table, by name. */
        //  - U+202F before PM and before the year marks (en, uk, ru, kk), as on Apple platforms;
        //  - plain `ar` writes Arabic-Indic digits in Android's ICU (the table writes Latin ones and
        //    says that depends on the CLDR version); the order, signs and marks are the table's.
        val platformDiffers: Map<String, String> = mapOf(
            "moneyCases: rtl: a signed number is one left-to-right piece — U+2066 … U+2069 around it" to
                "\u2066+\u0661\u0662\u0660\u2069",
            "moneyCases: rtl: a negative signed number" to
                "\u2066\u2212\u0661\u0662\u0660\u2069",
            "moneyCases: rtl: the amount with its currency sign is one piece" to
                "\u2066\u0665\u0660\u0660\u00A0\u20B4\u2069",
            "moneyCases: rtl: the currency before the amount stays before it in the piece" to
                "\u2066+\$\u0661\u0662\u0660\u2069",
            "moneyCases: rtl: the code before the amount" to
                "\u2066JOD\u00A0\u0661\u0662\u0660\u2069",
            "moneyCases: rtl: the piece inside an Arabic sentence" to
                "\u0631\u0635\u064A\u062F\u0643 \u2066\u0665\u0660\u0660\u00A0\u20B4\u2069",
            "moneyCases: rtl WITHOUT the fields nothing changed: no isolation marks" to
                "\u0661\u0662\u0660\u00A0JOD",
            "compactCases: ar: no table of its own — the English suffix" to
                "\u0664\u0668\u066B\u0662K",
            "cases: CLARIFIED-3 dateTime joins date and time with the CLDR pattern of the language, not a space: en" to
                "Oct 31, 2026, 2:05\u202FPM",
            "cases: CLARIFIED-3 dateTime joins date and time with the CLDR pattern of the language, not a space: uk" to
                "31 \u0436\u043E\u0432\u0442. 2026\u202F\u0440., 14:05",
            "cases: CLARIFIED-3 dateTime joins date and time with the CLDR pattern of the language, not a space: ar" to
                "\u0663\u0661 \u0623\u0643\u062A\u0648\u0628\u0631 \u0662\u0660\u0662\u0666\u060C \u0662:\u0660\u0665 \u0645",
            "cases: CLARIFIED-3 dateTime joins date and time with the CLDR pattern of the language, not a space: ru" to
                "31 \u043E\u043A\u0442. 2026\u202F\u0433., 14:05",
            "datePartsCases: WITHOUT the field nothing changed: year, short month, day (uk)" to
                "15 \u0436\u043E\u0432\u0442. 2026\u202F\u0440.",
            "datePartsCases: dayMonthYear: day, month in full and year (uk)" to
                "15 \u0436\u043E\u0432\u0442\u043D\u044F 2026\u202F\u0440.",
            "datePartsCases: WITHOUT the field nothing changed: year, short month, day (ar)" to
                "\u0661\u0665 \u0623\u0643\u062A\u0648\u0628\u0631 \u0662\u0660\u0662\u0666",
            "datePartsCases: dayMonth: day and month in full, no year (ar)" to
                "\u0661\u0665 \u0623\u0643\u062A\u0648\u0628\u0631",
            "datePartsCases: weekdayDayMonth: the day of the week, day and month (ar)" to
                "\u0627\u0644\u062E\u0645\u064A\u0633\u060C \u0661\u0665 \u0623\u0643\u062A\u0648\u0628\u0631",
            "datePartsCases: dayMonthYear: day, month in full and year (ar)" to
                "\u0661\u0665 \u0623\u0643\u062A\u0648\u0628\u0631 \u0662\u0660\u0662\u0666",
            "datePartsCases: the year stands where the language puts it: Kazakh" to
                "2026\u202F\u0436. 15 \u049B\u0430\u0437\u0430\u043D",
        )

        /** Rows WITHOUT `mayDiffer` that still differ on Android, with the reason in NOTES.md. */
        val unflaggedPlatformDiffers: Set<String> = setOf(
            // The same digits of plain `ar` as the flagged rows; the English suffix (the point of the row) is there.
            "compactCases: ar: no table of its own — the English suffix",
        )

        fun text(row: JsonObject): String {
            val translations = row["translations"]?.obj ?: JsonObject()
            val language = TemplateLanguage(row["translationLocale"]?.string, row["sourceLocale"]?.string) { translations[it]?.string }
            val zone = TimeZone.getTimeZone(row["timeZone"]?.string ?: "UTC")
            val now = DateText.moment(row["now"]) ?: DateText.moment(table["now"])
            val fallback = row["translationLocale"]?.string ?: row["sourceLocale"]?.string
            val rtl = row["direction"]?.string?.let { it == "rtl" }
            val base = TextEnvironment(Contract.scope(row["data"]), row["formattingLocale"]?.string, fallback, zone, now)
            val environment = if (rtl == null) base else base.copy(rightToLeft = rtl)
            return BindableText.text(row["value"], language, environment)
        }

        /**
         * The «no locale data» rows name languages no CLDR release had when the table was written.
         * A platform that HAS the data formats them, and then only non-empty text is required.
         */
        fun platformHasDataForMissingLocale(row: JsonObject): Boolean {
            val name = row["name"]?.string ?: return false
            val locale = row["formattingLocale"]?.string ?: return false
            return name.contains("no locale data") && NumberText.hasData(locale)
        }
    }

    private fun run(group: String): List<Mismatch> = Contract.rows(table, group).mapNotNull { row ->
        val expected = row["expected"]?.string ?: ""
        val actual = text(row)
        if (actual == expected || (platformHasDataForMissingLocale(row) && actual.isNotEmpty())) null else Mismatch("$group: ${row.name()}", expected, actual)
    }

    @Test fun tableShape() {
        assertEquals(179, Contract.rows(table, "cases").size)
        assertEquals(73, Contract.rows(table, "moneyCases").size)
        assertEquals(37, Contract.rows(table, "datePartsCases").size)
        assertEquals(28, Contract.rows(table, "countdownCases").size)
        assertEquals(41, Contract.rows(table, "compactCases").size)
    }

    /** Only rows whose answer the table says may depend on platform data stand in the list. */
    @Test fun exceptionsAreFlaggedRows() {
        val flagged = HashSet<String>()
        for (group in listOf("cases", "moneyCases", "datePartsCases", "countdownCases", "compactCases")) {
            for (row in Contract.rows(table, group)) if (row["mayDiffer"] != null) flagged.add("$group: ${row.name()}")
        }
        assertEquals(unflaggedPlatformDiffers, platformDiffers.keys - flagged)
    }

    @Test fun cases() = assertOnlyKnownDifferences(run("cases"), platformDiffers, "cases")
    @Test fun moneyCases() = assertOnlyKnownDifferences(run("moneyCases"), platformDiffers, "moneyCases")
    @Test fun datePartsCases() = assertOnlyKnownDifferences(run("datePartsCases"), platformDiffers, "datePartsCases")
    @Test fun countdownCases() = assertOnlyKnownDifferences(run("countdownCases"), platformDiffers, "countdownCases")
    @Test fun compactCases() = assertOnlyKnownDifferences(run("compactCases"), platformDiffers, "compactCases")

    @Test fun shownTextCases() {
        for (row in Contract.rows(table, "shownTextCases")) {
            assertEquals(row.name(), row["expected"]?.string, ShownText.of(row["text"]?.string ?: ""))
        }
    }

    @Test fun shownTemplateCases() {
        for (row in Contract.rows(table, "shownTemplateCases")) {
            val text = text(row)
            assertEquals("before the rule: ${row.name()}", row["expected"]?.string, text)
            assertEquals("after the rule: ${row.name()}", row["shown"]?.string, ShownText.of(text))
        }
    }
}
