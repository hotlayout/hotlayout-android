package com.hotlayout.model

import com.hotlayout.HotlayoutReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Collects reports while a document or a screen is read. Thread safe and bounded: a hostile
 * document could produce a report per node per frame.
 *
 * Adding a report writes no snapshot state, so a widget may report while it is composed; the
 * screen hands the reports to the host from an effect that watches [changes].
 */
internal class ReportSink {
    private val collected = ArrayList<HotlayoutReport>()
    private val seen = HashSet<HotlayoutReport>()
    private val count = MutableStateFlow(0)

    /** How many reports there are; a report that comes after the first frame (a picture that failed) changes it too. */
    internal val changes: StateFlow<Int> = count.asStateFlow()

    internal fun add(kind: HotlayoutReport.Kind, location: String, reason: String) {
        synchronized(this) {
            val report = HotlayoutReport(kind, location, reason)
            if (collected.size < LIMIT && seen.add(report)) {
                collected.add(report)
                count.value = collected.size
            }
        }
    }

    internal val reports: List<HotlayoutReport> get() = synchronized(this) { collected.toList() }

    internal companion object {
        internal const val LIMIT: Int = 500
    }
}
