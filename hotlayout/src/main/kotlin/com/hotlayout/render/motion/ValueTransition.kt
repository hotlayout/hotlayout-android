package com.hotlayout.render.motion

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.text.BindingFormat
import com.hotlayout.text.NumberText
import kotlin.math.floor
import kotlin.math.pow

/**
 * A value that changes from data (`animateChanges`, `x-valueTransition`) or grows from zero when
 * its node is shown (`animateIn`, `x-valueAppear`): the curve, the step, the decisions and the
 * numbers a node leads. Pure functions; the clock lives in [ValueTransitionEngine].
 *
 * One rule for every widget that counts a number (a scale, a text): a family never carries its own copy.
 */
internal object ValueTransition {
    const val DURATION_MS = 700.0
    const val PRECISION = 6
    const val MAX_FRACTION_DIGITS = 3

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
        if (format == "currency" && currency != null && BindingFormat.isCurrencyCode(JsonValue.Str(currency))) {
            return NumberText.currencyFractionDigits(currency)
        }
        return MAX_FRACTION_DIGITS
    }

    /** JavaScript `Math.round`: halves go up. */
    private fun jsRound(value: Double): Double = floor(value + 0.5)

    private fun rounded(value: Double, digits: Int): Double {
        val scale = 10.0.pow(digits)
        return jsRound(value * scale) / scale
    }

    /**
     * The value shown `t` ms after the transition began. A scale (`digits == null`) has no step; a
     * number moves in steps cut towards the OLD value, on a rounded product so that `0.3 × 0.875`
     * is not cut a step lower.
     */
    fun value(from: Double, to: Double, t: Double, digits: Int?): Double {
        if (!t.isFinite() || t >= DURATION_MS) return to
        val delta = (to - from) * ease(t / DURATION_MS)
        if (digits == null) return from + delta
        val scale = 10.0.pow(digits)
        val product = jsRound(delta * scale * 10.0.pow(PRECISION)) / 10.0.pow(PRECISION)
        val steps = if (product < 0) kotlin.math.ceil(product) else floor(product)
        return rounded(from + steps / scale, digits + PRECISION)
    }

    // region Decisions

    enum class NoTransition(val word: String) {
        NoField("noField"), FirstShow("firstShow"), SameValue("sameValue"), NotANumber("notANumber"),
        LocaleChanged("localeChanged"), ReducedMotion("reducedMotion"),
    }

    sealed class Decision {
        object Leads : Decision()
        data class AtOnce(val reason: NoTransition) : Decision()
    }

    data class Change(
        val animates: Boolean,
        val shownBefore: Boolean,
        val from: JsonValue?,
        val to: JsonValue?,
        val localeChanged: Boolean = false,
        val reducedMotion: Boolean = false,
    )

    /** The first reason in the contract order wins: no field, first show, reduced motion, a changed language, not a number, the same value. */
    fun decision(change: Change): Decision {
        if (!change.animates) return Decision.AtOnce(NoTransition.NoField)
        if (!change.shownBefore) return Decision.AtOnce(NoTransition.FirstShow)
        if (change.reducedMotion) return Decision.AtOnce(NoTransition.ReducedMotion)
        if (change.localeChanged) return Decision.AtOnce(NoTransition.LocaleChanged)
        val from = change.from?.number
        val to = change.to?.number
        if (from == null || to == null) return Decision.AtOnce(NoTransition.NotANumber)
        if (from == to) return Decision.AtOnce(NoTransition.SameValue)
        return Decision.Leads
    }

    enum class AppearNo(val word: String) { NoField("noField"), ReducedMotion("reducedMotion"), NotANumber("notANumber"), Zero("zero") }

    sealed class AppearDecision {
        object Grows : AppearDecision()
        data class AtOnce(val reason: AppearNo) : AppearDecision()
    }

    fun appearDecision(animates: Boolean, to: JsonValue?, reducedMotion: Boolean): AppearDecision {
        if (!animates) return AppearDecision.AtOnce(AppearNo.NoField)
        if (reducedMotion) return AppearDecision.AtOnce(AppearNo.ReducedMotion)
        val number = to?.number ?: return AppearDecision.AtOnce(AppearNo.NotANumber)
        if (number == 0.0) return AppearDecision.AtOnce(AppearNo.Zero)
        return AppearDecision.Grows
    }

    enum class Moment(val word: String) {
        FirstShow("firstShow"), Forward("forward"), Back("back"), Switch("switch"), ModalClosed("modalClosed"),
        ConditionMet("conditionMet"), DataChanged("dataChanged"),
        DataChangedDuringAppearWithAnimateChanges("dataChangedDuringAppearWithAnimateChanges"),
        DataChangedDuringAppear("dataChangedDuringAppear"),
    }

    enum class OnMoment(val word: String) { Appear("appear"), None("none"), Transition("transition"), AtOnce("atOnce") }

    fun appear(moment: Moment): OnMoment = when (moment) {
        Moment.FirstShow, Moment.Forward, Moment.Back, Moment.Switch, Moment.ConditionMet -> OnMoment.Appear
        Moment.ModalClosed, Moment.DataChanged -> OnMoment.None
        Moment.DataChangedDuringAppearWithAnimateChanges -> OnMoment.Transition
        Moment.DataChangedDuringAppear -> OnMoment.AtOnce
    }

    // endregion

    // region Targets

    data class Target(val path: String, val scale: Boolean, val format: String? = null, val currency: String? = null)

    fun animatesChanges(props: JsonObject): Boolean = props["animateChanges"]?.bool == true
    fun animatesIn(props: JsonObject): Boolean = props["animateIn"]?.bool == true

    /** Text formats counted through: none, `number` and `currency`. A date, `daysUntil` and a countdown part are not. */
    private fun transitionsInText(format: String?): Boolean = format == null || format == "number" || format == "currency"

    private fun bindingTarget(value: JsonValue?, scale: Boolean): Target? {
        val record = value?.obj ?: return null
        if (record["kind"]?.string != "binding") return null
        val path = record["path"]?.string ?: return null
        val format = record["format"]?.string
        if (!scale && !transitionsInText(format)) return null
        val currency = if (BindingFormat.isCurrencyCode(record["currency"])) record["currency"]?.string else null
        return Target(path, scale, format, currency)
    }

    /**
     * The numbers of a node that transition: the `value` and `max` of a scale, each its own run;
     * the bindings of a text, alone or inside a template.
     */
    fun targets(props: JsonObject): List<Target> {
        val result = ArrayList<Target?>()
        result.add(bindingTarget(props["value"], true))
        result.add(bindingTarget(props["max"], true))
        val content = props["content"]
        val values = content?.obj?.takeIf { content["kind"]?.string == "template" }?.let { content["values"]?.obj }
        if (values != null) {
            for (key in values.keys) result.add(bindingTarget(values[key], false))
        } else {
            result.add(bindingTarget(content, false))
        }
        return result.filterNotNull()
    }

    /** The numbers that appear: the VALUE of a scale (a literal one is `#value`), the numbers of a text. The `max` of a scale stands at once. */
    fun appearTargets(props: JsonObject): List<Target> {
        val value = props["value"]
        val literalScale = if (value?.get("kind")?.string == "literal") listOf(Target(LITERAL_KEY, true)) else emptyList()
        val valuePath = if (value?.get("kind")?.string == "binding") value["path"]?.string else null
        val counted = targets(props).filter { !(it.scale && it.path != valuePath) }
        return literalScale + counted
    }

    // endregion
}
