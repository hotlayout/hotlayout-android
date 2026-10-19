package com.hotlayout

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.widgets.LineMetric
import com.hotlayout.render.widgets.RichText
import com.hotlayout.render.widgets.TextEmphasis
import com.hotlayout.render.widgets.TextEmphasisRules
import com.hotlayout.render.widgets.TextGradientRules
import com.hotlayout.render.widgets.TextSpanRun
import com.hotlayout.render.widgets.TextSpanStyle
import com.hotlayout.render.widgets.TextSpans
import com.hotlayout.support.Contract
import com.hotlayout.support.Mismatch
import com.hotlayout.support.assertOnlyKnownDifferences
import com.hotlayout.support.name
import com.hotlayout.text.BindableText
import com.hotlayout.text.DateText
import com.hotlayout.text.TemplateLanguage
import com.hotlayout.text.TextEnvironment
import com.hotlayout.theme.HotlayoutThemeSet
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.TimeZone

/**
 * The shared table of composed text and text emphasis (`fixtures/rich-text/rich-text-cases.json`):
 * pieces of a string with their own style, the pitch of the lines, slant and line, and the gradient
 * on the glyphs. The groups that are verdicts of the builder's validator are named, not run.
 */
@RunWith(RobolectricTestRunner::class)
class TextRichCasesTest {
    companion object {
        val table get() = Contract.json("fixtures/rich-text/rich-text-cases.json")

        val notAReaderRule = mapOf("textGradientDocumentCases" to "codes of the validator for documents; a reader meets the field only through textGradientCases")
        val runGroups = setOf("cases", "struts", "emphasis", "emphasisStruts", "amountPartCases", "amountPartStruts", "textGradientCases", "textGradientPartCases")

        /** Rows where the platform's CLDR data writes other characters than the table (see NOTES.md). */
        //  - plain `ar` writes Arabic-Indic digits in Android's ICU; the table writes Latin ones and marks the row `mayDiffer`
        //    (the order, the isolation marks, the pieces and their parts are the table's).
        val platformDiffers: Map<String, String> = mapOf(
            "amountPartCases: rtl: the isolation marks sit inside the first and the last piece" to
                "\u2066\u0662\u0664\u066C\u0665\u0668\u0660\u066B\u0667\u0665\u00A0\u20B4\u2069",
        )
    }

    internal class Outcome(val text: String, val runs: List<TextSpanRun>, val styles: Map<String, TextSpanStyle>)

    private fun outcome(row: JsonObject): Outcome {
        val translations = row["translations"]?.obj ?: JsonObject()
        val language = TemplateLanguage(row["translationLocale"]?.string, row["sourceLocale"]?.string) { translations[it]?.string }
        val fallback = row["translationLocale"]?.string ?: row["sourceLocale"]?.string
        val rtl = row["direction"]?.string?.let { it == "rtl" }
        val base = TextEnvironment(Contract.scope(row["data"]), row["formattingLocale"]?.string, fallback, TimeZone.getTimeZone("UTC"), DateText.moment(row["now"]) ?: DateText.moment(table["now"]))
        val environment = if (rtl == null) base else base.copy(rightToLeft = rtl)
        val runs = BindableText.runs(row["content"], language, environment)
        val active = TextSpans.active(row["content"], TextSpans.read(row["spans"]))
        return Outcome(runs.joinToString("") { it.text }, TextSpans.runs(runs, active), active)
    }

    private fun check(group: String, expectedLine: (JsonObject, JsonObject, TextSpanRun, Outcome) -> String?): List<Mismatch> {
        val mismatches = ArrayList<Mismatch>()
        for (row in Contract.rows(table, group)) {
            val name = "$group: ${row.name()}"
            val outcome = outcome(row)
            val expected = row["expected"]?.obj ?: JsonObject()
            if (outcome.text != expected["text"]?.string) {
                mismatches.add(Mismatch(name, expected["text"]?.string ?: "", outcome.text))
                continue
            }
            expected["styles"]?.let { styles ->
                val read = JsonValue.Obj(JsonObject(outcome.styles.keys.sorted().map { it to outcome.styles.getValue(it).json }))
                assertEquals("styles read: $name", styles, read)
            }
            val runs = expected["runs"]?.array.orEmpty().mapNotNull { it.obj }
            if (outcome.runs.size != runs.size) {
                mismatches.add(Mismatch(name, "${runs.size} runs", "${outcome.runs.size} runs: ${outcome.runs.map { it.text }}"))
                continue
            }
            for ((run, want) in outcome.runs.zip(runs)) {
                val problem = expectedLine(row, want, run, outcome)
                if (problem != null) mismatches.add(Mismatch(name, problem.substringBefore('|'), problem.substringAfter('|')))
            }
        }
        return mismatches
    }

    @Test fun everyGroupIsRunOrNamed() {
        val groups = Contract.groups(table)
        assertEquals(groups, runGroups + notAReaderRule.keys)
    }

    /** Only rows whose answer the table says may depend on platform data stand in the list. */
    @Test fun exceptionsAreFlaggedRows() {
        val flagged = Contract.rows(table, "amountPartCases").filter { it["mayDiffer"] != null }.map { "amountPartCases: ${it.name()}" }.toSet()
        assertEquals(platformDiffers.keys, platformDiffers.keys.intersect(flagged))
    }

    @Test fun tableShape() {
        val counts = mapOf("cases" to 34, "struts" to 10, "emphasis" to 38, "emphasisStruts" to 6, "amountPartCases" to 19, "amountPartStruts" to 5, "textGradientCases" to 9, "textGradientPartCases" to 3)
        for ((group, count) in counts) assertEquals(group, count, Contract.rows(table, group).size)
    }

    @Test fun cases() = assertOnlyKnownDifferences(
        check("cases") { _, want, run, _ ->
            when {
                run.text != want["text"]?.string -> "${want["text"]?.string}|${run.text}"
                run.span != want["span"]?.string -> "place ${want["span"]?.string}|${run.span}"
                else -> null
            }
        },
        platformDiffers, "cases",
    )

    @Test fun amountPartCases() = assertOnlyKnownDifferences(
        check("amountPartCases") { _, want, run, _ ->
            when {
                run.text != want["text"]?.string -> "${want["text"]?.string}|${run.text}"
                run.span != want["span"]?.string -> "place ${want["span"]?.string}|${run.span}"
                run.part?.word != want["part"]?.string -> "part ${want["part"]?.string}|${run.part?.word}"
                else -> null
            }
        },
        platformDiffers, "amountPartCases",
    )

    @Test fun emphasis() = assertOnlyKnownDifferences(
        check("emphasis") { row, want, run, outcome ->
            var emphasis = TextEmphasisRules.inherit(TextEmphasis.Root, row["inherited"]?.obj)
            emphasis = TextEmphasisRules.inherit(emphasis, row["style"]?.obj)
            val drawn = TextEmphasisRules.run(emphasis, TextSpans.style(run, outcome.styles))
            when {
                run.text != want["text"]?.string -> "${want["text"]?.string}|${run.text}"
                run.span != want["span"]?.string -> "place ${want["span"]?.string}|${run.span}"
                drawn.fontStyle != want["fontStyle"]?.string -> "slant ${want["fontStyle"]?.string}|${drawn.fontStyle}"
                drawn.textDecoration != want["textDecoration"]?.string -> "line ${want["textDecoration"]?.string}|${drawn.textDecoration}"
                drawn.weight?.toDouble() != want["weight"]?.number -> "weight ${want["weight"]?.number}|${drawn.weight}"
                else -> null
            }
        },
        platformDiffers, "emphasis",
    )

    private fun metric(value: JsonValue?) = LineMetric(value?.get("fontSize")?.number ?: 0.0, value?.get("lineHeight")?.number ?: 0.0)

    private fun struts(group: String) {
        for (row in Contract.rows(table, group)) {
            val spans = (row["spans"]?.obj ?: JsonObject()).associate { it.first to metric(it.second) }
            val strut = RichText.strut(metric(row["base"]), spans)
            assertEquals("strut: ${row.name()}", row["expected"]?.get("span")?.string, strut.span)
            assertEquals("pitch: ${row.name()}", row["expected"]?.get("lineBlock")?.number ?: -1.0, strut.lineBlock, 1e-9)
        }
    }

    @Test fun struts() = struts("struts")
    @Test fun emphasisStruts() = struts("emphasisStruts")
    @Test fun amountPartStruts() = struts("amountPartStruts")

    @Test fun textGradientCases() {
        val theme = HotlayoutThemeSet.builtIn.theme()
        for (row in Contract.rows(table, "textGradientCases")) {
            var style = row["style"]?.obj ?: JsonObject()
            val met = row["met"]?.array.orEmpty().map { it.bool ?: false }
            for ((index, item) in row["styleWhen"]?.array.orEmpty().withIndex()) {
                if (index >= met.size || !met[index]) continue
                for ((key, value) in item["style"]?.obj ?: JsonObject()) style = style.with(key, value)
                break
            }
            val reference = TextGradientRules.reference(row["type"]?.string ?: "", style)
            val want = row["gradient"]?.takeIf { !it.isNull }
            assertEquals(row.name(), want, reference)
            val token = reference?.string
            if (token != null && theme.gradient[token] == null) assertNull("an unknown token gives the colour: ${row.name()}", StyleResolver(theme).gradient(reference))
        }
    }

    @Test fun textGradientPartCases() {
        for (row in Contract.rows(table, "textGradientPartCases")) {
            val gradient = row["gradient"]?.isNull == false
            val spans = TextSpans.read(row["spans"])
            for ((key, want) in row["parts"]?.obj ?: JsonObject()) {
                assertEquals("${row.name()}: «$key»", want.bool, TextGradientRules.takes(gradient, if (key.isEmpty()) null else spans[key]))
            }
        }
    }

    @Test fun theShownTextRuleOverRunsKeepsTheStylesOfTheTextItTrims() {
        val runs = listOf(TextSpanRun("  a  ", null), TextSpanRun(" b\n", "x"), TextSpanRun("  ", null))
        assertEquals(listOf(TextSpanRun("a ", null), TextSpanRun("b", "x")), TextSpans.shown(runs))
        assertTrue(TextSpans.shown(listOf(TextSpanRun(" \n ", null))).isEmpty())
    }
}
