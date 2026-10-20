package com.hotlayout.model

import androidx.compose.runtime.mutableIntStateOf
import com.hotlayout.HotlayoutReport

/**
 * Collects reports while a document or a screen is read. Thread safe and bounded: a hostile
 * document could produce a report per node per frame.
 */
internal class ReportSink {
    private val collected = ArrayList<HotlayoutReport>()
    private val seen = HashSet<HotlayoutReport>()
    private val count = mutableIntStateOf(0)

    /** How many reports there are: a snapshot state, so a screen can hand the host a report that comes after the first frame (a picture that failed). */
    internal val changes: Int get() = count.intValue

    internal fun add(kind: HotlayoutReport.Kind, location: String, reason: String) {
        synchronized(this) {
            val report = HotlayoutReport(kind, location, reason)
            if (collected.size < LIMIT && seen.add(report)) {
                collected.add(report)
                count.intValue += 1
            }
        }
    }

    internal val reports: List<HotlayoutReport> get() = synchronized(this) { collected.toList() }

    internal companion object {
        internal const val LIMIT: Int = 500
    }
}
