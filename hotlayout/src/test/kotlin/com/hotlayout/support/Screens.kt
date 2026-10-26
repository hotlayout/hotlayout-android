package com.hotlayout.support

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.hotlayout.HotlayoutDocument
import com.hotlayout.loadDocument

/** Small documents for tests that drive the real screen. */
object Screens {
    /** A document with one screen per entry of [screens] (`screenId` → children JSON of its root). */
    fun document(
        vararg screens: Pair<String, String>,
        rootStyle: String = """{"background":{"kind":"color","color":{"value":"#ffffff"}}}""",
        extra: String = "",
        modal: Set<String> = emptySet(),
    ): HotlayoutDocument {
        val entries = screens.joinToString(",") { (id, children) ->
            val presentation = if (id in modal) ""","presentation":"modal"""" else ""
            """{"screenId":"$id","name":"$id"$presentation,"root":{"id":"$id-root","type":"Screen","props":{"gap":"none"},"style":$rootStyle,"children":[$children]}}"""
        }
        return loadDocument("""{"schemaVersion":"9.30.0","documentId":"test","name":"Test","initialScreenId":"${screens.first().first}"$extra,"screens":[$entries]}""")
    }

    fun text(id: String, content: String, extra: String = ""): String =
        """{"id":"$id","type":"Text","props":{"content":$content}$extra}"""

    fun literal(value: String): String = """{"kind":"literal","value":"$value"}"""
}

/** The colour of a pixel of a captured image, as `0xRRGGBB`. */
fun ImageBitmap.rgb(x: Int, y: Int): Int = toPixelMap()[x, y].let {
    ((it.red * 255).toInt() shl 16) or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt()
}

/** Whether two colours are within a small tolerance of each other in every channel. */
fun near(a: Int, b: Int, tolerance: Int = 10): Boolean =
    listOf(16, 8, 0).all { shift -> Math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= tolerance }
