package com.hotlayout


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
    /** Open an `https` address. */
    public data class OpenUrl(val url: String) : StandardAction()
    /** Open a link of the app with an allowed scheme. */
    public data class Deeplink(val url: String, val scheme: String) : StandardAction()
    /** Close the host's own container. */
    public data object Close : StandardAction()
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
