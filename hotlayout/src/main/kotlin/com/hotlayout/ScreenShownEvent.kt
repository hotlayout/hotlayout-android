package com.hotlayout

import com.hotlayout.ScreenPresentation

/** How a screen is shown: a page, or a layer over the page below. */
public enum class ScreenPresentation(public val word: String) { Page("page"), Modal("modal") }

/** Why a screen was shown (`x-screenShown`). */
public enum class ScreenShownCause(public val word: String) { Entry("entry"), Forward("forward"), Back("back"), Switch("switch") }

/** The screen-shown event: once per change of the top of the stack. */
public data class ScreenShownEvent(
    val documentId: String,
    val screenId: String,
    /** The named entry point the flow started from; `null` when none was named or it is unknown. */
    val entry: String?,
    val presentation: ScreenPresentation,
    val cause: ScreenShownCause,
    val depth: Int,
    /** The record the screen was opened with. */
    val record: DataScope?,
    /** `bottom` / `center`, only for a modal screen that names it. */
    val modalPlacement: String?,
    /** The request of the shown screen about the status bar icons, only when the screen names one. */
    val statusBarIcons: String?,
)
