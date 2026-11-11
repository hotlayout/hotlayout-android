package com.hotlayout.render.widgets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.actions.ActionCatalog
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext
import com.hotlayout.render.StyledText
import com.hotlayout.render.color
import com.hotlayout.render.cssShadows
import com.hotlayout.render.paint.innerShadows
import com.hotlayout.render.dp
import com.hotlayout.render.motion.rememberReducedMotion
import com.hotlayout.render.paint.dashedBorder
import com.hotlayout.render.shape
import com.hotlayout.render.signedDp
import com.hotlayout.theme.Corners
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.ShadowLayer
import com.hotlayout.theme.Sides
import com.hotlayout.theme.TextStyleValue
import kotlinx.coroutines.CoroutineScope
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

/** What the button is doing after a release. */
private enum class SwipePhase { Idle, Gliding, Collapsing, Busy, Expanding, Returning, Rippling }

/** What the state of a swipe button needs from its surroundings at the moment of a press. */
private class SwipeEnv(
    val disabled: Boolean,
    val reduced: Boolean,
    val serial: Int,
    val run: () -> PressRun,
    /** Whether the fill over the screen plays for a release this way. */
    val plays: (SwipeMotion.Way) -> Boolean,
    /** Puts the fill over the screen; `null` where there is no place for it. */
    val startFill: (onCovered: () -> Unit) -> SwipeRippleHandle?,
)

/**
 * What a swipe button remembers: the thumb, the phase after a release and the token that ties a running task to it.
 *
 * After a release the thumb glides to the end (200 ms), the button collapses into a circle (600 ms) that is the look of
 * BUSY, and with the fill the circle grows over the screen and only then does the action run.
 */
@Stable
private class SwipeState(initialSerial: Int, private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
    var phase by mutableStateOf(SwipePhase.Idle)
    var dragging by mutableStateOf(false)

    /** The circle stands where the thumb was. */
    var circle by mutableStateOf(false)

    /** The thumb moves to its place at once, with no glide (the button comes back to rest under the fill). */
    var jump by mutableStateOf(false)

    /** The collapse in milliseconds, animated linearly; the curve lies inside [SwipeMotion.collapse]. */
    val collapse = Animatable(0f)
    private var token = 0
    private var startSerial = initialSerial
    private var endPending = false
    private var ripplePending = false
    private var stage: Job? = null
    private var fill: SwipeRippleHandle? = null

    val busy: Boolean get() = phase != SwipePhase.Idle && phase != SwipePhase.Returning

    /** The button leaves, or the shown screen changed under it: whatever is in flight is gone, a fill that has not covered the screen too. */
    fun release() {
        token += 1
        stage?.cancel()
        fill?.cancel()
        fill = null
        endPending = false
        ripplePending = false
    }

    fun screenChanged(serial: Int) {
        if (serial == startSerial || !busy) return
        release()
        circle = false
        jump = true
        phase = SwipePhase.Idle
        progress = 0f
        scope.launch { collapse.snapTo(0f) }
    }

    /** Runs the action; the thumb then goes to the end and waits, or returns. After a swipe that asks for the fill the action waits for it instead. */
    fun complete(way: SwipeMotion.Way, env: SwipeEnv) {
        if (env.disabled || busy) return
        if (env.plays(way)) {
            beginRipple(env)
            return
        }
        val result = env.run()
        if (SwipeConfirmRules.afterRelease(result.runner) != SwipeConfirmRules.AfterRelease.Busy) {
            progress = 0f
            return
        }
        dragging = false
        endPending = false
        jump = false
        phase = SwipePhase.Gliding
        progress = 1f
        token += 1
        startSerial = env.serial
        val mine = token
        glideThenCollapse(env)
        val task = result.task
        scope.launch {
            if (task != null) {
                task.join()
                end(mine, SwipeConfirmRules.BusyEvent.Fulfilled, true, env)
            } else {
                delay(SwipeConfirmRules.BUSY_MAX_MS.toLong())
                end(mine, SwipeConfirmRules.BusyEvent.Timeout, false, env)
            }
        }
    }

    /** The thumb goes to the end, the button collapses into a circle, and the circle grows over the screen. */
    private fun beginRipple(env: SwipeEnv) {
        dragging = false
        endPending = false
        ripplePending = true
        jump = false
        phase = SwipePhase.Gliding
        progress = 1f
        token += 1
        startSerial = env.serial
        glideThenCollapse(env)
    }

    /** The thumb reaches the end (200 ms), then the button collapses (600 ms). Under «reduce motion» the thumb jumps and waits at the end busy. */
    private fun glideThenCollapse(env: SwipeEnv) {
        stage?.cancel()
        stage = scope.launch {
            if (!env.reduced) {
                delay(SwipeConfirmRules.COMPLETE_MS.toLong())
                collapse.snapTo(0f)
                circle = true
                phase = SwipePhase.Collapsing
                collapse.animateTo(SwipeMotion.COLLAPSE_MS.toFloat(), tween(SwipeMotion.COLLAPSE_MS.toInt(), easing = LinearEasing))
            }
            if (ripplePending) {
                ripplePending = false
                launchRipple(env)
            } else {
                phase = SwipePhase.Busy
                if (endPending) {
                    endPending = false
                    giveBack(env)
                }
            }
        }
    }

    private fun launchRipple(env: SwipeEnv) {
        val handle = env.startFill { covered(env) }
        if (handle == null) {
            covered(env)
            return
        }
        fill = handle
        phase = SwipePhase.Rippling
    }

    /** The screen is covered: the action runs now, with its `track`, once. The button usually leaves with the screen; one that stays is at rest again under the fading fill. */
    private fun covered(env: SwipeEnv) {
        env.run()
        fill = null
        circle = false
        phase = SwipePhase.Idle
        jump = true
        progress = 0f
        scope.launch { collapse.snapTo(0f) }
    }

    /** An event of the busy state; the circle expands and the thumb returns when the rule says so. */
    private fun end(forToken: Int, event: SwipeConfirmRules.BusyEvent, promise: Boolean, env: SwipeEnv) {
        if (forToken != token || !busy) return
        if (SwipeConfirmRules.busyOutcome(event, promise) != SwipeConfirmRules.BusyOutcome.GiveBack) return
        when (phase) {
            SwipePhase.Gliding, SwipePhase.Collapsing -> endPending = true
            SwipePhase.Busy -> giveBack(env)
            else -> Unit
        }
    }

    /** «Busy» is over: the circle expands back (a thumb that never collapsed has nothing to expand), then the thumb returns to the start. */
    private fun giveBack(env: SwipeEnv) {
        stage?.cancel()
        if (!circle || env.reduced) {
            circle = false
            phase = SwipePhase.Returning
            progress = 0f
            return
        }
        phase = SwipePhase.Expanding
        stage = scope.launch {
            collapse.animateTo(0f, tween(SwipeMotion.COLLAPSE_MS.toInt(), easing = LinearEasing))
            circle = false
            phase = SwipePhase.Returning
            progress = 0f
        }
    }
}

/** The frame of the button in the window, kept as the layout reports it. */
private class FrameBox {
    var window: Rect = Rect.Zero
}

/**
 * `Button.confirm: 'swipe'`: a track with a thumb that is dragged along the line. A press without a
 * drag does nothing; the keyboard and assistive technology act at once.
 *
 * While the thumb is dragged a fill of its colour follows it and the label fades with the travel. After the action the
 * thumb glides to the end and the button collapses into a circle holding the busy indicator; that circle is the look of
 * BUSY, until the screen changes, the host's task finishes, or eight seconds pass without one. The circle then expands
 * back and the thumb returns. With `confirmTransition: 'ripple'` on a navigation the circle grows over the whole screen
 * and only then does the action run, under the fill.
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
    /** Fixed state: the collapse at this time in milliseconds (the circle is drawn from it). */
    fixedCollapse: Double? = null,
) {
    val action = node.props["action"]?.obj ?: JsonObject.of("type" to "none")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = rememberReducedMotion()
    val density = LocalDensity.current.density
    val inset = look.theme.spacing[SwipeConfirmRules.INSET_TOKEN] ?: 4.0
    val height = look.typography.lineBox + look.padding.top + look.padding.bottom + 2 * look.borderWidth
    val room = SwipeConfirmRules.geometry(SwipeConfirmRules.Track(height, 0.0, look.borderWidth, inset)).thumbRoom
    val serial = context.shownSerial
    val scope = rememberCoroutineScope()
    val state = remember(context.screenId, node.id) { SwipeState(serial, scope) }
    val frame = remember { FrameBox() }
    LaunchedEffect(serial) { state.screenChanged(serial) }
    // A fill that has not covered the screen yet goes with the button and the action never runs.
    DisposableEffect(state) { onDispose { state.release() } }

    val ripples = context.ripples
    val env by rememberUpdatedState(
        SwipeEnv(
            disabled = disabled, reduced = reduced, serial = context.shownSerial, run = { context.run(action, node) },
            plays = { way ->
                ripples != null && SwipeMotion.readsRipple(node.props) &&
                    SwipeMotion.sequence(SwipeConfirmRules.runner(action, context.scope), action["type"]?.string ?: "none", true, way, reduced).contains(SwipeMotion.Step.Ripple)
            },
            startFill = { onCovered ->
                val window = frame.window
                if (ripples == null || window.width <= 0f) {
                    null
                } else {
                    val ring = (SwipeMotion.collapse(SwipeConfirmRules.Track(height, window.width / density.toDouble(), look.borderWidth, inset), 0.0).ring * density).toFloat()
                    ripples.start(SwipeRippleSpec(window.center, window.height, maxOf(0f, window.height - 2 * ring), look.thumbFill.color, look.glyphColor.color, look.typography.fontSize.dp, onCovered))
                }
            },
        ),
    )

    val animated by animateFloatAsState(
        fixedProgress?.toFloat() ?: state.progress,
        if (state.dragging || fixedProgress != null || reduced || state.jump) tween(0) else tween((if (state.busy) SwipeConfirmRules.COMPLETE_MS else SwipeConfirmRules.RETURN_MS).toInt(), easing = completeEase),
        label = "swipe-progress",
    )
    val isBusy = state.busy || fixedBusy
    val showsCircle = state.circle || fixedCollapse != null || (fixedBusy && !reduced)
    val elapsed = fixedCollapse ?: if (fixedBusy && !state.circle) SwipeMotion.COLLAPSE_MS else state.collapse.value.toDouble()
    val widthModifier = if (look.fullWidth) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max)
    BoxWithConstraints(
        modifier.then(widthModifier).height(height.dp).onGloballyPositioned { frame.window = it.boundsInWindow() }
            .swipeTrack(look, disabled) { state.complete(SwipeMotion.Way.Keyboard, env) },
        contentAlignment = Alignment.Center,
    ) {
        val track = SwipeConfirmRules.Track(height, maxWidth.value.toDouble(), look.borderWidth, inset)
        val surfaceOpacity = if (showsCircle) SwipeMotion.collapse(track, elapsed).surfaceOpacity.toFloat() else 1f
        Box(Modifier.matchParentSize().graphicsLayer { alpha = surfaceOpacity }.swipeSurface(look))
        val shown = animated.toDouble()
        if (!showsCircle) SwipeTrail(track, shown, look)
        Box(Modifier.padding(horizontal = room.dp).graphicsLayer { alpha = SwipeMotion.labelOpacity(shown).toFloat() }, contentAlignment = Alignment.Center) {
            StyledText(look.label, look.typography, look.labelColor, context, align = TextAlign.Center)
        }
        if (showsCircle) {
            SwipeCircle(track, elapsed, look, reduced)
        } else {
            val geometry = SwipeConfirmRules.geometry(track)
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = SwipeConfirmRules.thumbStart(track, shown).signedDp)
                    .size(geometry.diameter.dp)
                    .clip(RoundedCornerShape(SwipeMotion.thumbRadius(track, look.corners.topStart).dp))
                    .background(look.thumbFill.color)
                    .swipeThumbGesture(state, disabled || isBusy, geometry.travel, rtl) { state.complete(SwipeMotion.Way.Swipe, env) },
                contentAlignment = Alignment.Center,
            ) {
                if (isBusy) {
                    SwipeSpinner(look.typography.fontSize, look.glyphColor.color, reduced)
                } else {
                    IconGlyph(look.icon ?: SwipeConfirmRules.THUMB_GLYPH, look.typography.fontSize, look.typography.fontSize, look.typography.fontSize, look.glyphColor.color, context.rightToLeft)
                }
            }
        }
    }
}

/** The fill behind the thumb: from the place of rest to the far edge of the thumb, with the radius of the thumb, under the label. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SwipeTrail(track: SwipeConfirmRules.Track, progress: Double, look: SwipeConfirmLook) {
    val strip = SwipeMotion.trail(track, progress)
    val geometry = SwipeConfirmRules.geometry(track)
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .offset(x = strip.start.signedDp)
            .width(strip.width.dp)
            .height(geometry.diameter.dp)
            .clip(RoundedCornerShape(SwipeMotion.thumbRadius(track, look.corners.topStart).dp))
            .background(look.thumbFill.color),
    )
}

/** The circle the button collapses into: the halo, the core with the ring of the border and the inset, and the indicator at the centre. */
@Composable
private fun SwipeCircle(track: SwipeConfirmRules.Track, elapsed: Double, look: SwipeConfirmLook, reduced: Boolean) {
    val frame = SwipeMotion.collapse(track, elapsed)
    val color = look.thumbFill.color
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        fun pill(left: Double, top: Double, width: Double, height: Double, paint: Color) {
            if (width <= 0 || height <= 0) return
            val radius = (height / 2 * d).toFloat()
            drawRoundRect(paint, Offset((left * d).toFloat(), (top * d).toFloat()), Size((width * d).toFloat(), (height * d).toFloat()), CornerRadius(radius, radius))
        }
        pill(frame.start, 0.0, frame.width, frame.height, color.copy(alpha = color.alpha * frame.haloOpacity.toFloat()))
        pill(frame.start + frame.ring, frame.ring, frame.width - 2 * frame.ring, frame.height - 2 * frame.ring, color)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SwipeSpinner(look.typography.fontSize, look.glyphColor.color, reduced) }
}

/** The track: semantics and keys of the button. */
private fun Modifier.swipeTrack(look: SwipeConfirmLook, disabled: Boolean, completeNow: () -> Unit): Modifier =
    this
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

/** The surface of the track: the shadow, the clip, the track fill and the border. It fades while the button collapses. */
private fun Modifier.swipeSurface(look: SwipeConfirmLook): Modifier {
    val shape = look.corners.shape()
    return this
        .cssShadows(look.shadow, shape)
        .clip(shape)
        .background(look.trackFill?.color ?: Color.Transparent, shape)
        .innerShadows(look.shadow, look.corners, look.borderWidth)
        .then(
            if (look.borderWidth > 0 && look.borderColor != null) {
                if (look.dashedBorder) Modifier.dashedBorder(look.borderWidth, look.borderColor.color, look.corners) else Modifier.border(look.borderWidth.dp, look.borderColor.color, shape)
            } else {
                Modifier
            },
        )
}

/** The drag of the thumb: it captures a mostly horizontal move, follows it, and acts on release past the threshold. */
private fun Modifier.swipeThumbGesture(state: SwipeState, inert: Boolean, travelDp: Double, rtl: Boolean, completeNow: () -> Unit): Modifier =
    pointerInput(inert, travelDp, rtl) {
        if (inert) return@pointerInput
        val travel = travelDp * density
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
                    state.jump = false
                    state.dragging = true
                    state.progress = SwipeConfirmRules.progress(total.x.toDouble(), travel, rtl).toFloat()
                }
                if (!change.pressed) break
            }
            val acts = captured == true && SwipeConfirmRules.release(state.progress.toDouble()) == SwipeConfirmRules.Release.Act
            state.dragging = false
            if (acts) completeNow() else state.progress = 0f
        }
    }

/** A ring of three quarters turning once in 800 ms (still under «reduce motion»); the test clock never waits for it. */
@Composable
internal fun SwipeSpinner(size: Double, color: Color, still: Boolean = false) {
    val turn = if (still) {
        0f
    } else {
        rememberInfiniteTransition(label = "swipe-spinner").animateFloat(
            0f, 360f, infiniteRepeatable(tween(SwipeConfirmRules.SPINNER_TURN_MS.toInt(), easing = LinearEasing), RepeatMode.Restart), label = "turn",
        ).value
    }
    val stroke = (size / 8).toFloat()
    Canvas(Modifier.size((size - size / 8).dp).rotate(turn)) {
        drawArc(color, 0f, 270f, useCenter = false, style = Stroke(width = stroke * density, cap = StrokeCap.Butt), size = Size(this.size.width, this.size.height))
    }
}
