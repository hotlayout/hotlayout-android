package com.hotlayout.render.motion

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext

/**
 * The clock of the numbers a node leads. The node does not learn a second way to read numbers:
 * the engine hands back the node's data scope with the counted paths replaced by the number shown
 * at this frame, and the node formats it as it always does.
 *
 * Two scopes come out: `stepped` for a label (the number moves in steps) and `smooth` for the fill
 * of a scale (no step). A change during a transition starts from the value shown at that moment and
 * runs the full duration.
 */
internal class ValueTransitionEngine {
    class Run(val from: Double, val to: Double, val digits: Int, val scale: Boolean, var start: Double? = null)

    data class Ends(val from: Double, val to: Double, val shown: Double)

    /** What the node reads in place of its own data. */
    class Shown(
        val stepped: DataScope,
        val smooth: DataScope,
        /** The shown number of a LITERAL `value` of a scale while it grows: (stepped, smooth). */
        val literal: Pair<Double, Double>? = null,
        /** The ends of every scale run in progress, by path (`#value` for a literal). */
        val ends: Map<String, Ends> = emptyMap(),
    ) {
        companion object {
            fun plain(scope: DataScope) = Shown(scope, scope)
        }
    }

    var mounted = false
        private set
    private var localeKey: String? = null
    private val last = HashMap<String, JsonValue>()
    val runs = LinkedHashMap<String, Run>()
    private var time = 0.0

    /** Counts every start of a run, so that the frame loop of the node knows to start again. */
    var generation = 0
        private set

    val isRunning: Boolean get() = runs.isNotEmpty()

    fun shownNow(path: String, smooth: Boolean): Double? {
        val run = runs[path] ?: return null
        val t = run.start?.let { time - it } ?: 0.0
        return ValueTransition.value(run.from, run.to, t, if (smooth) null else run.digits)
    }

    /** The appearance: every number of the node starts from zero at the moment it is shown. */
    fun appear(targets: List<ValueTransition.Target>, read: (String) -> JsonValue?, reducedMotion: Boolean) {
        for (target in targets) {
            val raw = read(target.path)
            last[target.path] = raw ?: JsonValue.Null
            if (ValueTransition.appearDecision(true, raw, reducedMotion) != ValueTransition.AppearDecision.Grows) continue
            val end = raw?.number ?: continue
            runs[target.path] = Run(
                ValueTransition.APPEAR_FROM, end,
                ValueTransition.digits(ValueTransition.APPEAR_FROM, end, target.format, target.currency), target.scale,
            )
            generation++
        }
    }

    /** Reads the data of this render. The first render of a node is its first show: no transition. */
    fun sync(targets: List<ValueTransition.Target>, read: (String) -> JsonValue?, leads: Boolean, localeKey: String, reducedMotion: Boolean) {
        val first = !mounted
        val localeChanged = !first && localeKey != this.localeKey
        mounted = true
        this.localeKey = localeKey
        // A language changed in the same step ends an appearance in progress.
        if (localeChanged) runs.clear()
        for (target in targets) {
            val raw = read(target.path)
            val before = last[target.path]
            if (first || before == null) {
                last[target.path] = raw ?: JsonValue.Null
                continue
            }
            if ((raw ?: JsonValue.Null) == before) continue
            last[target.path] = raw ?: JsonValue.Null
            if (!leads) {
                // Without `animateChanges` a change of the data ends the appearance.
                runs.remove(target.path)
                continue
            }
            val shown = runs[target.path]?.let { shownNow(target.path, it.scale) }
            val from: JsonValue = shown?.let { JsonValue.Num(it) } ?: before
            val change = ValueTransition.Change(true, true, from, raw, localeChanged, reducedMotion)
            val start = from.number
            val end = raw?.number
            if (ValueTransition.decision(change) != ValueTransition.Decision.Leads || start == null || end == null) {
                runs.remove(target.path)
                continue
            }
            runs[target.path] = Run(start, end, ValueTransition.digits(start, end, target.format, target.currency), target.scale)
            generation++
        }
    }

    /** A frame at [milliseconds]: the first frame of a run fixes its start, a run past the duration ends. */
    fun tick(milliseconds: Double) {
        time = milliseconds
        val iterator = runs.entries.iterator()
        while (iterator.hasNext()) {
            val run = iterator.next().value
            val start = run.start ?: milliseconds.also { run.start = it }
            if (milliseconds - start >= ValueTransition.DURATION_MS) iterator.remove()
        }
    }

    fun shown(scope: DataScope): Shown {
        if (runs.isEmpty()) return Shown.plain(scope)
        val stepped = LinkedHashMap(scope)
        val smooth = LinkedHashMap(scope)
        val ends = HashMap<String, Ends>()
        var literal: Pair<Double, Double>? = null
        for ((path, run) in runs) {
            val step = shownNow(path, false)
            val flow = if (run.scale) shownNow(path, true) else step
            if (run.scale) ends[path] = Ends(run.from, run.to, flow ?: run.to)
            if (path == ValueTransition.LITERAL_KEY) {
                literal = (step ?: run.to) to (flow ?: run.to)
                continue
            }
            if (step != null) stepped[path] = JsonValue.Num(step)
            if (flow != null) smooth[path] = JsonValue.Num(flow)
        }
        return Shown(stepped, smooth, literal, ends)
    }
}

/**
 * Whether the system asks for no motion: the animator duration scale of the device is zero
 * (the setting «Remove animations»).
 */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/**
 * Hosts a node that may lead its numbers: without `animateChanges` and `animateIn` the content gets
 * the data untouched; with them it is redrawn on every frame of the runs in progress. The frames
 * stop while the node is out of the window. [content] gets a modifier that must go on its root
 * (it tells where the node is).
 */
@Composable
internal fun ValueTransitioned(
    node: DocumentNode,
    context: RenderContext,
    content: @Composable (ValueTransitionEngine.Shown, Modifier) -> Unit,
) {
    val leads = ValueTransition.animatesChanges(node.props)
    val appears = ValueTransition.animatesIn(node.props)
    if (!leads && !appears) {
        content(ValueTransitionEngine.Shown.plain(context.scope), Modifier)
        return
    }
    val engine = remember { ValueTransitionEngine() }
    var frame by remember { mutableLongStateOf(0) }
    var visible by remember { mutableStateOf(true) }
    val reduced = rememberReducedMotion()
    // The frame is read here so that every tick draws the node again.
    @Suppress("UNUSED_VARIABLE") val read = frame

    val scope = context.scope
    val literal = node.props["value"]?.takeIf { it["kind"]?.string == "literal" }?.get("value")
    val reader: (String) -> JsonValue? = { path ->
        val value = if (path == ValueTransition.LITERAL_KEY) literal else scope[path]
        value?.takeUnless { it.isNull }
    }
    if (appears && !engine.mounted) engine.appear(ValueTransition.appearTargets(node.props), reader, reduced)
    val targets = if (leads) ValueTransition.targets(node.props) else ValueTransition.appearTargets(node.props)
    engine.sync(targets, reader, leads, "${context.locale.translation ?: ""}|${context.locale.formatting ?: ""}", reduced)

    val running = engine.isRunning
    LaunchedEffect(engine.generation, running, visible) {
        if (!running || !visible) return@LaunchedEffect
        while (engine.isRunning) {
            withFrameMillis { millis ->
                engine.tick(millis.toDouble())
                frame++
            }
        }
        // One more draw without a run: the plain data.
        frame++
    }
    val tracking = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val shown = coordinates.isAttached && bounds.width > 0f && bounds.height > 0f
        if (shown != visible) visible = shown
    }
    content(engine.shown(scope), tracking)
}
