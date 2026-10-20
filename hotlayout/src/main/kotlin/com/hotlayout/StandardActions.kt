package com.hotlayout

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hotlayout.actions.ActionCatalog

/**
 * A helper for the HOST: performs a standard action on Android. The reader never calls it; the
 * host decides (and may ask the person first).
 */
public object StandardActions {
    /**
     * The address to open for a standard action, or `null` for `close`, for an invalid action and
     * for an app link whose scheme the host did not allow.
     *
     * [HotlayoutAction] and [StandardAction] can be built by hand, so the type alone proves nothing:
     * the action is read ONCE into local values and the address is checked again by the contract's
     * form (the derived scheme of a deeplink must be the claimed one and an allowed one).
     */
    public fun url(action: HotlayoutAction, allowedDeeplinkSchemes: Set<String> = emptySet()): String? {
        val type = action.type
        val invalid = action.invalid
        val standard = action.standard
        if (invalid != null) return null
        return when (standard) {
            is StandardAction.OpenUrl -> {
                val address = standard.url
                address.takeIf { type == "openUrl" && ActionCatalog.formProblem("httpsUrl", it) == null }
            }
            is StandardAction.Deeplink -> {
                val address = standard.url
                val claimed = standard.scheme
                if (type != "deeplink" || ActionCatalog.formProblem("appLink", address) != null) return null
                val derived = ActionCatalog.appLinkScheme(address) ?: return null
                address.takeIf { derived == claimed && derived in allowedDeeplinkSchemes }
            }
            else -> null
        }
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
