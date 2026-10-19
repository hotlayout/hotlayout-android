package com.hotlayout.render.screen

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.NodeView
import com.hotlayout.render.Placement
import com.hotlayout.render.RenderContext
import com.hotlayout.render.layout.ColumnWidget
import com.hotlayout.render.layout.FlowPull
import com.hotlayout.render.layout.LocalScreenBodyHeight
import com.hotlayout.render.interaction.ProvideSiblings
import com.hotlayout.render.nodeBox
import com.hotlayout.render.withoutMargin
import com.hotlayout.theme.BoxLook
import com.hotlayout.theme.NodeStyle
import com.hotlayout.theme.Sides

/**
 * What the zones of a screen are, worked out from the document and the data: who is in which
 * zone, the lead and tail of the body, and the settings of the layout.
 */
internal class ScreenPlan(root: DocumentNode, private val context: RenderContext, look: BoxLook, val insets: ScreenInsets, val shrinks: Boolean) {
    val bar: DocumentNode?
    val topPinned: List<DocumentNode>
    val body: List<DocumentNode>
    val pinned: DocumentNode?
    val nav: DocumentNode?
    val bodyEmpty: Boolean
    val firstPull: Double
    val gap: Double
    val paddingTop = look.padding.top
    val paddingBottom = look.padding.bottom
    val paddingStart = look.padding.start
    val paddingEnd = look.padding.end
    val bodyTop: JsonValue? = root.props["bodyTop"]
    val bodyAtScreenTop: Boolean
    val floats: Boolean

    init {
        val parts = ScreenAnatomy.childrenByZone(root.children, root.props)
        fun shown(node: DocumentNode?): DocumentNode? = node?.takeIf { context.isShown(it) }
        bar = shown(parts.appBar)
        topPinned = parts.topPinned.filter { context.isShown(it) }
        body = parts.body
        pinned = shown(parts.pinned)
        // A navigation bar is not drawn in a modal layer, nor when it is hidden; it stands at the bottom on every width until the adaptive wave.
        nav = if (shrinks) null else shown(parts.navBar)
        val visibleBody = parts.body.filter { context.isShown(it) }
        bodyEmpty = visibleBody.isEmpty()
        firstPull = visibleBody.firstOrNull()?.let { child -> FlowPull.read(child, "Screen", root.props)?.let { context.resolver.spacing(it.first) } } ?: 0.0
        gap = context.resolver.spacing(root.props["gap"] ?: JsonValue.Str("sm")) ?: 0.0
        bodyAtScreenTop = ScreenAnatomy.bodyStartsAtScreenTop(bodyTop, if (bar == null) TopZoneOwner.None else TopZoneOwner.AppBar)
        floats = nav?.let { NavBarRules.floats("bottom", it.props) } ?: false
    }

    val hasHeader: Boolean get() = bar != null || topPinned.isNotEmpty()

    /**
     * What lies above the first child of the body inside the scroll: the gap of the screen under the
     * header, or the top padding of the root without one. A pulled first child lifts the body area
     * onto the header; the lift is part of the lead.
     */
    val lead: Double
        get() {
            val base = if (bodyAtScreenTop) (if (bar == null) paddingTop else 0.0) else if (hasHeader) (if (bodyEmpty) 0.0 else gap) else paddingTop
            return if (hasHeader && !bodyAtScreenTop) maxOf(base, firstPull) else base
        }

    /** How far the body area rises over the header. */
    val raise: Double get() = if (hasHeader && !bodyAtScreenTop) maxOf(0.0, firstPull - (if (bodyEmpty) 0.0 else gap)) else 0.0

    /** What lies under the last child: the gap of the screen above a pinned block, or the bottom padding of the root. */
    val tail: Double get() = if (pinned != null) (if (bodyEmpty) 0.0 else gap) else paddingBottom

    /** The lead of the top zone: the gap under the bar, or the inset and the top padding. */
    val topLead: Double get() = if (bar != null) gap else insets.top + paddingTop

    fun settings() = ScreenZonesSettings(
        shrink = shrinks, paddingTop = paddingTop, paddingBottom = paddingBottom, paddingStart = paddingStart, paddingEnd = paddingEnd, gap = gap,
        bodyEmpty = bodyEmpty, firstPull = firstPull, bodyTop = bodyTop, safeTop = insets.top, safeBottom = insets.bottom, keyboard = insets.keyboard, navFloat = floats,
    )
}

/**
 * A screen with the anatomy of the contract (`x-style.layout.screen`): the app bar and the bottom
 * zone are fixed and only the body scrolls; the background of the root lies under every zone; the
 * paddings of the root are shared between the zones.
 *
 * @param shrinks a modal layer: as tall as its content, not taller than the room it is given.
 * @param insets the platform insets; the window's own (or the host's) when `null`.
 */
@Composable
internal fun AnatomyScreen(root: DocumentNode, context: RenderContext, modifier: Modifier = Modifier, shrinks: Boolean = false, insets: ScreenInsets? = null) {
    val style = NodeStyle.effective(root, context.scope)
    val look = context.resolver.box(style, context.scope)
    val inner = context.copy(inherited = NodeStyle.inherited(context.inherited, style, context.resolver), listPosition = null, listNumber = null)
    val screenInsets = insets ?: rememberScreenInsets()
    val plan = ScreenPlan(root, context, look, screenInsets, shrinks)
    val scroll = rememberScrollState()
    val report = remember { ZoneReport() }
    val density = LocalDensity.current.density
    val current = rememberCurrent(plan)
    // Whether the bar is scrolled: strictly past the part of the bar zone that lies below the top of the body area.
    val scrolled by remember {
        derivedStateOf {
            val live = current.value
            if (live.bar == null || report.barHeight <= 0f) return@derivedStateOf false
            val barBottom = live.paddingTop + report.barHeight
            val bodyTop = if (live.bodyAtScreenTop) 0.0 else barBottom - live.raise
            AppBarRules.isScrolled(scroll.value / density.toDouble(), barBottom, bodyTop)
        }
    }
    // The root keeps its own paddings out of the box: they are shared between the zones.
    val container = (if (shrinks) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
        .nodeBox(look.withoutMargin().copy(padding = Sides.Zero), true, context.rightToLeft)
    // The zones read what the root's own style left: no style, no padding, so the host space is the body area itself.
    val zoneRoot = root.copy(fields = JsonObject())

    Box(modifier.then(container)) {
        // The children of the root are siblings of each other wherever the zones put them (the place of an option in its group).
        ProvideSiblings(root) {
        ScreenZonesLayout(plan.settings(), report, Modifier.then(if (shrinks) Modifier.fillMaxWidth() else Modifier.fillMaxSize())) {
            plan.bar?.let { bar ->
                val sink = if (plan.bodyAtScreenTop) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier
                Box(Modifier.inZone(ScreenZone.Bar).then(sink)) {
                    NodeView(bar, inner.copy(barInset = screenInsets.top, barScrolled = scrolled), Placement.Stretched, 1, "Screen")
                }
            }
            if (plan.topPinned.isNotEmpty()) {
                Column(Modifier.inZone(ScreenZone.Top)) {
                    Spacer(Modifier.height(plan.topLead.dp))
                    ColumnWidget(plan.topPinned, plan.gap, zoneRoot, inner, 0, ignoresFirstPull = true)
                }
            }
            BodyZone(plan, inner, zoneRoot, scroll, report, Modifier.inZone(ScreenZone.Body))
            plan.pinned?.let { pinned ->
                Box(Modifier.inZone(ScreenZone.Pinned)) { ColumnWidget(listOf(pinned), 0.0, zoneRoot, inner, 0, ignoresFirstPull = true) }
            }
            plan.nav?.let { nav ->
                Box(Modifier.inZone(ScreenZone.Nav)) {
                    NodeView(nav, inner.copy(navInset = if (plan.floats) 0.0 else screenInsets.bottom), Placement.Stretched, 1, "Screen")
                }
            }
        }
        }
    }
}

@Composable
private fun rememberCurrent(plan: ScreenPlan) = androidx.compose.runtime.rememberUpdatedState(plan)

/** The scrolling body: the lead, the children of the root that are in no other zone, the tail. */
@Composable
private fun BodyZone(plan: ScreenPlan, inner: RenderContext, zoneRoot: DocumentNode, scroll: androidx.compose.foundation.ScrollState, report: ZoneReport, modifier: Modifier) {
    val tail = plan.tail + if (plan.floats) report.floatTail else 0f
    val column: @Composable (androidx.compose.ui.unit.Dp?) -> Unit = { area ->
        Column(Modifier.then(if (area != null) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).verticalScroll(scroll)) {
            Spacer(Modifier.height(plan.lead.dp))
            Box(Modifier.padding(start = plan.paddingStart.dp, end = plan.paddingEnd.dp)) {
                // The visible body is what `justify` of the screen distributes (a layer as tall as its content has none).
                val free = area?.let { maxOf(0.0, it.value - plan.lead - plan.tail).dp }
                CompositionLocalProvider(LocalScreenBodyHeight provides free) {
                    ColumnWidget(plan.body, plan.gap, zoneRoot, inner, 0)
                }
            }
            Spacer(Modifier.height(tail.dp))
        }
    }
    if (plan.shrinks) {
        Box(modifier) { column(null) }
    } else {
        BoxWithConstraints(modifier) { column(maxHeight) }
    }
}
