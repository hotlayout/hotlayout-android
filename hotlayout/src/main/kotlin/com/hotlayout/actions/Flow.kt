package com.hotlayout.actions

import androidx.compose.runtime.Immutable
import com.hotlayout.DataScope
import com.hotlayout.HotlayoutAction
import com.hotlayout.HotlayoutDocument
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.NavigateKind
import com.hotlayout.ScreenPresentation
import com.hotlayout.ScreenShownCause
import com.hotlayout.ScreenShownEvent
import com.hotlayout.TrackEvent
import com.hotlayout.data.EntitySources
import com.hotlayout.data.LiveRecord
import com.hotlayout.data.RecordLink
import com.hotlayout.data.RecordScope
import com.hotlayout.model.ReportSink

/** One frame of the screen stack: a screen and the record it was opened with, as a chain of links. */
internal data class FlowFrame(val screenId: String, val chain: List<RecordLink> = emptyList()) {
    /** The record of the frame; `null` for a screen opened without one. */
    val record: DataScope? get() = if (chain.isEmpty()) null else LiveRecord.record(chain)
}

/** The screens of a document as navigation sees them. */
internal data class FlowDocument(
    val documentId: String,
    val screens: List<Screen>,
    val initialScreenId: String?,
    val entryPoints: Map<String, String>,
    /** The collections of each entity the document names (`x-liveRecord.search.entity`). */
    val entitySources: EntitySources = emptyMap(),
) {
    internal data class Screen(
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

    internal companion object {
        internal fun of(document: HotlayoutDocument): FlowDocument = FlowDocument(
            documentId = document.documentId,
            screens = document.screens.map { Screen(it.screenId, it.presentation, it.modalPlacement, it.statusBarIcons) },
            initialScreenId = document.initialScreenId,
            entryPoints = document.entryPoints,
            entitySources = LiveRecord.entitySources(document.screens.map { it.root }),
        )
    }
}

/** A Repeater instance a pressed node is drawn inside: the entity prefix and one record. */
internal data class ListInstance(
    val item: String,
    val fields: JsonObject,
    /** The collection the list read; empty when unknown (such a record is not found again in new data). */
    val source: String = "",
)

/** What a press did. Order of delivery: the haptic request, `track`, then either `handed` or `shown`. */
internal data class PressOutcome(
    val track: TrackEvent? = null,
    val haptic: String? = null,
    val handed: HotlayoutAction? = null,
    /** The record handed to the host together with the action. */
    val hostRecord: DataScope? = null,
    val shown: ScreenShownEvent? = null,
)

/**
 * The screen stack and how actions move it. An immutable value: every move returns the next flow,
 * so the screen keeps it in state as is and the tables drive it without any view.
 *
 * Rules: `navigate` pushes a screen of the document (an unknown one is the host's); `navigate.back`
 * pops, and on the first screen it is the host's; `navigate.section` resets the stack to the
 * section screen (the same single screen: nothing, no event); every other action goes to the host.
 * Navigation never happens when the host cancels it.
 */
@Immutable
internal class Flow private constructor(
    internal val document: FlowDocument,
    internal val entry: String?,
    internal val stack: List<FlowFrame>,
) {
    /** Starts a flow on its first screen. */
    constructor(document: FlowDocument, entry: String?) : this(document, entry, firstStack(document, entry))

    /** A flow after a move, and the screen the move showed (`null`: nothing new was shown). */
    internal data class Moved(val flow: Flow, val shown: ScreenShownEvent?)

    /** A flow after a press, and what the press did. */
    internal data class Pressed(val flow: Flow, val outcome: PressOutcome)

    /** The same flow with another stack (restored after a configuration change); the caller checks the screens exist. */
    internal fun withStack(stack: List<FlowFrame>): Flow = Flow(document, entry, stack)

    override fun equals(other: Any?): Boolean = other is Flow && other.document == document && other.entry == entry && other.stack == stack

    override fun hashCode(): Int = stack.hashCode()

    /** The named entry, as the event reports it: only a name the document knows. */
    internal val shownEntry: String? get() = entry?.takeIf { document.entryPoints.containsKey(it) }

    internal val top: FlowFrame? get() = stack.lastOrNull()

    /** The event of the current top screen. */
    internal fun shownEvent(cause: ScreenShownCause): ScreenShownEvent? {
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
    internal fun restart(entry: String?): Moved {
        val next = Flow(document, entry)
        return Moved(next, next.shownEvent(ScreenShownCause.Entry))
    }

    /**
     * The host renders a new version of the same document. If the stack holds a screen the new
     * version lacks, the flow starts again from the entry (`stackScreenGone`).
     */
    internal fun update(newDocument: FlowDocument): Moved {
        val next = Flow(newDocument, entry, stack)
        if (stack.all { newDocument.screen(it.screenId) != null }) return Moved(next, null)
        return next.restart(entry)
    }

    /** The record scope of a node on the top screen: the screen's record, overlaid by its list instances. */
    internal fun nodeRecord(instances: List<ListInstance>): DataScope? {
        val chain = nodeChain(instances)
        return if (chain.isEmpty()) null else LiveRecord.record(chain)
    }

    internal fun nodeChain(instances: List<ListInstance>): List<RecordLink> = LiveRecord.chain(stack.lastOrNull()?.chain.orEmpty(), instances)

    /**
     * The host gave new collections: every frame searches for its links again. The stack, the
     * events and the show cause do not change — an update of the record is not a show. Returns
     * this same flow when no frame changed.
     */
    internal fun refresh(collections: (String) -> JsonValue?): Flow {
        val next = stack.map { FlowFrame(it.screenId, LiveRecord.refresh(it.chain, collections, document.entitySources)) }
        return if (next == stack) this else Flow(document, entry, next)
    }

    /**
     * A press of a node with `action` on the top screen.
     *
     * @param data the host's data layers under the record (session, host, data — merged).
     * @param allowNavigation the host's answer to «may the reader navigate» (`false` cancels); it gets the outcome so far (the haptic request and the event are already set).
     */
    internal fun press(
        action: JsonObject,
        instances: List<ListInstance> = emptyList(),
        data: DataScope = emptyMap(),
        sink: ReportSink? = null,
        allowNavigation: (PressOutcome, NavigateKind, FlowFrame, String) -> Boolean = { _, _, _, _ -> true },
    ): Pressed {
        val outcome = PressOutcome(haptic = ActionCatalog.haptic(action), track = ActionCatalog.track(action, sink))
        val chain = nodeChain(instances)
        val record = if (chain.isEmpty()) null else LiveRecord.record(chain)
        val scope = RecordScope.overlay(data, record) ?: data
        val handed = ActionCatalog.handed(action, scope, sink)
        val target = handed.params?.get("screenId")
        val stays = Pressed(this, outcome)
        val toHost = Pressed(this, outcome.copy(handed = handed, hostRecord = record))

        fun moved(next: List<FlowFrame>, cause: ScreenShownCause): Pressed {
            val flow = Flow(document, entry, next)
            return Pressed(flow, outcome.copy(shown = flow.shownEvent(cause)))
        }

        return when (handed.type) {
            "none" -> stays
            "navigate" -> {
                val screen = target?.let(document::screen) ?: return toHost
                if (!allowNavigation(outcome, NavigateKind.Push, stack.last(), target)) return stays
                // A list row, a modal layer and an explicit `carryRecord` hand the record over.
                val carried = RecordScope.carries(instances.isNotEmpty(), screen.presentation, action["carryRecord"]?.bool == true)
                moved(stack + FlowFrame(target, if (carried) chain else emptyList()), ScreenShownCause.Forward)
            }
            "navigate.back" -> {
                if (stack.size <= 1) return toHost
                if (!allowNavigation(outcome, NavigateKind.Back, stack.last(), stack[stack.size - 2].screenId)) return stays
                moved(stack.dropLast(1), ScreenShownCause.Back)
            }
            "navigate.section" -> {
                if (target == null || document.screen(target) == null) return toHost
                if (stack.size == 1 && stack[0].screenId == target) return stays
                if (!allowNavigation(outcome, NavigateKind.Switch, stack.last(), target)) return stays
                moved(listOf(FlowFrame(target)), ScreenShownCause.Switch)
            }
            else -> toHost
        }
    }

    private companion object {
        fun firstStack(document: FlowDocument, entry: String?): List<FlowFrame> =
            document.start(entry)?.let { listOf(FlowFrame(it)) } ?: emptyList()
    }
}
