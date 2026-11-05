package com.hotlayout.render.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What a swipe button hands over to start the fill over the screen; lengths are in pixels of the window. */
internal class SwipeRippleSpec(
    /** The centre of the collapsed circle, in window coordinates. */
    val center: Offset,
    /** The diameter of the halo and of the core of the circle. */
    val halo: Float,
    val core: Float,
    /** The colour of the thumb: the core is this colour, the halo the same at [SwipeMotion.HALO_OPACITY]. */
    val color: Color,
    val indicatorColor: Color,
    val indicatorSize: Dp,
    /** Runs once, when the screen is covered. Not called when the fill is cancelled before. */
    val onCovered: () -> Unit,
)

/** One fill in flight. The button drops it when it leaves before the screen is covered. */
internal class SwipeRippleHandle(private val store: SwipeRippleStore, private val id: Int) {
    /** Removes the fill if the screen has not been covered yet; nothing runs. */
    fun cancel() = store.cancel(id)
}

/**
 * The fills over one screen: the circles that grow, and the stage they are in.
 *
 * The fill is a layer of the screen, not of the button: the button leaves the tree when the screen changes, and the fill
 * must still be there to show the next screen from under it. The circle grows linearly to cover the farthest corner, then
 * the action runs (the screen changes under the fill, with no motion of its own), then the fill fades away over the next screen.
 */
@Stable
internal class SwipeRippleStore(private val scope: CoroutineScope) {
    class Entry(val id: Int, val spec: SwipeRippleSpec, val inWindow: Boolean) {
        /** The action has run and the fill fades away. */
        var revealed by mutableStateOf(false)
    }

    val entries = mutableStateListOf<Entry>()
    private val timers = HashMap<Int, Job>()
    private var report: ((Boolean) -> Unit)? = null
    private var next = 0

    /** Whether a fill covers the screen now: the screen then changes with no motion of its own. */
    val covers: Boolean get() = entries.isNotEmpty()

    /** Whether a fill lies over the window, not over the screen. */
    val hasWindowLayer: Boolean get() = entries.any { it.inWindow }

    /**
     * Starts a fill. [report] hears `true` before the action when the screen starts to be covered, `false` once the last
     * fill is gone.
     */
    fun start(spec: SwipeRippleSpec, report: ((Boolean) -> Unit)?, overWindow: Boolean): SwipeRippleHandle {
        val wasCovering = covers
        next += 1
        val id = next
        if (report != null) this.report = report
        entries.add(Entry(id, spec, overWindow))
        timers[id] = scope.launch {
            delay(SwipeMotion.RIPPLE_MS.toLong())
            covered(id)
        }
        if (!wasCovering) report?.invoke(true)
        return SwipeRippleHandle(this, id)
    }

    private fun covered(id: Int) {
        val entry = entries.firstOrNull { it.id == id } ?: return
        entry.revealed = true
        timers[id] = scope.launch {
            delay(SwipeMotion.REVEAL_MS.toLong())
            remove(id)
        }
        entry.spec.onCovered()
    }

    fun cancel(id: Int) {
        // From the moment the action has run the fill belongs to the screen and goes on to its end.
        val entry = entries.firstOrNull { it.id == id } ?: return
        if (entry.revealed) return
        timers[id]?.cancel()
        remove(id)
    }

    private fun remove(id: Int) {
        timers.remove(id)
        if (!entries.removeAll { it.id == id } || entries.isNotEmpty()) return
        val report = report
        this.report = null
        // A button that leaves the tree asks for this while the tree is being updated, and the host may change its views on the answer.
        scope.launch { report?.invoke(false) }
    }
}

/** The place a button asks for a fill: the store of the screen it is in. */
@Stable
internal class SwipeRippleScope(private val store: SwipeRippleStore, private val report: (() -> ((Boolean) -> Unit)?)) {
    /** A host that hears `onScreenCovered` keeps its own routes: the fill then lies over the whole window. */
    fun start(spec: SwipeRippleSpec): SwipeRippleHandle {
        val reporter = report()
        return store.start(spec, reporter, overWindow = reporter != null)
    }
}

/** Whether a fill covers the screen: a change of screen then shows no motion of its own. */
internal val LocalFillCovers = compositionLocalOf { false }

/** The fills of a store over the box they are laid in, cut by its edges. */
@Composable
internal fun SwipeRippleLayers(store: SwipeRippleStore, inWindow: Boolean) {
    val shown = store.entries.filter { it.inWindow == inWindow }
    if (shown.isEmpty()) return
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { origin = it.positionInWindow() }) {
        val size = androidx.compose.ui.geometry.Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        for (entry in shown) androidx.compose.runtime.key(entry.id) { SwipeRippleLayer(entry, size, origin) }
    }
}

/** The layer over the window of a host that keeps its own stack of screens: above the host's views, so the fill outlives its screen. */
@Composable
internal fun SwipeRippleWindowLayer(store: SwipeRippleStore) {
    if (!store.hasWindowLayer) return
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
        },
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) { SwipeRippleLayers(store, inWindow = true) }
}

@Composable
private fun SwipeRippleLayer(entry: SwipeRippleStore.Entry, size: androidx.compose.ui.geometry.Size, origin: Offset) {
    val spec = entry.spec
    val center = spec.center - origin
    val scale = SwipeMotion.rippleScale(center.x.toDouble(), center.y.toDouble(), size.width.toDouble(), size.height.toDouble(), spec.core.toDouble())
    val grow = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(SwipeMotion.RIPPLE_MS.toFloat(), tween(SwipeMotion.RIPPLE_MS.toInt(), easing = LinearEasing)) }
    LaunchedEffect(entry.revealed) {
        if (entry.revealed) reveal.animateTo(SwipeMotion.REVEAL_MS.toFloat(), tween(SwipeMotion.REVEAL_MS.toInt(), easing = LinearEasing))
    }
    val scaleNow = (if (entry.revealed) scale else SwipeMotion.rippleAt(scale, grow.value.toDouble())).toFloat()
    val opacity = if (entry.revealed) SwipeMotion.revealAt(reveal.value.toDouble()).toFloat() else 1f
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = opacity }
            // While it grows the fill takes the presses; once the action has run it lets them through.
            .pointerInput(entry.revealed) {
                if (entry.revealed) return@pointerInput
                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(spec.color.copy(alpha = spec.color.alpha * SwipeMotion.HALO_OPACITY.toFloat()), spec.halo * scaleNow / 2, center)
            drawCircle(spec.color, spec.core * scaleNow / 2, center)
        }
        val indicator = with(LocalDensity.current) { spec.indicatorSize.roundToPx() }
        Box(Modifier.offset { IntOffset((center.x - indicator / 2f).roundToInt(), (center.y - indicator / 2f).roundToInt()) }.size(spec.indicatorSize)) {
            SwipeSpinner(spec.indicatorSize.value.toDouble(), spec.indicatorColor, still = false)
        }
    }
}
