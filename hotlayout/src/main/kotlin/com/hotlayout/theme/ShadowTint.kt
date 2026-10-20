package com.hotlayout.theme

import com.hotlayout.JsonObject
import com.hotlayout.JsonValue

/**
 * `style.shadowColor: background` (`x-shadowColor`): the shadow takes the hue of the node's own
 * background and keeps the opacity, offset, blur and spread of every layer.
 */
internal object ShadowTint {
    internal const val WORD: String = "background"

    /** The index of the stop whose position is nearest to the middle; the earlier one on a tie. */
    internal fun middleStop(positions: List<Double>): Int? {
        var best: Int? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for ((index, position) in positions.withIndex()) {
            val distance = Math.abs(position - 0.5)
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * The hue of a background: a solid colour, or the stop of a gradient nearest to the middle;
     * `null` for no background, an image or a colour that does not resolve. Its own opacity does not
     * count: the layer carries the opacity.
     */
    internal fun hue(background: JsonValue?, resolver: StyleResolver): Rgba? {
        if (background?.obj == null) return null
        return when (background["kind"]?.string) {
            "color" -> resolver.color(background["color"])
            "gradient" -> {
                val gradient = resolver.gradient(background["gradient"]) ?: return null
                val index = middleStop(gradient.stops.map { it.position }) ?: return null
                gradient.stops[index].color
            }
            else -> null
        }
    }

    /** The layers as drawn: with the hue of the background when the field asks for it. */
    internal fun apply(layers: List<ShadowLayer>, style: JsonObject, resolver: StyleResolver): List<ShadowLayer> {
        if (style["shadowColor"]?.string != WORD || layers.isEmpty()) return layers
        val hue = hue(style["background"], resolver) ?: return layers
        return layers.map { it.copy(color = Rgba(hue.red, hue.green, hue.blue, it.color.alpha)) }
    }
}
