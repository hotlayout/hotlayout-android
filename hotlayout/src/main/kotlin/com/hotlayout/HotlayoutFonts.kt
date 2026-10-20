package com.hotlayout

import androidx.compose.ui.text.font.FontFamily

/**
 * The fonts of the host app: the theme names a family (`fontFamilies.body.primary`, «Inter»), the
 * library ships no fonts. A family the host does not give is drawn with the system font.
 */
public fun interface HotlayoutFonts {
    /** The family the theme names, or `null` for the system font. */
    public fun family(name: String): FontFamily?

    public companion object {
        public val System: HotlayoutFonts = HotlayoutFonts { null }
    }
}
