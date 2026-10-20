package com.hotlayout.render.screen

import com.hotlayout.json.wholeIn

/**
 * The box and the drawing of a widget that may name its own glyph size
 * (`x-style.layout.ownGlyphSize`; reference `iconGeometryOf` / `iconButtonGeometryOf`).
 */
internal data class GlyphGeometry(
    /** The side of the square the drawing is drawn in. */
    val glyph: Double,
    /** The content box, without padding and border. */
    val contentWidth: Double,
    val contentHeight: Double,
    /** The box of the node, with padding and border. */
    val boxWidth: Double,
    val boxHeight: Double,
) {
    class Input(
        val glyphSize: Double?,
        val fontSize: Double,
        /** The sum of the padding on both sides of each axis. */
        val paddingInline: Double,
        val paddingBlock: Double,
        val borderWidth: Double,
        val width: Double?,
        val height: Double?,
        /** The content box the word of the size gives (no own number). */
        val wordWidth: Double,
        val wordHeight: Double,
    )

    companion object {
        /** A whole number in 8…128, else no field. */
        fun own(value: Double?): Double? = value.wholeIn(8..128)?.toDouble()

        fun of(input: Input): GlyphGeometry {
            val own = own(input.glyphSize)
            val insetInline = input.paddingInline + 2 * input.borderWidth
            val insetBlock = input.paddingBlock + 2 * input.borderWidth
            val naturalWidth = own ?: input.wordWidth
            val naturalHeight = own ?: input.wordHeight
            val contentWidth = input.width?.let { maxOf(0.0, it - insetInline) } ?: naturalWidth
            val contentHeight = input.height?.let { maxOf(0.0, it - insetBlock) } ?: naturalHeight
            val boxWidth = input.width ?: (contentWidth + insetInline)
            val boxHeight = input.height ?: (contentHeight + insetBlock)
            // With a word the drawing is the font size and a named box does not constrain it.
            if (own == null) return GlyphGeometry(input.fontSize, contentWidth, contentHeight, boxWidth, boxHeight)
            val glyph = minOf(own, if (input.width == null) own else contentWidth, if (input.height == null) own else contentHeight)
            return GlyphGeometry(glyph, contentWidth, contentHeight, boxWidth, boxHeight)
        }
    }
}
