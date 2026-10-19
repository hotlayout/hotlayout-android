package com.hotlayout.render.modal

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable

/**
 * The system back key (the button, the gesture, predictive back) as one step back in the reader's
 * own stack of screens. While [enabled] the reader takes it; when the stack is down to its first
 * screen, when the top layer is not dismissible, or when the host asked to keep the key
 * (`handleSystemBack = false`), [enabled] is false and the key goes to whoever is below: the host.
 *
 * It is a `BackHandler` of `activity-compose`, so a host that registers its own handler after the
 * reader's wins over it, and the predictive-back animation of the platform follows the host's
 * manifest. Outside an activity with a back dispatcher there is nothing to register.
 */
@Composable
internal fun SystemBackStep(enabled: Boolean, onBack: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current == null) return
    BackHandler(enabled = enabled, onBack = onBack)
}
