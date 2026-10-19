package com.hotlayout.render.screen

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

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
 * unless the host named them. The reader only reads them; the status bar and the navigation bar
 * of the system are never touched (a screen's `statusBarIcons` is a request that goes to the host
 * in the show event).
 */
@Composable
internal fun rememberScreenInsets(): ScreenInsets {
    val override = LocalSafeAreaOverride.current
    val density = LocalDensity.current
    val top = override.top?.value?.toDouble() ?: with(density) { WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density).toDp().value.toDouble() }
    val bottom = override.bottom?.value?.toDouble() ?: with(density) { WindowInsets.navigationBars.getBottom(density).toDp().value.toDouble() }
    val keyboard = with(density) { WindowInsets.ime.getBottom(density).toDp().value.toDouble() }
    return ScreenInsets(top, bottom, keyboard)
}
