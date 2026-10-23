package com.hotlayout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
 * screen follows a change. Everything else is the parameters of [HotlayoutScreen]; [entry] of the map is used,
 * not one of yours.
 *
 * @param name the name of the place, as the app writes it (`home.banner`).
 * @param source where the map and the documents come from.
 * @param refreshIntervalMs the pause between two asks of the map; `null` asks once. Not below 5 seconds.
 * @param onPlacement called with every new result of the load, also when nothing is drawn: [PlacementLoad.report]
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
    val load by produceState<PlacementLoad?>(null, name, source, refreshIntervalMs) {
        while (true) {
            val next = loadPlacement(source, name)
            if (next != value) value = next
            delay((refreshIntervalMs ?: break).coerceAtLeast(MIN_REFRESH_MS))
        }
    }
    val tell by rememberUpdatedState(onPlacement)
    LaunchedEffect(load) { load?.let { tell?.invoke(it) } }

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
