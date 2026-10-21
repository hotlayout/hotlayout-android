package com.hotlayout.demo

// region example

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutFonts
import com.hotlayout.HotlayoutLoadException
import com.hotlayout.HotlayoutScreen
import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.dataScopeOf
import com.hotlayout.loadDocument
import com.hotlayout.runStandardAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PromoScreen(documentJson: String, themeJson: String, inter: FontFamily) {
    val context = LocalContext.current

    // Reading is work for a background thread, and it throws on a document the reader cannot use
    // (malformed, a newer major version): the screen waits for it and the host decides what to do on failure.
    val document by produceState<HotlayoutDocument?>(null, documentJson) {
        value = withContext(Dispatchers.Default) {
            try {
                loadDocument(documentJson)                      // tolerant read + version gate
            } catch (error: HotlayoutLoadException) {
                Log.w("promo", "document refused: ${error.message}")
                null
            }
        }
    }
    val theme = remember(themeJson) { HotlayoutThemeSet.read(themeJson) ?: HotlayoutThemeSet.builtIn }

    document?.let { loaded ->
        HotlayoutScreen(
            document = loaded,
            theme = theme,
            data = dataScopeOf("user.name" to "Maya", "user.points" to 1850),
            entry = "promo",                                    // a named entry point, optional
            fonts = HotlayoutFonts { name -> if (name == "Inter") inter else null },
            onAction = { action, _ ->
                // The reader opens nothing itself. A standard action arrives checked:
                runStandardAction(context, action)
            },
            onTrack = { event, _ -> Log.i("analytics", "${event.event} ${event.params}") },
            onScreenChange = { shown -> Log.i("screen", "${shown.screenId} ${shown.cause}") },
        )
    }
}
// endregion
