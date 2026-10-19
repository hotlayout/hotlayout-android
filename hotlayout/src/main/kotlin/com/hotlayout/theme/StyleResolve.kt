package com.hotlayout.theme

import com.hotlayout.data.Conditions
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode

/** Logical sides: `start` and `end` follow the writing direction (`x-style.sides: 'logical'`). */
public data class Sides(val top: Double = 0.0, val start: Double = 0.0, val bottom: Double = 0.0, val end: Double = 0.0) {
    val horizontal: Double get() = start + end
    val vertical: Double get() = top + bottom

    public companion object {
        public val Zero: Sides = Sides()
        public fun all(value: Double): Sides = Sides(value, value, value, value)
    }
}

public data class Corners(val topStart: Double = 0.0, val topEnd: Double = 0.0, val bottomEnd: Double = 0.0, val bottomStart: Double = 0.0) {
    public companion object {
        public fun all(value: Double): Corners = Corners(value, value, value, value)
    }
}

public sealed class BackgroundValue {
    public data class Color(val color: Rgba) : BackgroundValue()
    public data class Gradient(val gradient: GradientValue) : BackgroundValue()
    public data class Image(val source: JsonValue, val fit: String) : BackgroundValue()
}

/** The box look of a node after tokens were resolved: what the `style` of a node draws. */
public data class BoxLook(
    val padding: Sides = Sides.Zero,
    val margin: Sides = Sides.Zero,
    val width: Double? = null,
    val height: Double? = null,
    val minWidth: Double? = null,
    val maxWidth: Double? = null,
    val minHeight: Double? = null,
    val maxHeight: Double? = null,
    val radius: Corners? = null,
    val borderWidth: Double? = null,
    val borderColor: Rgba? = null,
    /** `style.border.style: 'dashed'`; any other word is a solid line. */
    val borderDashed: Boolean = false,
    val background: BackgroundValue? = null,
    val shadow: List<ShadowLayer> = emptyList(),
    val opacity: Double? = null,
    /** `style.backdropBlur` (glass): the deviation of the blur under the box, 1…40. */
    val backdropBlur: Int? = null,
)

/** Text look that a node passes to its descendants (`x-style.inherited`: colour and typography). */
public data class InheritedLook(
    val color: Rgba,
    val typography: TextStyleValue,
    val italic: Boolean = false,
    /** `underline` or `lineThrough`; `null` is no line. */
    val decoration: String? = null,
    /** The reference of the colour a node or an ancestor NAMED for its text; `null` while nobody has. */
    val colorRef: JsonValue? = null,
)

/**
 * Turns references of a document (a token, a token with opacity, an own value) into values of a
 * theme. Literal values are clamped to the bounds of the schema; an unknown token gives `null`,
 * and the field default applies.
 */
public class StyleResolver(public val theme: HotlayoutTheme) {
    public fun color(ref: JsonValue?): Rgba? = when (ref) {
        is JsonValue.Str -> theme.colorValue(ref.value)
        is JsonValue.Obj -> {
            val own = ref["value"]?.string
            if (own != null) {
                Rgba.hex(own)?.let { shade -> ref["opacity"]?.number?.let { shade.withOpacity(it.coerceIn(0.0, 1.0)) } ?: shade }
            } else {
                ref["token"]?.string?.let(theme::colorValue)?.withOpacity((ref["opacity"]?.number ?: 1.0).coerceIn(0.0, 1.0))
            }
        }
        else -> null
    }

    public fun spacing(ref: JsonValue?): Double? = when (ref) {
        is JsonValue.Num -> ref.number?.coerceIn(0.0, 128.0)
        is JsonValue.Str -> if (ref.value == "none") 0.0 else theme.spacing[ref.value]
        else -> null
    }

    public fun radius(ref: JsonValue?): Double? = when (ref) {
        is JsonValue.Num -> ref.number?.coerceIn(0.0, 256.0)
        is JsonValue.Str -> if (ref.value == "none") 0.0 else theme.radius[ref.value]
        else -> null
    }

    /** A box spacing: one reference for all sides, or named sides (an unnamed side is zero). */
    public fun sides(ref: JsonValue?): Sides? {
        if (ref == null) return null
        if (ref.obj != null) {
            return Sides(spacing(ref["top"]) ?: 0.0, spacing(ref["start"]) ?: 0.0, spacing(ref["bottom"]) ?: 0.0, spacing(ref["end"]) ?: 0.0)
        }
        return spacing(ref)?.let(Sides::all)
    }

    public fun corners(ref: JsonValue?): Corners? {
        if (ref == null) return null
        if (ref.obj != null) {
            return Corners(radius(ref["topStart"]) ?: 0.0, radius(ref["topEnd"]) ?: 0.0, radius(ref["bottomEnd"]) ?: 0.0, radius(ref["bottomStart"]) ?: 0.0)
        }
        return radius(ref)?.let(Corners::all)
    }

    public fun typography(ref: JsonValue?): TextStyleValue? = when (ref) {
        is JsonValue.Str -> theme.typography[ref.value]
        is JsonValue.Obj -> ref["token"]?.string?.let { theme.typography[it] }?.let { base ->
            var style = base
            ref["fontSize"]?.number?.let { style = style.copy(fontSize = it.coerceIn(8.0, 96.0)) }
            ref["fontWeight"]?.number?.let { style = style.copy(fontWeight = it.coerceIn(100.0, 900.0).toInt()) }
            ref["lineHeight"]?.number?.let { style = style.copy(lineHeight = it.coerceIn(0.8, 3.0)) }
            ref["letterSpacing"]?.number?.let { style = style.copy(letterSpacing = it.coerceIn(-2.0, 8.0)) }
            style
        }
        else -> null
    }

    public fun shadow(ref: JsonValue?): List<ShadowLayer>? = when (ref) {
        is JsonValue.Str -> theme.elevation[ref.value]
        is JsonValue.Arr -> if (ref.items.size > 4) null else ref.items.map { layer ->
            if (layer.obj == null) return null
            val shade = color(layer["color"]) ?: return null
            ShadowLayer(
                (layer["offsetX"]?.number ?: 0.0).coerceIn(-64.0, 64.0),
                (layer["offsetY"]?.number ?: 0.0).coerceIn(-64.0, 64.0),
                (layer["blur"]?.number ?: 0.0).coerceIn(0.0, 128.0),
                (layer["spread"]?.number ?: 0.0).coerceIn(-64.0, 64.0),
                shade,
            )
        }
        else -> null
    }

    public fun gradient(ref: JsonValue?): GradientValue? = when (ref) {
        is JsonValue.Str -> theme.gradient[ref.value]
        is JsonValue.Obj -> {
            val angle = ref["angle"]?.number
            val stops = ref["stops"]?.array
            if (angle == null || stops == null || stops.size !in 2..8) {
                null
            } else {
                val read = stops.map { stop ->
                    val shade = color(stop["color"]) ?: return null
                    val position = stop["position"]?.number ?: return null
                    GradientValue.Stop(shade, position.coerceIn(0.0, 1.0))
                }
                // Stops out of order are not drawn (`unorderedGradient: 'do-not-draw'`).
                if (read.zipWithNext().all { (a, b) -> a.position <= b.position }) GradientValue(angle, read) else null
            }
        }
        else -> null
    }

    /**
     * A size of the box: a number in 1…1200, or a binding whose value out of range gives the
     * fallback (`boundSizeOutOfRange: 'use-fallback'`).
     */
    public fun dimension(ref: JsonValue?, scope: DataScope): Double? = when (ref) {
        is JsonValue.Num -> ref.number?.coerceIn(1.0, 1200.0)
        is JsonValue.Obj -> if (ref["kind"]?.string != "binding") {
            null
        } else {
            val fallback = ref["fallback"]?.number?.coerceIn(1.0, 1200.0)
            val bound = ref["path"]?.string?.let { scope[it]?.number }
            if (bound != null && bound in 1.0..1200.0) bound else fallback
        }
        else -> null
    }

    /** The box look of an effective style. */
    public fun box(style: JsonObject, scope: DataScope): BoxLook {
        val minWidth = dimension(style["minWidth"], scope)
        var maxWidth = dimension(style["maxWidth"], scope)
        val minHeight = dimension(style["minHeight"], scope)
        var maxHeight = dimension(style["maxHeight"], scope)
        // An inverted range: the minimum wins (`invertedSizeRange: 'min-wins'`).
        if (minWidth != null && maxWidth != null && maxWidth < minWidth) maxWidth = minWidth
        if (minHeight != null && maxHeight != null && maxHeight < minHeight) maxHeight = minHeight
        var borderWidth: Double? = null
        var borderColor: Rgba? = null
        var dashed = false
        style["border"]?.let { border ->
            val shade = color(border["color"])
            val width = border["width"]?.number
            if (border.obj != null && shade != null && width != null) {
                borderWidth = width.coerceIn(0.5, 16.0)
                borderColor = shade
                dashed = border["style"]?.string == "dashed"
            }
        }
        val background = style["background"]?.takeIf { it.obj != null }?.let { value ->
            when (value["kind"]?.string) {
                "color" -> color(value["color"])?.let(BackgroundValue::Color)
                "gradient" -> gradient(value["gradient"])?.let(BackgroundValue::Gradient)
                "image" -> value["source"]?.let { BackgroundValue.Image(it, if (value["fit"]?.string == "contain") "contain" else "cover") }
                else -> null
            }
        }
        return BoxLook(
            padding = sides(style["padding"]) ?: Sides.Zero,
            margin = sides(style["margin"]) ?: Sides.Zero,
            width = dimension(style["width"], scope),
            height = dimension(style["height"], scope),
            minWidth = minWidth,
            maxWidth = maxWidth,
            minHeight = minHeight,
            maxHeight = maxHeight,
            radius = corners(style["radius"]),
            borderWidth = borderWidth,
            borderColor = borderColor,
            borderDashed = dashed,
            background = background,
            shadow = ShadowTint.apply(shadow(style["shadow"]) ?: emptyList(), style, this),
            opacity = style["opacity"]?.number?.coerceIn(0.0, 1.0),
            backdropBlur = style["backdropBlur"]?.number?.takeIf { it == Math.rint(it) && it in 1.0..40.0 }?.toInt(),
        )
    }
}

/** The style of a node (`style`, `styleWhen`). */
public object NodeStyle {
    /**
     * The effective style: the base style with the FIRST case whose condition holds laid over it,
     * field by top-level field (`caseSelection: 'first-match'`, `caseMerge: 'top-level-fields'`).
     */
    public fun effective(node: DocumentNode, scope: DataScope): JsonObject {
        var style = node.style?.obj ?: JsonObject()
        for (item in node.styleWhen?.array.orEmpty()) {
            val condition = item["when"] ?: continue
            if (item.obj == null || !Conditions.holds(condition, scope)) continue
            for ((key, value) in item["style"]?.obj ?: JsonObject()) style = style.with(key, value)
            break
        }
        return style
    }

    /**
     * The text look a node passes down: its own colour and typography where it names them, the
     * parent's otherwise (an unknown token keeps the parent's).
     */
    public fun inherited(parent: InheritedLook, style: JsonObject, resolver: StyleResolver): InheritedLook {
        var look = parent
        resolver.color(style["color"])?.let { look = look.copy(color = it, colorRef = style["color"]) }
        resolver.typography(style["typography"])?.let { look = look.copy(typography = it) }
        // A word outside the lists is no field: the inherited one stays.
        when (style["fontStyle"]?.string) {
            "italic" -> look = look.copy(italic = true)
            "normal" -> look = look.copy(italic = false)
        }
        when (val word = style["textDecoration"]?.string) {
            "none" -> look = look.copy(decoration = null)
            "underline", "lineThrough" -> look = look.copy(decoration = word)
        }
        return look
    }

    /** The look at the screen root (`x-style.root`): `onSurface` text in `bodyM`. */
    public fun root(resolver: StyleResolver): InheritedLook = InheritedLook(
        color = resolver.theme.colorValue("onSurface") ?: Rgba(0.07, 0.09, 0.15),
        typography = resolver.theme.typography["bodyM"] ?: TextStyleValue(14.0, 1.4286, 400),
    )
}
