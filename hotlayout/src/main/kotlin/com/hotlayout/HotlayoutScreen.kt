package com.hotlayout

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.hotlayout.actions.ActionCatalog
import com.hotlayout.actions.Flow
import com.hotlayout.actions.FlowDocument
import com.hotlayout.actions.FlowFrame
import com.hotlayout.actions.InputOrder
import com.hotlayout.actions.ListInstance
import com.hotlayout.actions.PressOutcome
import com.hotlayout.actions.WriteActions
import com.hotlayout.actions.WritePress
import com.hotlayout.data.DataLayers
import com.hotlayout.data.RecordScope
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.ReportSink
import com.hotlayout.model.ScreenEntry
import com.hotlayout.model.WidthClass
import com.hotlayout.model.inWidthClass
import com.hotlayout.render.RenderContext
import com.hotlayout.render.adaptive.ConfigChangeState
import com.hotlayout.render.adaptive.WideChrome
import com.hotlayout.render.modal.ModalLayerSpec
import com.hotlayout.render.modal.ModalRules
import com.hotlayout.render.modal.ModalStack
import com.hotlayout.render.modal.PageTransition
import com.hotlayout.render.modal.SystemBackStep
import com.hotlayout.render.screen.AnatomyScreen
import com.hotlayout.render.screen.LocalSafeAreaOverride
import com.hotlayout.render.screen.SafeAreaOverride
import com.hotlayout.render.screen.ScreenInsets
import com.hotlayout.render.widgets.CarouselMemory
import com.hotlayout.render.widgets.InputFocusRegistries
import com.hotlayout.render.widgets.InputForm
import com.hotlayout.render.widgets.ListBudget
import com.hotlayout.render.widgets.PressRun
import com.hotlayout.render.widgets.SwipeConfirmRules
import com.hotlayout.text.Localization
import com.hotlayout.text.TextDirectionRule
import com.hotlayout.theme.NodeStyle
import com.hotlayout.theme.StyleResolver
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Job

/** Where a press came from: the node, the screen, and the record the node reads. */
public data class ActionSource(val nodeId: String, val screenId: String, val record: DataScope?)

/**
 * Draws a HotLayout document: the screen the flow is on, with the stack of screens the reader
 * navigates itself (`navigate`, `navigate.back`, `navigate.section`).
 *
 * The reader opens nothing by itself. Every other action — `openUrl`, `deeplink`, `close` and the
 * actions of the app — goes to [onAction], already checked: a standard action carries `standard` or
 * `invalid`. [runStandardAction] helps a host that wants to open it.
 *
 * The stack is bound to the document's `documentId` and the [entry], not to the document object: a
 * host that reads the JSON again on every composition stays on the screen it is on.
 *
 * @param document a document from [loadDocument].
 * @param modifier the box the screen is drawn in; the width class of the screen comes from this box, not from the window.
 * @param theme the theme contract of the project; the built-in theme without one.
 * @param themeId the theme of the set to draw with; the set's default theme without it.
 * @param data the host's data, a flat map from a data path to a value.
 * @param locale the device locale as the platform gives it (`ar-EG`, `pt_BR`).
 * @param entry a named entry point of the document; an unknown name starts on the initial screen.
 * @param timeZone the zone dates are written in; the zone of the device without it.
 * @param now «now» in epoch milliseconds for dates and counted days; the clock of the device without it. A host that gives
 * it owns the clock: a time left shown by the document does not count down on its own then.
 * @param images which picture addresses the reader may load and how much of one (see [ImagePolicy]).
 * @param fonts the families the theme names, from the host's resources; the system font otherwise.
 * @param writablePaths the data paths the reader may write to; by default every path of the form of a data path.
 * @param autofill whether the text fields hand the platform hints for autofill (email, phone, address); `false` hands none.
 * @param handleSystemBack whether the reader takes the system back key (button, gesture, predictive back) as one
 * step back of its own stack while the stack holds more than one screen and the top layer may be dismissed; with
 * `false`, or at the first screen, the key goes to the host. A `BackHandler` the host registers after the reader's wins.
 * @param labels the texts the reader says to assistive technology, in the language of your app.
 * @param onAction an action the reader does not perform itself (`openUrl`, `deeplink`, `close`, the actions of your app), already checked.
 * @param onTrack the analytics event of a press (`track`); sent once per press, before the action.
 * @param onWrite a value the person entered or chose, already put into the session of the screen.
 * @param onHaptic the haptic feedback a press asks for; the reader never vibrates by itself.
 * @param onActionTask the host's answer to an action as a task: a swipe-to-confirm button stays busy until it ends.
 * @param onNavigate called before the reader changes screens by itself (`navigate`, `navigate.back`, `navigate.section`); return `false` to cancel: the reader then does nothing and navigation is the host's. Called after the haptic request and the analytics event of the same press, which are sent either way.
 * @param onScreenChange the top of the stack changed (the screen was shown, with the cause).
 * @param onReport what the reader skipped or replaced while reading the document, the theme or the data; never shown to the person.
 * @param safeAreaTop the top inset the host has already accounted for, instead of the window's (status bar, cutout); zero for a host that did.
 * @param safeAreaBottom the same for the bottom (navigation bar); the reader adds it only under a bottom zone.
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
    autofill: Boolean = true,
    handleSystemBack: Boolean = true,
    labels: HotlayoutLabels = HotlayoutLabels(),
    onAction: ((HotlayoutAction, ActionSource) -> Unit)? = null,
    onTrack: ((TrackEvent, ActionSource) -> Unit)? = null,
    onWrite: ((String, JsonValue) -> Unit)? = null,
    onHaptic: ((String, ActionSource) -> Unit)? = null,
    onActionTask: ((HotlayoutAction, ActionSource) -> Job?)? = null,
    onNavigate: ((NavigateRequest) -> Boolean)? = null,
    onScreenChange: ((ScreenShownEvent) -> Unit)? = null,
    onReport: ((List<HotlayoutReport>) -> Unit)? = null,
    safeAreaTop: Dp? = null,
    safeAreaBottom: Dp? = null,
) {
    val host = rememberFlowHost(document, entry, data, writablePaths, Callbacks(onAction, onTrack, onWrite, onHaptic, onActionTask, onNavigate, onScreenChange, onReport))
    FlowEffects(host, document, entry, data)

    val resolution = Localization.resolve(document.localization, locale)
    val rightToLeft = TextDirectionRule.isRightToLeft(resolution.directionLocale) ?: false
    val resolver = StyleResolver(theme.theme(themeId))
    val carousels = host.carousels
    val frames = host.flow.stack
    SideEffect { carousels.prune(frames.map { it.screenId }) }
    // One class for the whole screen, from the box the host gave it (not the window, not the device): a page takes the class
    // of that box, a modal layer is always compact. The box changes with a rotation or a resize, and the tree is resolved again.
    BoxWithConstraints(modifier) {
        val areaWidth = maxWidth.value.toDouble()
        val setup = ScreenSetup(
            document, resolver, resolution, rightToLeft, data, host.session, timeZone, (now ?: System.currentTimeMillis()).toDouble(), images, fonts,
            host.sink, host::perform, host::write, host.shownSerial, host.focusRegistries, autofill, carousels, frames, WidthClass.of(areaWidth), labels.back,
            clockPinned = now != null,
        )
        CompositionLocalProvider(
            LocalLayoutDirection provides if (rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalSafeAreaOverride provides SafeAreaOverride(safeAreaTop, safeAreaBottom),
        ) {
            ScreenStack(host, setup, areaWidth, handleSystemBack)
        }
    }
}

/** The state of the flow of one document: the stack, the session, the counters and the three ways to change them. */
@Stable
private class FlowHost(
    val sink: ReportSink,
    val focusRegistries: InputFocusRegistries,
    val carousels: CarouselMemory,
    flowState: MutableState<Flow>,
    sessionState: MutableState<DataScope>,
    announcedState: MutableState<Boolean>,
    private val callbacksState: State<Callbacks>,
    private val dataState: State<DataScope>,
    private val writableState: State<WritablePaths?>,
) {
    var flow: Flow by flowState
    var session: DataScope by sessionState
    var announced: Boolean by announcedState
    var shownSerial: Int by mutableIntStateOf(0)

    /** Counts the moves the person made in the stack: only they move the focus (never the first screen or the host's entry). */
    var movedSerial: Int by mutableIntStateOf(0)

    /** Why the stack last changed: it only picks the motion between two pages, nothing else reads it. */
    var lastShownCause: ScreenShownCause? by mutableStateOf(null)

    val callbacks: Callbacks get() = callbacksState.value

    fun hostScope(): DataScope = RecordScope.merge(DataLayers(session = session, data = dataState.value))

    fun write(path: String, value: JsonValue) {
        if (!WritablePaths.permits(writableState.value, path)) {
            sink.add(HotlayoutReport.Kind.IgnoredInput, "write: ${path.take(64)}", "the path is not one the host lets the reader write to; nothing was written")
            return
        }
        session = session + (path to value)
        callbacks.onWrite?.invoke(path, value)
    }

    fun perform(action: JsonObject, node: DocumentNode, instances: List<ListInstance>): PressRun =
        if (WriteActions.isWriteType(action["type"]?.string)) performWrite(action, node, instances) else performFlow(action, node, instances)

    /**
     * The write actions are the reader's own, never the host's: the haptic request, the
     * analytics event, then the write itself (into the session and to the host at once).
     */
    private fun performWrite(action: JsonObject, node: DocumentNode, instances: List<ListInstance>): PressRun {
        val host = hostScope()
        val record = flow.nodeRecord(instances)
        val scope = RecordScope.overlay(host, record) ?: host
        val effects = WritePress.effects(action, { scope[it] }, sink, "node “${node.id}”: action")
        val source = ActionSource(node.id, flow.top?.screenId ?: "", record)
        effects?.haptic?.let { callbacks.onHaptic?.invoke(it, source) }
        effects?.track?.let { callbacks.onTrack?.invoke(it, source) }
        (effects?.step as? WriteActions.Step.Write)?.let { write(it.path, it.value) }
        return PressRun(SwipeConfirmRules.Runner.Reader, null)
    }

    private fun performFlow(action: JsonObject, node: DocumentNode, instances: List<ListInstance>): PressRun {
        val before = flow
        val host = hostScope()
        val handlers = callbacks
        val updated = before.copy()
        val navigationSource = ActionSource(node.id, before.top?.screenId ?: "", before.nodeRecord(instances))
        // The haptic request and the event come first, also when the host then cancels the navigation.
        var announced = false
        fun announce(outcome: PressOutcome) {
            if (announced) return
            announced = true
            outcome.haptic?.let { handlers.onHaptic?.invoke(it, navigationSource) }
            outcome.track?.let { handlers.onTrack?.invoke(it, navigationSource) }
        }
        val outcome = updated.press(action, instances, host, sink) { pending, kind, from, to ->
            announce(pending)
            val handed = ActionCatalog.handed(action, RecordScope.overlay(host, navigationSource.record) ?: host)
            handlers.onNavigate?.invoke(NavigateRequest(kind, from.screenId, to, handed, navigationSource)) != false
        }
        val source = ActionSource(node.id, before.top?.screenId ?: "", outcome.hostRecord)
        if (!announced) {
            announced = true
            outcome.haptic?.let { handlers.onHaptic?.invoke(it, source) }
            outcome.track?.let { handlers.onTrack?.invoke(it, source) }
        }
        var task: Job? = null
        outcome.handed?.let {
            handlers.onAction?.invoke(it, source)
            task = handlers.onActionTask?.invoke(it, source)
        }
        if (updated != before) flow = updated
        outcome.shown?.let {
            shownSerial += 1
            movedSerial += 1
            lastShownCause = it.cause
            handlers.onScreenChange?.invoke(it)
        }
        return PressRun(SwipeConfirmRules.runner(action, host), task)
    }
}

@Composable
private fun rememberFlowHost(
    document: HotlayoutDocument,
    entry: String?,
    data: DataScope,
    writablePaths: WritablePaths?,
    callbacks: Callbacks,
): FlowHost {
    val id = document.documentId
    // The stack and the entered values survive the recreation of the host's activity (a rotation, a fold): saved as JSON.
    val flowSaver = remember(id, entry) { ConfigChangeState.flowSaver(FlowDocument.of(document), entry) }
    val flow = rememberSaveable(id, stateSaver = flowSaver) { mutableStateOf(Flow(FlowDocument.of(document), entry)) }
    val session = rememberSaveable(id, stateSaver = ConfigChangeState.sessionSaver()) { mutableStateOf<DataScope>(emptyMap()) }
    val announced = rememberSaveable(id) { mutableStateOf(false) }
    val callbacksState = rememberUpdatedState(callbacks)
    val dataState = rememberUpdatedState(data)
    val writableState = rememberUpdatedState(writablePaths)
    return remember(id) {
        FlowHost(ReportSink(), InputFocusRegistries(), CarouselMemory(), flow, session, announced, callbacksState, dataState, writableState)
    }
}

/** What reaches the host when the document, the entry or the data change under a running flow. */
@Composable
private fun FlowEffects(host: FlowHost, document: HotlayoutDocument, entry: String?, data: DataScope) {
    LaunchedEffect(document.documentId) {
        // After the activity was recreated the screen was shown already: the host is not told again.
        if (host.announced) return@LaunchedEffect
        host.announced = true
        host.flow.shownEvent(ScreenShownCause.Entry)?.let { host.callbacks.onScreenChange?.invoke(it) }
    }
    LaunchedEffect(document) {
        // A new version of the same document: the stack stays unless it holds a screen that is gone.
        val updated = host.flow.copy()
        val shown = updated.update(FlowDocument.of(document))
        if (updated != host.flow) host.flow = updated
        shown?.let {
            host.lastShownCause = it.cause
            host.callbacks.onScreenChange?.invoke(it)
        }
        host.callbacks.onReport?.invoke(document.reports + host.sink.reports)
    }
    // A report that comes after the first frame (a refused picture address, a list over its budget, a picture that failed) reaches the host too.
    LaunchedEffect(host, document) {
        snapshotFlow { host.sink.changes }.drop(1).collect { host.callbacks.onReport?.invoke(document.reports + host.sink.reports) }
    }
    LaunchedEffect(entry) {
        if (entry == host.flow.entry) return@LaunchedEffect
        val updated = host.flow.copy()
        val shown = updated.restart(entry)
        host.flow = updated
        shown?.let {
            host.shownSerial += 1
            host.lastShownCause = it.cause
            host.callbacks.onScreenChange?.invoke(it)
        }
    }
    LaunchedEffect(data) {
        // New data: every frame looks for its record again (`x-liveRecord`); an update of a record is not a show.
        val scope = RecordScope.merge(DataLayers(session = host.session, data = data))
        val updated = host.flow.copy()
        if (updated.refresh { scope[it] }) host.flow = updated
    }
}

/** The page the stack is on and the modal layers over it, with the system back key. */
@Composable
private fun ScreenStack(host: FlowHost, setup: ScreenSetup, areaWidth: Double, handleSystemBack: Boolean) {
    val document = setup.document
    val frames = setup.frames
    val pageIndex = frames.indices.lastOrNull { index -> index == 0 || document.screen(frames[index].screenId)?.presentation != ScreenPresentation.Modal } ?: 0
    val goBack = { host.perform(JsonObject.of("type" to "navigate.back"), DocumentNode("modal-dismiss", "Screen"), emptyList()); Unit }
    val topScreen = frames.lastOrNull()?.let { document.screen(it.screenId) }
    val topIsLayer = frames.size > 1 && topScreen?.presentation == ScreenPresentation.Modal
    SystemBackStep(enabled = handleSystemBack && frames.size > 1 && (!topIsLayer || topScreen?.dismissible != false), onBack = goBack)

    val layers = frames.mapIndexedNotNull { index, frame ->
        if (index <= pageIndex) return@mapIndexedNotNull null
        val screen = document.screen(frame.screenId) ?: return@mapIndexedNotNull null
        val shape = WideChrome.modalShape(screen.modalPlacement?.let(JsonValue::Str), areaWidth)
        ModalLayerSpec(index, screen.screenId, screen.name, shape != WideChrome.ModalShape.Sheet, shape == WideChrome.ModalShape.SheetWindow, screen.backdropBlur, screen.dismissible) {
            ScreenRoot(screen, frame, setup, asLayer = true, sheetWindow = shape == WideChrome.ModalShape.SheetWindow)
        }
    }
    ModalStack(
        page = {
            val page = frames.getOrNull(pageIndex)
            val pageScreen = page?.let { document.screen(it.screenId) }
            // A page shown again is a new show for its nodes (`animateIn`): one identity per place in the stack and screen.
            if (page != null && pageScreen != null) {
                PageTransition(pageIndex to pageScreen.screenId, host.lastShownCause) { ScreenRoot(pageScreen, page, setup, asLayer = false) }
            }
        },
        layers = layers,
        marginDp = setup.resolver.spacing(JsonValue.Str("xl")) ?: ModalRules.DEFAULT_MARGIN,
        focusSerial = host.movedSerial,
        dismiss = goBack,
        modifier = Modifier,
    )
}

private data class Callbacks(
    val onAction: ((HotlayoutAction, ActionSource) -> Unit)?,
    val onTrack: ((TrackEvent, ActionSource) -> Unit)?,
    val onWrite: ((String, JsonValue) -> Unit)?,
    val onHaptic: ((String, ActionSource) -> Unit)?,
    val onActionTask: ((HotlayoutAction, ActionSource) -> Job?)?,
    val onNavigate: ((NavigateRequest) -> Boolean)?,
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
    val focusRegistries: InputFocusRegistries,
    val autofill: Boolean,
    val carousels: CarouselMemory? = null,
    val frames: List<FlowFrame> = emptyList(),
    /** The width class of the box the host gave the screen. */
    val areaClass: WidthClass = WidthClass.Compact,
    /** The accessible name of the back arrow of an app bar. */
    val backLabel: String = "Back",
    val clockPinned: Boolean = false,
) {
    fun context(screen: ScreenEntry, frame: FlowFrame, root: DocumentNode, widthClass: WidthClass): RenderContext {
        val host = RecordScope.merge(DataLayers(session = session, data = data))
        val scope = RecordScope.overlay(host, frame.record) ?: host
        // One plan per screen, made before anything is drawn: a list cannot know what the lists before it drew.
        val budget = ListBudget.plan(root, scope)
        if (budget.notDrawn > 0) {
            val count = if (budget.notDrawn >= ListBudget.COUNT_CEILING) "at least ${budget.notDrawn}" else "${budget.notDrawn}"
            sink.add(
                HotlayoutReport.Kind.IgnoredInput, "screen “${screen.screenId}”",
                "the list budget of ${ListBudget.PER_SCREEN} instances per screen was reached: $count instances of list templates were not drawn",
            )
        }
        return RenderContext(
            resolver = resolver,
            screenId = screen.screenId,
            localization = document.localization,
            locale = resolution,
            scope = scope,
            timeZone = timeZone,
            now = now,
            clockPinned = clockPinned,
            rightToLeft = rightToLeft,
            inherited = NodeStyle.root(resolver),
            sink = sink,
            images = images,
            fonts = fonts,
            pressRun = perform,
            write = write,
            screenRoot = root,
            screenScope = scope,
            shownSerial = shownSerial,
            backLabel = backLabel,
            form = InputForm(InputOrder.fields(root, scope, widthClass), focusRegistries.of(screen.screenId), autofill),
            carousels = carousels,
            listBudget = budget,
            frameIndex = frames.indexOfFirst { it === frame }.coerceAtLeast(0),
            widthClass = widthClass,
        )
    }
}

/**
 * A page: the screen with the zones of the contract (`render/screen/`). A modal layer is as tall as
 * its content and takes no platform insets of its own.
 */
@Composable
private fun ScreenRoot(screen: ScreenEntry, frame: FlowFrame, setup: ScreenSetup, asLayer: Boolean, sheetWindow: Boolean = false) {
    // The reader resolves the tree for the class and then draws a plain document: no widget knows the word «class».
    val widthClass = WideChrome.contentWidthClass(screen.presentation, setup.areaClass)
    val root = remember(screen.root, widthClass) { screen.root.inWidthClass(widthClass) }
    AnatomyScreen(
        root, setup.context(screen, frame, root, widthClass), Modifier.testTag("screen:${screen.screenId}"),
        shrinks = asLayer, insets = if (asLayer) ScreenInsets() else null, sheetWindow = sheetWindow,
    )
}
