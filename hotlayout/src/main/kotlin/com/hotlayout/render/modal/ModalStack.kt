package com.hotlayout.render.modal

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import com.hotlayout.render.screen.rememberConsumedInsets
import com.hotlayout.render.widgets.keyboardInset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.zIndex
import com.hotlayout.render.adaptive.WideChrome
import com.hotlayout.render.dp
import com.hotlayout.render.motion.rememberReducedMotion
import com.hotlayout.render.roundToInt
import kotlinx.coroutines.launch

/** One modal layer of the stack, as the screen hands it over. */
internal class ModalLayerSpec(
    /** The place in the stack: layers of one screen opened twice are two layers. */
    val index: Int,
    val screenId: String,
    val name: String,
    val window: Boolean,
    /** A sheet shown as a window in a wide place (`WideChrome.ModalShape.SheetWindow`): [window] too, but sized by the wide rule. */
    val sheetWindow: Boolean = false,
    /** The blur under the layer, 1…40, or `null`. */
    val blur: Int?,
    val dismissible: Boolean,
    /** How the layer comes when this step opened it (`null`: it was there, or nothing moves). */
    val enter: ScreenTransitionPlan? = null,
    val content: @Composable () -> Unit,
)

/** The layer a step closed and how: it stays drawn while the plan [plan] moves it out; [plan] is asked with whether a drag already took it down. */
internal class LayerLeave(val index: Int, val plan: (dragged: Boolean) -> ScreenTransitionPlan)

/** Whether the screen the composition is in is the one in front: a page under a layer, a layer under another, a page that is going away are not. */
internal val LocalScreenInFront = androidx.compose.runtime.compositionLocalOf { true }

/** The blur radius (logical pixels) the page under the layers is drawn with; read by tests from the semantics. */
internal val ModalPageBlurKey = SemanticsPropertyKey<Float>("HotlayoutModalPageBlur")
internal var SemanticsPropertyReceiver.modalPageBlur by ModalPageBlurKey

/** The blur radius a layer's own picture is drawn with (the layers above it blur it). */
internal val ModalLayerBlurKey = SemanticsPropertyKey<Float>("HotlayoutModalLayerBlur")
internal var SemanticsPropertyReceiver.modalLayerBlur by ModalLayerBlurKey

/**
 * Blur of the picture under the dim. Compose draws it with a render effect, which exists from API
 * 31; below that the picture stays sharp and the dim alone separates the layer from the page.
 */
internal fun Modifier.pictureBlur(radius: Double): Modifier =
    if (radius <= 0.0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) this else blur(radius.dp, BlurredEdgeTreatment.Rectangle)

/** The same blur with the share of it drawn now (0…1), read while drawing: the blur under a layer comes and goes with the layer's motion without recomposing. */
internal fun Modifier.pictureBlur(radius: Double, share: MutableFloatState): Modifier =
    if (radius <= 0.0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) this else graphicsLayer {
        val px = (radius * share.floatValue * density).toFloat()
        renderEffect = if (px > 0.01f) BlurEffect(px, px, TileMode.Clamp) else null
        clip = true
    }

/**
 * The page and the modal layers over it. Every layer is its own stacking context (a `zIndex` of its
 * own over a sibling that holds all of the page), and the page and the layers below the top are
 * inert: not focusable, not hit by a touch, hidden from accessibility. Under a layer the picture is
 * blurred by the layer's `backdropBlur`; the dim lies over the blur.
 *
 * @param focusSerial counts the moves the USER made in the stack (a press that shows another screen):
 * only a change of it moves the focus, so the first screen and the host's entry never take it.
 * @param dismiss one step back of the stack, as `navigate.back`.
 */
@Composable
internal fun ModalStack(
    page: @Composable () -> Unit,
    layers: List<ModalLayerSpec>,
    marginDp: Double,
    focusSerial: Int,
    dismiss: () -> Unit,
    modifier: Modifier = Modifier,
    leave: LayerLeave? = null,
) {
    val pageFocus = remember { FocusRequester() }
    val topKey = layers.lastOrNull()?.index
    LaunchedEffect(focusSerial) {
        // A layer on top takes the focus itself (see [ModalLayerView]); the page takes it back after the last layer closed.
        if (focusSerial != 0 && topKey == null) runCatching { pageFocus.requestFocus() }
    }
    val pageBlur = ModalRules.combinedBlur(layers.mapNotNull { it.blur })
    // How much of the blur under the layers is drawn now: it comes and goes with the layer that moves (1 at rest).
    val blurShare = remember { mutableFloatStateOf(1f) }
    // The layers of the last composition: one that left the stack is drawn from here while it sinks back.
    var seen by remember { mutableStateOf(emptyMap<Int, ModalLayerSpec>()) }
    var finished by remember { mutableStateOf(emptySet<Int>()) }
    // A place of the stack taken again by a new layer is no longer a finished exit.
    val takenAgain = layers.map { it.index }.filter { it in finished }
    if (takenAgain.isNotEmpty()) SideEffect { finished = finished - takenAgain.toSet() }
    // A layer that left the stack stays drawn while it sinks back: the stack (and the event) has already moved on.
    val currentIndexes = layers.map { it.index }.toSet()
    // Only the layer the step closed sinks back, and only when its plan moves it; any other change takes layers away at once.
    val leaves = leave != null && leave.plan(false).motion.isLayer
    val leaving = if (!leaves) emptyList() else seen.values.filter { it.index !in currentIndexes && it.index !in finished && it.index == leave!!.index }
    // The blur is drawn for the layers that are drawn, those that sink back included.
    val drawnBlur = ModalRules.combinedBlur((layers + leaving).mapNotNull { it.blur })
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .let { base ->
                    if (layers.isEmpty()) {
                        // The anchor of the focus after a step back: present only once the user has moved.
                        base.focusRequester(pageFocus).let { if (focusSerial > 0) it.focusable() else it }
                    } else {
                        base.focusProperties { canFocus = false }.clearAndSetSemantics { modalPageBlur = pageBlur.toFloat() }
                    }
                }
                .pictureBlur(drawnBlur, blurShare),
        ) { CompositionLocalProvider(LocalScreenInFront provides layers.isEmpty()) { page() } }
        SideEffect {
            val next = seen.filterKeys { it in currentIndexes || it !in finished } + layers.associateBy { it.index }
            if (next != seen) seen = next
        }
        val drawn = (layers + leaving).sortedBy { it.index }
        drawn.forEachIndexed { position, layer ->
            key(layer.index, layer.screenId) {
                val isLeaving = leaving.any { it === layer }
                val isTop = !isLeaving && layer === layers.lastOrNull()
                val above = if (isLeaving) 0.0 else ModalRules.combinedBlur(layers.filter { it.index > layer.index }.mapNotNull { it.blur })
                ModalLayerView(
                    layer, isTop, above, marginDp, focusSerial, dismiss, Modifier.zIndex((position + 1).toFloat()),
                    leaving = isLeaving, leavePlan = leave?.plan, blurShare = blurShare, onGone = { finished = finished + layer.index },
                )
            }
        }
    }
}

@Composable
private fun ModalLayerView(
    layer: ModalLayerSpec,
    isTop: Boolean,
    blurredBy: Double,
    marginDp: Double,
    focusSerial: Int,
    dismiss: () -> Unit,
    modifier: Modifier,
    leaving: Boolean = false,
    leavePlan: ((Boolean) -> ScreenTransitionPlan)? = null,
    blurShare: MutableFloatState? = null,
    onGone: () -> Unit = {},
) {
    val reduced = rememberReducedMotion()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val dismissNow = rememberUpdatedState(dismiss)
    val offset = remember { Animatable(0f) }
    // How the layer came (the plan of the step that opened it): it starts out of view only when that plan moves it.
    val enterPlan = remember { layer.enter?.takeIf { it.moves && it.motion.isLayerIn } }
    val appear = remember { Animatable(if (enterPlan == null) 1f else 0f) }
    // What the layer is doing now, and whether the dim moves with it (a layer over a layer leaves the dim still).
    var moving by remember { mutableStateOf(enterPlan?.motion ?: if (layer.window) TransitionMotion.WindowOut else TransitionMotion.SheetIn) }
    var scaleFrom by remember { mutableStateOf(enterPlan?.scaleFrom ?: 1.0) }
    var dimMoves by remember { mutableStateOf(enterPlan?.scrim != ScrimMotion.Still) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    var exiting by remember { mutableStateOf(false) }
    val scrollSink = remember { ModalBodyScroll() }
    val focus = remember { FocusRequester() }
    val topNow = rememberUpdatedState(isTop)
    LaunchedEffect(focusSerial) {
        // Only a move the user made takes the focus, and only to the layer on top.
        if (focusSerial != 0 && topNow.value) runCatching { focus.requestFocus() }
    }

    val gone = rememberUpdatedState(onGone)
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        // A layer a drag already took down has gone: the plan says «dragged» and nothing plays.
        val plan = leavePlan?.invoke(exiting) ?: ScreenTransitionPlan.still(StillReason.Dragged)
        exiting = true
        if (plan.moves) {
            moving = plan.motion
            dimMoves = plan.scrim != ScrimMotion.Still
            appear.animateTo(0f, planTween(plan)) { blurShare?.floatValue = if (dimMoves) value else 1f }
        }
        blurShare?.floatValue = 1f
        gone.value()
    }
    LaunchedEffect(Unit) {
        val plan = enterPlan ?: return@LaunchedEffect
        appear.animateTo(1f, planTween(plan)) { blurShare?.floatValue = if (dimMoves) value else 1f }
        blurShare?.floatValue = 1f
    }

    val live = isTop && !exiting

    Box(
        modifier.fillMaxSize()
            .let { base ->
                // A leaving layer carries no semantics at all, its tag included.
                if (leaving) base else base.testTag("modal:${layer.screenId}")
            }
            .let { base ->
                if (isTop) {
                    base.semantics {
                        isTraversalGroup = true
                        if (layer.name.isNotEmpty()) paneTitle = layer.name
                        if (layer.dismissible) dismiss { dismissNow.value(); true }
                        modalLayerBlur = blurredBy.toFloat()
                    }
                } else if (leaving) {
                    // Already gone from the stack: a screen reader must not meet it while it sinks back.
                    base.focusProperties { canFocus = false }.clearAndSetSemantics { }
                } else {
                    base.focusProperties { canFocus = false }.clearAndSetSemantics { modalLayerBlur = blurredBy.toFloat() }
                }
            }
            .let { base ->
                if (leaving) base.focusProperties { canFocus = false }.pointerInput(Unit) {
                    // The layer is on its way out: it takes every touch, so nothing of it is pressed twice.
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                } else base
            }
            .focusRequester(focus)
            .focusable(isTop)
            .onPreviewKeyEvent { event -> isTop && layer.dismissible && escapeKey(event.type == KeyEventType.KeyDown, event.key, event.nativeKeyEvent.repeatCount > 0) { dismissNow.value() } },
    ) {
        // The dim: it takes every touch, so nothing reaches what lies under the layer.
        Box(
            Modifier.fillMaxSize()
                .drawBehind {
                    // Read while drawing: the dim follows the drag and the appearance frame by frame without recomposing.
                    val dragged = offset.value
                    val visibleShare = if (dragged <= 0f || sheetHeight <= 0f) 1f else (1f - dragged / sheetHeight).coerceIn(0f, 1f)
                    drawRect(Color.Black.copy(alpha = (ModalRules.SCRIM_OPACITY * visibleShare * (if (dimMoves) appear.value else 1f)).toFloat()))
                }
                .testTag("modal-scrim:${layer.screenId}")
                .clearAndSetSemantics { }
                .pointerInput(live, layer.dismissible) { detectTapGestures { if (live && layer.dismissible) dismissNow.value() } },
        )
        ModalBox(layer.window, layer.sheetWindow, marginDp) {
            Box(
                Modifier
                    .sheetDrag(
                        enabled = !layer.window && layer.dismissible && isTop && !exiting,
                        sink = scrollSink,
                        offsetPx = { offset.value },
                        density = density,
                        onDrag = { px -> scope.launch { offset.snapTo(px) } },
                        onRelease = { dragged, velocity ->
                            val outcome = ModalRules.dragOutcome(sheetHeight / density.toDouble(), dragged, velocity)
                            scope.launch {
                                if (outcome == ModalRules.DragOutcome.Return) {
                                    offset.animateTo(0f, motion(ModalRules.returnMotion, reduced))
                                } else {
                                    exiting = true
                                    offset.animateTo(sheetHeight, motion(ModalRules.exitMotion, reduced))
                                    dismissNow.value()
                                }
                            }
                        },
                    )
                    .onSizeChanged { sheetHeight = it.height.toFloat() }
                    .graphicsLayer {
                        val share = appear.value
                        when (moving) {
                            // `y` of the plan is a share of the layer's own height.
                            TransitionMotion.SheetIn, TransitionMotion.SheetOut -> translationY = offset.value + (1f - share) * size.height
                            TransitionMotion.WindowIn -> {
                                val scale = (scaleFrom + (1 - scaleFrom) * share).toFloat()
                                scaleX = scale
                                scaleY = scale
                                alpha = share
                            }
                            else -> {
                                alpha = share
                                translationY = offset.value
                            }
                        }
                    }
                    .pictureBlur(blurredBy),
            ) { CompositionLocalProvider(LocalScreenInFront provides (isTop && !leaving)) { layer.content() } }
        }
    }
}

/** The motion of a plan on a share that runs 0 → 1 (or back): its length and its curve. */
private fun planTween(plan: ScreenTransitionPlan) =
    tween<Float>(plan.durationMs.toInt(), easing = CubicBezierEasing(plan.curve[0].toFloat(), plan.curve[1].toFloat(), plan.curve[2].toFloat(), plan.curve[3].toFloat()))

private fun motion(motion: ModalRules.Motion, reduced: Boolean) =
    tween<Float>(if (reduced) 0 else motion.durationMs, easing = motion.curve.let { CubicBezierEasing(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) })

/** Escape on a layer that may be closed: one step on the first key down, a held key closes nothing and is still taken. */
private fun escapeKey(down: Boolean, key: Key, repeating: Boolean, step: () -> Unit): Boolean {
    if (!down || key != Key.Escape) return false
    if (ModalRules.escapeSteps("Escape", repeating, modalOnTop = true, topDismissible = true) > 0) step()
    return true
}

/**
 * Places its one child as [ModalRules.box] says: a sheet at the bottom of the area, a window in the
 * middle. The height is that of the content, no taller than the room; taller content is measured
 * with the smaller height and its body scrolls inside. While the on-screen keyboard is shown the area ends at its
 * top, so the layer rises above it (`x-style.layout.inputs.focus.modal`); a keyboard that an ancestor of the host
 * has already consumed is not taken twice.
 */
@Composable
private fun ModalBox(window: Boolean, sheetWindow: Boolean, marginDp: Double, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val shape = when {
        sheetWindow -> WideChrome.ModalShape.SheetWindow
        window -> WideChrome.ModalShape.Window
        else -> WideChrome.ModalShape.Sheet
    }
    val consumed = rememberConsumedInsets()
    val keyboard = keyboardInset(consumed.consumed).value.toDouble()
    Layout(content, Modifier.then(consumed.modifier).fillMaxSize()) { measurables, constraints ->
        // The rules speak logical pixels (400 wide at most): the box is worked out in them and then scaled.
        val width = constraints.maxWidth / density.toDouble()
        val height = maxOf(0.0, constraints.maxHeight / density.toDouble() - keyboard)
        val layerWidth = (WideChrome.layerWidth(shape, width, marginDp) * density).roundToInt()
        val available = (WideChrome.layerRoom(shape, height, marginDp) * density).roundToInt()
        val placeable = measurables.firstOrNull()?.measure(Constraints(layerWidth, layerWidth, 0, available))
        val box = WideChrome.layerBox(shape, width, height, (placeable?.height ?: 0) / density.toDouble(), marginDp).box
        layout(constraints.maxWidth, constraints.maxHeight) { placeable?.place((box.x * density).roundToInt(), (box.y * density).roundToInt()) }
    }
}
