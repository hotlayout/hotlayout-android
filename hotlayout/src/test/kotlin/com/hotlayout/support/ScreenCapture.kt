package com.hotlayout.support

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutScreen
import com.hotlayout.HotlayoutThemeSet

/** Draws a document on a 360 × 640 screen and reads it back as pixels, as the wiring tests do. */
class ScreenCapture(private val compose: AndroidComposeTestRule<*, ComponentActivity>) {
    fun show(document: HotlayoutDocument, locale: String = "en", theme: HotlayoutThemeSet = HotlayoutThemeSet.builtIn): android.graphics.Bitmap {
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, theme = theme, locale = locale) } }
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }
}

/** The red, green and blue of a pixel. */
fun android.graphics.Bitmap.channels(x: Int, y: Int): Triple<Int, Int, Int> {
    val p = getPixel(x, y)
    return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
}

/** Whether a pixel is within [tolerance] of a colour in every channel. */
fun android.graphics.Bitmap.isColor(x: Int, y: Int, rgb: Int, tolerance: Int = 30): Boolean =
    near(getPixel(x, y) and 0xFFFFFF, rgb, tolerance)
