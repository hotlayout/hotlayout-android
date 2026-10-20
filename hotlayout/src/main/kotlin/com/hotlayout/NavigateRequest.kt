package com.hotlayout

/** The kind of a screen change the reader is about to make on its own. */
public enum class NavigateKind {
    /** `navigate` to a screen of the document. */
    Push,

    /** `navigate.back` with a screen below. */
    Back,

    /** `navigate.section`: the stack becomes the one named screen (shown as a page). */
    Switch,
}

/**
 * A screen change the reader is about to make on its own. Passed to `onNavigate` of
 * [HotlayoutScreen] before the stack changes; the host cancels it by returning `false`.
 */
public data class NavigateRequest(
    val kind: NavigateKind,
    /** The screen on top of the stack now. */
    val fromScreenId: String,
    /** The screen the stack would show. */
    val toScreenId: String,
    /** The action that asks for the change, as the host would receive any action. */
    val action: HotlayoutAction,
    val source: ActionSource,
)
