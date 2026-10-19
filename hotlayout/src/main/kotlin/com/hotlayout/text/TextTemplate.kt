package com.hotlayout.text

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutReport
import com.hotlayout.model.ReportSink

/** The language of a string: the locale-rule answers and the translation table of its place. */
public class TemplateLanguage(
    /** The shown translation language (`translation`); `null` for a document without localization. */
    public val translationLocale: String?,
    /** The document's source language. */
    public val sourceLocale: String?,
    /**
     * The translation in the shown language by a path INSIDE the value (`template`,
     * `values.unit.forms.few`); the empty path is the literal itself.
     */
    public val translated: (String) -> String?,
) {
    internal val showsSource: Boolean get() = translationLocale == sourceLocale

    public companion object {
        public val None: TemplateLanguage = TemplateLanguage(null, null) { null }
    }
}

/** A piece of a string's text: what is written and from which declared place. */
public data class TemplateRun(
    val text: String,
    /** The template value the piece comes from; `null` is the literal text of the string. */
    val place: String?,
    val part: FormatPart? = null,
)

/** Strings of a document: a literal, a binding or a template, and how each becomes text. */
public object BindableText {
    private const val NAME_PATTERN = "[a-z][A-Za-z0-9]{0,23}"
    private val placeholder = Regex("\\{($NAME_PATTERN)\\}")
    private val numericFallback = Regex("^-?[0-9]+(\\.[0-9]+)?$")

    /** The text of any document string. */
    public fun text(value: JsonValue?, language: TemplateLanguage, environment: TextEnvironment, sink: ReportSink? = null, location: String = "text"): String =
        runs(value, language, environment, sink, location).joinToString("") { it.text }

    /** The text as runs (composed text): pieces in the logical order of the shown string. */
    public fun runs(value: JsonValue?, language: TemplateLanguage, environment: TextEnvironment, sink: ReportSink? = null, location: String = "text"): List<TemplateRun> {
        val string = value?.obj
        if (string == null) {
            // A string where a bindable string stands is read as a literal: an older shape.
            return value?.string?.let { listOf(TemplateRun(it, null)) } ?: emptyList()
        }
        return when (string["kind"]?.string) {
            "literal" -> {
                val own = asText(string["value"])
                val translated = if (language.showsSource) null else language.translated("")
                listOf(TemplateRun(translated ?: own, null))
            }
            "binding" -> boundPieces(string, environment).map { TemplateRun(it.text, null, it.part) }
            "template" -> templateRuns(string, language, environment, sink, location)
            else -> {
                // A string of an unfamiliar kind is drawn empty (`unknownStringKind: 'render-empty'`).
                sink?.add(HotlayoutReport.Kind.IgnoredInput, location, "a string of an unknown kind is drawn empty")
                emptyList()
            }
        }
    }

    /**
     * The text of ONE binding. No data, an empty string or a value that is not a string, number or
     * flag gives the fallback, which is never formatted (`emptyBoundString`).
     */
    public fun boundPieces(binding: JsonObject, environment: TextEnvironment): List<FormattedPiece> {
        val fallback = asText(binding["fallback"])
        val path = binding["path"]?.string
        val raw = path?.let { environment.scope[it] } ?: return listOf(FormattedPiece(fallback))
        when (raw) {
            JsonValue.Null, is JsonValue.Arr, is JsonValue.Obj -> return listOf(FormattedPiece(fallback))
            is JsonValue.Str -> if (raw.value.isEmpty()) return listOf(FormattedPiece(fallback))
            else -> Unit
        }
        return BindingFormat.pieces(raw, binding, environment) ?: listOf(FormattedPiece(asText(raw)))
    }

    /** A document scalar as text: a string as is, a number in its shortest form, a flag as `true` / `false`. */
    public fun asText(value: JsonValue?): String = when (value) {
        is JsonValue.Str -> value.value
        is JsonValue.Num -> DecimalText.javaScriptNumber(value.value)
        is JsonValue.Bool -> if (value.value) "true" else "false"
        else -> ""
    }

    private fun templateRuns(value: JsonObject, language: TemplateLanguage, environment: TextEnvironment, sink: ReportSink?, location: String): List<TemplateRun> {
        val translated = if (language.showsSource) null else language.translated("template")
        val template = translated ?: value["template"]?.string
        if (template == null) {
            sink?.add(HotlayoutReport.Kind.IgnoredInput, "$location: template", "the template is not a string; the text is drawn empty")
            return emptyList()
        }
        if (!template.contains('{')) return listOf(TemplateRun(template, null))
        val valuesRaw = value["values"]
        if (valuesRaw?.obj == null) {
            sink?.add(HotlayoutReport.Kind.IgnoredInput, "$location: values", "the values of a template are not an object; every placeholder is drawn as plain text")
        }
        val values = valuesRaw?.obj ?: JsonObject()
        val cache = HashMap<String, List<FormattedPiece>>()
        val runs = ArrayList<TemplateRun>()
        var cursor = 0
        for (match in placeholder.findAll(template)) {
            val name = match.groupValues[1]
            // Only a declared value is a place; anything else stays literal, braces included.
            if (!values.containsKey(name)) continue
            runs.add(TemplateRun(template.substring(cursor, match.range.first), null))
            val pieces = cache.getOrPut(name) { valuePieces(name, values, language, environment, sink, location) }
            for (piece in pieces) runs.add(TemplateRun(piece.text, name, piece.part))
            cursor = match.range.last + 1
        }
        runs.add(TemplateRun(template.substring(cursor), null))
        return runs
    }

    private enum class ValueKind { Binding, Plural, Word }

    /** A template value the reader understands; anything else leaves its place empty. */
    private fun kind(entry: JsonValue?): ValueKind? {
        if (entry?.obj == null) return null
        return when (entry["kind"]?.string) {
            "text" -> if (entry["text"]?.string != null) ValueKind.Word else null
            "binding" -> if (entry["path"]?.string != null && entry["fallback"]?.string != null) ValueKind.Binding else null
            "plural" -> {
                val forms = entry["forms"]?.obj
                if (entry["of"]?.string != null && forms != null && forms["other"]?.string != null) ValueKind.Plural else null
            }
            else -> null
        }
    }

    private fun valuePieces(name: String, values: JsonObject, language: TemplateLanguage, environment: TextEnvironment, sink: ReportSink?, location: String): List<FormattedPiece> {
        val entry = values[name]
        return when (kind(entry)) {
            ValueKind.Binding -> boundPieces(entry!!.obj!!, environment)
            ValueKind.Word -> {
                val own = entry?.get("text")?.string ?: ""
                listOf(FormattedPiece((if (language.showsSource) null else language.translated("values.$name.text")) ?: own))
            }
            ValueKind.Plural -> listOf(FormattedPiece(pluralText(name, entry!!.obj!!, values, language, environment)))
            null -> {
                val known = entry?.get("kind")?.string in setOf("binding", "plural", "text")
                val reason = when {
                    entry?.obj == null -> "a template value is not an object; its place is drawn empty"
                    known -> "a template value of kind “${entry["kind"]?.string ?: ""}” does not have the shape of that kind; its place is drawn empty"
                    else -> "a template value of an unknown kind; its place is drawn empty"
                }
                sink?.add(HotlayoutReport.Kind.IgnoredInput, "$location: values.$name", reason)
                listOf(FormattedPiece(""))
            }
        }
    }

    private fun pluralText(name: String, entry: JsonObject, values: JsonObject, language: TemplateLanguage, environment: TextEnvironment): String {
        // The language of the SHOWN forms: translated `other` brings the translation's forms and
        // rules; otherwise the document's forms with the source rules.
        val forms = HashMap<PluralCategory, String>()
        val other: String
        val rulesLocale: String?
        val translatedOther = if (language.showsSource) null else language.translated("values.$name.forms.other")
        if (translatedOther != null) {
            for (category in PluralCategory.entries) {
                if (category == PluralCategory.Other) continue
                language.translated("values.$name.forms.${category.word}")?.let { forms[category] = it }
            }
            other = translatedOther
            rulesLocale = language.translationLocale
        } else {
            val documentForms = entry["forms"]?.obj ?: JsonObject()
            for (category in PluralCategory.entries) {
                if (category == PluralCategory.Other) continue
                documentForms[category.word]?.string?.let { forms[category] = it }
            }
            other = documentForms["other"]?.string ?: ""
            rulesLocale = language.sourceLocale
        }
        // The number comes only from a BINDING of the same template (`pluralWithoutNumber`).
        val target = entry["of"]?.string ?: return other
        val binding = values[target]
        if (kind(binding) != ValueKind.Binding) return other
        val count = countOf(binding!!.obj!!, environment) ?: return other
        return forms[PluralRules.category(count, rulesLocale)] ?: other
    }

    private fun countOf(binding: JsonObject, environment: TextEnvironment): Double? {
        val raw = binding["path"]?.string?.let { environment.scope[it] }
        val isTextLike = when (raw) {
            is JsonValue.Str -> raw.value.isNotEmpty()
            is JsonValue.Num, is JsonValue.Bool -> true
            else -> false
        }
        if (!isTextLike || raw == null) {
            // No data: the fallback is on screen, and the word agrees with what is shown.
            val fallback = binding["fallback"]?.string ?: return null
            if (!numericFallback.matches(fallback)) return null
            val number = fallback.toDoubleOrNull() ?: return null
            return BindingFormat.numericValue(JsonValue.Num(number), null, null)
        }
        val formatValue = binding["format"]
        val format = formatValue?.string
        if (format != null && format !in BindingFormat.countable) return null
        if (formatValue != null && format == null) return null
        return BindingFormat.numericValue(raw, format, environment.now, binding["unit"])
    }
}

/**
 * The shown text (`x-style.layout.shownText`): runs of space, tab, LF and CR become one space, the
 * edges are trimmed, U+00A0 and every other character stay.
 */
public object ShownText {
    public fun of(text: String): String {
        val result = StringBuilder()
        var pendingSpace = false
        for (char in text) {
            if (char == ' ' || char == '\t' || char == '\n' || char == '\r') {
                pendingSpace = result.isNotEmpty()
            } else {
                if (pendingSpace) result.append(' ')
                pendingSpace = false
                result.append(char)
            }
        }
        return result.toString()
    }
}

/**
 * Text nobody can see or hear (`heard-text`): spaces, format and control characters, combining
 * marks without a base, and the fillers that Unicode counts as letters.
 */
public object TextHeard {
    public fun isSilent(text: String): Boolean = text.codePoints().allMatch(::isSilentCodePoint)

    private fun isSilentCodePoint(point: Int): Boolean {
        if (Character.isWhitespace(point) || Character.isSpaceChar(point)) return true
        when (Character.getType(point).toByte()) {
            Character.FORMAT, Character.CONTROL, Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> return true
        }
        return point in setOf(0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800)
    }
}
