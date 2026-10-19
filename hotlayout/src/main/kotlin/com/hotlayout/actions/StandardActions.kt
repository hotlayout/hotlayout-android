package com.hotlayout.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A helper for the HOST: performs a standard action on Android. The reader never calls it; the
 * host decides (and may ask the person first).
 */
public object StandardActions {
    /**
     * The address to open for a standard action, or `null` for `close`, for an invalid action and
     * for an app link whose scheme the host did not allow.
     */
    public fun url(action: HotlayoutAction, allowedDeeplinkSchemes: Set<String> = emptySet()): String? =
        when (val standard = action.standard) {
            is StandardAction.OpenUrl -> standard.url
            is StandardAction.Deeplink -> standard.url.takeIf { standard.scheme in allowedDeeplinkSchemes }
            else -> null
        }
}

/**
 * Opens a standard action with the system: `openUrl` and an allowed `deeplink` as a view intent.
 * Returns whether something was opened; `close` and every other action are left to the host.
 *
 * The action is read ONCE into local values, and only a [StandardAction] (an address that passed
 * the contract's form) is ever opened.
 */
public fun runStandardAction(context: Context, action: HotlayoutAction, deeplinkSchemes: Set<String> = emptySet()): Boolean {
    val address = StandardActions.url(action, deeplinkSchemes) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
