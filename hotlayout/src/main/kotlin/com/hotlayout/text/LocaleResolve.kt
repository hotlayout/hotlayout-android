package com.hotlayout.text

import android.icu.util.ULocale

internal object LocaleTags {
    /** The longest string that is considered a language tag at all. */
    internal const val MAX_LENGTH: Int = 100

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
    private fun isAlpha(part: String, range: IntRange) = part.length in range && part.all(::isAsciiLetter)
    private fun isAlnum(part: String, range: IntRange) = part.length in range && part.all { isAsciiLetter(it) || it in '0'..'9' }

    /**
     * The tag in canonical form (language lowercase, script capitalized, region uppercase,
     * everything after a singleton lowercase), or `null` if it is not a language tag.
     */
    internal fun normalize(raw: String?): String? {
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
    internal fun lookupChain(normalized: String): List<String> {
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
    internal fun wellFormedTag(normalized: String?): String? =
        normalized?.let { lookupChain(it).firstOrNull(wellFormed::matches) }
}


/**
 * Writing direction of a language from platform locale data (CLDR through ICU), not from a list of
 * our own. `null` means "no language": the caller decides (the device direction).
 */
internal object TextDirectionRule {
    internal fun isRightToLeft(locale: String?): Boolean? {
        val tag = LocaleTags.normalize(locale) ?: return null
        return ULocale.forLanguageTag(tag).isRightToLeft
    }
}
