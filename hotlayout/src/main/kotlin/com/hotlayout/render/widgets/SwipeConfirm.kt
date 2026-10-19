package com.hotlayout.render.widgets

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.border
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hotlayout.actions.ActionCatalog
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.render.RenderContext
import com.hotlayout.render.color
import com.hotlayout.render.paint.dashedBorder
import com.hotlayout.render.cssShadows
import com.hotlayout.render.shape
import com.hotlayout.theme.Corners
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.ShadowLayer
import com.hotlayout.theme.Sides
import com.hotlayout.theme.TextStyleValue
import com.hotlayout.model.DocumentNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «Swipe to confirm» (`Button.confirm: 'swipe'`, `x-swipeConfirm`, `x-swipeConfirmBusy`): the
 * geometry of the track, the progress of a drag, what a release does, how the busy state ends.
 */
internal object SwipeConfirmRules {
    const val WORD = "swipe"
    const val THRESHOLD = 0.9
    const val THUMB_GLYPH = "chevron-right"

    /** The thumb stands an `xs` step inside the border. */
    const val INSET_TOKEN = "xs"
    const val BUSY_MAX_MS = 8000.0
    const val COMPLETE_MS = 200.0
    const val RETURN_MS = 250.0
    const val SPINNER_TURN_MS = 800.0

    fun confirmsBySwipe(props: JsonObject): Boolean = props["confirm"]?.string == WORD

    data class Track(val height: Double, val width: Double, val border: Double, val inset: Double)

    data class Geometry(val diameter: Double, val thumbRoom: Double, val travel: Double, val labelStart: Double, val labelWidth: Double)

    fun geometry(track: Track): Geometry {
        val border = maxOf(0.0, track.border)
        val inset = maxOf(0.0, track.inset)
        val diameter = maxOf(0.0, track.height - 2 * border - 2 * inset)
        val room = border + inset + diameter
        return Geometry(diameter, room, maxOf(0.0, track.width - 2 * border - 2 * inset - diameter), room, maxOf(0.0, track.width - 2 * room))
    }

    /** The label plus the thumb room on both sides; the inline padding of the size is not read. */
    fun naturalWidth(label: Double, height: Double, border: Double, inset: Double): Double =
        maxOf(0.0, label) + 2 * geometry(Track(height, 0.0, border, inset)).thumbRoom

    /**
     * The drag along the inline axis over the travel, clamped to 0…1. `dx` is the movement on the
     * screen, positive to the right; the end of the line is on the left in right-to-left text.
     */
    fun progress(dx: Double, travel: Double, rightToLeft: Boolean): Double {
        if (travel <= 0 || !dx.isFinite()) return 0.0
        return ((if (rightToLeft) -dx else dx) / travel).coerceIn(0.0, 1.0)
    }

    enum class Release(val word: String) { Act("act"), Return("return") }

    fun release(progress: Double, disabled: Boolean = false): Release = if (!disabled && progress >= THRESHOLD) Release.Act else Release.Return

    /** Where the thumb starts, from the inline start of the track. */
    fun thumbStart(track: Track, progress: Double): Double =
        maxOf(0.0, track.border) + maxOf(0.0, track.inset) + progress.coerceIn(0.0, 1.0) * geometry(track).travel

    /** The drag is captured once it goes more along the inline axis than across. */
    fun captures(dx: Double, dy: Double): Boolean = Math.abs(dx) > Math.abs(dy)

    enum class Way(val word: String) { PointerTap("pointerTap"), DragPastThreshold("dragPastThreshold"), DragShort("dragShort"), Enter("enter"), Space("space"), EnterRepeat("enterRepeat") }

    enum class Press(val word: String) { Act("act"), Nothing("nothing") }

    /** What each way of pressing does (busy: nothing; disabled: nothing). */
    fun press(way: Way, disabled: Boolean, busy: Boolean = false): Press {
        if (disabled || busy) return Press.Nothing
        return when (way) {
            Way.DragPastThreshold, Way.Enter, Way.Space -> Press.Act
            Way.PointerTap, Way.DragShort, Way.EnterRepeat -> Press.Nothing
        }
    }

    enum class Runner(val word: String) { Reader("reader"), Host("host"), None("none"), Invalid("invalid") }

    enum class AfterRelease(val word: String) { Busy("busy"), Return("return") }

    /** Who runs an action: the reader navigates and writes by itself, the host does the rest. */
    fun runner(action: JsonObject, scope: DataScope): Runner {
        val handed = ActionCatalog.handed(action, scope)
        if (handed.type == "none") return Runner.None
        if (handed.invalid != null) return Runner.Invalid
        return when (handed.type) {
            "navigate", "navigate.back", "navigate.section", "setValue", "toggleValue" -> Runner.Reader
            else -> Runner.Host
        }
    }

    fun afterRelease(runner: Runner): AfterRelease = if (runner == Runner.None || runner == Runner.Invalid) AfterRelease.Return else AfterRelease.Busy

    enum class BusyEvent(val word: String) { Fulfilled("fulfilled"), Rejected("rejected"), ScreenChanged("screenChanged"), Timeout("timeout") }

    enum class BusyOutcome(val word: String) { GiveBack("return"), Gone("gone"), Stay("stay") }

    /** How the busy state ends. `promise`: the host answered the action with a task. */
    fun busyOutcome(event: BusyEvent, promise: Boolean): BusyOutcome {
        if (event == BusyEvent.ScreenChanged) return BusyOutcome.Gone
        if (event == BusyEvent.Timeout) return if (promise) BusyOutcome.Stay else BusyOutcome.GiveBack
        return if (promise) BusyOutcome.GiveBack else BusyOutcome.Stay
    }
}

/** The look of the track, as `ButtonWidget` resolved it. */
internal class SwipeConfirmLook(
    val label: String,
    val typography: TextStyleValue,
    val theme: HotlayoutTheme,
    /** The track (`background` of the node, else of the role). */
    val trackFill: Rgba?,
    val labelColor: Rgba,
    /** The thumb: the background of the role, else `primary`. */
    val thumbFill: Rgba,
    val glyphColor: Rgba,
    val borderColor: Rgba?,
    val borderWidth: Double,
    val dashedBorder: Boolean,
    val padding: Sides,
    val corners: Corners,
    val shadow: List<ShadowLayer>,
    val fullWidth: Boolean,
    val icon: String?,
)

private val completeEase = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/**
 * `Button.confirm: 'swipe'`: a track with a thumb that is dragged along the line. A press without a
 * drag does nothing; the keyboard and assistive technology act at once. After the action the thumb
 * glides to the end and stays BUSY until the screen changes, the host's task finishes, or eight
 * seconds pass without one.
 */
@Composable
internal fun SwipeConfirmButton(
    node: DocumentNode,
    context: RenderContext,
    look: SwipeConfirmLook,
    disabled: Boolean,
    modifier: Modifier = Modifier,
    /** Fixed state for tests and frames: the thumb at this progress. */
    fixedProgress: Double? = null,
    fixedBusy: Boolean = false,
) {
    val action = node.props["action"]?.obj ?: JsonObject.of("type" to "none")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inset = look.theme.spacing[SwipeConfirmRules.INSET_TOKEN] ?: 4.0
    val height = look.typography.lineBox + look.padding.top + look.padding.bottom + 2 * look.borderWidth
    val room = SwipeConfirmRules.geometry(SwipeConfirmRules.Track(height, 0.0, look.borderWidth, inset)).thumbRoom
    var progress by remember(context.screenId, node.id) { mutableFloatStateOf(0f) }
    var busy by remember(context.screenId, node.id) { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var token by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val serial = context.shownSerial
    val startSerial = remember(context.screenId, node.id) { mutableIntStateOf(serial) }
    LaunchedEffect(serial) {
        // The shown screen changed under a busy thumb: the state is gone with it.
        if (serial != startSerial.intValue && busy) {
            busy = false
            progress = 0f
        }
    }

    fun end(forToken: Int) {
        if (forToken != token || !busy) return
        busy = false
        progress = 0f
    }

    fun complete() {
        if (disabled || busy) return
        val run = context.run(action, node)
        if (SwipeConfirmRules.afterRelease(run.runner) != SwipeConfirmRules.AfterRelease.Busy) {
            progress = 0f
            return
        }
        dragging = false
        busy = true
        progress = 1f
        token += 1
        startSerial.intValue = context.shownSerial
        val mine = token
        val task: Job? = run.task
        scope.launch {
            if (task != null) {
                task.join()
                end(mine)
            } else {
                delay(SwipeConfirmRules.BUSY_MAX_MS.toLong())
                end(mine)
            }
        }
    }

    val completeNow by rememberUpdatedState({ complete() })
    val shownTarget = fixedProgress?.toFloat() ?: progress
    val animated by animateFloatAsState(
        shownTarget,
        if (dragging || fixedProgress != null) tween(0) else tween((if (busy) SwipeConfirmRules.COMPLETE_MS else SwipeConfirmRules.RETURN_MS).toInt(), easing = completeEase),
        label = "swipe-progress",
    )
    val isBusy = busy || fixedBusy
    val shape = look.corners.shape()
    val widthModifier = if (look.fullWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)
    BoxWithConstraints(
        modifier
            .then(widthModifier)
            .height(height.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = look.label
                testTag = "swipe-confirm"
                onClick { completeNow(); true }
            }
            .then(if (disabled) Modifier else Modifier.focusable())
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || (event.key != Key.Enter && event.key != Key.NumPadEnter && event.key != Key.Spacebar)) return@onKeyEvent false
                if (event.nativeKeyEvent.repeatCount == 0) completeNow()
                true
            }
            .cssShadows(look.shadow, shape)
            .clip(shape)
            .background(look.trackFill?.color ?: Color.Transparent, shape)
            .then(if (look.borderWidth > 0 && look.borderColor != null) {
                if (look.dashedBorder) Modifier.dashedBorder(look.borderWidth, look.borderColor.color, look.corners) else Modifier.border(look.borderWidth.dp, look.borderColor.color, shape)
            } else {
                Modifier
            }),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = room.dp), contentAlignment = Alignment.Center) {
            com.hotlayout.render.StyledText(look.label, look.typography, look.labelColor, context, align = androidx.compose.ui.text.style.TextAlign.Center)
        }
        val track = SwipeConfirmRules.Track(height, maxWidth.value.toDouble(), look.borderWidth, inset)
        val geometry = SwipeConfirmRules.geometry(track)
        val start = SwipeConfirmRules.thumbStart(track, animated.toDouble())
        val diameter = geometry.diameter.dp
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = start.dp)
                .size(diameter)
                .clip(CircleShape)
                .background(look.thumbFill.color)
                .pointerInput(disabled, isBusy, geometry.travel, rtl) {
                    if (disabled || isBusy) return@pointerInput
                    val travel = geometry.travel * density
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var captured: Boolean? = null
                        var total = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            total += change.positionChange()
                            if (captured == null && maxOf(Math.abs(total.x), Math.abs(total.y)) >= 4f * density) {
                                captured = SwipeConfirmRules.captures(total.x.toDouble(), total.y.toDouble())
                            }
                            if (captured == true) {
                                change.consume()
                                dragging = true
                                progress = SwipeConfirmRules.progress(total.x.toDouble(), travel, rtl).toFloat()
                            }
                            if (!change.pressed) break
                        }
                        val acts = captured == true && SwipeConfirmRules.release(progress.toDouble()) == SwipeConfirmRules.Release.Act
                        dragging = false
                        if (acts) completeNow() else progress = 0f
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isBusy) {
                SwipeSpinner(look.typography.fontSize, look.glyphColor.color)
            } else {
                IconGlyph(look.icon ?: SwipeConfirmRules.THUMB_GLYPH, look.typography.fontSize, look.typography.fontSize, look.typography.fontSize, look.glyphColor.color, context.rightToLeft)
            }
        }
    }
}

/** A ring of three quarters turning once in 800 ms; the test clock never waits for it. */
@Composable
private fun SwipeSpinner(size: Double, color: Color) {
    val turn by rememberInfiniteTransition(label = "swipe-spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(SwipeConfirmRules.SPINNER_TURN_MS.toInt(), easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    val stroke = (size / 8).toFloat()
    Canvas(Modifier.size((size - size / 8).dp).rotate(turn)) {
        drawArc(color, 0f, 270f, useCenter = false, style = Stroke(width = stroke * density, cap = StrokeCap.Butt), size = Size(this.size.width, this.size.height))
    }
}
