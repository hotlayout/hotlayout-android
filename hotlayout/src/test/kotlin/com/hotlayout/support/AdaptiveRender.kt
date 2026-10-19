package com.hotlayout.support

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import com.hotlayout.HotlayoutScreen
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.loadDocument
import org.junit.rules.TestRule

/** The boxes of the adaptive tables drawn by the real screen at a given width, found by the colour of their background. */
object AdaptiveRender {
    const val HOST_HEX = "#ee4422"
    val hostColor = Triple(0xee, 0x44, 0x22)

    /** The colour of the i-th box: red 0x11, blue 0xcc, green stepped by eight so that two are never close. */
    fun hex(index: Int): String = "#11%02xcc".format(40 + 8 * index)

    fun colour(index: Int) = Triple(0x11, 40 + 8 * index, 0xcc)

    fun background(index: Int): JsonValue = JsonValue.of(mapOf("kind" to "color", "color" to mapOf("value" to hex(index))))

    val hostBackground: JsonValue = JsonValue.of(mapOf("kind" to "color", "color" to mapOf("value" to HOST_HEX)))

    fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject.of(*pairs)

    fun document(root: JsonValue, extra: List<JsonValue> = emptyList()): HotlayoutDocument {
        val screen = JsonValue.of(mapOf("screenId" to "s", "name" to "s", "root" to root))
        return loadDocument(
            JsonValue.of(mapOf("schemaVersion" to "9.27.0", "documentId" to "adaptive", "name" to "adaptive", "initialScreenId" to "s", "screens" to listOf(screen) + extra)),
        )
    }

    fun screenRoot(children: List<JsonValue>, props: JsonObject = JsonObject(), style: JsonValue? = null, propsOn: JsonValue? = null, fields: JsonObject = JsonObject()): JsonValue {
        var allProps = JsonObject.of("gap" to "none")
        for ((key, value) in props) allProps = allProps.with(key, value)
        var o = JsonObject.of("id" to "root", "type" to "Screen", "props" to JsonValue.Obj(allProps), "children" to JsonValue.Arr(children))
        style?.let { o = o.with("style", it) }
        propsOn?.let { o = o.with("propsOn", it) }
        for ((key, value) in fields) o = o.with(key, value)
        return JsonValue.Obj(o)
    }

    /** A column container of the height the table names, with the colour of its index. */
    fun cell(index: Int, spec: JsonObject): JsonValue {
        var style = JsonObject.of("background" to background(index), "height" to (spec["height"] ?: JsonValue.Num(10.0)))
        spec["width"]?.let { style = style.with("width", it) }
        var o = JsonObject.of("id" to "c$index", "type" to "Container", "props" to mapOf("direction" to "column"), "style" to JsonValue.Obj(style))
        for (key in listOf("weight", "showOn", "visibleIf")) spec[key]?.let { o = o.with(key, it) }
        return JsonValue.Obj(o)
    }

    class Spec(val document: HotlayoutDocument, val width: Double, val height: Double, val rightToLeft: Boolean = false, val data: DataScope = emptyMap())

    /** The bounding box of each colour in the picture (left, top, right exclusive, bottom exclusive), or `null` when it is not there. */
    fun boxes(bitmap: Bitmap, colors: List<Triple<Int, Int, Int>>): List<IntArray?> {
        val minX = IntArray(colors.size) { Int.MAX_VALUE }
        val minY = IntArray(colors.size) { Int.MAX_VALUE }
        val maxX = IntArray(colors.size) { -1 }
        val maxY = IntArray(colors.size) { -1 }
        val pixels = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in 0 until bitmap.width) {
                val c = pixels[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 245 && g > 245 && b > 245) continue
                for ((i, want) in colors.withIndex()) {
                    if (Math.abs(r - want.first) <= 3 && Math.abs(g - want.second) <= 3 && Math.abs(b - want.third) <= 3) {
                        if (x < minX[i]) minX[i] = x
                        if (x > maxX[i]) maxX[i] = x
                        if (y < minY[i]) minY[i] = y
                        if (y > maxY[i]) maxY[i] = y
                    }
                }
            }
        }
        return colors.indices.map { if (maxX[it] < 0) null else intArrayOf(minX[it], minY[it], maxX[it] + 1, maxY[it] + 1) }
    }
}

/** Draws the real screen at a size inside a big window and returns the picture; one pixel to a logical pixel (mdpi). */
class ScreenStage(private val compose: AndroidComposeTestRule<out TestRule, ComponentActivity>) {
    private val current = mutableStateOf<AdaptiveRender.Spec?>(null)
    private var counter = 0
    private var ready = false

    fun draw(spec: AdaptiveRender.Spec): Bitmap {
        if (!ready) {
            ready = true
            compose.setContent {
                val live = current.value
                if (live != null) {
                    key(counter) {
                        androidx.compose.runtime.CompositionLocalProvider {
                            Box(Modifier.size(live.width.dp, live.height.dp)) {
                                HotlayoutScreen(live.document, data = live.data, locale = if (live.rightToLeft) "ar" else "en", now = 1_791_000_000_000L)
                            }
                        }
                    }
                }
            }
        }
        counter += 1
        current.value = spec
        compose.waitForIdle()
        val view = compose.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }
}
