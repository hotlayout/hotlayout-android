package com.hotlayout.text

import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import android.icu.util.Currency
import android.icu.util.ULocale

/**
 * Numbers in a locale, the way every reader of the contract writes them.
 *
 * The locale supplies the digits, the decimal sign and the group separator (CLDR, through ICU).
 * Two things are the contract's and are done here by hand:
 *  - groups ALWAYS (`x-template.number.grouping`): CLDR `minimumGroupingDigits` makes Spanish write
 *    «1850», while the contract says «1.850»;
 *  - rounding half away from zero over the shortest decimal string ([DecimalText]).
 */
internal object NumberText {
    private val available: Set<String> by lazy { ULocale.getAvailableLocales().map { it.toString().lowercase() }.toSet() }
    private val cache = HashMap<String, DecimalFormat>()
    private const val CACHE_LIMIT = 64

    /** Whether the platform has locale data for a tag or any of its shorter forms. */
    fun hasData(tag: String): Boolean {
        val normalized = LocaleTags.normalize(tag) ?: return false
        return LocaleTags.lookupChain(normalized).any { available.contains(it.replace('-', '_').lowercase()) }
    }

    /** A locale to format in, as the answer of [usableLocale]. */
    sealed class Usable {
        /** Nobody named a language: the device language. */
        object Device : Usable()
        data class Tag(val tag: String) : Usable()
    }

    /**
     * The locale to format in, or `null` when the format does not apply.
     *
     * Two questions, in this order:
     *  1. SHAPE: the first tag of the lookup chain of the format locale with a valid shape; only if
     *     the format locale has none, the language of the shown text;
     *  2. DATA: whether the platform has data for that tag (or a shorter form of it). A language
     *     without data shows the value as is, never a number in another language.
     */
    fun usableLocale(locale: String?, fallback: String?): Usable? {
        if (locale.isNullOrEmpty()) return Usable.Device
        for (candidate in listOfNotNull(locale, fallback)) {
            val normalized = LocaleTags.normalize(candidate.take(LocaleTags.MAX_LENGTH)) ?: continue
            val shaped = LocaleTags.wellFormedTag(normalized) ?: continue
            return if (hasData(shaped)) Usable.Tag(shaped) else null
        }
        return null
    }

    fun uLocale(locale: String?): ULocale = locale?.let(ULocale::forLanguageTag) ?: ULocale.getDefault()

    private fun formatter(locale: String?, minimumFraction: Int, maximumFraction: Int, minimumInteger: Int): DecimalFormat = synchronized(cache) {
        val key = "${locale ?: ""}|$minimumFraction|$maximumFraction|$minimumInteger"
        cache[key]?.let { return it }
        val format = NumberFormat.getInstance(uLocale(locale), NumberFormat.NUMBERSTYLE) as DecimalFormat
        format.isGroupingUsed = false
        format.minimumFractionDigits = minimumFraction
        format.maximumFractionDigits = maximumFraction
        format.minimumIntegerDigits = minimumInteger
        format.roundingMode = android.icu.math.BigDecimal.ROUND_HALF_UP
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[key] = format
        format
    }

    data class Split(val integer: String, val fraction: String)

    /**
     * A number written in a locale, split at the decimal sign: [Split.integer] carries the sign and
     * the grouped integer digits, [Split.fraction] the decimal sign and the fraction digits (empty
     * when there are none).
     */
    fun parts(value: Double, locale: String?, minimumFraction: Int, maximumFraction: Int, minimumInteger: Int = 1): Split? {
        if (!value.isFinite()) return null
        val format = formatter(locale, minimumFraction, maximumFraction, minimumInteger)
        val written: String
        val separator: String
        val group: String
        val primary: Int
        val secondary: Int
        synchronized(format) {
            written = format.format(java.math.BigDecimal(DecimalText.roundedString(value, maximumFraction)))
            val symbols = format.decimalFormatSymbols
            separator = symbols.decimalSeparator.toString()
            group = symbols.groupingSeparator.toString()
            primary = format.groupingSize
            secondary = format.secondaryGroupingSize
        }
        val cut = if (maximumFraction > 0) written.lastIndexOf(separator) else -1
        val integerText = if (cut >= 0) written.substring(0, cut) else written
        val fractionText = if (cut >= 0) written.substring(cut) else ""
        return Split(groupDigits(integerText, group, primary, secondary), fractionText)
    }

    fun text(value: Double, locale: String?, minimumFraction: Int, maximumFraction: Int, minimumInteger: Int = 1): String? =
        parts(value, locale, minimumFraction, maximumFraction, minimumInteger)?.let { it.integer + it.fraction }

    /**
     * Inserts group separators into the run of decimal digits of the integer part. The digits may be
     * of any script (Arabic-Indic, Persian): what counts is that they are digits.
     */
    fun groupDigits(text: String, separator: String, primary: Int, secondary: Int): String {
        if (primary <= 0) return text
        val points = text.codePoints().toArray()
        val first = points.indexOfFirst(Character::isDigit)
        if (first < 0) return text
        var last = first
        while (last + 1 < points.size && Character.isDigit(points[last + 1])) last++
        val digits = points.copyOfRange(first, last + 1)
        if (digits.size <= primary) return text
        val groups = ArrayList<String>()
        var end = digits.size
        var size = primary
        while (end > 0) {
            val start = maxOf(0, end - size)
            groups.add(0, String(digits, start, end - start))
            end = start
            if (secondary > 0) size = secondary
        }
        return String(points, 0, first) + groups.joinToString(separator) + String(points, last + 1, points.size - last - 1)
    }

    /** Fraction digits of a currency from platform data (ISO 4217 as CLDR carries it); an unknown code gets two. */
    fun currencyFractionDigits(code: String): Int = try {
        Currency.getInstance(code).defaultFractionDigits.takeIf { it >= 0 } ?: 2
    } catch (_: IllegalArgumentException) {
        2
    }

    /** Whether a number is written as zero under the given fraction digits (no digit 1–9). */
    fun writtenAsZero(magnitude: Double, maximumFraction: Int): Boolean {
        val parts = DecimalText.rounded(Math.abs(magnitude), maximumFraction)
        return (parts.integer + parts.fraction).none { it in '1'..'9' }
    }
}
