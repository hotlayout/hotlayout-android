package com.hotlayout.text

import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import java.util.TimeZone

/** What turns a data value into text: the data, the format locale, the zone and «now». */
public data class TextEnvironment(
    val scope: DataScope,
    /** The format locale (`formatting` of [resolveLocale]); `null` means the device language. */
    val formattingLocale: String? = null,
    /** The language of the shown text, used when the platform rejects the format locale. */
    val fallbackLocale: String? = null,
    val timeZone: TimeZone? = null,
    /** «Now» in epoch milliseconds (`readerClock: 'injectable-read-at-render'`). */
    val now: Double? = null,
    /** Direction of the shown text; `true` is right to left. */
    val rightToLeft: Boolean = TextDirectionRule.isRightToLeft(fallbackLocale) ?: false,
)

/** A part of a formatted number that a text span may style (9.19.0). */
public enum class FormatPart(public val word: String) { Fraction("fraction"), Currency("currency") }

/** A piece of a formatted value: the text and which part of the number it is. */
public data class FormattedPiece(val text: String, val part: FormatPart? = null)

/**
 * Format of a data-bound value (`binding.format`).
 *
 * The document names an INTENT; the locale supplies the look. `null` from a function here means
 * «the format did not apply»: the value is then shown AS IS.
 */
public object BindingFormat {
    /** `x-template.number`: groups always, fraction 0…3 digits, up to 2^53 − 1. */
    internal const val MAXIMUM_ABSOLUTE_VALUE = 9_007_199_254_740_991.0
    internal const val MAXIMUM_FRACTION_DIGITS = 3

    /** `x-template.daysUntil`: a day is exactly 24 hours. */
    internal const val DAY_MILLISECONDS = 86_400_000.0

    /** Formats under which a value is a NUMBER for plural selection. */
    internal val countable = setOf("number", "currency", "daysUntil", "countdownPart")
    internal const val CURRENCY_SEPARATOR = " "
    private val currencyCode = Regex("^[A-Z]{3}$")

    internal fun isCurrencyCode(value: JsonValue?): Boolean = value?.string?.let(currencyCode::matches) ?: false

    /**
     * The NUMBER a value becomes under a format: what is printed and what picks a plural form. The
     * string «12» is not a number; a date is one only under `daysUntil` / `countdownPart`.
     */
    public fun numericValue(value: JsonValue?, format: String?, now: Double?, unit: JsonValue? = null): Double? = when (format) {
        "countdownPart" -> {
            val moment = DateText.moment(value)
            if (moment == null || now == null || !now.isFinite()) null else countdownPart(moment, now, unit?.string)
        }
        "daysUntil" -> {
            val moment = DateText.moment(value)
            if (moment == null || now == null || !now.isFinite()) null else maxOf(0.0, Math.ceil((moment - now) / DAY_MILLISECONDS))
        }
        null, "number", "currency" -> value?.number?.takeIf { Math.abs(it) <= MAXIMUM_ABSOLUTE_VALUE }
        else -> null
    }

    /** A part of the time left (`x-countdown`): whole days, hours 0–23, minutes 0–59 or seconds 0–59, rounded DOWN. */
    internal fun countdownPart(moment: Double, now: Double, unit: String?): Double? {
        val seconds = Math.floor(maxOf(0.0, moment - now) / 1000)
        return when (unit) {
            "days" -> Math.floor(seconds / 86_400)
            "hours" -> Math.floor(seconds / 3_600) % 24
            "minutes" -> Math.floor(seconds / 60) % 60
            "seconds" -> seconds % 60
            else -> null
        }
    }

    /** The text of a value under the format of its binding, as pieces, or `null` when the format did not apply. */
    public fun pieces(value: JsonValue, binding: JsonObject, environment: TextEnvironment): List<FormattedPiece>? {
        val format = binding["format"]?.string ?: return null
        val located = NumberText.usableLocale(environment.formattingLocale, environment.fallbackLocale)
        val locale = (located as? NumberText.Usable.Tag)?.tag
        return when (format) {
            "number", "currency" -> if (located == null) null else MoneyFormat.pieces(value, binding, locale, environment)
            "daysUntil" -> {
                val days = numericValue(value, format, environment.now) ?: return null
                // No locale data: counted days are plain digits (an ISO string mid-sentence is worse).
                val text = if (located == null) null else NumberText.text(days, locale, 0, MAXIMUM_FRACTION_DIGITS)
                listOf(FormattedPiece(text ?: DecimalText.plain(days)))
            }
            "countdownPart" -> {
                val part = numericValue(value, format, environment.now, binding["unit"]) ?: return null
                val digits = if (binding["pad"]?.number == 2.0) 2 else 1
                val plainDigits = DecimalText.plain(part)
                val plain = "0".repeat(maxOf(0, digits - plainDigits.length)) + plainDigits
                val text = if (located == null) null else NumberText.text(part, locale, 0, 0, digits)
                listOf(FormattedPiece(text ?: plain))
            }
            "date", "dateTime", "time" -> {
                val moment = DateText.moment(value) ?: return null
                // A locale without date data shows the value as is (`missingDateLocaleData`).
                if (located == null) return null
                val parts = if (format == "date") binding["dateParts"]?.string else null
                DateText.format(moment, format, parts, locale, environment.timeZone)?.let { listOf(FormattedPiece(it)) }
            }
            else -> null
        }
    }
}

/** Money as money (`x-money`) and the compact number (`x-compactNumber`): the pieces of `number` and `currency`. */
internal object MoneyFormat {
    val symbols = mapOf(
        "UAH" to "₴", "USD" to "$", "EUR" to "€", "GBP" to "£", "PLN" to "zł", "JPY" to "¥",
        "CZK" to "Kč", "TRY" to "₺", "INR" to "₹", "ILS" to "₪", "KZT" to "₸", "GEL" to "₾",
    )
    const val PLUS = "+"
    const val MINUS = "−"
    const val ISOLATE_OPEN = "⁦"
    const val ISOLATE_CLOSE = "⁩"

    fun pieces(value: JsonValue, binding: JsonObject, locale: String?, environment: TextEnvironment): List<FormattedPiece>? {
        val format = binding["format"]?.string
        val money = format == "currency"
        val number = BindingFormat.numericValue(value, format, environment.now) ?: return null
        val code = if (money && BindingFormat.isCurrencyCode(binding["currency"])) binding["currency"]?.string else null
        if (money && code == null) return null

        val symbolDisplay = money && binding["currencyDisplay"]?.string == "symbol"
        val before = money && binding["currencyPosition"]?.string == "before"
        val signAlways = binding["sign"]?.string == "always"
        val compact = if (format == "number" && binding["notation"]?.string == "compact") CompactNumber.parts(number) else null

        val (minDigits, maxDigits) = when {
            compact != null -> 0 to 1
            code != null -> {
                val count = if (number == Math.rint(number)) 0 else NumberText.currencyFractionDigits(code)
                count to count
            }
            else -> 0 to BindingFormat.MAXIMUM_FRACTION_DIGITS
        }
        val composed = signAlways || symbolDisplay || before
        val shown = compact?.let { (if (number < 0) -1 else 1) * it.first } ?: number
        val amount = NumberText.parts(if (composed) Math.abs(shown) else shown, locale, minDigits, maxDigits) ?: return null

        var aroundBefore = ""
        var aroundAfter = ""
        if (code != null) {
            val symbol = if (symbolDisplay) symbols[code] else null
            if (before) aroundBefore = symbol ?: "$code${BindingFormat.CURRENCY_SEPARATOR}" else aroundAfter = "${BindingFormat.CURRENCY_SEPARATOR}${symbol ?: code}"
        }
        var sign = ""
        if (composed && !NumberText.writtenAsZero(Math.abs(number), maxDigits)) sign = if (number < 0) MINUS else if (signAlways) PLUS else ""
        val pieces = merged(
            listOf(
                FormattedPiece(sign),
                FormattedPiece(aroundBefore, FormatPart.Currency),
                FormattedPiece(amount.integer),
                FormattedPiece(amount.fraction, FormatPart.Fraction),
                FormattedPiece(compact?.let { CompactNumber.suffix(it.second, locale) } ?: ""),
                FormattedPiece(aroundAfter, FormatPart.Currency),
            ),
        ).toMutableList()
        if (composed && environment.rightToLeft && pieces.isNotEmpty()) {
            pieces[0] = pieces[0].copy(text = ISOLATE_OPEN + pieces[0].text)
            val last = pieces.size - 1
            pieces[last] = pieces[last].copy(text = pieces[last].text + ISOLATE_CLOSE)
        }
        return pieces
    }

    /** Empty pieces dropped, neighbours of the same part merged. */
    fun merged(pieces: List<FormattedPiece>): List<FormattedPiece> {
        val result = ArrayList<FormattedPiece>()
        for (piece in pieces) {
            if (piece.text.isEmpty()) continue
            val last = result.lastOrNull()
            if (last != null && last.part == piece.part) result[result.size - 1] = FormattedPiece(last.text + piece.text, last.part) else result.add(piece)
        }
        return result
    }
}

/**
 * The compact number (`x-compactNumber`): 1.2K, 3.4M — the contract's own arithmetic and suffix
 * table, not the platform's compact style (which differs between platforms).
 */
internal object CompactNumber {
    enum class Step { Thousand, Million, Billion }

    private val steps = listOf(Step.Thousand to 1_000.0, Step.Million to 1_000_000.0, Step.Billion to 1_000_000_000.0)

    private class Suffixes(val gap: String, val thousand: String, val million: String, val billion: String)

    private val suffixes = mapOf(
        "en" to Suffixes("", "K", "M", "B"),
        "uk" to Suffixes(" ", "тис.", "млн", "млрд"),
    )

    /** The mantissa (tenths) and the step, or `null` under a thousand. */
    fun parts(value: Double): Pair<Double, Step>? {
        val magnitude = Math.abs(value)
        if (!magnitude.isFinite() || magnitude < steps[0].second) return null
        var index = steps.size - 1
        while (index > 0 && magnitude < steps[index].second) index--
        // Half away from zero, as the shortest decimal is rounded elsewhere (`rint` would round half to even).
        fun tenths(at: Int) = Math.floor(Math.floor((magnitude * 10) / steps[at].second * 1e6 + 0.5) / 1e6 + 0.5)
        var count = tenths(index)
        if (count >= 10_000 && index < steps.size - 1) {
            index++
            count = tenths(index)
        }
        return count / 10 to steps[index].first
    }

    fun suffix(step: Step, locale: String?): String {
        val language = (locale ?: "en").split('-', '_').first().lowercase()
        val table = suffixes[if (language == "uk") "uk" else "en"]!!
        return table.gap + when (step) {
            Step.Thousand -> table.thousand
            Step.Million -> table.million
            Step.Billion -> table.billion
        }
    }
}
