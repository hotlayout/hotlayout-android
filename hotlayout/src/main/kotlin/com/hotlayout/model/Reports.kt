package com.hotlayout.model

/**
 * Something the reader skipped or replaced while reading a document, a theme or the data.
 *
 * The document and the theme arrive over the network: a piece of the wrong shape is ignored (the
 * rest of the screen is drawn) and goes into a report, so that the host can log it. Nothing in a
 * report is shown to the end user.
 */
public data class HotlayoutReport(val kind: Kind, val location: String, val reason: String) {
    public enum class Kind(public val word: String) {
        /** A piece of the wrong shape was ignored. */
        IgnoredInput("ignored-input"),

        /** A widget this reader does not know or does not draw yet; its children were drawn. */
        UnknownWidget("unknown-widget"),

        /** A token the theme does not declare; the field default applies. */
        UnknownToken("unknown-token"),
    }

    override fun toString(): String = "[${kind.word}] $location: $reason"
}

/**
 * Collects reports while a document or a screen is read. Thread safe and bounded: a hostile
 * document could produce a report per node per frame.
 */
public class ReportSink {
    private val collected = ArrayList<HotlayoutReport>()
    private val seen = HashSet<HotlayoutReport>()

    public fun add(kind: HotlayoutReport.Kind, location: String, reason: String) {
        synchronized(this) {
            val report = HotlayoutReport(kind, location, reason)
            if (collected.size < LIMIT && seen.add(report)) collected.add(report)
        }
    }

    public val reports: List<HotlayoutReport> get() = synchronized(this) { collected.toList() }

    public companion object {
        public const val LIMIT: Int = 500
    }
}
