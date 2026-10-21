package com.hotlayout.render.motion

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Whether the system asks for no motion, as the screen observes it; `null` outside a screen. */
private val LocalReducedMotion = compositionLocalOf<Boolean?> { null }

/**
 * Whether the system asks for no motion: animations switched off in the developer settings or by
 * the person (the animator scale is zero). Inside a screen it is the screen's one observed answer,
 * so a change of the setting reaches every node that reads it.
 */
@Composable
internal fun rememberReducedMotion(): Boolean = LocalReducedMotion.current ?: rememberObservedReducedMotion()

/** Observes the setting once for everything below: the nodes read it through [rememberReducedMotion]. */
@Composable
internal fun ProvideReducedMotion(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides rememberObservedReducedMotion(), content = content)
}

@Composable
private fun rememberObservedReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(readsReducedMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readsReducedMotion(resolver)
            }
        }
        // The value may have changed between the first read and the registration.
        reduced = readsReducedMotion(resolver)
        val registered = runCatching {
            resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        }.isSuccess
        onDispose { if (registered) resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun readsReducedMotion(resolver: ContentResolver): Boolean =
    runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
