package com.hotlayout.render.motion

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext
import kotlin.math.floor
import kotlinx.coroutines.delay

/** Whole seconds a countdown below has ticked, instead of the real clock; tests read a real node at a chosen second. */
internal val LocalCountdownTickedSeconds = compositionLocalOf<Double?> { null }

/** Milliseconds since the first frame of every number run below, instead of the real clock; tests read a real node at a chosen moment. */
internal val LocalValueTransitionElapsedMs = compositionLocalOf<Double?> { null }

internal object CountdownRules {
    /** Whether the text shows a part of a time left: a binding with `format: countdownPart`, alone or inside a template. */
    fun ticks(props: JsonObject): Boolean {
        val content = props["content"]?.obj ?: return false
        if (content["kind"]?.string == "template") {
            val values = content["values"]?.obj ?: return false
            return values.any { isCountdown(it.second) }
        }
        return isCountdown(JsonValue.Obj(content))
    }

    private fun isCountdown(value: JsonValue): Boolean {
        val record = value.obj ?: return false
        return record["kind"]?.string == "binding" && record["format"]?.string == "countdownPart"
    }

    /** The moment [seconds] whole seconds later: the fraction of the sample moment stays, so the parts go down by whole seconds. */
    fun now(base: Double, seconds: Double): Double = base + floor(seconds) * 1000
}

/**
 * Hosts a text whose time left counts down: once a second, from the moment the reader already uses
 * as «now» (the clock of the device, unless the host pinned «now»). The clock runs only while the node is on screen, so a screen under another one or a
 * scrolled-away node does not keep the app awake.
 */
@Composable
internal fun CountdownTicking(node: DocumentNode, context: RenderContext, content: @Composable (RenderContext) -> Unit) {
    if (!CountdownRules.ticks(node.props)) {
        content(context)
        return
    }
    val fixed = LocalCountdownTickedSeconds.current
    // A host that pinned «now» owns the clock: the time left stays where the host put it.
    if (context.clockPinned && fixed == null) {
        content(context)
        return
    }
    if (fixed != null) {
        content(context.copy(now = CountdownRules.now(context.now, fixed)))
        return
    }
    var onScreen by remember { mutableStateOf(false) }
    var seconds by remember { mutableDoubleStateOf(0.0) }
    val window = LocalWindowInfo.current.containerSize
    LaunchedEffect(onScreen) {
        if (!onScreen) return@LaunchedEffect
        val begin = withFrameMillis { it }
        seconds = 0.0
        while (true) {
            delay(1000)
            seconds = (withFrameMillis { it } - begin) / 1000.0 + 0.05
        }
    }
    Box(
        Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            onScreen = coordinates.isAttached && !bounds.isEmpty && bounds.right > 0f && bounds.bottom > 0f && bounds.left < window.width && bounds.top < window.height
        },
    ) {
        content(context.copy(now = CountdownRules.now(context.now, if (onScreen) seconds else 0.0)))
    }
}

/**
 * Hosts a node that may lead its numbers: without `animateChanges` and `animateIn` the content gets
 * the data untouched; with them it is redrawn on every frame of the runs in progress.
 *
 * The engine is a value in snapshot state: composing works on a copy of it and keeps the copy from an
 * effect when it changed (a show, new data), and the frame loop puts a ticked copy into the state.
 */
@Composable
internal fun ValueTransitioned(node: DocumentNode, context: RenderContext, content: @Composable (ValueTransitionEngine.Shown) -> Unit) {
    val leads = ValueTransition.animatesChanges(node.props)
    val appears = ValueTransition.animatesIn(node.props)
    if (!leads && !appears) {
        content(ValueTransitionEngine.Shown.plain(context.scope))
        return
    }
    var committed by remember { mutableStateOf(ValueTransitionEngine(), referentialEqualityPolicy()) }
    val reduced = rememberReducedMotion()
    val fixed = LocalValueTransitionElapsedMs.current
    val scope = context.scope
    val literal = node.props["value"]?.takeIf { it["kind"]?.string == "literal" }?.get("value")
    val read: (String) -> JsonValue? = { path ->
        val value = if (path == ValueTransition.LITERAL_KEY) literal else scope[path]
        value?.takeIf { !it.isNull }
    }
    val localeKey = "${context.locale.translation ?: ""}|${context.locale.formatting ?: ""}"
    val engine = committed.copy()
    if (appears && !engine.mounted) engine.appear(ValueTransition.appearTargets(node.props), read, reduced)
    val targets = if (leads) ValueTransition.targets(node.props) else ValueTransition.appearTargets(node.props)
    engine.sync(targets, read, leads, localeKey, reduced)
    if (fixed != null) {
        // The first frame fixes the start of a run; the second is the chosen moment.
        engine.tick(0.0)
        engine.tick(fixed)
    }
    if (!engine.sameState(committed)) SideEffect { committed = engine }
    if (fixed == null) {
        // A frame loop that lives while runs are going on; a new run restarts it (the scope is the key).
        LaunchedEffect(scope, localeKey) {
            // The first frame comes after the composition kept its copy: the loop reads the engine that is drawn.
            do {
                withFrameMillis { millis -> if (committed.isRunning) committed = committed.copy().apply { tick(millis.toDouble()) } }
            } while (committed.isRunning)
        }
    }
    content(engine.shown(scope))
}
