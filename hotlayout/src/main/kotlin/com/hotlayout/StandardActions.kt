package com.hotlayout

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
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

    /**
     * The text a `copy` action asks to put on the clipboard, or `null` for any other action and for a
     * `copy` that failed its form.
     */
    public fun copiedText(action: HotlayoutAction): String? {
        if (action.invalid != null) return null
        val standard = action.standard as? StandardAction.Copy ?: return null
        return standard.text.takeIf { action.type == "copy" && ActionCatalog.formProblem("text", it) == null }
    }

    /**
     * The text and the address a `share` action asks to offer to the share sheet, only those that have
     * a value, or `null` for any other action and for a `share` that failed its form. The address is
     * checked again by the contract's form.
     */
    public fun shareContent(action: HotlayoutAction): ShareContent? {
        if (action.invalid != null || action.type != "share") return null
        val standard = action.standard as? StandardAction.Share ?: return null
        val text = standard.text?.takeIf { it.isNotEmpty() && ActionCatalog.formProblem("text", it) == null }
        val url = standard.url?.takeIf { ActionCatalog.formProblem("httpsUrl", it) == null }
        if (text == null && url == null) return null
        return ShareContent(text, url)
    }
}

/** What a `share` action offers to the share sheet: a text, an address, or both. */
public data class ShareContent(val text: String?, val url: String?) {
    /** The two joined for a plain-text share: the text, then the address on its own line. */
    public val plainText: String get() = listOfNotNull(text, url).joinToString("\n")
}

/**
 * Puts the text of a checked `copy` action on the clipboard. Returns whether it did; the host
 * decides whether to confirm it (Android 13 and later shows a confirmation of its own).
 */
public fun copyToClipboard(context: Context, action: HotlayoutAction): Boolean {
    val text = StandardActions.copiedText(action) ?: return false
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    manager.setPrimaryClip(ClipData.newPlainText(null, text))
    return true
}

/**
 * The chooser intent of a checked `share` action, or `null`. The host starts it; the reader never
 * opens a share sheet.
 */
public fun shareIntent(action: HotlayoutAction): Intent? {
    val content = StandardActions.shareContent(action) ?: return null
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, content.plainText)
    return Intent.createChooser(send, null)
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
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
