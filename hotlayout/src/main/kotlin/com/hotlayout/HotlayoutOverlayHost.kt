package com.hotlayout

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.hotlayout.render.widgets.LocalRippleHost
import com.hotlayout.render.widgets.SwipeRippleLayers
import com.hotlayout.render.widgets.SwipeRippleStore

/**
 * The place for the fill of an app that keeps its own stack of screens: put it above the routes, at the root of the
 * window. The fill of a swipe button lies over this whole box, so it outlives the screen the button was on and fades away
 * over the next one. A [HotlayoutScreen] that is given `onScreenCovered` finds it here; without it there is no fill and
 * the action runs at once.
 */
@Composable
public fun HotlayoutOverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { SwipeRippleStore(scope) }
    Box(modifier) {
        CompositionLocalProvider(LocalRippleHost provides store) { content() }
        SwipeRippleLayers(store)
    }
}
