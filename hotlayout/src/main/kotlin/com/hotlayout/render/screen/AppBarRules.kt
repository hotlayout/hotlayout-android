package com.hotlayout.render.screen

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.theme.StyleResolver

/**
 * The rules of the app bar (`x-style.layout.appBar`, `appBarLeading`, `appBarTitle`, `appBarScrolled`):
 * three zones in reading order, the node of the leading zone, the actions that are drawn, and the
 * state «scrolled». Coordinates run from the START of the row in the reading direction; whoever
 * draws mirrors them for right-to-left text.
 */
internal object AppBarRules {
    /** How many actions the bar draws: the first two VISIBLE ones. */
    const val MAX_DRAWN_ACTIONS = 2

    /** The widest share of the bar the node of the leading zone takes. */
    const val LEADING_MAX_SHARE = 0.5
    const val BACK_SIDE_TOKEN = "xl"
    const val GAP_TOKEN = "sm"
    const val ACTION_GAP_TOKEN = "xs"
    const val SUBTITLE_GAP_TOKEN = "xs"

    /** The words of `zone` the reader knows; only `leading`. */
    fun zone(node: DocumentNode): String? = if (node.fields["zone"]?.string == "leading") "leading" else null

    fun canStandInLeadingZone(type: String): Boolean = type == "Button" || type == "IconButton"

    /** The index of the node of the leading zone: the first child with the field whose type may stand there. */
    fun leadingIndex(children: List<DocumentNode>): Int? =
        children.indices.firstOrNull { zone(children[it]) == "leading" && canStandInLeadingZone(children[it].type) }

    /** The indexes of the actions: icon buttons WITHOUT the field, in document order. */
    fun actionIndexes(children: List<DocumentNode>): List<Int> =
        children.indices.filter { children[it].type == "IconButton" && zone(children[it]) == null }

    /** Which actions are drawn, given which are visible: the first two visible ones. */
    fun drawn(visible: List<Boolean>): List<Boolean> {
        var count = 0
        return visible.map { isVisible ->
            if (!isVisible || count >= MAX_DRAWN_ACTIONS) false else { count += 1; true }
        }
    }

    enum class LeadingShown(val word: String) { Node("node"), Back("back"), None("none") }

    /** What stands in the leading zone: a VISIBLE node replaces the arrow whatever `showBack` says; a hidden one leaves it to `showBack`. */
    fun leadingShown(nodeVisible: Boolean?, showBack: Boolean): LeadingShown = when {
        nodeVisible == true -> LeadingShown.Node
        showBack -> LeadingShown.Back
        else -> LeadingShown.None
    }

    /** The width of the leading node: natural, at most half of the bar. */
    fun leadingWidth(natural: Double, barWidth: Double): Double = maxOf(0.0, minOf(natural, barWidth * LEADING_MAX_SHARE))

    enum class TitleAlign { Start, Center }

    fun titleAlign(value: JsonValue?): TitleAlign = if (value?.string == "start") TitleAlign.Start else TitleAlign.Center

    /** `titleShown: scrolled` — the title is drawn only while the body is scrolled. Another word and no field mean «always». */
    fun titleShownOnlyWhenScrolled(props: JsonObject): Boolean = props["titleShown"]?.string == "scrolled"

    class ZonesInput(
        val width: Double,
        val showBack: Boolean,
        val backSide: Double,
        val actionSides: List<Double>,
        val gap: Double,
        val actionGap: Double,
        val titleNatural: Double,
        /** The natural width of the visible node of the leading zone; `null` — none. */
        val leadingNatural: Double? = null,
        val titleAlign: JsonValue? = null,
    )

    data class Zones(
        val leading: Double,
        val trailing: Double,
        val titleRoom: Double,
        val titleWidth: Double,
        val titleStart: Double,
        val centered: Boolean,
        val truncated: Boolean,
        val actionStarts: List<Double>,
        val leadingShown: LeadingShown,
        val leadingWidth: Double,
    )

    fun zones(input: ZonesInput): Zones {
        val actions = input.actionSides.sum() + maxOf(0, input.actionSides.size - 1) * input.actionGap
        val shown = leadingShown(if (input.leadingNatural == null) null else true, input.showBack)
        val leadingWidth = when (shown) {
            LeadingShown.Node -> leadingWidth(input.leadingNatural ?: 0.0, input.width)
            LeadingShown.Back -> input.backSide
            LeadingShown.None -> 0.0
        }
        val leading = if (shown == LeadingShown.None) 0.0 else leadingWidth + input.gap
        val trailing = if (input.actionSides.isEmpty()) 0.0 else actions + input.gap
        val titleRoom = maxOf(0.0, input.width - leading - trailing)
        val titleWidth = minOf(maxOf(0.0, input.titleNatural), titleRoom)
        val ideal = (input.width - titleWidth) / 2
        // Shifted off a zone by exactly as much as needed; the leading zone is stronger when there is no room at all.
        val titleStart = if (titleAlign(input.titleAlign) == TitleAlign.Start) leading else maxOf(leading, minOf(ideal, input.width - trailing - titleWidth))
        var cursor = input.width - actions
        val starts = input.actionSides.map { side ->
            val start = cursor
            cursor += side + input.actionGap
            start
        }
        return Zones(leading, trailing, titleRoom, titleWidth, titleStart, titleStart == ideal, input.titleNatural > titleRoom, starts, shown, leadingWidth)
    }

    // Scrolled

    /** The keys a scrolled layer may carry. */
    val scrolledKeys = listOf("background", "color", "shadow", "backdropBlur")

    /** How far the body must be scrolled before the bar is «scrolled»: the part of the bar zone that lies below the top of the body area. */
    fun scrolledThreshold(barBottom: Double, bodyTop: Double): Double = maxOf(0.0, barBottom - bodyTop)

    /** Strictly past the threshold; a scroll that is not finite is not scrolled. */
    fun isScrolled(scrollTop: Double, barBottom: Double, bodyTop: Double): Boolean =
        scrollTop.isFinite() && scrollTop > scrolledThreshold(barBottom, bodyTop)

    /** The scrolled layer of the bar node: only the four keys the structure allows; `null` when the field is absent or empty. */
    fun scrolledStyle(node: DocumentNode): JsonObject? {
        val source = node.fields["styleScrolled"]?.obj ?: return null
        val layer = ArrayList<Pair<String, JsonValue>>()
        for (key in scrolledKeys) source[key]?.takeIf { !it.isNull }?.let { layer.add(key to it) }
        return if (layer.isEmpty()) null else JsonObject(layer)
    }

    /**
     * The style while the bar is scrolled: the layer lies LAST, over the base style, the width-class
     * layers and the state. A field whose token the theme does not know is dropped, and the field under it stays.
     */
    fun withScrolledLayer(style: JsonObject, scrolled: JsonObject?, resolver: StyleResolver): JsonObject {
        if (scrolled == null) return style
        var result = style
        for (key in scrolledKeys) {
            val value = scrolled[key] ?: continue
            if (resolves(key, value, resolver)) result = result.with(key, value)
        }
        return result
    }

    private fun resolves(key: String, value: JsonValue, resolver: StyleResolver): Boolean = when (key) {
        "color" -> resolver.color(value) != null
        "shadow" -> resolver.shadow(value) != null
        "background" -> when (value["kind"]?.string) {
            "color" -> resolver.color(value["color"]) != null
            "gradient" -> resolver.gradient(value["gradient"]) != null
            else -> true
        }
        else -> true
    }
}
