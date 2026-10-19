package com.hotlayout.text

import android.icu.util.ULocale

/**
 * Which language a document is shown in (schema 9.1.0).
 *
 * The host passes the DEVICE locale (`ar-EG`, `pt_BR`); the document has translations under short
 * codes. The contract's policy rows:
 *  - `localeNormalization`: `_` equals `-`, case does not matter;
 *  - `localeMatching: 'bcp47-lookup-truncate'`: exact match, then drop the last part;
 *  - `localeChildFallback: 'none'`: `ar` does NOT find `ar-EG`;
 *  - `unmatchedLocale: 'use-default-locale'`;
 *  - `directionFollows: 'shown-translation-locale'`;
 *  - `formattingLocale: 'requested-when-matched-else-default'`.
 */
public data class LocaleResolution(
    /** The language the texts are shown in, as the document spells it; `null` only without localization. */
    val translation: String?,
    /** The locale numbers and dates are written in (canonical BCP 47); `null`: the device's. */
    val formatting: String?,
    /** The tag the writing direction comes from: the language of the SHOWN text. */
    val directionLocale: String?,
    /** Whether the requested locale was found, exactly or by dropping subtags. */
    val matched: Boolean,
)

public object LocaleTags {
    /** The longest string that is considered a language tag at all. */
    public const val MAX_LENGTH: Int = 100

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isAlpha(part: String, range: IntRange) = part.length in range && part.all(::isAsciiLetter)
    private fun isAlnum(part: String, range: IntRange) = part.length in range && part.all { isAsciiLetter(it) || it in '0'..'9' }

    /**
     * The tag in canonical form (language lowercase, script capitalized, region uppercase,
     * everything after a singleton lowercase), or `null` if it is not a language tag.
     */
    public fun normalize(raw: String?): String? {
        if (raw == null || raw.length > MAX_LENGTH) return null
        val parts = raw.trim().replace('_', '-').split('-')
        if (!isAlpha(parts[0], 2..8)) return null
        if (!parts.all { isAlnum(it, 1..8) }) return null
        var afterSingleton = false
        return parts.mapIndexed { index, part ->
            when {
                index == 0 || afterSingleton -> part.lowercase()
                part.length == 1 -> { afterSingleton = true; part.lowercase() }
                isAlpha(part, 4..4) -> part.substring(0, 1).uppercase() + part.substring(1).lowercase()
                isAlpha(part, 2..2) -> part.uppercase()
                else -> part.lowercase()
            }
        }.joinToString("-")
    }

    /** The tag and everything left after dropping parts from the end; a single-letter part is never left last. */
    public fun lookupChain(normalized: String): List<String> {
        if (normalized.length > MAX_LENGTH) return emptyList()
        val parts = normalized.split('-').toMutableList()
        val chain = ArrayList<String>()
        while (parts.isNotEmpty()) {
            chain.add(parts.joinToString("-"))
            parts.removeAt(parts.size - 1)
            while (parts.size > 1 && parts.last().length == 1) parts.removeAt(parts.size - 1)
        }
        return chain
    }

    // Language (2-3 or 5-8 letters), script, region, variants, extensions, private use — on the canonical form.
    private val wellFormed = Regex(
        "^(?:[a-z]{2,3}|[a-z]{5,8})(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?(?:-(?:[a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*(?:-[a-wyz0-9](?:-[a-z0-9]{2,8})+)*(?:-x(?:-[a-z0-9]{1,8})+)?$",
    )

    /** The longest prefix of a tag with a valid shape: `ja-JP-JP` → `ja-JP`. */
    public fun wellFormedTag(normalized: String?): String? =
        normalized?.let { lookupChain(it).firstOrNull(wellFormed::matches) }
}

/**
 * Which language a document is shown in when the host asks for [requested].
 *
 * @param requested the locale of the user, as the platform gives it.
 * @param available the document's `localization.locales`.
 * @param source the document's `localization.defaultLocale`; `null` — no localization.
 */
public fun resolveLocale(requested: String?, available: List<String>, source: String?): LocaleResolution {
    val asked = LocaleTags.normalize(requested)
    val askedFormat = LocaleTags.wellFormedTag(asked)
    if (source == null) return LocaleResolution(null, askedFormat, askedFormat ?: asked, false)
    fun canonical(locale: String) = LocaleTags.normalize(locale) ?: locale
    val unmatched = LocaleResolution(
        translation = source,
        formatting = LocaleTags.wellFormedTag(LocaleTags.normalize(source)) ?: canonical(source),
        directionLocale = canonical(source),
        matched = false,
    )
    if (asked == null) return unmatched
    // Comparison key → the DOCUMENT's spelling; the first in the list wins over a case twin.
    val keys = LinkedHashMap<String, String>()
    for (locale in available + source) {
        val key = LocaleTags.normalize(locale)?.lowercase() ?: continue
        if (!keys.containsKey(key)) keys[key] = locale
    }
    for (candidate in LocaleTags.lookupChain(asked)) {
        val found = keys[candidate.lowercase()] ?: continue
        val shown = canonical(found)
        return LocaleResolution(found, askedFormat ?: LocaleTags.wellFormedTag(shown) ?: shown, shown, true)
    }
    return unmatched
}

/**
 * Writing direction of a language from platform locale data (CLDR through ICU), not from a list of
 * our own. `null` means "no language": the caller decides (the device direction).
 */
public object TextDirectionRule {
    public fun isRightToLeft(locale: String?): Boolean? {
        val tag = LocaleTags.normalize(locale) ?: return null
        return ULocale.forLanguageTag(tag).isRightToLeft
    }
}
