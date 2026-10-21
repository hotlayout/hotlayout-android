package com.hotlayout.render.motion

import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.text.BindingFormat
import com.hotlayout.text.NumberText
import kotlin.math.floor
import kotlin.math.pow

/**
 * A value that changes from data (`animateChanges`) or grows from zero when its node is shown
 * (`animateIn`): the curve, the step, the decisions and the numbers a node leads. Pure functions;
 * the clock lives in [ValueTransitionEngine].
 */
internal object ValueTransition {
    const val DURATION_MS = 700.0
    private const val PRECISION = 6
    private const val MAX_FRACTION_DIGITS = 3

    /** The key of the literal `value` of a scale among the numbers that appear. */
    const val LITERAL_KEY = "#value"
    const val APPEAR_FROM = 0.0

    /** `e = 1 − (1 − p)³`, `p` clamped to 0…1. */
    fun ease(progress: Double): Double {
        val p = progress.coerceIn(0.0, 1.0)
        return 1 - (1 - p).pow(3)
    }

    /** Whole when both ends are whole; otherwise the digits of the currency, else three. */
    fun digits(from: Double, to: Double, format: String?, currency: String?): Int {
        if (from == Math.rint(from) && to == Math.rint(to)) return 0
        if (format == "currency" && currency != null && BindingFormat.isCurrencyCode(JsonValue.Str(currency))) return NumberText.currencyFractionDigits(currency)
        return MAX_FRACTION_DIGITS
    }

    /** JavaScript `Math.round`: halves go up. */
    private fun jsRound(value: Double) = floor(value + 0.5)

    private fun rounded(value: Double, digits: Int): Double {
        val scale = 10.0.pow(digits)
        return jsRound(value * scale) / scale
    }

    /**
     * The value shown [t] ms after the transition began. A scale ([digits] `null`) has no step; a
     * number moves in steps cut towards the OLD value, on a rounded product so that `0.3 × 0.875`
     * is not cut a step lower.
     */
    fun value(from: Double, to: Double, t: Double, digits: Int?): Double {
        if (!t.isFinite() || t >= DURATION_MS) return to
        val delta = (to - from) * ease(t / DURATION_MS)
        if (digits == null) return from + delta
        val scale = 10.0.pow(digits)
        val big = 10.0.pow(PRECISION)
        val raw = jsRound(delta * scale * big) / big
        val steps = if (raw < 0) kotlin.math.ceil(raw) else floor(raw)
        return rounded(from + steps / scale, digits + PRECISION)
    }

    // ---- Decisions ----------------------------------------------------------------------------

    enum class NoTransition(val word: String) {
        NoField("noField"), FirstShow("firstShow"), SameValue("sameValue"), NotANumber("notANumber"),
        LocaleChanged("localeChanged"), ReducedMotion("reducedMotion"),
    }

    /** `null` leads (a transition); otherwise the first reason a value goes at once. */
    class Change(
        val animates: Boolean, val shownBefore: Boolean, val from: JsonValue?, val to: JsonValue?,
        val localeChanged: Boolean = false, val reducedMotion: Boolean = false,
    )

    /** The first reason in the contract order wins: no field, first show, reduced motion, a changed language, not a number, the same value. */
    fun decision(change: Change): NoTransition? {
        if (!change.animates) return NoTransition.NoField
        if (!change.shownBefore) return NoTransition.FirstShow
        if (change.reducedMotion) return NoTransition.ReducedMotion
        if (change.localeChanged) return NoTransition.LocaleChanged
        val from = change.from?.number
        val to = change.to?.number
        if (from == null || to == null) return NoTransition.NotANumber
        if (from == to) return NoTransition.SameValue
        return null
    }

    enum class AppearNo(val word: String) { NoField("noField"), ReducedMotion("reducedMotion"), NotANumber("notANumber"), Zero("zero") }

    /** `null` grows from zero; otherwise why the value stands at once. */
    fun appearDecision(animates: Boolean, to: JsonValue?, reducedMotion: Boolean): AppearNo? {
        if (!animates) return AppearNo.NoField
        if (reducedMotion) return AppearNo.ReducedMotion
        val number = to?.number ?: return AppearNo.NotANumber
        if (number == 0.0) return AppearNo.Zero
        return null
    }

    enum class Moment(val word: String) {
        FirstShow("firstShow"), Forward("forward"), Back("back"), Switch("switch"), ModalClosed("modalClosed"),
        ConditionMet("conditionMet"), DataChanged("dataChanged"),
        DataChangedDuringAppearWithAnimateChanges("dataChangedDuringAppearWithAnimateChanges"),
        DataChangedDuringAppear("dataChangedDuringAppear"),
    }

    enum class OnMoment(val word: String) { Appear("appear"), None("none"), Transition("transition"), AtOnce("atOnce") }

    fun appear(on: Moment): OnMoment = when (on) {
        Moment.FirstShow, Moment.Forward, Moment.Back, Moment.Switch, Moment.ConditionMet -> OnMoment.Appear
        Moment.ModalClosed, Moment.DataChanged -> OnMoment.None
        Moment.DataChangedDuringAppearWithAnimateChanges -> OnMoment.Transition
        Moment.DataChangedDuringAppear -> OnMoment.AtOnce
    }

    // ---- Targets ------------------------------------------------------------------------------

    data class Target(val path: String, val scale: Boolean, val format: String? = null, val currency: String? = null)

    fun animatesChanges(props: JsonObject): Boolean = props["animateChanges"]?.bool == true

    fun animatesIn(props: JsonObject): Boolean = props["animateIn"]?.bool == true

    /** Text formats counted through: none, `number` and `currency`; a date, `daysUntil` and a countdown part are not. */
    private fun transitionsInText(format: String?) = format == null || format == "number" || format == "currency"

    private fun bindingTarget(value: JsonValue?, scale: Boolean): Target? {
        val record = value?.obj ?: return null
        if (record["kind"]?.string != "binding") return null
        val path = record["path"]?.string ?: return null
        val format = record["format"]?.string
        if (!scale && !transitionsInText(format)) return null
        val currency = record["currency"]?.takeIf { BindingFormat.isCurrencyCode(it) }?.string
        return Target(path, scale, format, currency)
    }

    /** The numbers of a node that transition: `value` and `max` of a scale, each its own run; the bindings of a text. */
    fun targets(props: JsonObject): List<Target> {
        val result = ArrayList<Target?>()
        result.add(bindingTarget(props["value"], true))
        result.add(bindingTarget(props["max"], true))
        val content = props["content"]
        val values = content?.get("values")?.obj
        if (content?.get("kind")?.string == "template" && values != null) {
            for (key in values.keys) result.add(bindingTarget(values[key], false))
        } else {
            result.add(bindingTarget(content, false))
        }
        return result.filterNotNull()
    }

    /** The numbers that appear: the VALUE of a scale (a literal one is `#value`) and the numbers of a text; the `max` stands at once. */
    fun appearTargets(props: JsonObject): List<Target> {
        val literal = if (props["value"]?.get("kind")?.string == "literal") listOf(Target(LITERAL_KEY, true)) else emptyList()
        val valuePath = props["value"]?.takeIf { it["kind"]?.string == "binding" }?.get("path")?.string
        return literal + targets(props).filter { !(it.scale && it.path != valuePath) }
    }
}

/**
 * The clock of the numbers a node leads. The node does not learn a second way to read numbers:
 * the engine hands back the node's data scope with the counted paths replaced by the number shown
 * at this frame, and the node formats it as it always does.
 *
 * Two scopes come out: `stepped` for the label (the number moves in steps) and `smooth` for the
 * fill of a scale. A change during a transition starts from the value shown at that moment and
 * runs the full duration.
 *
 * A screen treats an engine as a value: it changes a [copy] while it composes and keeps the copy
 * only when it differs ([sameState]), so composing changes nothing that was drawn before.
 */
internal class ValueTransitionEngine {
    data class Run(val from: Double, val to: Double, val digits: Int, val scale: Boolean, var start: Double? = null)

    data class Ends(val from: Double, val to: Double, val shown: Double)

    /** What the node reads in place of its own data. */
    class Shown(val stepped: DataScope, val smooth: DataScope, val literal: Pair<Double, Double>?, val ends: Map<String, Ends>) {
        companion object {
            fun plain(scope: DataScope) = Shown(scope, scope, null, emptyMap())
        }
    }

    var mounted = false
        private set
    private var localeKey: String? = null
    private val last = HashMap<String, JsonValue>()
    val runs = LinkedHashMap<String, Run>()
    private var time = 0.0
    private var justFinished = false

    val isRunning: Boolean get() = runs.isNotEmpty()

    /** An engine in the same state that changes on its own. */
    fun copy(): ValueTransitionEngine = ValueTransitionEngine().also { copy ->
        copy.mounted = mounted
        copy.localeKey = localeKey
        copy.last.putAll(last)
        runs.forEach { (path, run) -> copy.runs[path] = run.copy() }
        copy.time = time
        copy.justFinished = justFinished
    }

    /** Whether [other] shows the same numbers now and later. */
    fun sameState(other: ValueTransitionEngine): Boolean =
        mounted == other.mounted && localeKey == other.localeKey && last == other.last && runs == other.runs &&
            time == other.time && justFinished == other.justFinished

    /** True once after the last run ended. */
    fun takeFinished(): Boolean = justFinished.also { justFinished = false }

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
            if (ValueTransition.appearDecision(true, raw, reducedMotion) != null) continue
            val end = raw?.number ?: continue
            runs[target.path] = ValueTransitionEngine.Run(
                ValueTransition.APPEAR_FROM, end, ValueTransition.digits(ValueTransition.APPEAR_FROM, end, target.format, target.currency), target.scale,
            )
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
            if (ValueTransition.decision(change) != null || start == null || end == null) {
                runs.remove(target.path)
                continue
            }
            runs[target.path] = ValueTransitionEngine.Run(start, end, ValueTransition.digits(start, end, target.format, target.currency), target.scale)
        }
    }

    /** A frame at [milliseconds]: the first frame of a run fixes its start, a run past the duration ends. */
    fun tick(milliseconds: Double) {
        time = milliseconds
        val was = runs.isNotEmpty()
        for (path in runs.keys.toList()) {
            val run = runs.getValue(path)
            if (run.start == null) run.start = milliseconds
            if (milliseconds - (run.start ?: milliseconds) >= ValueTransition.DURATION_MS) runs.remove(path)
        }
        if (was && runs.isEmpty()) justFinished = true
    }

    fun shown(scope: DataScope): Shown {
        if (runs.isEmpty()) return Shown.plain(scope)
        val stepped = HashMap(scope)
        val smooth = HashMap(scope)
        var literal: Pair<Double, Double>? = null
        val ends = HashMap<String, Ends>()
        for ((path, run) in runs) {
            val step = shownNow(path, false)
            val flow = if (run.scale) shownNow(path, true) else step
            if (run.scale) ends[path] = Ends(run.from, run.to, flow ?: run.to)
            if (path == ValueTransition.LITERAL_KEY) {
                literal = (step ?: run.to) to (flow ?: run.to)
                continue
            }
            step?.let { stepped[path] = JsonValue.Num(it) }
            flow?.let { smooth[path] = JsonValue.Num(it) }
        }
        return Shown(stepped, smooth, literal, ends)
    }
}
