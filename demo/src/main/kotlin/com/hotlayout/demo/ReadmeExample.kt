package com.hotlayout.demo

// region example

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import com.hotlayout.HotlayoutFonts
import com.hotlayout.HotlayoutLabels
import com.hotlayout.HotlayoutScreen
import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.dataScopeOf
import com.hotlayout.loadDocument
import com.hotlayout.runStandardAction
import java.util.Locale

@Composable
fun PromoScreen(context: Context, documentJson: String, themeJson: String, inter: FontFamily) {
    val document = remember(documentJson) { loadDocument(documentJson) }          // tolerant read + version gate
    val theme = remember(themeJson) { HotlayoutThemeSet.read(themeJson) ?: HotlayoutThemeSet.builtIn }

    HotlayoutScreen(
        document = document,
        theme = theme,
        data = dataScopeOf("user.name" to "Maya", "user.points" to 1850),
        locale = Locale.getDefault().toLanguageTag(),         // the device locale, as is
        entry = "promo",                                      // a named entry point, optional
        fonts = HotlayoutFonts { name -> if (name == "Inter") inter else null },
        labels = HotlayoutLabels(back = "Back"),              // what assistive technology hears, in your language
        onAction = { action, _ ->
            // The reader opens nothing itself. A standard action arrives checked:
            runStandardAction(context, action)
        },
        onTrack = { event, _ -> Log.i("analytics", "${event.event} ${event.params}") },
        onScreenChange = { shown -> Log.i("screen", "${shown.screenId} ${shown.cause}") },
    )
}
// endregion
