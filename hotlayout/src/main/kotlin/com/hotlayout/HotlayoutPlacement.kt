package com.hotlayout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hotlayout.placements.PlacementRules
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Draws the document that stands in the place [name] of the project's placement map, with [HotlayoutScreen],
 * and nothing when the place is empty, unknown, unreadable or its document cannot be had. A place that draws
 * nothing is no error: hide the frame around it when [onPlacement] says [PlacementLoad.document] is `null`.
 *
 * The map is asked again every [refreshIntervalMs] (with its `ETag`, so an unchanged map costs no body), and the
 * screen follows a change. A place may carry a schedule: windows of time in which another document (or nothing)
 * stands there. The place is read against the clock of [source] (the device clock, no network); a timer is set at the
 * next boundary of the schedule, and the place is decided again there, on each new reading of the map and when the
 * app returns to the foreground. The map is not asked again at a boundary. Everything else is the parameters of [HotlayoutScreen]; [entry] of the map is used,
 * not one of yours.
 *
 * @param name the name of the place, as the app writes it (`home.banner`).
 * @param source where the map and the documents come from.
 * @param refreshIntervalMs the pause between two asks of the map; `null` asks once. Not below 5 seconds.
 * @param onPlacement called with the first result and with every result that changes the place (what is drawn, the
 * report, the map status, the document or the entry point), also when nothing is drawn and when a document comes back: [PlacementLoad.report]
 * says why (`Empty`, `Unknown`, `Unreadable`, `DocumentMissing`, `DocumentUnreadable`) or what to fix while a document
 * is drawn (`EntryMissing`).
 */
@Composable
public fun HotlayoutPlacement(
    name: String,
    source: PlacementSource,
    modifier: Modifier = Modifier,
    refreshIntervalMs: Long? = PlacementSource.DEFAULT_REFRESH_MS,
    theme: HotlayoutThemeSet = HotlayoutThemeSet.builtIn,
    themeId: String? = null,
    data: DataScope = emptyMap(),
    locale: String? = LocalConfiguration.current.locales[0].toLanguageTag(),
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
    onPlacement: ((PlacementLoad) -> Unit)? = null,
    safeAreaTop: Dp? = null,
    safeAreaBottom: Dp? = null,
) {
    // The map is asked on its own interval; the clock decides again on the map already held, without the network.
    val map by produceState<PlacementRules.Decision?>(null, name, source, refreshIntervalMs) {
        while (true) {
            value = source.map()
            delay((refreshIntervalMs ?: break).coerceAtLeast(MIN_REFRESH_MS))
        }
    }
    // Bumped when the clock may have moved the place: a boundary of the schedule passed, or the app came back.
    var epoch by remember(name, source) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, name, source) {
        var seen = false
        val observer = LifecycleEventObserver { _, event ->
            // Adding the observer replays the events up to the current state: only a later resume is a return.
            if (event == Lifecycle.Event.ON_RESUME) { if (seen) epoch++ else seen = true }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val run by produceState<PlacementRun?>(null, map, epoch) {
        val held = map ?: return@produceState
        val next = decidePlacement(source, name, held, source.clock())
        // The same content again keeps the old result, so nothing downstream sees a change.
        val before = value?.load
        value = PlacementRun(if (before != null && before == next.load) before else next.load, next.boundary)
    }
    // One timer at the next boundary of the schedule, cut to what a timer can hold and counted again at its end.
    LaunchedEffect(run) {
        val boundary = run?.boundary ?: return@LaunchedEffect
        delay(PlacementRules.timerDelayMs(boundary, source.clock()))
        epoch++
    }
    val load = run?.load
    val tell by rememberUpdatedState(onPlacement)
    // The host hears the first settled state and every change of status, report, map status, document or entry
    // point, also the return to a shown document; the same state again, and new content of the same document, is no event.
    var told by remember(name, source) { mutableStateOf<PlacementRules.Settled?>(null) }
    LaunchedEffect(load) {
        val now = load ?: return@LaunchedEffect
        if (PlacementRules.event(told, now.settled()) != null) {
            told = now.settled()
            tell?.invoke(now)
        }
    }

    val current = load ?: return
    val document = current.document ?: return
    HotlayoutScreen(
        document = document,
        modifier = modifier,
        theme = theme,
        themeId = themeId,
        data = data,
        locale = locale,
        entry = current.entry,
        timeZone = timeZone,
        now = now,
        images = images,
        fonts = fonts,
        writablePaths = writablePaths,
        autofill = autofill,
        handleSystemBack = handleSystemBack,
        labels = labels,
        onAction = onAction,
        onTrack = onTrack,
        onWrite = onWrite,
        onHaptic = onHaptic,
        onActionTask = onActionTask,
        onNavigate = onNavigate,
        onScreenChange = onScreenChange,
        onReport = onReport,
        safeAreaTop = safeAreaTop,
        safeAreaBottom = safeAreaBottom,
    )
}

private const val MIN_REFRESH_MS = 5_000L
