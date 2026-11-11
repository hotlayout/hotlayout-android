package com.hotlayout.theme

import com.hotlayout.JsonValue

/**
 * Display typography: how a large font size meets the system text scale, and tracking as a share of the font size.
 */
internal object DisplayTypography {
    /**
     * The largest font size of an override on a node. The theme keeps its own, smaller bound for its tokens
     * ([ThemeBounds.FONT_SIZE]): the theme names roles of text, a poster size is the decision of one node.
     */
    const val MAX_OVERRIDE_FONT_SIZE: Double = 400.0

    /** A font size above this is not multiplied directly by the system text scale. */
    const val SCALE_CEILING: Double = 96.0
    const val MIN_TRACKING_SHARE: Double = -0.1
    const val MAX_TRACKING_SHARE: Double = 0.5
    const val MAX_TEXT_SHADOW_LAYERS: Int = 2

    /**
     * The font size after the system text scale [scale]: `s <= 1 ? f * s : max(f, min(f, 96) * s)`. Up to the ceiling it is
     * exactly `f * s`; above it the size grows no further than `96 * s` and is never below `f`.
     *
     * The reader draws with the sizes of the document as they are and does not multiply them by the system scale, which is
     * the scale 1 of this rule.
     */
    fun scaledFontSize(fontSize: Double, scale: Double): Double =
        if (scale <= 1) fontSize * scale else maxOf(fontSize, minOf(fontSize, SCALE_CEILING) * scale)

    /**
     * The tracking in pixels for the EFFECTIVE font size: a share of it when the style gives `letterSpacingEm` (the share
     * wins), else the pixels as written, which the scale does not multiply.
     */
    fun trackingPx(letterSpacing: Double? = null, letterSpacingEm: Double? = null, effectiveFontSize: Double): Double =
        if (letterSpacingEm != null) letterSpacingEm * effectiveFontSize else letterSpacing ?: 0.0

    /** The line block: the effective font size times the line height multiplier. */
    fun lineBlock(fontSize: Double, lineHeight: Double, scale: Double): Double = scaledFontSize(fontSize, scale) * lineHeight
}

/** `style.textShadow` (the field is read by `Text` only). */
internal object TextShadowRules {
    /** The layers a node paints: only `Text` reads the field. */
    fun layers(type: String, style: com.hotlayout.JsonObject, resolver: StyleResolver): List<TextShadowLayer>? {
        if (type != "Text") return null
        val value: JsonValue = style["textShadow"]?.takeIf { !it.isNull } ?: return null
        return resolver.textShadow(value)
    }

    /**
     * The layers from the one nearest the page to the one nearest the text and on top: the order of a painter that draws a
     * list from the bottom (`bottomFirst` of the table).
     */
    fun bottomFirst(layers: List<TextShadowLayer>): List<TextShadowLayer> = layers.reversed()
}
