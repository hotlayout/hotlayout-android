package com.hotlayout.render.widgets

import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.data.ListFilter
import com.hotlayout.data.RecordScope
import com.hotlayout.json.wholeIn
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext
import com.hotlayout.theme.Rgba
import kotlin.math.floor

/** The number of a node in a numbered list from data and the size of the group. */
internal data class ListNumber(val number: Int, val groupSize: Int)

/**
 * Grouping of a list from data (`Repeater.groupBy`): records that follow one another with the same
 * value under the path form a group, and the second child of the list (the header) is drawn before
 * each group.
 */
internal object RecordGroups {
    /** The header is the second child of the list. */
    const val HEADER_CHILD = 1

    private fun missing(value: JsonValue?): Boolean = when (value) {
        null, JsonValue.Null -> true
        is JsonValue.Str -> value.value.isEmpty()
        else -> false
    }

    /**
     * «Absent» (missing, `null`, the empty string) is one value; otherwise the same JSON type and
     * equal, without conversion (`1` and `"1"` are two groups). An object or a list is a group of its own.
     */
    fun isSame(left: JsonValue?, right: JsonValue?): Boolean {
        if (missing(left) || missing(right)) return missing(left) && missing(right)
        return when {
            left is JsonValue.Str && right is JsonValue.Str -> left.value == right.value
            left is JsonValue.Num && right is JsonValue.Num -> left.value == right.value
            left is JsonValue.Bool && right is JsonValue.Bool -> left.value == right.value
            else -> false
        }
    }

    /** The groups of the drawn records as the index of the first record and the size. */
    fun groups(values: List<JsonValue?>): List<Pair<Int, Int>> {
        val groups = ArrayList<Pair<Int, Int>>()
        for (index in values.indices) {
            val last = groups.lastOrNull()
            if (last != null && isSame(values[last.first], values[index])) {
                groups[groups.size - 1] = last.first to last.second + 1
            } else {
                groups.add(index to 1)
            }
        }
        return groups
    }

    fun starts(values: List<JsonValue?>): Set<Int> = groups(values).map { it.first }.toSet()
}

/** What a `Repeater` draws, worked out from its props and the scope it stands in. */
internal class RepeaterPlan(node: DocumentNode, scope: DataScope, allowed: Int = Int.MAX_VALUE, shownIn: (DocumentNode, DataScope) -> Boolean = { _, _ -> true }) {
    class Instance(
        /** The raw record as the host gave it (an object; anything else is a record without fields). */
        val fields: JsonObject,
        /** The scope of the record: its fields under the prefix over the outer scope. */
        val scope: DataScope,
        /** Whether the template is shown for this record (`visibleIf` on the template). */
        val shown: Boolean,
        /** The number among the shown instances, from 1; `null` when hidden. */
        val number: Int?,
        /** A group header goes before this record. */
        val headerBefore: Boolean,
    )

    val instances: List<Instance>

    init {
        val item = node.props["item"]?.string ?: ""
        val records = scope[node.props["source"]?.string ?: ""]?.array.orEmpty()
        val drawn = drawnIndexes(records, item, node.props["where"], scope, limit(node.props)).take(allowed)
        val template = node.children.firstOrNull()
        val fieldsOf = drawn.map { records[it].obj ?: JsonObject() }
        val scopes = fieldsOf.map { RecordScope.overlay(scope, RecordScope.instanceRecord(item, it)) ?: scope }
        val shown = scopes.map { template != null && shownIn(template, it) }
        val numbers = numbers(shown)
        var starts: Set<Int> = emptySet()
        val groupPath = groupBy(node.props)
        if (node.children.size > RecordGroups.HEADER_CHILD && groupPath != null) {
            starts = RecordGroups.starts(fieldsOf.map { RecordScope.instanceRecord(item, it)[groupPath] })
        }
        instances = fieldsOf.indices.map { Instance(fieldsOf[it], scopes[it], shown[it], numbers[it], it in starts) }
    }

    companion object {
        /** The most records a list draws, whatever the document asks (`maxCollectionItems`). */
        const val MAX_ITEMS = 50

        /** A number outside its bounds, or not a number, is the lower bound (`nonNumericLiteral`). */
        fun limit(props: JsonObject): Int? {
            val raw = props["limit"] ?: return null
            if (raw.isNull) return null
            val number = raw.number ?: return 1
            return floor(number).coerceIn(1.0, MAX_ITEMS.toDouble()).toInt()
        }

        /** `columns` of a list: a whole number of cells in a row; a value that is not a number is 2. */
        fun columns(props: JsonObject, overridden: Boolean = true): Int? = gridColumns(props["columns"], overridden)

        /** The path of `groupBy`; a value that is not a non-empty string is no field. */
        fun groupBy(props: JsonObject): String? = props["groupBy"]?.string?.takeIf { it.isNotEmpty() }

        /** The records a list draws: the filter first, then the limit, then the ceiling — a record that did not pass does not exist. */
        fun drawnIndexes(records: List<JsonValue>, item: String, filter: JsonValue?, outer: DataScope, limit: Int?): List<Int> =
            ListFilter.passedIndexes(records, item, filter, outer, limit).take(MAX_ITEMS)

        /** An instance whose template is shown gets how many shown ones are drawn up to and including it; a hidden one gets `null`. */
        fun numbers(shown: List<Boolean>): List<Int?> {
            var drawn = 0
            return shown.map { flag -> if (!flag) null else ++drawn }
        }
    }
}

/**
 * The number of cells in a row of a grid: a whole count from 1 to 6 (the schema allows 2…4 in the
 * document and 1…6 as a per-width override); a value that is not a number is 2.
 */
internal fun gridColumns(raw: JsonValue?, overridden: Boolean = true): Int? {
    if (raw == null || raw.isNull) return null
    val number = raw.number ?: return 2
    return floor(number).coerceIn(if (overridden) 1.0 else 2.0, if (overridden) 6.0 else 4.0).toInt()
}

/** The grid arithmetic shared by `Grid` and a `Repeater` with `columns`. */
internal object GridGeometry {
    /** `(width − gaps) ÷ columns`, so the last row's missing cells keep the others' width. */
    fun cellWidth(total: Double, columns: Int, gap: Double): Double = (total - gap * (columns - 1)) / columns

    /** (row, column) of each of [count] cells, in reading order, no holes. */
    fun cells(count: Int, columns: Int): List<List<Int>> = (0 until count).map { listOf(it / columns, it % columns) }
}

/** Which tab is open (`hiddenInitialTab`, `initialTabOutOfRange`). */
internal object TabsRules {
    /**
     * `initialTab` counts ALL tabs in document order, hidden ones too; a hidden tab, or a number that
     * is not a whole number in range, opens the first visible tab. `null` means no tab is visible.
     */
    fun openTabIndex(initialTab: JsonValue?, visible: List<Boolean>): Int? {
        val number = initialTab?.number
        if (number != null && number == Math.rint(number) && number >= 0 && number < visible.size && visible[number.toInt()]) return number.toInt()
        return visible.indexOf(true).takeIf { it >= 0 }
    }

    /** The target of an arrow key: [direction] is +1 for «next in reading order». The strip does not wrap. */
    fun arrowTarget(open: Int, count: Int, key: String, rightToLeft: Boolean): Int? {
        val step = when (key) {
            "next" -> 1
            "previous" -> -1
            "right" -> if (rightToLeft) -1 else 1
            "left" -> if (rightToLeft) 1 else -1
            "home" -> return 0
            "end" -> return count - 1
            else -> return null
        }
        return (open + step).takeIf { it in 0 until count }
    }

    const val TITLE_GAP_TOKEN = "lg"
    const val TITLE_PADDING_TOKEN = "sm"
    const val INDICATOR_TOKEN = "xs"
    const val PANEL_GAP_TOKEN = "md"
}

/** The rules of the parts of a list row (`x-style.layout.listTileParts`, `partStyle`). */
internal object ListTileRules {
    val linesRange = 1..5

    /** The line limit the reader understands: a whole number in range; anything else is «no limit». */
    fun lineLimit(value: JsonValue?): Int? {
        return value.wholeIn(linesRange)
    }

    /** How many lines are drawn out of the natural ones, and whether the text is cut. */
    fun shownLines(natural: Int, limit: JsonValue?): Pair<Int, Boolean> {
        val max = lineLimit(limit)
        return if (max == null || natural <= max) natural to false else max to true
    }

    const val TILE_SIZE = 40.0
    val tileSizeRange = 24.0..96.0
    val tileDefaultRadius: JsonValue = JsonValue.Str("sm")

    data class Tile(val color: JsonValue, val radius: JsonValue, val size: Double)

    /** The tile of a leading slot: only under an icon, only with a colour that can be read; a radius or a side of the wrong form is the default. */
    fun tile(leading: JsonValue?): Tile? {
        val slot = leading?.obj ?: return null
        if (slot["kind"]?.string != "icon") return null
        val tile = slot["tile"]?.obj ?: return null
        val color = tile["color"]?.takeIf { PartStyleRules.readColor(it) != null } ?: return null
        var radius = tileDefaultRadius
        tile["radius"]?.let { value ->
            value.string?.let { if (PartStyleRules.isTokenName(it)) radius = value }
            value.number?.let { if (it == Math.rint(it) && it in 0.0..256.0) radius = value }
        }
        var size = TILE_SIZE
        tile["size"]?.number?.let { if (it == Math.rint(it) && it in tileSizeRange) size = it }
        return Tile(color, radius, size)
    }

    data class LeadingBox(val inline: Double, val block: Double, val glyphInline: Double, val glyphBlock: Double)

    /** The box of the leading icon: the box of the glyph, or the square of the tile with the glyph in its centre. */
    fun leadingBox(leading: JsonValue?, glyphInline: Double, glyphBlock: Double): LeadingBox {
        val tile = tile(leading) ?: return LeadingBox(glyphInline, glyphBlock, 0.0, 0.0)
        return LeadingBox(tile.size, tile.size, (tile.size - glyphInline) / 2, (tile.size - glyphBlock) / 2)
    }

    data class TrailingColumn(val inline: Double, val block: Double, val textStart: Double, val captionStart: Double?)

    /** The text line over the caption, both pushed to the end; no gap between them. */
    fun trailingColumn(textInline: Double, textLine: Double, caption: Pair<Double, Double>?): TrailingColumn {
        val inline = maxOf(textInline, caption?.first ?: 0.0)
        return TrailingColumn(inline, textLine + (caption?.second ?: 0.0), inline - textInline, caption?.let { inline - it.first })
    }

    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double)
    data class RowRects(val block: Double, val leading: Rect?, val body: Rect, val trailing: Rect?)

    /** The leading and the trailing column take their own width, the texts the rest (not less than zero), each centred on the row. */
    fun rowRects(
        rowInline: Double,
        gap: Double,
        leading: Pair<Double, Double>?,
        trailing: Pair<Double, Double>?,
        bodyBlock: Double,
        rightToLeft: Boolean,
    ): RowRects {
        val block = maxOf(leading?.second ?: 0.0, bodyBlock, trailing?.second ?: 0.0)
        val before = leading?.let { it.first + gap } ?: 0.0
        val after = trailing?.let { it.first + gap } ?: 0.0
        val bodyInline = maxOf(0.0, rowInline - before - after)
        fun place(start: Double, inline: Double, height: Double) =
            Rect(if (rightToLeft) rowInline - start - inline else start, (block - height) / 2, inline, height)
        return RowRects(
            block,
            leading?.let { place(0.0, it.first, it.second) },
            place(before, bodyInline, bodyBlock),
            trailing?.let { place(before + bodyInline + gap, it.first, it.second) },
        )
    }

    const val LEADING_ICON_COLOR = "primary"
    const val TRAILING_TEXT_COLOR = "onSurface"
    const val TRAILING_TEXT_TYPOGRAPHY = "bodyM"
    const val CAPTION_COLOR = "muted"
    const val CAPTION_TYPOGRAPHY = "bodyS"

    /** The opacity of a secondary part under a named colour. */
    const val SECONDARY_OPACITY = 0.6

    data class PartLook(val color: JsonValue, val typography: JsonValue)

    /** The colour the node names for its text, if it does. */
    data class NamedColor(val ref: JsonValue, val named: Boolean)

    /** The trailing text: the colour is the state of the part, the style of the part, the named colour of the node, then `onSurface`; the font is the state, the style, then `bodyM`. */
    fun trailingTextLook(trailing: JsonValue?, context: NamedColor, isMet: (JsonValue) -> Boolean): PartLook {
        val own = PartStyleRules.resolve(trailing?.get("style"), trailing?.get("styleWhen"), isMet)
        return PartLook(
            own?.color ?: if (context.named) context.ref else JsonValue.Str(TRAILING_TEXT_COLOR),
            own?.typography ?: JsonValue.Str(TRAILING_TEXT_TYPOGRAPHY),
        )
    }

    /** The caption: its own colour, else the named colour with the secondary opacity, else `muted`. The style of the text does not reach it. */
    fun captionLook(trailing: JsonValue?, context: NamedColor, isMet: (JsonValue) -> Boolean): PartLook {
        val own = PartStyleRules.resolve(trailing?.get("captionStyle"), trailing?.get("captionStyleWhen"), isMet)
        return PartLook(
            own?.color ?: if (context.named) PartStyleRules.withOpacity(context.ref, SECONDARY_OPACITY) else JsonValue.Str(CAPTION_COLOR),
            own?.typography ?: JsonValue.Str(CAPTION_TYPOGRAPHY),
        )
    }

    /** The leading icon: its own colour, the named colour of the node, `primary`. */
    fun leadingIconColor(leading: JsonValue?, context: NamedColor): JsonValue {
        if (leading?.get("kind")?.string == "icon") PartStyleRules.readColor(leading["color"])?.let { return it }
        return if (context.named) context.ref else JsonValue.Str(LEADING_ICON_COLOR)
    }

    /** What the node names for its text, from the inherited look. */
    fun named(context: RenderContext): NamedColor = NamedColor(context.inherited.colorRef ?: JsonValue.Null, context.inherited.colorRef != null)
}

/** The style of one part of a widget (`x-style.layout.partStyle`): a colour and a typography, and the states that lay over them. */
internal object PartStyleRules {
    data class Style(val color: JsonValue?, val typography: JsonValue?)

    /** A token name: a lower-case letter and up to 39 letters and digits. */
    fun isTokenName(text: String): Boolean {
        if (text.isEmpty() || text.length > 40 || text.codePointCount(0, text.length) != text.length) return false
        if (text[0] !in 'a'..'z') return false
        return text.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }
    }

    /** A colour of the form of the contract: a token, a token with opacity, an own `#rrggbb[aa]`. */
    fun readColor(value: JsonValue?): JsonValue? {
        when (value) {
            is JsonValue.Str -> return value.takeIf { isTokenName(value.value) }
            is JsonValue.Obj -> {
                val object_ = value.fields
                val opacityOk = object_["opacity"]?.let { o -> o.number?.let { it in 0.0..1.0 } ?: false } ?: true
                object_["token"]?.string?.let { token ->
                    return value.takeIf { object_.keys.toSet() == setOf("token", "opacity") && isTokenName(token) && opacityOk }
                }
                object_["value"]?.string?.let { hex ->
                    return value.takeIf { object_.keys.toSet().all { it in setOf("value", "opacity") } && Rgba.hex(hex) != null && opacityOk }
                }
                return null
            }
            else -> return null
        }
    }

    fun readTypography(value: JsonValue?): JsonValue? = when (value) {
        is JsonValue.Str -> value.takeIf { isTokenName(value.value) }
        is JsonValue.Obj -> value.takeIf { value.fields["token"]?.string?.let(::isTokenName) == true }
        else -> null
    }

    /** Only the keys the reader understands; a value of the wrong form gives no key. */
    fun read(value: JsonValue?): Style? {
        val object_ = value?.obj ?: return null
        val style = Style(readColor(object_["color"]), readTypography(object_["typography"]))
        return if (style.color == null && style.typography == null) null else style
    }

    /** The base style with the first state whose condition holds laid over it, key by key. A state that cannot be read is skipped before the choice. */
    fun resolve(base: JsonValue?, cases: JsonValue?, isMet: (JsonValue) -> Boolean): Style? {
        val own = read(base)
        val active = cases?.array.orEmpty().mapNotNull { entry ->
            val whenCondition = entry["when"] ?: return@mapNotNull null
            val style = read(entry["style"]) ?: return@mapNotNull null
            whenCondition to style
        }.firstOrNull { isMet(it.first) } ?: return own
        return Style(active.second.color ?: own?.color, active.second.typography ?: own?.typography)
    }

    /** A colour with the opacity multiplied: a token becomes a tinted token. */
    fun withOpacity(ref: JsonValue, factor: Double): JsonValue = when (ref) {
        is JsonValue.Str -> JsonValue.of(mapOf("token" to ref.value, "opacity" to factor))
        is JsonValue.Obj -> JsonValue.Obj(ref.fields.with("opacity", JsonValue.Num((ref.fields["opacity"]?.number ?: 1.0) * factor)))
        else -> ref
    }
}
