package com.hotlayout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import com.hotlayout.actions.Flow
import com.hotlayout.actions.WriteActions
import com.hotlayout.actions.WritePress
import com.hotlayout.render.widgets.PressRun
import com.hotlayout.render.widgets.SwipeConfirmRules
import kotlinx.coroutines.Job
import com.hotlayout.actions.FlowDocument
import com.hotlayout.actions.FlowFrame
import com.hotlayout.actions.HotlayoutAction
import com.hotlayout.actions.ListInstance
import com.hotlayout.actions.ScreenShownCause
import com.hotlayout.actions.ScreenShownEvent
import com.hotlayout.actions.TrackEvent
import com.hotlayout.data.DataLayers
import com.hotlayout.data.DataScope
import com.hotlayout.data.RecordScope
import com.hotlayout.data.WritablePaths
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.HotlayoutReport
import com.hotlayout.model.ReportSink
import com.hotlayout.model.ScreenEntry
import com.hotlayout.model.ScreenPresentation
import com.hotlayout.render.HotlayoutFonts
import com.hotlayout.render.ImagePolicy
import com.hotlayout.render.NodeView
import com.hotlayout.render.layout.LocalScreenBodyHeight
import com.hotlayout.render.Placement
import com.hotlayout.render.RenderContext
import com.hotlayout.render.modal.ModalLayerSpec
import com.hotlayout.render.modal.ModalRules
import com.hotlayout.render.modal.ModalStack
import com.hotlayout.render.modal.SystemBackStep
import com.hotlayout.text.LocaleResolution
import com.hotlayout.text.Localization
import com.hotlayout.text.TextDirectionRule
import com.hotlayout.render.widgets.CarouselMemory
import com.hotlayout.theme.HotlayoutThemeSet
import com.hotlayout.theme.NodeStyle
import com.hotlayout.theme.StyleResolver
import java.util.Locale
import java.util.TimeZone

/** Where a press came from: the node, the screen, and the record the node reads. */
public data class ActionSource(val nodeId: String, val screenId: String, val record: DataScope?)

/**
 * Draws a HotLayout document: the screen the flow is on, with the stack of screens the reader
 * navigates itself (`navigate`, `navigate.back`, `navigate.section`).
 *
 * The reader opens nothing by itself. Every other action — `openUrl`, `deeplink`, `close` and the
 * actions of the app — goes to [onAction], already checked: a standard action carries `standard` or
 * `invalid`. [com.hotlayout.actions.runStandardAction] helps a host that wants to open it.
 *
 * The stack is bound to the document's `documentId` and the [entry], not to the document object: a
 * host that reads the JSON again on every composition stays on the screen it is on.
 *
 * @param document a document from [com.hotlayout.model.loadDocument].
 * @param theme the theme contract of the project; the built-in theme without one.
 * @param data the host's data, a flat map from a data path to a value.
 * @param locale the device locale as the platform gives it (`ar-EG`, `pt_BR`).
 * @param entry a named entry point of the document; an unknown name starts on the initial screen.
 * @param now «now» in epoch milliseconds for dates and counted days; the clock of the device without it.
 * @param fonts the families the theme names, from the host's resources; the system font otherwise.
 * @param onWrite a value the person entered or chose, already put into the session of the screen.
 * @param onHaptic the haptic feedback a press asks for; the reader never vibrates by itself.
 * @param onActionTask the host's answer to an action as a task: a swipe-to-confirm button stays busy until it ends.
 * @param writablePaths the data paths the reader may write to; by default every path of the form of a data path.
 * @param handleSystemBack whether the reader takes the system back key (button, gesture, predictive back) as one
 * step back of its own stack while the stack holds more than one screen and the top layer may be dismissed; with
 * `false`, or at the first screen, the key goes to the host. A `BackHandler` the host registers after the reader's wins.
 */
@Composable
public fun HotlayoutScreen(
    document: HotlayoutDocument,
    modifier: Modifier = Modifier,
    theme: HotlayoutThemeSet = HotlayoutThemeSet.builtIn,
    themeId: String? = null,
    data: DataScope = emptyMap(),
    locale: String? = Locale.getDefault().toLanguageTag(),
    entry: String? = null,
    timeZone: TimeZone? = null,
    now: Long? = null,
    images: ImagePolicy = ImagePolicy(),
    fonts: HotlayoutFonts = HotlayoutFonts.System,
    writablePaths: WritablePaths? = null,
    handleSystemBack: Boolean = true,
    onAction: ((HotlayoutAction, ActionSource) -> Unit)? = null,
    onTrack: ((TrackEvent, ActionSource) -> Unit)? = null,
    onWrite: ((String, JsonValue) -> Unit)? = null,
    onHaptic: ((String, ActionSource) -> Unit)? = null,
    onActionTask: ((HotlayoutAction, ActionSource) -> Job?)? = null,
    onScreenChange: ((ScreenShownEvent) -> Unit)? = null,
    onReport: ((List<HotlayoutReport>) -> Unit)? = null,
) {
    val sink = remember(document.documentId) { ReportSink() }
    var flow by remember(document.documentId) { mutableStateOf(Flow(FlowDocument.of(document), entry)) }
    var session by remember(document.documentId) { mutableStateOf<DataScope>(emptyMap()) }
    var shownSerial by remember(document.documentId) { mutableIntStateOf(0) }
    // Counts the moves the person made in the stack: only they move the focus (never the first screen or the host's entry).
    var movedSerial by remember(document.documentId) { mutableIntStateOf(0) }
    val callbacks = rememberUpdatedState(Callbacks(onAction, onTrack, onWrite, onHaptic, onActionTask, onScreenChange, onReport))

    LaunchedEffect(document.documentId) {
        flow.shownEvent(ScreenShownCause.Entry)?.let { callbacks.value.onScreenChange?.invoke(it) }
    }
    LaunchedEffect(document) {
        // A new version of the same document: the stack stays unless it holds a screen that is gone.
        val updated = flow.copy()
        val shown = updated.update(FlowDocument.of(document))
        if (updated != flow) flow = updated
        shown?.let { callbacks.value.onScreenChange?.invoke(it) }
        callbacks.value.onReport?.invoke(document.reports + sink.reports)
    }
    LaunchedEffect(entry) {
        if (entry == flow.entry) return@LaunchedEffect
        val updated = flow.copy()
        val shown = updated.restart(entry)
        flow = updated
        shown?.let {
            shownSerial += 1
            callbacks.value.onScreenChange?.invoke(it)
        }
    }
    LaunchedEffect(data) {
        // New data: every frame looks for its record again (`x-liveRecord`); an update of a record is not a show.
        val host = RecordScope.merge(DataLayers(session = session, data = data))
        val updated = flow.copy()
        if (updated.refresh { host[it] }) flow = updated
    }

    fun write(path: String, value: JsonValue) {
        if (!WritablePaths.permits(writablePaths, path)) {
            sink.add(HotlayoutReport.Kind.IgnoredInput, "write: ${path.take(64)}", "the path is not one the host lets the reader write to; nothing was written")
            return
        }
        session = session + (path to value)
        callbacks.value.onWrite?.invoke(path, value)
    }

    fun perform(action: JsonObject, node: DocumentNode, instances: List<ListInstance>): PressRun {
        val before = flow
        val host = RecordScope.merge(DataLayers(session = session, data = data))
        val handlers = callbacks.value
        val type = action["type"]?.string
        if (WriteActions.isWriteType(type)) {
            // The write actions are the reader's own, never the host's: the haptic request, the
            // analytics event, then the write itself (into the session and to the host at once).
            val record = before.nodeRecord(instances)
            val scope = RecordScope.overlay(host, record) ?: host
            val effects = WritePress.effects(action, { scope[it] }, sink, "node “${node.id}”: action")
            val source = ActionSource(node.id, before.top?.screenId ?: "", record)
            effects?.haptic?.let { handlers.onHaptic?.invoke(it, source) }
            effects?.track?.let { handlers.onTrack?.invoke(it, source) }
            (effects?.step as? WriteActions.Step.Write)?.let { write(it.path, it.value) }
            return PressRun(SwipeConfirmRules.Runner.Reader, null)
        }
        val updated = before.copy()
        val outcome = updated.press(action, instances, host, sink)
        val source = ActionSource(node.id, before.top?.screenId ?: "", outcome.hostRecord)
        outcome.haptic?.let { handlers.onHaptic?.invoke(it, source) }
        outcome.track?.let { handlers.onTrack?.invoke(it, source) }
        var task: Job? = null
        outcome.handed?.let {
            handlers.onAction?.invoke(it, source)
            task = handlers.onActionTask?.invoke(it, source)
        }
        if (updated != before) flow = updated
        outcome.shown?.let {
            shownSerial += 1
            movedSerial += 1
            handlers.onScreenChange?.invoke(it)
        }
        return PressRun(SwipeConfirmRules.runner(action, host), task)
    }

    val resolution = Localization.resolve(document.localization, locale)
    val rightToLeft = TextDirectionRule.isRightToLeft(resolution.directionLocale) ?: false
    val frames = flow.stack
    val pageIndex = frames.indices.lastOrNull { index -> index == 0 || document.screen(frames[index].screenId)?.presentation != ScreenPresentation.Modal } ?: 0
    val resolver = StyleResolver(theme.theme(themeId))
    val carousels = remember(document.documentId) { CarouselMemory() }
    SideEffect { carousels.prune(frames.map { it.screenId }) }
    val setup = ScreenSetup(document, resolver, resolution, rightToLeft, data, session, timeZone, (now ?: System.currentTimeMillis()).toDouble(), images, fonts, sink, ::perform, ::write, shownSerial, carousels, frames)

    val goBack = { perform(JsonObject.of("type" to "navigate.back"), DocumentNode("modal-dismiss", "Screen"), emptyList()); Unit }
    val topScreen = frames.lastOrNull()?.let { document.screen(it.screenId) }
    val topIsLayer = frames.size > 1 && topScreen?.presentation == ScreenPresentation.Modal
    SystemBackStep(enabled = handleSystemBack && frames.size > 1 && (!topIsLayer || topScreen?.dismissible != false), onBack = goBack)

    val layers = frames.mapIndexedNotNull { index, frame ->
        if (index <= pageIndex) return@mapIndexedNotNull null
        val screen = document.screen(frame.screenId) ?: return@mapIndexedNotNull null
        ModalLayerSpec(index, screen.screenId, screen.name, ModalRules.isWindow(screen.modalPlacement?.let(JsonValue::Str)), screen.backdropBlur, screen.dismissible) {
            ScreenRoot(screen, frame, setup, asLayer = true)
        }
    }
    val margin = resolver.spacing(JsonValue.Str("xl")) ?: ModalRules.DEFAULT_MARGIN
    CompositionLocalProvider(LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        ModalStack(
            page = {
                val page = frames.getOrNull(pageIndex)
                val pageScreen = page?.let { document.screen(it.screenId) }
                // A page shown again is a new show for its nodes (`animateIn`): one identity per place in the stack and screen.
                if (page != null && pageScreen != null) androidx.compose.runtime.key(pageIndex, pageScreen.screenId) { ScreenRoot(pageScreen, page, setup, asLayer = false) }
            },
            layers = layers,
            marginDp = margin,
            focusSerial = movedSerial,
            dismiss = goBack,
            modifier = modifier,
        )
    }
}

private data class Callbacks(
    val onAction: ((HotlayoutAction, ActionSource) -> Unit)?,
    val onTrack: ((TrackEvent, ActionSource) -> Unit)?,
    val onWrite: ((String, JsonValue) -> Unit)?,
    val onHaptic: ((String, ActionSource) -> Unit)?,
    val onActionTask: ((HotlayoutAction, ActionSource) -> Job?)?,
    val onScreenChange: ((ScreenShownEvent) -> Unit)?,
    val onReport: ((List<HotlayoutReport>) -> Unit)?,
)

/** What every screen of the stack is drawn with. */
private class ScreenSetup(
    val document: HotlayoutDocument,
    val resolver: StyleResolver,
    val resolution: LocaleResolution,
    val rightToLeft: Boolean,
    val data: DataScope,
    val session: DataScope,
    val timeZone: TimeZone?,
    val now: Double,
    val images: ImagePolicy,
    val fonts: HotlayoutFonts,
    val sink: ReportSink,
    val perform: (JsonObject, DocumentNode, List<ListInstance>) -> PressRun,
    val write: (String, JsonValue) -> Unit,
    val shownSerial: Int,
    val carousels: CarouselMemory? = null,
    val frames: List<FlowFrame> = emptyList(),
) {
    fun context(screen: ScreenEntry, frame: FlowFrame): RenderContext {
        val host = RecordScope.merge(DataLayers(session = session, data = data))
        return RenderContext(
            resolver = resolver,
            screenId = screen.screenId,
            localization = document.localization,
            locale = resolution,
            scope = RecordScope.overlay(host, frame.record) ?: host,
            timeZone = timeZone,
            now = now,
            rightToLeft = rightToLeft,
            inherited = NodeStyle.root(resolver),
            sink = sink,
            images = images,
            fonts = fonts,
            pressHandler = { action, node, instances -> perform(action, node, instances) },
            write = write,
            screenRoot = screen.root,
            screenScope = RecordScope.overlay(host, frame.record) ?: host,
            shownSerial = shownSerial,
            pressRun = perform,
            carousels = carousels,
            frameIndex = frames.indexOfFirst { it === frame }.coerceAtLeast(0),
        )
    }
}

/**
 * A page: the root of the screen fills the area and its content scrolls.
 *
 * Wave «screen anatomy» replaces this with the zones of the contract (a fixed app bar and bottom
 * zone, only the body scrolls, safe insets).
 */
@Composable
private fun ScreenRoot(screen: ScreenEntry, frame: FlowFrame, setup: ScreenSetup, asLayer: Boolean) {
    val context = setup.context(screen, frame)
    if (asLayer) {
        Column(Modifier.fillMaxWidth().testTag("screen:${screen.screenId}").verticalScroll(rememberScrollState())) {
            NodeView(screen.root, context, Placement.Stretched)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("screen:${screen.screenId}")) {
        val area = maxHeight
        // The visible body is what `justify` of the screen distributes (a layer as tall as its content has none).
        CompositionLocalProvider(LocalScreenBodyHeight provides area) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                NodeView(screen.root, context, Placement.Stretched, modifier = Modifier.heightIn(min = area))
            }
        }
    }
}
