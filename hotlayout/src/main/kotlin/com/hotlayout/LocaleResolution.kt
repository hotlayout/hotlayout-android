package com.hotlayout

import com.hotlayout.text.LocaleTags

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
