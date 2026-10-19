package com.hotlayout.render.widgets

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext
import com.hotlayout.render.color
import com.hotlayout.render.layout.AlignWord
import com.hotlayout.render.layout.AlignedRow
import com.hotlayout.render.layout.FlowChild
import com.hotlayout.render.layout.JustifyWord
import com.hotlayout.render.layout.RowKind
import com.hotlayout.render.motion.rememberReducedMotion
import com.hotlayout.theme.NodeStyle
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.roundToInt

/** How long the scroll takes to settle on a stop. */
private const val SNAP_MS = 260

/** How far ahead of the finger a throw is projected, in seconds of its velocity. */
private const val THROW_SECONDS = 0.12f

/**
 * A row of cards that scrolls sideways, snaps to the stops and shows the position as dots
 * (`x-style.layout.carousel`). The node's padding lies INSIDE the scroll: the first card stands a
 * padding from the edge and the last one scrolls past it.
 *
 * [count] cards are drawn by [card] with the width of its box (`null`: its own) and the width of the
 * place when it is known. The width of the place decides everything and the carousel never changes
 * it: an unbounded place is «not known» and keeps the view as it is.
 */
@Composable
internal fun CarouselRow(
    node: DocumentNode,
    context: RenderContext,
    spec: CarouselSpec,
    count: Int,
    gap: Double,
    align: AlignWord,
    kindOf: (Int) -> RowKind = { RowKind.Natural },
    card: @Composable (index: Int, cardWidth: Double?, viewport: Dp?) -> Unit,
) {
    val resolver = context.resolver
    val padding = resolver.box(NodeStyle.effective(node, context.scope), context.scope).padding
    val density = LocalDensity.current.density
    var viewportPx by remember { mutableIntStateOf(0) }
    val widthsPx = remember { mutableStateMapOf<Int, Int>() }
    val scroll = rememberScrollState()
    val reduced = rememberReducedMotion()

    val viewport: Double? = if (viewportPx > 0) viewportPx / density.toDouble() else null
    val peek = CarouselRules.peekPixels(spec.peek) { resolver.spacing(JsonValue.Str(it)) }
    val cardWidth = if (viewport != null && spec.perView != null) CarouselRules.cardWidth(viewport, padding.start, gap, peek, spec.perView) else null
    val measured = (0 until count).map { widthsPx[it] }
    val known = viewport != null && count > 0 && measured.all { it != null }
    val plan = if (known) {
        CarouselRules.stops(viewport!!, padding.start, padding.end, gap, measured.map { it!! / density.toDouble() })
    } else {
        null
    }
    val stopsPx = plan?.stops?.map { (it * density).toFloat() }.orEmpty()

    val memoryKey = CarouselMemory.key(context.frameIndex, context.screenId, CarouselRules.stateKey(node.id, context.instancePath))
    var restored by remember { mutableIntStateOf(0) }
    val remembered = context.carousels?.stop(memoryKey)
    val active = when {
        plan == null -> 0
        restored == 0 && remembered != null -> remembered.coerceIn(0, plan.stops.size - 1)
        else -> CarouselRules.activeStop(plan.stops, scroll.value / density.toDouble())
    }

    // Put the scroll back on the stop the frame remembers, once the cards are measured at their final width.
    val settled = known && (cardWidth == null || measured.all { it!! >= cardWidth * density - 1 })
    LaunchedEffect(settled, stopsPx) {
        if (!settled || restored != 0) return@LaunchedEffect
        awaitFrame()
        restored = 1
        val stop = remembered ?: 0
        if (stop in 1 until stopsPx.size) scroll.scrollTo(stopsPx[stop].roundToInt())
    }
    val latest = rememberUpdatedState(plan)
    LaunchedEffect(memoryKey) {
        snapshotFlow { scroll.value }.collect { value ->
            val now = latest.value ?: return@collect
            if (restored != 0) context.carousels?.set(CarouselRules.activeStop(now.stops, value / density.toDouble()), memoryKey)
        }
    }

    val fling = remember(scroll, reduced) { SnapFling(scroll, reduced) }
    fling.stops = stopsPx

    val dotSize = resolver.spacing(JsonValue.Str(CarouselRules.DOT_SIZE_TOKEN)) ?: 8.0
    val dotGap = resolver.spacing(JsonValue.Str(CarouselRules.DOT_GAP_TOKEN)) ?: 8.0
    val indicatorGap = resolver.spacing(JsonValue.Str(CarouselRules.INDICATOR_GAP_TOKEN)) ?: 8.0
    val dots = if (viewport != null && plan != null) CarouselRules.dotCount(spec.dots, plan.stops.size, viewport, dotSize, dotGap) else 0

    Column(
        Modifier.then(PlaceModifier { place -> if (viewportPx != place) viewportPx = place }).semantics { collectionInfo = CollectionInfo(rowCount = 1, columnCount = count) },
    ) {
        AlignedRow(
            gap, JustifyWord.Start, align, null, false,
            Modifier.fillMaxWidth().horizontalScroll(scroll, flingBehavior = fling).focusable()
                .padding(start = padding.start.dp, top = padding.top.dp, end = padding.end.dp, bottom = padding.bottom.dp),
        ) {
            for (index in 0 until count) {
                Box(
                    Modifier.then(FlowChild(kind = kindOf(index))).onSizeChanged { widthsPx[index] = it.width }
                        .semantics { collectionItemInfo = CollectionItemInfo(rowIndex = 0, rowSpan = 1, columnIndex = index, columnSpan = 1) },
                ) { card(index, cardWidth, viewport?.dp) }
            }
        }
        if (dots > 0) {
            val base = resolver.color(JsonValue.Str(CarouselRules.DOT_COLOR_TOKEN))?.color ?: Color(0xFF4F46E5)
            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(top = indicatorGap.dp).clearAndSetSemantics { },
                horizontalArrangement = Arrangement.spacedBy(dotGap.dp),
            ) {
                for (index in 0 until dots) {
                    Box(
                        Modifier.testTag(if (index == active) "carousel:dot:active" else "carousel:dot").size(dotSize.dp)
                            .alpha(if (index == active) 1f else CarouselRules.INACTIVE_DOT_OPACITY.toFloat())
                            .clip(CircleShape).background(base),
                    )
                }
            }
        }
    }
}

/**
 * Reports the width the place gives (a bounded maximum width; `0` when it is unbounded) and fills it.
 * Only the real measure reports: the intrinsic queries of a parent run the same layout with other
 * constraints and would make the place flip between two answers for ever.
 */
private class PlaceModifier(private val onPlace: (Int) -> Unit) : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val bounded = constraints.hasBoundedWidth
        val placeable = measurable.measure(if (bounded) constraints.copy(minWidth = constraints.maxWidth) else constraints)
        onPlace(if (bounded) constraints.maxWidth else 0)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = measurable.minIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) = measurable.maxIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = measurable.minIntrinsicHeight(width)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) = measurable.maxIntrinsicHeight(width)
}

/** Ends a drag or a throw on the stop it would reach; with reduced motion the scroll jumps there. */
private class SnapFling(private val scroll: androidx.compose.foundation.ScrollState, private val reduced: Boolean) : FlingBehavior {
    var stops: List<Float> = emptyList()

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (stops.size < 2) return initialVelocity
        val from = scroll.value.toFloat()
        val projected = from + initialVelocity * THROW_SECONDS
        val index = CarouselRules.activeStop(stops.map { it.toDouble() }, projected.toDouble())
        val to = stops[index].coerceAtMost(scroll.maxValue.toFloat())
        if (reduced) {
            scrollBy(to - from)
        } else {
            var last = 0f
            animate(0f, to - from, animationSpec = tween(SNAP_MS, easing = FastOutSlowInEasing)) { value, _ ->
                scrollBy(value - last)
                last = value
            }
        }
        return 0f
    }
}
