package com.hotlayout.demo

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.hotlayout.HotlayoutScreen
import com.hotlayout.actions.HotlayoutAction
import com.hotlayout.actions.ScreenShownEvent
import com.hotlayout.actions.TrackEvent
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.loadDocument
import com.hotlayout.theme.HotlayoutThemeSet

/** What the reader told the host during a test. */
class Recorder {
    val actions = mutableListOf<HotlayoutAction>()
    val tracks = mutableListOf<TrackEvent>()
    val shown = mutableListOf<ScreenShownEvent>()
    val writes = mutableListOf<Pair<String, JsonValue>>()
    val haptics = mutableListOf<String>()
}

val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

val targetContext: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

fun fixture(name: String, screen: String? = null): HotlayoutDocument = Fixtures.document(targetContext, name, screen)

/** A fixture drawn the way the demo draws it, with every callback recorded. */
@Composable
fun FixtureHost(
    document: HotlayoutDocument,
    recorder: Recorder,
    name: String = "",
    locale: String = "en",
    modifier: Modifier = Modifier,
    safeAreaTop: Dp? = null,
    safeAreaBottom: Dp? = null,
    theme: HotlayoutThemeSet? = null,
) {
    val context = targetContext
    HotlayoutScreen(
        document = document,
        modifier = modifier,
        theme = theme ?: remember(name) { Fixtures.theme(context, name) },
        data = remember { SampleData.scope(context) },
        locale = locale,
        fonts = DemoFonts,
        images = remember { ContractSamples.policy(context) },
        onAction = { action, _ -> recorder.actions += action },
        onTrack = { event, _ -> recorder.tracks += event },
        onWrite = { path, value -> recorder.writes += path to value },
        onHaptic = { haptic, _ -> recorder.haptics += haptic },
        onScreenChange = { recorder.shown += it },
        safeAreaTop = safeAreaTop,
        safeAreaBottom = safeAreaBottom,
    )
}

fun setAnimatorScale(scale: Float) {
    for (key in listOf("animator_duration_scale", "transition_animation_scale", "window_animation_scale")) {
        device.executeShellCommand("settings put global $key $scale")
    }
}

fun hostile(json: String): HotlayoutDocument = loadDocument(json)
