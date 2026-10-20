package com.hotlayout.render.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import com.hotlayout.JsonValue
import com.hotlayout.render.roundToInt
import kotlin.math.abs

/** The zone of the screen a child of [ScreenZonesLayout] stands in. */
internal enum class ScreenZone {
    Bar, Top, Body, Pinned, Nav,

    /** The navigation bar of its own at the top of the screen (`wideNav.ownBar`); takes the whole top, the rest is counted under it. */
    TopNav,
}

internal fun Modifier.inZone(zone: ScreenZone): Modifier = layoutId(zone)

/** What the zones of one screen depend on, besides the sizes of the children. Numbers are in logical pixels. */
internal data class ScreenZonesSettings(
    /** A modal layer is as tall as its content, not taller than the area it is given. */
    val shrink: Boolean = false,
    val paddingTop: Double = 0.0,
    val paddingBottom: Double = 0.0,
    val paddingStart: Double = 0.0,
    val paddingEnd: Double = 0.0,
    val gap: Double = 0.0,
    /** The body has no visible child. */
    val bodyEmpty: Boolean = false,
    val firstPull: Double = 0.0,
    val bodyTop: JsonValue? = null,
    val safeTop: Double = 0.0,
    val safeBottom: Double = 0.0,
    val keyboard: Double = 0.0,
    val navFloat: Boolean = false,
)

/** What the layout tells the composition about what it measured. Written during measure only when a value changed. */
internal class ZoneReport {
    /** The height of the box of the app bar, with the inset inside it. */
    var barHeight by mutableFloatStateOf(0f)

    /** The height a floating bottom zone adds to the end of the body. */
    var floatTail by mutableFloatStateOf(0f)

    fun report(bar: Double, tail: Double) {
        if (abs(barHeight - bar) > 0.01) barHeight = bar.toFloat()
        if (abs(floatTail - tail) > 0.01) floatTail = tail.toFloat()
    }
}

/**
 * The layout of a screen: the app bar, the top zone, the body, the pinned block and the navigation
 * bar. Only the body scrolls; the other zones stand where [ScreenAnatomy.zones] puts them
 * (`x-style.layout.screen`). Placement is logical: `placeRelative` mirrors it in right-to-left text.
 */
@Composable
internal fun ScreenZonesLayout(settings: ScreenZonesSettings, report: ZoneReport, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val policy = remember(settings, report) { ScreenZonesPolicy(settings, report) }
    Layout(content, modifier, policy)
}

private val Measurable.zone: ScreenZone? get() = layoutId as? ScreenZone

private class ScreenZonesPolicy(val settings: ScreenZonesSettings, val report: ZoneReport) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val d = density.toDouble()
        val s = settings
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val bounded = constraints.hasBoundedHeight
        // A layer inside a parent with no height is as tall as its content.
        val available = if (bounded) constraints.maxHeight else LIMIT
        val shrink = s.shrink || !bounded
        fun px(dp: Double) = (dp * d).roundToInt()
        val inner = maxOf(0, width - px(s.paddingStart) - px(s.paddingEnd))
        val inside = Constraints(minWidth = inner, maxWidth = inner)
        fun zone(zone: ScreenZone) = measurables.firstOrNull { it.zone == zone }

        val bar = zone(ScreenZone.Bar)?.measure(inside)
        val topZone = zone(ScreenZone.Top)?.measure(inside)
        val pinned = zone(ScreenZone.Pinned)?.measure(inside)
        val nav = zone(ScreenZone.Nav)?.measure(Constraints(minWidth = width, maxWidth = width))
        val topNav = zone(ScreenZone.TopNav)?.measure(Constraints(minWidth = width, maxWidth = width))
        // The bar of its own takes the top of the screen; everything else is counted in what is left under it.
        val topNavHeight = (topNav?.height ?: 0) / d
        val barHeight = (bar?.height ?: 0) / d
        val topHeight = (topZone?.height ?: 0) / d
        val navHeight = nav?.let { it.height / d }
        val floats = s.navFloat && nav != null
        // The bar holds the inset inside its box; a floating bar does not: the inset lies under it.
        val navContent = navHeight?.let { if (floats) it else maxOf(0.0, it - s.safeBottom) }

        var input = AnatomyInput(
            height = available / d - topNavHeight, shrink = shrink, appBarHeight = null, paddingTop = s.paddingTop, paddingBottom = s.paddingBottom,
            gap = s.gap, bodyContentHeight = if (s.bodyEmpty) null else 0.0, firstPull = s.firstPull,
            bottom = if (pinned != null || navContent != null) BottomZoneInput(pinned?.let { it.height / d }, navContent, s.safeBottom) else null,
            navFloat = floats, bodyTop = s.bodyTop, safeTop = s.safeTop, keyboard = if (shrink) 0.0 else s.keyboard,
        )
        var barTop: Double? = null
        var topZoneTop: Double? = null
        if (topZone != null) {
            // The top zone holds the inset and the root padding itself; the bar above it holds the inset in its box.
            val header = (if (bar != null) s.paddingTop + barHeight else 0.0) + topHeight
            input = input.copy(appBarHeight = header - s.paddingTop, safeTop = 0.0, bodyTop = null)
            barTop = if (bar != null) s.paddingTop else null
            topZoneTop = (if (bar != null) s.paddingTop + barHeight else 0.0) + topNavHeight
        } else if (bar != null) {
            input = input.copy(appBarHeight = maxOf(0.0, barHeight - ScreenAnatomy.safeTop(s.safeTop, input.height)))
        }
        var zones = ScreenAnatomy.zones(input)
        val body = zone(ScreenZone.Body)
        var bodyPlaced: Placeable? = null
        if (shrink && !s.bodyEmpty && body != null) {
            // The body gives its own length: lead, the column, tail. A measurable is measured once, so it is given the most room it can have:
            // what a body this long would leave (the rule with a huge flow) is the room the layer has.
            val roomy = ScreenAnatomy.zones(input.copy(bodyContentHeight = HUGE))
            val room = px(roomy.body.height)
            // A body from the top edge is not shorter than the bar that lies over it: the area then is the one of a screen as tall as the bar's bottom edge.
            val floor = roomy.appBar?.takeIf { roomy.bodyAtScreenTop }?.let { minOf(it.top + it.height, maxOf(0.0, input.height)) }
            val least = floor?.let { px(ScreenAnatomy.zones(input.copy(shrink = false, height = it)).body.height) } ?: 0
            val placed = body.measure(Constraints(minWidth = width, maxWidth = width, minHeight = minOf(room, least), maxHeight = room))
            input = input.copy(bodyContentHeight = maxOf(0.0, placed.height / d - zones.contentHeight))
            zones = ScreenAnatomy.zones(input)
            bodyPlaced = placed
        }
        if (bar != null && topZone == null) barTop = zones.appBar?.top
        zones = zones.lowered(topNavHeight)
        if (bodyPlaced == null && body != null) bodyPlaced = body.measure(Constraints.fixed(width, px(zones.body.height)))
        report.report(barHeight, if (zones.float) zones.bottom?.height ?: 0.0 else 0.0)
        val total = constraints.constrainHeight(px(zones.height))

        return layout(width, total) {
            val atTop = zones.bodyAtScreenTop
            topNav?.placeRelative(0, 0)
            fun placeBar() { if (bar != null && barTop != null) bar.placeRelative(px(s.paddingStart), px(barTop!!)) }
            // With the body from the top edge the bar lies over it; otherwise a pulled first child lifts the body over the bar.
            if (atTop) bodyPlaced?.placeRelative(0, px(zones.body.top))
            placeBar()
            if (topZone != null && topZoneTop != null) topZone.placeRelative(px(s.paddingStart), px(topZoneTop))
            if (!atTop) bodyPlaced?.placeRelative(0, px(zones.body.top))
            zones.bottom?.let { bottom ->
                if (pinned != null && bottom.pinned != null) pinned.placeRelative(px(s.paddingStart), px(bottom.pinned.top))
                if (nav != null && bottom.navBar != null) nav.placeRelative(0, px(bottom.navBar.top))
            }
        }
    }

    private companion object {
        /** The height of a screen that was given none: large enough for any content, finite for the scroll. */
        const val LIMIT = 200_000
        const val HUGE = 1.0e6
    }
}
