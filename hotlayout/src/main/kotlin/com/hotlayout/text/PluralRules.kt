package com.hotlayout.text

import android.icu.util.ULocale

/** CLDR plural categories (`x-template.pluralCategories`). */
public enum class PluralCategory(public val word: String) { Zero("zero"), One("one"), Two("two"), Few("few"), Many("many"), Other("other") }

/**
 * CLDR cardinal plural rules (`pluralCategory: 'cldr-cardinal-categories-not-exact-numbers'`),
 * through the platform's ICU.
 *
 * The number is the absolute value rounded to three fraction digits (`pluralNumber`); its visible
 * fraction digits take part in the rule (`pluralFractionOperands: 'cldr-with-fraction-digits'`).
 * A language without rules, a non-tag or a non-finite number gives `other`, never the rules of the
 * device language.
 */
public object PluralRules {
    public const val MAXIMUM_FRACTION_DIGITS: Int = 3

    public fun category(value: Double, locale: String?): PluralCategory {
        if (locale == null || locale.length > LocaleTags.MAX_LENGTH || !value.isFinite()) return PluralCategory.Other
        val tag = LocaleTags.normalize(locale) ?: return PluralCategory.Other
        val rules = android.icu.text.PluralRules.forLocale(ULocale.forLanguageTag(tag))
        val word = rules.select(operandNumber(value))
        return PluralCategory.entries.firstOrNull { it.word == word } ?: PluralCategory.Other
    }

    /**
     * The number handed to ICU: the absolute value rounded to three fraction digits. ICU reads a
     * double with sixteen significant digits at most, so an integer part beyond a billion is
     * replaced by one with the same last nine digits (every CLDR rule reads at most `i % 1000000`).
     */
    internal fun operandNumber(value: Double): Double {
        val parts = DecimalText.rounded(Math.abs(value), MAXIMUM_FRACTION_DIGITS)
        val integer = if (parts.integer.length > 9) "1" + parts.integer.takeLast(9) else parts.integer
        return (if (parts.fraction.isEmpty()) integer else "$integer.${parts.fraction}").toDouble()
    }
}
