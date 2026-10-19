package com.hotlayout.actions

import com.hotlayout.data.DataScope
import com.hotlayout.data.EntitySources
import com.hotlayout.data.LiveRecord
import com.hotlayout.data.RecordLink
import com.hotlayout.data.RecordScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.ReportSink
import com.hotlayout.model.ScreenPresentation

/** Why a screen was shown (`x-screenShown`). */
public enum class ScreenShownCause(public val word: String) { Entry("entry"), Forward("forward"), Back("back"), Switch("switch") }

/** The screen-shown event: once per change of the top of the stack. */
public data class ScreenShownEvent(
    val documentId: String,
    val screenId: String,
    /** The named entry point the flow started from; `null` when none was named or it is unknown. */
    val entry: String?,
    val presentation: ScreenPresentation,
    val cause: ScreenShownCause,
    val depth: Int,
    /** The record the screen was opened with. */
    val record: DataScope?,
    /** `bottom` / `center`, only for a modal screen that names it. */
    val modalPlacement: String?,
    /** The request of the shown screen about the status bar icons, only when the screen names one. */
    val statusBarIcons: String?,
)

/** One frame of the screen stack: a screen and the record it was opened with, as a chain of links. */
public data class FlowFrame(val screenId: String, val chain: List<RecordLink> = emptyList()) {
    /** The record of the frame; `null` for a screen opened without one. */
    val record: DataScope? get() = if (chain.isEmpty()) null else LiveRecord.record(chain)
}

/** The screens of a document as navigation sees them. */
public data class FlowDocument(
    val documentId: String,
    val screens: List<Screen>,
    val initialScreenId: String?,
    val entryPoints: Map<String, String>,
    /** The collections of each entity the document names (`x-liveRecord.search.entity`). */
    val entitySources: EntitySources = emptyMap(),
) {
    public data class Screen(
        val screenId: String,
        val presentation: ScreenPresentation,
        val modalPlacement: String? = null,
        val statusBarIcons: String? = null,
    )

    internal fun screen(screenId: String): Screen? = screens.firstOrNull { it.screenId == screenId }

    /** The first screen: the named entry point, else the initial screen, else the first one. */
    internal fun start(entry: String?): String? {
        entry?.let { entryPoints[it] }?.takeIf { screen(it) != null }?.let { return it }
        initialScreenId?.takeIf { screen(it) != null }?.let { return it }
        return screens.firstOrNull()?.screenId
    }

    public companion object {
        public fun of(document: HotlayoutDocument): FlowDocument = FlowDocument(
            documentId = document.documentId,
            screens = document.screens.map { Screen(it.screenId, it.presentation, it.modalPlacement, it.statusBarIcons) },
            initialScreenId = document.initialScreenId,
            entryPoints = document.entryPoints,
            entitySources = LiveRecord.entitySources(document.screens.map { it.root }),
        )
    }
}

/** A Repeater instance a pressed node is drawn inside: the entity prefix and one record. */
public data class ListInstance(
    val item: String,
    val fields: JsonObject,
    /** The collection the list read; empty when unknown (such a record is not found again in new data). */
    val source: String = "",
)

/** What a press did. Order of delivery: the haptic request, `track`, then either `handed` or `shown`. */
public data class PressOutcome(
    var track: TrackEvent? = null,
    var haptic: String? = null,
    var handed: HotlayoutAction? = null,
    /** The record handed to the host together with the action. */
    var hostRecord: DataScope? = null,
    var shown: ScreenShownEvent? = null,
)

/**
 * The screen stack and how actions move it. A value: the screen keeps it in state, and the tables
 * drive it without any view.
 *
 * Rules: `navigate` pushes a screen of the document (an unknown one is the host's); `navigate.back`
 * pops, and on the first screen it is the host's; `navigate.section` resets the stack to the
 * section screen (the same single screen: nothing, no event); every other action goes to the host.
 * Navigation never happens when the host cancels it.
 */
public class Flow private constructor(
    document: FlowDocument,
    entry: String?,
    stack: List<FlowFrame>,
) {
    public var document: FlowDocument = document
        private set
    public var entry: String? = entry
        private set
    public var stack: List<FlowFrame> = stack
        private set

    /** Starts a flow on its first screen. */
    public constructor(document: FlowDocument, entry: String?) : this(document, entry, emptyList()) {
        restart(entry)
    }

    public fun copy(): Flow = Flow(document, entry, stack)

    /** The same flow with another stack (restored after a configuration change); the caller checks the screens exist. */
    internal fun withStack(stack: List<FlowFrame>): Flow = Flow(document, entry, stack)

    override fun equals(other: Any?): Boolean = other is Flow && other.document == document && other.entry == entry && other.stack == stack

    override fun hashCode(): Int = stack.hashCode()

    /** The named entry, as the event reports it: only a name the document knows. */
    internal val shownEntry: String? get() = entry?.takeIf { document.entryPoints.containsKey(it) }

    public val top: FlowFrame? get() = stack.lastOrNull()

    /** The event of the current top screen. */
    public fun shownEvent(cause: ScreenShownCause): ScreenShownEvent? {
        val top = stack.lastOrNull() ?: return null
        val screen = document.screen(top.screenId)
        // The first shown screen is never modal: a malformed document is drawn as a page.
        val modal = stack.size > 1 && screen?.presentation == ScreenPresentation.Modal
        return ScreenShownEvent(
            documentId = document.documentId,
            screenId = top.screenId,
            entry = shownEntry,
            presentation = if (modal) ScreenPresentation.Modal else ScreenPresentation.Page,
            cause = cause,
            depth = stack.size,
            record = top.record,
            modalPlacement = if (modal) screen?.modalPlacement else null,
            statusBarIcons = screen?.statusBarIcons,
        )
    }

    /** The host starts the flow again, with another entry. */
    public fun restart(entry: String?): ScreenShownEvent? {
        this.entry = entry
        val first = document.start(entry)
        if (first == null) {
            stack = emptyList()
            return null
        }
        stack = listOf(FlowFrame(first))
        return shownEvent(ScreenShownCause.Entry)
    }

    /**
     * The host renders a new version of the same document. If the stack holds a screen the new
     * version lacks, the flow starts again from the entry (`stackScreenGone`).
     */
    public fun update(newDocument: FlowDocument): ScreenShownEvent? {
        document = newDocument
        if (stack.all { newDocument.screen(it.screenId) != null }) return null
        return restart(entry)
    }

    /** The record scope of a node on the top screen: the screen's record, overlaid by its list instances. */
    public fun nodeRecord(instances: List<ListInstance>): DataScope? {
        val chain = nodeChain(instances)
        return if (chain.isEmpty()) null else LiveRecord.record(chain)
    }

    internal fun nodeChain(instances: List<ListInstance>): List<RecordLink> = LiveRecord.chain(stack.lastOrNull()?.chain.orEmpty(), instances)

    /**
     * The host gave new collections: every frame searches for its links again. The stack, the
     * events and the show cause do not change — an update of the record is not a show. Returns
     * whether any frame changed.
     */
    public fun refresh(collections: (String) -> JsonValue?): Boolean {
        val next = stack.map { FlowFrame(it.screenId, LiveRecord.refresh(it.chain, collections, document.entitySources)) }
        if (next == stack) return false
        stack = next
        return true
    }

    /**
     * A press of a node with `action` on the top screen.
     *
     * @param data the host's data layers under the record (session, host, data — merged).
     * @param allowNavigation the host's answer to «may the reader navigate» (`false` cancels).
     */
    public fun press(
        action: JsonObject,
        instances: List<ListInstance> = emptyList(),
        data: DataScope = emptyMap(),
        sink: ReportSink? = null,
        allowNavigation: (FlowFrame, String) -> Boolean = { _, _ -> true },
    ): PressOutcome {
        val outcome = PressOutcome()
        outcome.haptic = ActionCatalog.haptic(action)
        outcome.track = ActionCatalog.track(action, sink)
        val chain = nodeChain(instances)
        val record = if (chain.isEmpty()) null else LiveRecord.record(chain)
        val scope = RecordScope.overlay(data, record) ?: data
        val handed = ActionCatalog.handed(action, scope, sink)
        val target = handed.params?.get("screenId")

        fun toHost(): PressOutcome {
            outcome.handed = handed
            outcome.hostRecord = record
            return outcome
        }

        when (handed.type) {
            "none" -> return outcome
            "navigate" -> {
                val screen = target?.let(document::screen) ?: return toHost()
                if (!allowNavigation(stack.last(), target)) return outcome
                // A list row, a modal layer and an explicit `carryRecord` hand the record over.
                val carried = instances.isNotEmpty() || screen.presentation == ScreenPresentation.Modal || action["carryRecord"]?.bool == true
                stack = stack + FlowFrame(target, if (carried) chain else emptyList())
                outcome.shown = shownEvent(ScreenShownCause.Forward)
            }
            "navigate.back" -> {
                if (stack.size <= 1) return toHost()
                if (!allowNavigation(stack.last(), stack[stack.size - 2].screenId)) return outcome
                stack = stack.dropLast(1)
                outcome.shown = shownEvent(ScreenShownCause.Back)
            }
            "navigate.section" -> {
                if (target == null || document.screen(target) == null) return toHost()
                if (stack.size == 1 && stack[0].screenId == target) return outcome
                if (!allowNavigation(stack.last(), target)) return outcome
                stack = listOf(FlowFrame(target))
                outcome.shown = shownEvent(ScreenShownCause.Switch)
            }
            else -> return toHost()
        }
        return outcome
    }
}
