package com.hotlayout.actions

import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutReport
import com.hotlayout.model.ReportSink

/** Problems of an action parameter against its form, in the order the checks run: missing, too long, the form. */
public enum class ActionParamProblem(public val word: String) {
    Missing("missing"),
    TooLong("too-long"),
    NotHttps("not-https"),
    Malformed("malformed"),
    ForbiddenScheme("forbidden-scheme"),
}

/** A catalog action the HOST performs, with parameters that passed their form. */
public sealed class StandardAction {
    public data class OpenUrl(val url: String) : StandardAction()
    public data class Deeplink(val url: String, val scheme: String) : StandardAction()
    public object Close : StandardAction() {
        override fun toString(): String = "Close"
    }
}

/** A parameter that did not pass its form. */
public data class InvalidParam(val param: String, val problem: ActionParamProblem)

/**
 * The action as the host receives it through `onAction`.
 *
 * The reader opens nothing itself: an address is checked by its form and handed over as
 * [standard] or [invalid], never both. `track` is not part of it: the analytics event goes
 * separately, before the action.
 */
public data class HotlayoutAction(
    val type: String,
    /** Parameters after `paramBindings` were applied; `null` when there are none. */
    val params: Map<String, String>? = null,
    val standard: StandardAction? = null,
    val invalid: InvalidParam? = null,
)

/** The analytics event of a press (`track`), sent once per press, before the action. */
public data class TrackEvent(val event: String, val params: Map<String, String>?)

/** The action catalog (`x-actions`): forms of parameters and what the host receives. */
public object ActionCatalog {
    internal class Param(val name: String, val required: Boolean, val form: String, val bindable: Boolean)

    internal class Entry(val type: String, val hostExecutes: Boolean, val params: List<Param>)

    internal val entries: List<Entry> = listOf(
        Entry("none", false, emptyList()),
        Entry("navigate", false, listOf(Param("screenId", true, "screenId", false))),
        Entry("navigate.back", false, emptyList()),
        Entry("navigate.section", false, listOf(Param("screenId", true, "screenId", false))),
        Entry("openUrl", true, listOf(Param("url", true, "httpsUrl", true))),
        Entry("deeplink", true, listOf(Param("url", true, "appLink", true))),
        Entry("close", true, emptyList()),
        Entry(
            "setValue", false,
            listOf(Param("path", true, "dataPath", false), Param("value", false, "literal", false), Param("valueType", false, "valueType", false)),
        ),
        Entry("toggleValue", false, listOf(Param("path", true, "dataPath", false))),
    )

    internal fun entry(type: String): Entry? = entries.firstOrNull { it.type == type }

    /** `x-actions.forms`, one source with the artifact (compared by a test). */
    public const val URL_MAX_LENGTH: Int = 2048
    private const val URL_CHARS = "A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%"
    private const val PORT = "(?:[1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])"
    public const val HTTPS_URL_PATTERN: String =
        "^https:\\/\\/(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+(?:[A-Za-z]{2,}|xn--[A-Za-z0-9-]*[A-Za-z0-9])(?::$PORT)?(?:[/?#][$URL_CHARS]*)?$"
    public const val APP_LINK_PATTERN: String = "^([a-z][a-z0-9+.-]{1,31}):[$URL_CHARS]+$"
    public val forbiddenSchemes: Set<String> = setOf(
        "javascript", "data", "file", "blob", "vbscript", "filesystem", "content", "jar", "resource", "intent",
        "android-app", "about", "chrome", "view-source", "http", "https", "ftp", "ws", "wss",
    )

    // `matches()` covers the whole string: Java's `$` would also accept a final line feed.
    private val httpsUrl = Regex(HTTPS_URL_PATTERN)
    private val appLink = Regex(APP_LINK_PATTERN)
    private val paramKey = Regex("^(?!(?:__proto__|constructor|prototype)$)\\S+$")
    private val trackName = Regex("^(?!(?:constructor|prototype)$)[A-Za-z][A-Za-z0-9_]*$")
    private val dataPathSegment = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")
    internal val forbiddenSegments = setOf("__proto__", "constructor", "prototype")

    /** The scheme of an app link, or `null` if the value does not have the form. */
    internal fun appLinkScheme(value: String): String? = appLink.matchEntire(value)?.groupValues?.get(1)

    /**
     * The problem of a parameter value against its form, or `null` when it passes. The form is an
     * EXPRESSION, not a platform URL parser: the value is neither trimmed nor normalized.
     */
    public fun formProblem(form: String, value: String?): ActionParamProblem? {
        if (value.isNullOrEmpty()) return ActionParamProblem.Missing
        if (form != "httpsUrl" && form != "appLink") return null
        if (value.length > URL_MAX_LENGTH) return ActionParamProblem.TooLong
        if (form == "httpsUrl") {
            if (!value.startsWith("https://")) return ActionParamProblem.NotHttps
            return if (httpsUrl.matches(value)) null else ActionParamProblem.Malformed
        }
        val scheme = appLinkScheme(value) ?: return ActionParamProblem.Malformed
        return if (scheme in forbiddenSchemes) ActionParamProblem.ForbiddenScheme else null
    }

    internal fun isParamKey(key: String): Boolean = key.length <= 32 && paramKey.matches(key)

    /** A data path: dot-separated segments of the contract's shape, none of them forbidden. */
    public fun isDataPath(value: String): Boolean {
        if (value.isEmpty() || value.length > 128) return false
        return value.split('.').all { dataPathSegment.matches(it) && it !in forbiddenSegments }
    }

    /** What the host receives for an action of the document, read with the node's data scope. */
    public fun handed(action: JsonObject, scope: DataScope, sink: ReportSink? = null, location: String = "action"): HotlayoutAction {
        val type = action["type"]?.string ?: ""
        val entry = entry(type)
        val params = LinkedHashMap<String, String>()
        action["params"]?.let { literals ->
            val map = literals.obj
            if (map != null) {
                for ((name, value) in map) {
                    val text = value.string
                    if (isParamKey(name) && text != null && text.length <= URL_MAX_LENGTH) {
                        params[name] = text
                    } else {
                        sink?.add(HotlayoutReport.Kind.IgnoredInput, "$location: params.${name.take(32)}", "an action parameter that is not a plain string value is dropped")
                    }
                }
            } else if (!literals.isNull) {
                sink?.add(HotlayoutReport.Kind.IgnoredInput, "$location: params", "params is not an object; the parameters were ignored")
            }
        }
        // A parameter is whole data or whole literal: a binding replaces the literal only with a
        // non-empty string, and never where the catalog says the parameter is not bindable.
        for ((name, pathValue) in action["paramBindings"]?.obj ?: JsonObject()) {
            val path = pathValue.string ?: continue
            if (!isParamKey(name) || !isDataPath(path)) continue
            val param = entry?.params?.firstOrNull { it.name == name }
            if (param != null && !param.bindable) continue
            val value = scope[path]?.string
            if (!value.isNullOrEmpty() && value.length <= URL_MAX_LENGTH) params[name] = value
        }
        val base = params.takeIf { it.isNotEmpty() }
        if (entry == null || !entry.hostExecutes) return HotlayoutAction(type, base)
        for (param in entry.params) {
            val problem = formProblem(param.form, params[param.name])
            if (problem != null && (param.required || problem != ActionParamProblem.Missing)) {
                return HotlayoutAction(type, base, invalid = InvalidParam(param.name, problem))
            }
        }
        return when (type) {
            "openUrl" -> HotlayoutAction(type, base, StandardAction.OpenUrl(params["url"] ?: ""))
            "deeplink" -> {
                val url = params["url"] ?: ""
                HotlayoutAction(type, base, StandardAction.Deeplink(url, appLinkScheme(url) ?: ""))
            }
            else -> HotlayoutAction(type, base, StandardAction.Close)
        }
    }

    /** The analytics event of an action; a `track` of the wrong form is ignored WHOLE. */
    public fun track(action: JsonObject, sink: ReportSink? = null, location: String = "action"): TrackEvent? {
        val value = action["track"] ?: return null
        if (value.isNull) return null
        val event = value["event"]?.string
        if (value.obj == null || event == null || event.length > 40 || !trackName.matches(event)) {
            sink?.add(HotlayoutReport.Kind.IgnoredInput, "$location: track", "track is not an event of the form the contract names; no analytics event is sent")
            return null
        }
        val raw = value["params"]
        if (raw == null || raw.isNull) return TrackEvent(event, null)
        val map = raw.obj
        fun refuse(): TrackEvent? {
            sink?.add(HotlayoutReport.Kind.IgnoredInput, "$location: track.params", "track.params is not a flat map of the form the contract names; no analytics event is sent")
            return null
        }
        if (map == null || map.size > 25) return refuse()
        val params = LinkedHashMap<String, String>()
        for ((key, item) in map) {
            val text = item.string
            if (key.length > 40 || !trackName.matches(key) || key in forbiddenSegments || text == null || text.length > 100) return refuse()
            params[key] = text
        }
        return TrackEvent(event, params.takeIf { it.isNotEmpty() })
    }

    /** Haptic feedback a press asks the host for (`x-haptic`), or `null`. The reader never vibrates. */
    public fun haptic(action: JsonObject): String? =
        action["haptic"]?.string?.takeIf { it in setOf("light", "medium", "heavy", "selection", "success", "warning") }
}
