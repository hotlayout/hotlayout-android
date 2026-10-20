package com.hotlayout

import com.hotlayout.model.DocumentReader
import com.hotlayout.model.ReportSink
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.ThemeReader

/** A theme contract: a set of themes and the one a reader takes by default. */
@ConsistentCopyVisibility
public data class HotlayoutThemeSet internal constructor(
    val themeContractVersion: String,
    val defaultThemeId: String,
    internal val themes: Map<String, HotlayoutTheme>,
) {
    /** The theme the reader draws with: the requested one, else the default, else any. */
    internal fun theme(id: String? = null): HotlayoutTheme =
        id?.let { themes[it] } ?: themes[defaultThemeId] ?: themes.values.firstOrNull() ?: builtIn.themes.values.first()

    public companion object {
        /** The theme this reader ships with: the `contract` block of `theme.json`. */
        public val builtIn: HotlayoutThemeSet by lazy {
            val bytes = HotlayoutThemeSet::class.java.getResourceAsStream("/com/hotlayout/built-in-theme.json")?.use { it.readBytes() }
                ?: error("HotLayout: the built-in theme is missing from the library resources")
            ThemeReader.read(JsonValue.parse(bytes), null, ReportSink()) ?: error("HotLayout: the built-in theme does not read")
        }

        /**
         * Reads a theme contract from the network. A newer minor's fields are skipped; a missing
         * entry is filled from the built-in theme of the same brightness; a theme that cannot be
         * read at all gives `null` (draw with [builtIn]). What was skipped is added to [reports].
         */
        public fun read(json: JsonValue, reports: MutableCollection<HotlayoutReport>? = null): HotlayoutThemeSet? {
            val sink = ReportSink()
            return read(json, sink).also { reports?.addAll(sink.reports) }
        }

        internal fun read(json: JsonValue, sink: ReportSink): HotlayoutThemeSet? =
            try {
                ThemeReader.read(DocumentReader.withoutForbiddenKeys(json), builtIn, sink)
            } catch (_: HotlayoutLoadException.MalformedJson) {
                sink.add(HotlayoutReport.Kind.IgnoredInput, "theme", "the theme is nested too deep; the built-in theme is used")
                null
            }

        /** Reads a theme contract from JSON text; text that is not JSON gives `null`. */
        public fun read(text: String, reports: MutableCollection<HotlayoutReport>? = null): HotlayoutThemeSet? {
            val sink = ReportSink()
            return read(text, sink).also { reports?.addAll(sink.reports) }
        }

        internal fun read(text: String, sink: ReportSink): HotlayoutThemeSet? =
            runCatching { JsonValue.parse(text) }.getOrNull()?.let { read(it, sink) }
    }
}
