package com.hotlayout.text

import com.hotlayout.model.DocumentLocalization

/** Translation keys and lookups of a document (`localization.strings`). */
public object Localization {
    /** The key of a string field of a node: `<screenId>/<nodeId>/<field>`. */
    public fun key(screenId: String, nodeId: String, field: String): String = "$screenId/$nodeId/$field"

    /**
     * The translation under [key] in the shown language, or `null` (`missingTranslation`: the text
     * of the document). The source language has no table of its own.
     */
    public fun translate(localization: DocumentLocalization?, translation: String?, key: String): String? {
        if (localization == null || translation == null || translation == localization.defaultLocale) return null
        return localization.strings[translation]?.get(key)
    }

    /**
     * The language a document is shown in for the host's locale. A language counts as available
     * only if its table holds at least one string; a partly translated language is shown, and the
     * rest falls back to the source text.
     */
    public fun resolve(localization: DocumentLocalization?, requested: String?): LocaleResolution {
        if (localization == null) return resolveLocale(requested, emptyList(), null)
        val available = localization.locales.filter { it == localization.defaultLocale || !localization.strings[it].isNullOrEmpty() }
        return resolveLocale(requested, available, localization.defaultLocale)
    }
}
