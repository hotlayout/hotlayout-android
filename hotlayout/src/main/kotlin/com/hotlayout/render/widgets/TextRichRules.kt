package com.hotlayout.render.widgets

import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.text.BindingFormat
import com.hotlayout.text.FormatPart
import com.hotlayout.text.TemplateRun
import com.hotlayout.text.TextEnvironment

// Rules of composed text, text emphasis, list markers and the gradient on the glyphs. Everything
// here is a plain function of document values and numbers, so the shared case tables run against
// it without a view; the Compose drawing is in `TextRichWidget.kt`.

// ---- Emphasis ---------------------------------------------------------------------------------

/** The slant and the line of a text at a point of the tree: both words are resolved. */
internal data class TextEmphasis(val fontStyle: String = "normal", val textDecoration: String = "none") {
    companion object {
        val Root = TextEmphasis()
    }
}

/** The emphasis of one run; [weight] is the number of the part's weight word (`null`: its font style gives the weight). */
internal data class RunEmphasis(val fontStyle: String, val textDecoration: String, val weight: Int?)

internal object TextEmphasisRules {
    val weights: Map<String, Int> = mapOf("regular" to 400, "medium" to 500, "semibold" to 600, "bold" to 700)

    /** A word of `fontStyle`, or `null` (`unknownWord: 'field-not-applied'`). */
    fun fontStyle(value: JsonValue?): String? = value?.string?.takeIf { it == "normal" || it == "italic" }

    fun decoration(value: JsonValue?): String? = value?.string?.takeIf { it == "none" || it == "underline" || it == "lineThrough" }

    fun weightWord(value: JsonValue?): String? = value?.string?.takeIf { it in weights }

    /** The emphasis of a run: the part replaces each of its fields on its own. */
    fun run(text: TextEmphasis, span: TextSpanStyle?): RunEmphasis = RunEmphasis(
        span?.fontStyle ?: text.fontStyle,
        span?.textDecoration ?: text.textDecoration,
        span?.weightWord?.let(weights::get),
    )
}

// ---- Composed text ----------------------------------------------------------------------------

/** The own style of a part of a text. A `null` field is «not named»: it stays as the text has it. */
internal data class TextSpanStyle(
    val color: JsonValue? = null,
    val typography: JsonValue? = null,
    val weightWord: String? = null,
    val fontStyle: String? = null,
    val textDecoration: String? = null,
    val parts: Map<FormatPart, TextSpanStyle> = emptyMap(),
) {
    /** This style with the fields of [part] laid over it. */
    fun overlaid(part: TextSpanStyle) = TextSpanStyle(
        part.color ?: color, part.typography ?: typography, part.weightWord ?: weightWord,
        part.fontStyle ?: fontStyle, part.textDecoration ?: textDecoration,
    )

    val namesColor: Boolean get() = color != null

    /** What the reader has read, in the form the artifact and the case tables use. */
    val json: JsonValue
        get() {
            val pairs = ArrayList<Pair<String, JsonValue>>()
            color?.let { pairs.add("color" to it) }
            typography?.let { pairs.add("typography" to it) }
            weightWord?.let { pairs.add("weight" to JsonValue.Str(it)) }
            fontStyle?.let { pairs.add("fontStyle" to JsonValue.Str(it)) }
            textDecoration?.let { pairs.add("textDecoration" to JsonValue.Str(it)) }
            if (parts.isNotEmpty()) {
                val inner = FormatPart.entries.mapNotNull { part -> parts[part]?.let { part.word to it.json } }
                pairs.add("parts" to JsonValue.Obj(JsonObject(inner)))
            }
            return JsonValue.Obj(JsonObject(pairs))
        }
}

/** A piece of rich text: what is written, the place whose style it takes and, inside a number, the part. */
internal data class TextSpanRun(val text: String, val span: String?, val part: FormatPart? = null)

internal object TextSpans {
    private val tokenName = Regex("[a-z][a-zA-Z0-9]{0,39}")
    private val placeName = Regex("[a-z][A-Za-z0-9]{0,23}")
    private val hexColor = Regex("#[0-9a-fA-F]{6}")

    private fun isTokenName(value: JsonValue?) = value?.string?.let(tokenName::matches) ?: false

    /** A readable colour reference by SHAPE (whether the theme has the token is another question). */
    fun readableColor(value: JsonValue?): JsonValue? {
        if (value == null) return null
        if (isTokenName(value)) return value
        val obj = value.obj ?: return null
        val own = obj["value"]
        if (own != null && !own.isNull) {
            val hex = own.string ?: return null
            if (!hexColor.matches(hex)) return null
            val opacity = obj["opacity"]
            if (opacity != null && !opacity.isNull && opacity.number?.let { it in 0.0..1.0 } != true) return null
            return value
        }
        if (!isTokenName(obj["token"])) return null
        val opacity = obj["opacity"]?.number ?: return null
        return if (opacity in 0.0..1.0) value else null
    }

    fun readableTypography(value: JsonValue?): JsonValue? {
        if (value == null) return null
        if (isTokenName(value)) return value
        return value.takeIf { isTokenName(it.obj?.get("token")) }
    }

    /** The five fields of a style, each read on its own; `null` when none is readable. */
    private fun fields(obj: JsonObject): TextSpanStyle? {
        val style = TextSpanStyle(
            readableColor(obj["color"]), readableTypography(obj["typography"]), TextEmphasisRules.weightWord(obj["weight"]),
            TextEmphasisRules.fontStyle(obj["fontStyle"]), TextEmphasisRules.decoration(obj["textDecoration"]),
        )
        return style.takeIf { it != TextSpanStyle() }
    }

    private fun readParts(value: JsonValue?): Map<FormatPart, TextSpanStyle> {
        val obj = value?.obj ?: return emptyMap()
        val parts = LinkedHashMap<FormatPart, TextSpanStyle>()
        for ((name, entry) in obj) {
            val part = FormatPart.entries.firstOrNull { it.word == name } ?: continue
            parts[part] = entry.obj?.let(::fields) ?: continue
        }
        return parts
    }

    /** `Text.spans` as the reader reads it: tolerantly, field by field, never failing. */
    fun read(value: JsonValue?): Map<String, TextSpanStyle> {
        val obj = value?.obj ?: return emptyMap()
        val spans = LinkedHashMap<String, TextSpanStyle>()
        for ((name, entry) in obj) {
            if (!placeName.matches(name)) continue
            val style = entry.obj ?: continue
            val own = fields(style)
            val parts = readParts(style["parts"])
            if (own == null && parts.isEmpty()) continue
            spans[name] = (own ?: TextSpanStyle()).copy(parts = parts)
        }
        return spans
    }

    /** The styles that apply: only for places `content` declares among its own `values`. */
    fun active(content: JsonValue?, spans: Map<String, TextSpanStyle>): Map<String, TextSpanStyle> {
        if (spans.isEmpty() || content?.get("kind")?.string != "template") return emptyMap()
        val values = content["values"]?.obj ?: return emptyMap()
        return spans.filterKeys(values::containsKey)
    }

    /** Styled runs: empty ones dropped, neighbours of the same place (and part) merged. */
    fun runs(runs: List<TemplateRun>, active: Map<String, TextSpanStyle>): List<TextSpanRun> {
        val result = ArrayList<TextSpanRun>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val span = run.place?.takeIf { active[it] != null }
            val part = span?.let { name -> run.part?.takeIf { active[name]?.parts?.get(it) != null } }
            val last = result.lastOrNull()
            if (last != null && last.span == span && last.part == part) {
                result[result.size - 1] = last.copy(text = last.text + run.text)
            } else {
                result.add(TextSpanRun(run.text, span, part))
            }
        }
        return result
    }

    /** The style a run is drawn with over the text's: the place with its part laid over it; `null` for the text's own. */
    fun style(run: TextSpanRun, active: Map<String, TextSpanStyle>): TextSpanStyle? {
        val place = run.span?.let(active::get) ?: return null
        val own = run.part?.let { place.parts[it] } ?: return place
        return place.overlaid(own)
    }

    fun key(place: String, part: FormatPart?): String = part?.let { "$place.${it.word}" } ?: place

    /** Every declared style (a place and each of its parts, the part over the place) by strut key. */
    fun declared(active: Map<String, TextSpanStyle>): List<Pair<String, TextSpanStyle>> {
        val result = ArrayList<Pair<String, TextSpanStyle>>()
        for (name in active.keys.sorted()) {
            val place = active.getValue(name)
            result.add(name to place)
            for (part in FormatPart.entries) place.parts[part]?.let { result.add(key(name, part) to place.overlaid(it)) }
        }
        return result
    }

    /** The shown-text rule over runs: runs of space, tab, LF and CR become one space across the whole string, the edges are trimmed. */
    fun shown(runs: List<TextSpanRun>): List<TextSpanRun> {
        val result = ArrayList<TextSpanRun>()
        var written = false
        var afterSpace = false
        for (run in runs) {
            val text = StringBuilder()
            for (char in run.text) {
                if (char == ' ' || char == '\t' || char == '\n' || char == '\r') {
                    // The space stays with the run where the whitespace begins.
                    if (written && !afterSpace) text.append(' ')
                    afterSpace = written
                } else {
                    text.append(char)
                    written = true
                    afterSpace = false
                }
            }
            if (text.isEmpty()) continue
            val last = result.lastOrNull()
            if (last != null && last.span == run.span && last.part == run.part) {
                result[result.size - 1] = last.copy(text = last.text + text)
            } else {
                result.add(TextSpanRun(text.toString(), run.span, run.part))
            }
        }
        // A space at the very end is an edge: trimmed.
        if (afterSpace && result.isNotEmpty()) {
            val last = result.removeAt(result.size - 1)
            val trimmed = last.text.dropLast(1)
            if (trimmed.isNotEmpty()) result.add(last.copy(text = trimmed))
        }
        return result
    }
}

// ---- Line pitch -------------------------------------------------------------------------------

/** A font size in logical pixels and the multiplier of the line it sets. */
internal data class LineMetric(val fontSize: Double, val lineHeight: Double) {
    val block: Double get() = fontSize * lineHeight
}

internal data class RichTextStrut(val span: String?, val lineBlock: Double)

internal object RichText {
    /**
     * The pitch of EVERY line of the paragraph: the tallest line (size × multiplier) among the base
     * style and ALL declared parts, shown or not. A tie goes to the larger font size, then to the
     * base style, then to the name in ascending order.
     */
    fun strut(base: LineMetric, spans: Map<String, LineMetric>): RichTextStrut {
        var bestSpan: String? = null
        var best = base
        for (name in spans.keys.sorted()) {
            val metric = spans.getValue(name)
            if (metric.block > best.block || (metric.block == best.block && metric.fontSize > best.fontSize)) {
                bestSpan = name
                best = metric
            }
        }
        return RichTextStrut(bestSpan, best.block)
    }
}

// ---- List marker ------------------------------------------------------------------------------

internal enum class DrawnMarker { Bullet, Number }

/** Column, gap and hanging indent of a list item, in logical pixels. */
internal data class ListMarkerGeometry(val column: Double, val gap: Double, val indent: Double)

/** Where the marker box and the text column lie along the line, from the LEFT edge of the node, whatever the writing direction. */
internal data class ListMarkerLayout(
    val markerFrom: Double, val markerTo: Double, val textFrom: Double, val textTo: Double, val textInline: Double,
    val markerOnRight: Boolean, val block: Double?, val markerDrawn: Boolean,
)

internal object ListMarkerRules {
    const val BULLET = "•"
    const val SUFFIX = "."
    private const val BULLET_COLUMN = 1.0
    private const val DIGIT = 0.6
    private const val SUFFIX_COLUMN = 0.4
    private const val MIN_COLUMN = 1.0
    private const val GAP = 0.5

    /** The marker the field draws: `none`, an unknown word and a non-string are no marker. */
    fun drawn(value: JsonValue?): DrawnMarker? = when (value?.string) {
        "bullet" -> DrawnMarker.Bullet
        "number" -> DrawnMarker.Number
        else -> null
    }

    private fun round6(value: Double) = Math.rint(value * 1e6) / 1e6

    /** The column in fractions of the font size; a number is `max(1, 0.6 × digits + 0.4)`. */
    fun geometry(marker: DrawnMarker, fontSize: Double, groupSize: Int = 1): ListMarkerGeometry {
        val size = if (fontSize.isFinite() && fontSize > 0) fontSize else 0.0
        val em = when (marker) {
            DrawnMarker.Bullet -> BULLET_COLUMN
            DrawnMarker.Number -> maxOf(MIN_COLUMN, maxOf(1, groupSize).toString().length * DIGIT + SUFFIX_COLUMN)
        }
        val column = round6(em * size)
        val space = round6(GAP * size)
        return ListMarkerGeometry(column, space, round6(column + space))
    }

    private fun run(markers: List<DrawnMarker?>, index: Int): IntRange? {
        if (markers[index] != DrawnMarker.Number) return null
        var first = index
        while (first > 0 && markers[first - 1] == DrawnMarker.Number) first--
        var last = index
        while (last < markers.size - 1 && markers[last + 1] == DrawnMarker.Number) last++
        return first..last
    }

    /** How many items the group of a numbered item has BY THE DOCUMENT, hidden ones included. */
    fun groupSize(markers: List<DrawnMarker?>, index: Int): Int? = run(markers, index)?.let { it.last - it.first + 1 }

    /** The number of a numbered item: the visible items of its group up to and including it; a hidden one takes none for others. */
    fun number(markers: List<DrawnMarker?>, index: Int, visible: List<Boolean>): Int? {
        val group = run(markers, index) ?: return null
        return (group.first until index).count { visible[it] } + 1
    }

    /** What the marker says: «•», or the number in the digits of the formatting language and a period. */
    fun text(marker: DrawnMarker, number: Int, formattingLocale: String?): String {
        if (marker == DrawnMarker.Bullet) return BULLET
        val environment = TextEnvironment(scope = emptyMap(), formattingLocale = formattingLocale)
        val digits = BindingFormat.pieces(JsonValue.Num(number.toDouble()), JsonObject.of("format" to "number"), environment)?.joinToString("") { it.text } ?: number.toString()
        return digits + SUFFIX
    }

    /** The boxes of an item across a place [placeInline] wide. */
    fun layout(
        marker: DrawnMarker, fontSize: Double, groupSize: Int, placeInline: Double, rightToLeft: Boolean,
        lines: Int? = null, maxLines: Int? = null, lineBlock: Double? = null,
    ): ListMarkerLayout {
        val metrics = geometry(marker, fontSize, groupSize)
        val textInline = round6(placeInline - metrics.indent)
        val block = if (lines != null && lineBlock != null) (maxLines?.let { minOf(lines, it) } ?: lines) * lineBlock else null
        return if (rightToLeft) {
            ListMarkerLayout(round6(placeInline - metrics.column), placeInline, 0.0, textInline, textInline, true, block, true)
        } else {
            ListMarkerLayout(0.0, metrics.column, metrics.indent, placeInline, textInline, false, block, true)
        }
    }

    /** The width an item wants in a row: the column, the gap and the text. */
    fun naturalInline(marker: DrawnMarker, fontSize: Double, groupSize: Int, textNaturalInline: Double): Double =
        round6(geometry(marker, fontSize, groupSize).indent + textNaturalInline)
}

// ---- Gradient on the glyphs -------------------------------------------------------------------

internal object TextGradientRules {
    /**
     * The gradient reference that paints the letters of a node: `Text` only, from its own effective
     * style (the field is not inherited). The theme still resolves it: an unknown token or stops
     * out of order give the colour.
     */
    fun reference(type: String, style: JsonObject): JsonValue? =
        if (type != "Text") null else style["textGradient"]?.takeIf { !it.isNull }

    /** Whether the pieces of a place take the gradient: a part that names its own colour keeps it; the text itself always takes it. */
    fun takes(gradient: Boolean, span: TextSpanStyle?): Boolean = gradient && !(span?.namesColor ?: false)
}
