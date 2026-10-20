package com.hotlayout.text

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Decimal arithmetic over the shortest round-trip string of a number
 * (`numberRounding: 'half-away-from-zero-over-shortest-decimal'`).
 *
 * `1.0005` as a binary double is slightly below 1.0005, so rounding the double gives 1.000; every
 * reader of the contract rounds the SHORTEST decimal string the number prints as, which gives
 * 1.001. The work happens on digit strings, never on the double.
 */
internal object DecimalText {
    /**
     * The shortest decimal that reads back as the same double, the closest one when several have
     * the same length (what JavaScript prints). Not `Double.toString`: older runtimes do not
     * promise the shortest form.
     */
    fun shortest(value: Double): BigDecimal {
        val magnitude = Math.abs(value)
        // A computed value may overflow to infinity or be NaN (a label of a progress, a count); it prints as 0, never throws.
        if (magnitude == 0.0 || !magnitude.isFinite()) return BigDecimal.ZERO
        val exact = BigDecimal(magnitude)
        for (precision in 1..17) {
            val candidate = exact.round(MathContext(precision, RoundingMode.HALF_EVEN))
            if (candidate.toDouble() == magnitude) return candidate.stripTrailingZeros()
        }
        return exact.stripTrailingZeros()
    }

    /** The plain decimal digits of a finite number: no exponent, no sign. */
    fun plain(value: Double): String {
        val text = shortest(value).toPlainString()
        return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
    }

    data class Parts(val negative: Boolean, val integer: String, val fraction: String)

    /**
     * The number rounded half away from zero to at most [digits] fraction digits, as parts. Trailing
     * zeros of the fraction are dropped; callers that want a fixed count pad themselves.
     */
    fun rounded(value: Double, digits: Int): Parts {
        val negative = value < 0
        val text = plain(value)
        val dot = text.indexOf('.')
        var integer = if (dot < 0) text else text.substring(0, dot)
        var fraction = if (dot < 0) "" else text.substring(dot + 1)
        if (fraction.length > digits) {
            val roundUp = fraction[digits] >= '5'
            fraction = fraction.substring(0, digits)
            if (roundUp) {
                val all = (integer + fraction).toCharArray().toMutableList()
                var index = all.size - 1
                var carry = true
                while (carry && index >= 0) {
                    if (all[index] == '9') {
                        all[index] = '0'
                        index--
                    } else {
                        all[index] = all[index] + 1
                        carry = false
                    }
                }
                if (carry) all.add(0, '1')
                val joined = String(all.toCharArray())
                integer = joined.substring(0, joined.length - digits)
                fraction = joined.substring(joined.length - digits)
            }
        }
        fraction = fraction.trimEnd('0')
        return Parts(negative, integer.ifEmpty { "0" }, fraction)
    }

    /** The rounded number back as a decimal string (`-12.5`). */
    fun roundedString(value: Double, digits: Int): String {
        val parts = rounded(value, digits)
        val body = if (parts.fraction.isEmpty()) parts.integer else "${parts.integer}.${parts.fraction}"
        val isZero = body.all { it == '0' || it == '.' }
        return if (parts.negative && !isZero) "-$body" else body
    }

    /** A number written the way JavaScript's `String(number)` writes it. */
    fun javaScriptNumber(number: Double): String {
        if (number.isNaN()) return "NaN"
        if (number.isInfinite()) return if (number < 0) "-Infinity" else "Infinity"
        if (number == 0.0) return "0"
        val magnitude = Math.abs(number)
        val sign = if (number < 0) "-" else ""
        if (magnitude >= 1e21 || magnitude < 1e-6) {
            val decimal = shortest(magnitude)
            val digits = decimal.unscaledValue().toString()
            val exponent = digits.length - 1 - decimal.scale()
            val mantissa = if (digits.length > 1) "${digits[0]}.${digits.substring(1)}" else digits
            return "$sign${mantissa}e${if (exponent < 0) "-" else "+"}${Math.abs(exponent)}"
        }
        return sign + plain(magnitude)
    }
}
