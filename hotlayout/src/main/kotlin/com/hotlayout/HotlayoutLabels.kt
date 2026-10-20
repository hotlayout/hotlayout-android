package com.hotlayout

/**
 * The texts the reader says to assistive technology itself: they are not part of a document, so a host that
 * shows its app in another language passes them here. The defaults are English.
 */
public data class HotlayoutLabels(
    /** The accessible name of the back arrow of an app bar. */
    val back: String = "Back",
)
