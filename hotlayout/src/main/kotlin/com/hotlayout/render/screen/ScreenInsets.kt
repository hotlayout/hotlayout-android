package com.hotlayout.render.screen

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.hotlayout.render.widgets.keyboardInset

/** What the host's window leaves the screen: the platform insets, in logical pixels. A host that has already accounted for them gives zero. */
internal data class ScreenInsets(val top: Double = 0.0, val bottom: Double = 0.0, val keyboard: Double = 0.0)

/**
 * The insets the host gives instead of the window's (`safeAreaTop` / `safeAreaBottom` of
 * [com.hotlayout.HotlayoutScreen]); `null` reads the window.
 */
internal data class SafeAreaOverride(val top: Dp? = null, val bottom: Dp? = null)

internal val LocalSafeAreaOverride = compositionLocalOf { SafeAreaOverride() }

/**
 * The insets of this screen: the status bar and the display cutout above, the navigation bar below,
 * and the keyboard, unless the host named them. What an ancestor already consumed (`safeDrawingPadding`,
 * a `Scaffold`, `consumeWindowInsets`) is taken out side by side, so nothing is added twice. The
 * reader only reads them; the status bar and the navigation bar of the system are never touched (a
 * screen's `statusBarIcons` is a request that goes to the host in the show event).
 *
 * Attach [ConsumedInsets.modifier] to the root of the screen: it is what tells the reader what the
 * ancestors consumed.
 */
internal class ConsumedInsets {
    var consumed: WindowInsets by mutableStateOf(WindowInsets(0, 0, 0, 0))
    val modifier: Modifier = Modifier.onConsumedWindowInsetsChanged { consumed = it }
}

@Composable
internal fun rememberConsumedInsets(): ConsumedInsets = remember { ConsumedInsets() }

@Composable
internal fun rememberScreenInsets(consumed: ConsumedInsets): ScreenInsets {
    val override = LocalSafeAreaOverride.current
    val density = LocalDensity.current
    val left = consumed.consumed
    val top = override.top?.value?.toDouble()
        ?: with(density) { WindowInsets.statusBars.union(WindowInsets.displayCutout).exclude(left).getTop(density).toDp().value.toDouble() }
    val bottom = override.bottom?.value?.toDouble()
        ?: with(density) { WindowInsets.navigationBars.exclude(left).getBottom(density).toDp().value.toDouble() }
    val keyboard = keyboardInset(left).value.toDouble()
    return ScreenInsets(top, bottom, keyboard)
}
