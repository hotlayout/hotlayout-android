package com.hotlayout.model

import com.hotlayout.data.Conditions
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue

/**
 * The width class of a screen (`x-style.layout.widthClass`). There are three and no fourth: a very
 * wide box is still `Expanded`.
 */
public enum class WidthClass(public val word: String, public val from: Double) {
    Compact("compact", 0.0),
    Medium("medium", 600.0),
    Expanded("expanded", 1024.0),
    ;

    /** The layers of `styleOn` / `propsOn` that act in this class, in the order they are laid on: the cascade runs from narrow to wide. */
    public val layers: List<WidthClass>
        get() = when (this) {
            Compact -> emptyList()
            Medium -> listOf(Medium)
            Expanded -> listOf(Medium, Expanded)
        }

    /** Wide places keep the navigation bar at the top and show a sheet as a window. */
    public val isWide: Boolean get() = this != Compact

    public companion object {
        /** The class while the width is unknown. */
        public val Unknown: WidthClass = Compact

        /** The class of the box the screen is drawn in. The width is NOT rounded: 599.9 is compact, 600 is medium; a width that is not a finite non-negative number is unknown. */
        public fun of(width: Double?): WidthClass {
            if (width == null || !width.isFinite() || width < 0) return Unknown
            if (width >= Expanded.from) return Expanded
            return if (width >= Medium.from) Medium else Compact
        }

        public fun named(word: String?): WidthClass? = entries.firstOrNull { it.word == word }
    }
}

/**
 * The rules of the width classes that are not about one widget: who is shown where, which props a
 * class may override, how wide the content of a screen is.
 */
public object WidthRules {
    // Visibility

    /** The classes a node NAMES in `showOn`; `null`: no field, a field of another shape, or no known word — shown everywhere. */
    public fun showOn(fields: JsonObject): List<WidthClass>? {
        val list = fields["showOn"]?.array?.takeIf { it.isNotEmpty() } ?: return null
        val known = list.mapNotNull { WidthClass.named(it.string) }
        return known.ifEmpty { null }
    }

    public fun showsOn(node: DocumentNode, widthClass: WidthClass): Boolean = showOn(node.fields)?.contains(widthClass) ?: true

    /**
     * Whether a node is shown: the class is listed AND `visibleIf` holds. THE one question every place of the
     * contract that asks «visible» puts (zones, bar actions, nav items, numbering, forms, tabs, layers, weights, cards).
     */
    public fun isShown(node: DocumentNode, widthClass: WidthClass, scope: DataScope): Boolean =
        showsOn(node, widthClass) && Conditions.isVisible(node.visibleIf, scope)

    // Props

    /** The closed list of props each widget lets a width class override (`widthClass.props.byWidget`); any other widget has none. */
    public val responsiveProps: Map<String, List<String>> = mapOf(
        "Screen" to listOf("gap", "align", "justify", "contentMaxWidth"),
        "Column" to listOf("gap", "align", "justify"),
        "Row" to listOf("gap", "align", "justify", "carousel"),
        "Container" to listOf("direction", "gap", "align", "justify"),
        "Grid" to listOf("columns", "gap"),
        "Repeater" to listOf("direction", "columns", "gap", "carousel"),
    )

    /** The props a layer may REMOVE with `unset`: those whose absence is a state of its own. */
    public val unsettableProps: Map<String, List<String>> = mapOf("Screen" to listOf("contentMaxWidth"))

    /** The names `unset` lists for a layer; a carrier or a list of another shape removes nothing, an entry that is not a string is skipped. */
    public fun unsetNames(carrier: JsonValue?, layer: WidthClass): List<String> =
        carrier?.get("unset")?.get(layer.word)?.array?.mapNotNull { it.string }.orEmpty()

    private fun without(source: JsonObject, names: List<String>, allowed: List<String>? = null): JsonObject {
        var result = source
        for (name in names) if (allowed == null || name in allowed) result = result.with(name, null)
        return result
    }

    /** The layer of a carrier (`styleOn`, `propsOn`) for a class: an object, or nothing. */
    private fun layer(carrier: JsonValue?, widthClass: WidthClass): JsonObject? = carrier?.get(widthClass.word)?.obj

    public class EffectiveProps(public val props: JsonObject, public val columnsFromOverride: Boolean = false)

    /**
     * The props of a node in a class: the base with the `propsOn` layers laid on, from narrow to wide. A layer replaces
     * only the keys it names, and only keys of the closed list of the widget; `null` does not override; a carousel is
     * overridden by `perView` alone, and only where the base already has one.
     */
    public fun effectiveProps(type: String, props: JsonObject, propsOn: JsonValue?, widthClass: WidthClass): EffectiveProps {
        val allowed = responsiveProps[type]
        if (allowed == null || widthClass.layers.isEmpty()) return EffectiveProps(props)
        var result = props
        var columns = false
        for (layer in widthClass.layers) {
            // What the layer removes goes first, what it names after: named and removed means named.
            result = without(result, unsetNames(propsOn, layer), unsettableProps[type].orEmpty())
            val override = layer(propsOn, layer) ?: continue
            for (key in allowed) {
                val value = override[key] ?: continue
                val next = overlaid(key, result[key], value) ?: continue
                result = result.with(key, next)
                if (key == "columns") columns = true
            }
        }
        return EffectiveProps(result, columns)
    }

    private fun overlaid(key: String, below: JsonValue?, value: JsonValue): JsonValue? {
        if (value.isNull) return null
        if (key != "carousel") return value
        val base = below?.obj ?: return null
        val perView = value["perView"]?.takeIf { !it.isNull } ?: return null
        return JsonValue.Obj(base.with("perView", perView))
    }

    // Style

    /** The base style of a node in a class: `style` with the `styleOn` layers laid on (top-level fields replaced whole), WITHOUT the states; `null` when nothing is left. */
    public fun baseStyle(fields: JsonObject, widthClass: WidthClass): JsonObject? {
        var result = fields["style"]?.obj
        val carrier = fields["styleOn"]
        for (layer in widthClass.layers) {
            result?.let { current ->
                val kept = without(current, unsetNames(carrier, layer))
                result = kept.takeIf { !it.isEmpty() }
            }
            val overlay = layer(carrier, layer) ?: continue
            var merged = result ?: JsonObject()
            for ((key, value) in overlay) merged = merged.with(key, value)
            result = merged
        }
        return result?.takeIf { !it.isEmpty() }
    }

    // Content column

    public const val CONTENT_MAX_MIN: Double = 320.0
    public const val CONTENT_MAX_MAX: Double = 1920.0

    /** The largest content width as the reader reads it: a whole number clamped into the bounds of the schema; any other value is no field. */
    public fun contentMaxWidth(value: JsonValue?): Double? {
        val number = value?.number ?: return null
        if (number != Math.rint(number)) return null
        return minOf(CONTENT_MAX_MAX, maxOf(CONTENT_MAX_MIN, number))
    }

    public class ContentBox(public val start: Double, public val width: Double)

    /** The column of the content: no wider than the number, centred; half of the remainder is not rounded. A screen narrower than the number is unchanged. */
    public fun contentBox(available: Double, maxWidth: Double?): ContentBox {
        val space = maxOf(0.0, available)
        if (maxWidth == null || maxWidth >= space) return ContentBox(0.0, space)
        return ContentBox((space - maxWidth) / 2, maxWidth)
    }

    public class InlineRect(public val x: Double, public val width: Double)

    public class ContentRects(public val body: InlineRect, public val pinned: InlineRect, public val appBar: InlineRect, public val navBar: InlineRect)

    /**
     * Where the content of each zone stands along the width, from the LEFT edge of the root box in either direction:
     * body and pinned block are a column in the root box without its inline padding; the app bar keeps its box
     * between the paddings and its content is a column in its own content box; the bar of navigation runs edge to
     * edge and its items are such a column.
     */
    public fun contentRects(
        width: Double, paddingStart: Double, paddingEnd: Double, maxWidth: Double?,
        appBarInset: Pair<Double, Double> = 0.0 to 0.0, navBarInset: Pair<Double, Double> = 0.0 to 0.0, rightToLeft: Boolean,
    ): ContentRects {
        fun column(outer: Double, inset: Pair<Double, Double>, before: Double): InlineRect {
            val box = contentBox(outer - inset.first - inset.second, maxWidth)
            val start = before + inset.first + box.start
            return InlineRect(if (rightToLeft) width - start - box.width else start, box.width)
        }
        val inside = width - paddingStart - paddingEnd
        val body = column(width, paddingStart to paddingEnd, 0.0)
        return ContentRects(body, body, column(inside, appBarInset, paddingStart), column(width, navBarInset, 0.0))
    }
}

/**
 * The node with its subtree in a class: effective props, a base style with the width layers, no `propsOn` / `styleOn`.
 * `Compact` is the node itself: the base of the document is the phone. The reader resolves the tree for its class
 * and then draws an ordinary document; no rule takes a «class» parameter.
 */
public fun DocumentNode.inWidthClass(widthClass: WidthClass): DocumentNode {
    if (widthClass == WidthClass.Compact) return this
    val effective = WidthRules.effectiveProps(type, props, fields["propsOn"], widthClass)
    val style = WidthRules.baseStyle(fields, widthClass)
    val resolved = fields.with("propsOn", null).with("styleOn", null).with("style", style?.let { JsonValue.Obj(it) })
    return DocumentNode(id, type, effective.props, resolved, children.map { it.inWidthClass(widthClass) }, effective.columnsFromOverride)
}
