package com.hotlayout.render.modal

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
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
    val content: @Composable () -> Unit,
)

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
) {
    val pageFocus = remember { FocusRequester() }
    val focus = remember { HashMap<Int, FocusRequester>() }
    val topKey = layers.lastOrNull()?.index
    LaunchedEffect(focusSerial) {
        if (focusSerial == 0) return@LaunchedEffect
        val target = if (topKey == null) pageFocus else focus.getOrPut(topKey) { FocusRequester() }
        runCatching { target.requestFocus() }
    }
    val pageBlur = ModalRules.combinedBlur(layers.mapNotNull { it.blur })
    val reduced = rememberReducedMotion()
    val seen = remember { LinkedHashMap<Int, ModalLayerSpec>() }
    var finished by remember { androidx.compose.runtime.mutableStateOf(emptySet<Int>()) }
    // A place of the stack taken again by a new layer is no longer a finished exit.
    val takenAgain = layers.map { it.index }.filter { it in finished }
    if (takenAgain.isNotEmpty()) SideEffect { finished = finished - takenAgain.toSet() }
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
                .pictureBlur(pageBlur),
        ) { page() }
        // A layer that left the stack stays drawn while it sinks back: the stack (and the event) has already moved on.
        val currentIndexes = layers.map { it.index }.toSet()
        val leaving = if (reduced) emptyList() else seen.values.filter { it.index !in currentIndexes && it.index !in finished }
        SideEffect {
            seen.keys.retainAll { it in currentIndexes || it !in finished }
            layers.forEach { seen[it.index] = it }
        }
        val drawn = (layers + leaving).sortedBy { it.index }
        drawn.forEachIndexed { position, layer ->
            androidx.compose.runtime.key(layer.index, layer.screenId) {
                val isLeaving = leaving.any { it === layer }
                val isTop = !isLeaving && layer === layers.lastOrNull()
                val above = if (isLeaving) 0.0 else ModalRules.combinedBlur(layers.filter { it.index > layer.index }.mapNotNull { it.blur })
                ModalLayerView(
                    layer, isTop, above, marginDp, focus.getOrPut(layer.index) { FocusRequester() }, dismiss, Modifier.zIndex((position + 1).toFloat()),
                    leaving = isLeaving, onGone = { finished = finished + layer.index },
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
    focus: FocusRequester,
    dismiss: () -> Unit,
    modifier: Modifier,
    leaving: Boolean = false,
    onGone: () -> Unit = {},
) {
    val reduced = rememberReducedMotion()
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val dismissNow = rememberUpdatedState(dismiss)
    val offset = remember { Animatable(0f) }
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    var exiting by remember { androidx.compose.runtime.mutableStateOf(false) }
    val scrollSink = remember { ModalBodyScroll() }

    val gone = rememberUpdatedState(onGone)
    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        // The same sink as a drag that was let go past the threshold (a drag already took the sheet there).
        exiting = true
        if (layer.window) appear.animateTo(0f, motion(ModalRules.exitMotion, reduced))
        else offset.animateTo(maxOf(sheetHeight, offset.value), motion(ModalRules.exitMotion, reduced))
        gone.value()
    }
    LaunchedEffect(Unit) { if (appear.value < 1f) appear.animateTo(1f, tween(APPEAR_MS, easing = ENTER_EASING)) }

    val visibleShare = if (offset.value <= 0f || sheetHeight <= 0f) 1f else (1f - offset.value / sheetHeight).coerceIn(0f, 1f)
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
                .background(Color.Black.copy(alpha = (ModalRules.SCRIM_OPACITY * visibleShare * appear.value).toFloat()))
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
                        if (layer.window) {
                            val scale = (ModalRules.WINDOW_START_SCALE + (1 - ModalRules.WINDOW_START_SCALE) * share).toFloat()
                            scaleX = scale
                            scaleY = scale
                            alpha = share
                        } else {
                            translationY = offset.value + (1f - share) * size.height
                        }
                    }
                    .pictureBlur(blurredBy),
            ) { layer.content() }
        }
    }
}

private const val APPEAR_MS = 220
private val ENTER_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

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
 * with the smaller height and its body scrolls inside.
 */
@Composable
private fun ModalBox(window: Boolean, sheetWindow: Boolean, marginDp: Double, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val shape = when {
        sheetWindow -> WideChrome.ModalShape.SheetWindow
        window -> WideChrome.ModalShape.Window
        else -> WideChrome.ModalShape.Sheet
    }
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        // The rules speak logical pixels (400 wide at most): the box is worked out in them and then scaled.
        val width = constraints.maxWidth / density.toDouble()
        val height = constraints.maxHeight / density.toDouble()
        val layerWidth = (WideChrome.layerWidth(shape, width, marginDp) * density).roundToInt()
        val available = (WideChrome.layerRoom(shape, height, marginDp) * density).roundToInt()
        val placeable = measurables.firstOrNull()?.measure(Constraints(layerWidth, layerWidth, 0, available))
        val box = WideChrome.layerBox(shape, width, height, (placeable?.height ?: 0) / density.toDouble(), marginDp).box
        layout(constraints.maxWidth, constraints.maxHeight) { placeable?.place((box.x * density).roundToInt(), (box.y * density).roundToInt()) }
    }
}
