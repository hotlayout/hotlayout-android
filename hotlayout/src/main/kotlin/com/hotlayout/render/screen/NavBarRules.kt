package com.hotlayout.render.screen

import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.data.Conditions
import com.hotlayout.model.WidthClass
import com.hotlayout.render.adaptive.WideChrome

/**
 * The rules of the navigation bar (`x-style.layout.navBar`, `navBadge`, `navBadgeMotion`): its
 * height, the shares of the items, which one is active, the dot on an item.
 */
internal object NavBarRules {
    const val MAX_ITEMS = 5
    const val DOT_DIAMETER = 8.0
    const val APPEAR_MS = 320.0
    const val DISAPPEAR_MS = 200.0
    const val PEAK_SCALE = 1.25
    const val PEAK_AT = 0.6

    class Metrics(val paddingBlock: Double, val iconBox: Double, val gap: Double, val labelLine: Double, val borderTop: Double)

    /** The height of the bar: top line, padding, icon box, gap, one label line, padding. */
    fun height(metrics: Metrics): Double =
        metrics.borderTop + metrics.paddingBlock + metrics.iconBox + metrics.gap + metrics.labelLine + metrics.paddingBlock

    /** The items that are drawn: the first five visible ones. */
    fun drawn(visible: List<Boolean>): List<Boolean> {
        var count = 0
        return visible.map { isVisible -> if (!isVisible || count >= MAX_ITEMS) false else { count += 1; true } }
    }

    class Share(val start: Double, val width: Double)

    /** The share of the bar width of each item: equal parts among the drawn items, the first at the start of the reading direction. */
    fun shares(barWidth: Double, visible: List<Boolean>, rightToLeft: Boolean): List<Share?> {
        val flags = drawn(visible)
        val count = flags.count { it }
        if (count == 0) return visible.map { null }
        val width = maxOf(0.0, barWidth) / count
        var position = 0
        return flags.map { isDrawn ->
            if (!isDrawn) return@map null
            val index = position
            position += 1
            Share(if (rightToLeft) barWidth - width * (index + 1) else width * index, width)
        }
    }

    // Sections

    /** The target of a `navigate.section` action: a non-empty `screenId` parameter. */
    fun sectionTarget(action: JsonObject?): String? {
        if (action?.get("type")?.string != "navigate.section") return null
        return action["params"]?.get("screenId")?.string?.takeIf { it.isNotEmpty() }
    }

    /** An item is active when it switches to the screen that is shown. */
    fun isActive(action: JsonObject?, shown: String): Boolean = sectionTarget(action) == shown

    /** The stack after a section switch: the target alone; `null` when it already is the only screen of the stack. */
    fun sectionStack(stack: List<String>, target: String): List<String>? =
        if (stack.size == 1 && stack[0] == target) null else listOf(target)

    // The dot

    /** Whether an item shows its dot: a condition the reader understands, and it holds. */
    fun badgeShown(props: JsonObject, scope: DataScope): Boolean {
        val condition = props["badgeWhen"] ?: return false
        return Conditions.understoodHolds(condition, scope)
    }

    class DotBox(val left: Double, val top: Double, val size: Double)

    /** The box of the dot: its centre on the top inline-end corner of the glyph. */
    fun badgeBox(glyphLeft: Double, glyphTop: Double, glyphSize: Double, rightToLeft: Boolean): DotBox {
        val corner = if (rightToLeft) glyphLeft else glyphLeft + glyphSize
        return DotBox(corner - DOT_DIAMETER / 2, glyphTop - DOT_DIAMETER / 2, DOT_DIAMETER)
    }

    enum class BadgeChange(val word: String) { Appear("appear"), Disappear("disappear"), None("none") }

    /** Whether the dot moves: only when its condition changes its answer on a shown screen, and not with reduced motion. */
    fun badgeChange(shownBefore: Boolean, before: Boolean, after: Boolean, reducedMotion: Boolean): BadgeChange = when {
        !shownBefore || reducedMotion || before == after -> BadgeChange.None
        after -> BadgeChange.Appear
        else -> BadgeChange.Disappear
    }

    private fun share(t: Double, duration: Double): Double = minOf(1.0, maxOf(0.0, t / duration))

    /** The scale of the dot [t] milliseconds into the appearance: a point, the peak at six tenths, then settles at one. */
    fun appearScale(t: Double): Double {
        val p = share(t, APPEAR_MS)
        if (p <= PEAK_AT) return PEAK_SCALE * (p / PEAK_AT)
        return PEAK_SCALE + (1 - PEAK_SCALE) * ((p - PEAK_AT) / (1 - PEAK_AT))
    }

    fun disappearOpacity(t: Double): Double = 1 - share(t, DISAPPEAR_MS)

    // Float

    /** Whether the bar floats: it stands in the bottom zone and names `float: true`. */
    fun floats(place: String, props: JsonObject): Boolean = place == "bottom" && props["float"]?.bool == true

    /** Where the bar stands (`bottom`, `appBar` or `top`): the rule of the wide places, [com.hotlayout.render.adaptive.WideChrome.navPlace]. */
    fun place(widthClass: String, widePlacement: com.hotlayout.JsonValue?, appBarShown: Boolean): String =
        WideChrome.navPlace(
            WidthClass.named(widthClass) ?: WidthClass.Unknown, widePlacement?.string == "bottom", appBarShown,
        ).word
}
