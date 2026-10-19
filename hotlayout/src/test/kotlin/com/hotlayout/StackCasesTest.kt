package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.render.widgets.LayerAlignWord
import com.hotlayout.render.widgets.LayerGeometry
import com.hotlayout.render.widgets.LayerKind
import com.hotlayout.render.widgets.LayerRead
import com.hotlayout.render.widgets.LayerRules
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.name
import com.hotlayout.theme.HotlayoutThemeSet
import com.hotlayout.theme.StyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared table of overlay layers (`fixtures/offset/offset-cases.json`): the rules and the
 * geometry as pure functions, and the real stack drawn through `HotlayoutScreen`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class StackCasesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table get() = Contract.json("fixtures/offset/offset-cases.json")
    private val resolver = StyleResolver(HotlayoutThemeSet.builtIn.theme())

    private fun JsonValue?.objects(): List<JsonObject> = this?.array.orEmpty().mapNotNull { it.obj }

    @Test fun theTableSpacingIsTheBuiltInTheme() {
        for ((token, value) in (table["tokens"]?.get("spacing")?.obj ?: JsonObject())) {
            assertEquals(token, value.number, resolver.spacing(JsonValue.Str(token)))
        }
    }


    @Test fun layerReadCases() {
        val rows = Contract.rows(table, "layerReadCases")
        assertEquals(21, rows.size)
        for (row in rows) {
            val read = LayerRead.read(row["value"])
            val expect = row["expect"]?.obj
            if (expect == null) {
                assertNull(row.name(), read)
                continue
            }
            assertNotNull("not read: ${row.name()}", read)
            read!!
            assertEquals("alignHorizontal: ${row.name()}", expect["alignHorizontal"]?.string, read.alignHorizontal?.word)
            assertEquals("alignVertical: ${row.name()}", expect["alignVertical"]?.string, read.alignVertical?.word)
            for ((key, axis) in listOf("offsetX" to read.offsetX, "offsetY" to read.offsetY)) {
                val wanted = expect[key]?.obj
                assertEquals("$key toward: ${row.name()}", wanted?.get("toward")?.string, axis?.toward)
                assertEquals("$key by: ${row.name()}", wanted?.get("by"), axis?.by?.json)
            }
            assertEquals("rotate: ${row.name()}", expect["rotate"]?.number?.toInt() ?: 0, read.rotate)
        }
    }

    @Test fun layerPlaceCases() {
        val rows = Contract.rows(table, "layerPlaceCases")
        assertEquals(10, rows.size)
        for (row in rows) {
            val kinds = LayerRules.kinds(row["parent"]?.string ?: "", row["children"].objects())
            assertEquals("overlays: ${row.name()}", row["overlays"]?.array.orEmpty().map { it.bool == true }, kinds.map { it.isOverlay })
            assertEquals("sizesStack: ${row.name()}", row["sizesStack"]?.array.orEmpty().map { it.bool == true }, kinds.map { it.sizesStack })
            row["visibleSizers"]?.number?.let { wanted ->
                val hidden = row["hidden"]?.array.orEmpty().map { it.bool == true }
                assertEquals("visibleSizers: ${row.name()}", wanted.toInt(), LayerRules.visibleSizers(kinds, hidden.map { !it }))
            }
        }
    }

    /** What the reader does with a document the validator refuses (the refusal itself is the builder's). */
    @Test fun layerDocumentCasesReaderOutcome() {
        val rows = Contract.rows(table, "layerDocumentCases")
        assertEquals(16, rows.size)
        for (row in rows) {
            val children = row["children"].objects()
            val kinds = LayerRules.kinds(row["parent"]?.string ?: "", children)
            val issues = row["issues"]?.array.orEmpty().mapNotNull { it.string }
            val name = row.name()
            if ("v.layerOutsideStack" in issues) assertEquals(name, listOf(LayerKind.Sizing, LayerKind.Sizing), kinds)
            if ("v.layerOnFill" in issues) assertEquals(name, listOf(LayerKind.Sizing, LayerKind.Fill), kinds)
            if ("v.noSizingLayer" in issues) assertFalse(name, kinds.any { it.isOverlay })
            if ("v.allLayersFilled" in issues) {
                assertEquals(name, listOf(LayerKind.Fill, LayerKind.Fill), kinds)
                assertEquals(name, listOf(LayerKind.Sizing, LayerKind.Sizing), LayerRules.acting(kinds, listOf(true, true)))
            }
            if ("v.unknownToken" in issues) {
                val read = LayerRead.read(children[1]["layer"])
                assertNotNull(name, read?.offsetX)
                assertNull(name, resolver.spacing(read?.offsetX?.by?.json))
            }
            // A form Zod refuses: the reader reads what it can and never fails.
            if (row["schema"]?.bool == false) children.lastOrNull()?.get("layer")?.let { LayerRead.read(it) }
        }
    }

    private fun geometry(row: JsonObject, rtl: Boolean): LayerGeometry.Result {
        fun size(key: String, field: String) = row[key]?.get(field)?.number ?: 0.0
        val read = LayerRead.read(row["layer"]) ?: LayerRead()
        fun pixels(axis: com.hotlayout.render.widgets.LayerAxisOffset?) = axis?.let { a -> resolver.spacing(a.by.json)?.let { a.toward to it } }
        return LayerGeometry.place(LayerGeometry.Input(
            size("stack", "width"), size("stack", "height"), size("size", "width"), size("size", "height"),
            read.alignHorizontal ?: LayerAlignWord.of(row["stackAlign"]?.get("horizontal")) ?: LayerAlignWord.Center,
            read.alignVertical ?: LayerAlignWord.of(row["stackAlign"]?.get("vertical")) ?: LayerAlignWord.Center,
            pixels(read.offsetX), pixels(read.offsetY), read.rotate, rtl,
        ))
    }

    @Test fun layerGeometryCases() {
        val rows = Contract.rows(table, "layerCases")
        assertEquals(12, rows.size)
        for (row in rows) for ((direction, rtl) in listOf("ltr" to false, "rtl" to true)) {
            val name = "${row.name()} [$direction]"
            val expected = row[direction]
            val result = geometry(row, rtl)
            val rect = expected?.get("rect")
            assertEquals("x: $name", rect?.get("x")?.number ?: Double.NaN, result.x, 1e-9)
            assertEquals("y: $name", rect?.get("y")?.number ?: Double.NaN, result.y, 1e-9)
            assertEquals("width: $name", rect?.get("width")?.number ?: Double.NaN, result.width, 1e-9)
            assertEquals("height: $name", rect?.get("height")?.number ?: Double.NaN, result.height, 1e-9)
            assertEquals("angle: $name", expected?.get("angle")?.number ?: Double.NaN, result.angle, 1e-9)
            assertEquals("protrudes: $name", row["protrudes"]?.bool, result.protrudes)
            for (tap in expected?.get("taps").objects()) {
                val hit = LayerGeometry.contains(result, tap["x"]?.number ?: 0.0, tap["y"]?.number ?: 0.0)
                assertEquals("tap (${tap["x"]?.number}, ${tap["y"]?.number}): $name", tap["hits"]?.bool, hit)
            }
        }
    }

    // ---- the real stack, drawn through the screen

    private val margin = 80

    private fun box(id: String, width: Number, height: Number, color: String, fields: String = "", props: String = "") =
        """{"id":"$id","type":"Container","props":{$props},"style":{"width":$width,"height":$height,"background":{"kind":"color","color":{"value":"$color"}}}$fields}"""

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.isColor(x: Int, y: Int, rgb: Int) = com.hotlayout.support.near(getPixel(x, y) and 0xFFFFFF, rgb, 4)

    /** The stack in a black screen, [margin] away from the top-start corner; right-to-left through a translation. */
    private var shown: androidx.compose.runtime.MutableState<Pair<com.hotlayout.model.HotlayoutDocument, String>>? = null

    /** One composition per test; the next document replaces the previous one. */
    private fun show(document: com.hotlayout.model.HotlayoutDocument, locale: String) {
        val state = shown
        if (state != null) { state.value = document to locale; return }
        val created = androidx.compose.runtime.mutableStateOf(document to locale)
        shown = created
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(created.value.first, locale = created.value.second) } }
    }

    private fun draw(stack: String, rtl: Boolean): android.graphics.Bitmap {
        val body = """{"id":"pad","type":"Row","props":{"align":"start"},"style":{"padding":$margin},"children":[$stack]}"""
        val localization = if (rtl) ""","localization":{"defaultLocale":"en","locales":["en","ar"],"strings":{"ar":{"x/y/z":"w"}}}""" else ""
        val document = Screens.document("a" to body, rootStyle = """{"background":{"kind":"color","color":{"value":"#000000"}}}""", extra = localization)
        show(document, if (rtl) "ar" else "en")
        return capture()
    }

    @Test fun overlaysAreDrawnWhereTheTableSays() = overlays("ltr", false)

    @Test fun overlaysAreDrawnWhereTheTableSaysRtl() = overlays("rtl", true)

    private fun overlays(direction: String, rtl: Boolean) {
        val red = 0xFF0000
        for (row in Contract.rows(table, "layerCases")) {
            val name = "${row.name()} [$direction]"
            val sw = row["stack"]?.get("width")?.number?.toInt() ?: 0
            val sh = row["stack"]?.get("height")?.number?.toInt() ?: 0
            val w = row["size"]?.get("width")?.number?.toInt() ?: 0
            val h = row["size"]?.get("height")?.number?.toInt() ?: 0
            val align = row["stackAlign"]
            val stack = """{"id":"stack","type":"Stack","props":{"alignHorizontal":"${align?.get("horizontal")?.string}","alignVertical":"${align?.get("vertical")?.string}"},"children":[
                ${box("base", sw, sh, "#ffffff")},
                ${box("layer", w, h, "#ff0000", ""","layer":${row["layer"]?.let { jsonText(it) }}""")}]}"""
            val pixels = draw(stack, rtl)
            // The stack keeps its size: white under the base, black right outside it.
            val origin = if (rtl) 360 - margin - sw else margin
            val expected = row[direction]
            val rect = expected?.get("rect")
            val cx = origin + ((rect?.get("x")?.number ?: 0.0) + w / 2).toInt()
            val cy = margin + ((rect?.get("y")?.number ?: 0.0) + h / 2).toInt()
            assertTrue("centre of the layer: $name", pixels.isColor(cx, cy, red))
            for (tap in expected?.get("taps").objects()) {
                val x = origin + (tap["x"]?.number ?: 0.0).toInt()
                val y = margin + (tap["y"]?.number ?: 0.0).toInt()
                val hit = pixels.isColor(x, y, red)
                assertEquals("pixel at tap (${tap["x"]?.number}, ${tap["y"]?.number}): $name", tap["hits"]?.bool, hit)
                if (tap["hits"]?.bool == false) assertTrue("the stack is white under a miss: $name", pixels.isColor(x, y, 0xFFFFFF))
            }
            if (sw > 8 && sh > 8 && row["protrudes"]?.bool == false) {
                assertTrue("outside the stack stays black (right): $name", pixels.isColor(origin + sw + 2, margin + sh / 2, 0x000000))
                assertTrue("outside the stack stays black (below): $name", pixels.isColor(origin + sw / 2, margin + sh + 2, 0x000000))
            }
        }
    }

    private fun jsonText(value: JsonValue): String = when (value) {
        is JsonValue.Obj -> value.fields.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${jsonText(v)}" }
        is JsonValue.Arr -> value.items.joinToString(",", "[", "]") { jsonText(it) }
        is JsonValue.Str -> "\"${value.value}\""
        is JsonValue.Num -> if (value.value == Math.rint(value.value)) value.value.toLong().toString() else value.value.toString()
        is JsonValue.Bool -> value.value.toString()
        JsonValue.Null -> "null"
    }

    @Test fun aFillLayerTakesTheWholeStackAndTheOverlayLiesOverIt() = fillLayer(false)

    @Test fun aFillLayerTakesTheWholeStackAndTheOverlayLiesOverItRtl() = fillLayer(true)

    private fun fillLayer(rtl: Boolean) {
        val stack = """{"id":"stack","type":"Stack","props":{"alignHorizontal":"center","alignVertical":"center"},"children":[
            ${box("base", 100, 60, "#ffffff")},
            ${box("fill", 10, 10, "#00ff00", ""","fill":true""")},
            ${box("dot", 10, 10, "#ff0000", ""","layer":{"alignHorizontal":"start","alignVertical":"start"}""")}]}"""
        run {
            val pixels = draw(stack, rtl)
            val left = if (rtl) 360 - margin - 100 else margin
            for ((x, y) in listOf(11 to 11, 88 to 11, 11 to 48, 88 to 48, 50 to 30)) {
                val dotHere = if (rtl) x >= 80 && y < 20 else x < 20 && y < 20
                if (!dotHere) assertTrue("the fill layer covers ($x, $y), rtl=$rtl", pixels.isColor(left + x, margin + y, 0x00FF00))
            }
            assertTrue("the overlay lies over the fill layer, rtl=$rtl", pixels.isColor(left + (if (rtl) 95 else 5), margin + 5, 0xFF0000))
            assertTrue("the stack is not larger than its sizing layer, rtl=$rtl", pixels.isColor(left + 50, margin + 62, 0x000000))
        }
    }

    @Test fun aHiddenSizingLayerLeavesTheOverlayAnOverlayOfAStackOfNoSize() {
        val data = ""","visibleIf":{"path":"user.vip","op":"isTrue"}"""
        val stack = """{"id":"stack","type":"Stack","props":{},"children":[
            ${box("base", 100, 60, "#ffffff", data)},
            ${box("dot", 10, 10, "#ff0000", ""","layer":{"alignHorizontal":"start"}""")}]}"""
        val body = """{"id":"pad","type":"Row","props":{"align":"start"},"style":{"padding":$margin},"children":[$stack]}"""
        val document = Screens.document("a" to body, rootStyle = """{"background":{"kind":"color","color":{"value":"#000000"}}}""")
        show(document, "en")
        val pixels = capture()
        // Nothing sizes the stack now: it has no size, and the overlay still stands at the start of that point.
        assertTrue(pixels.isColor(margin + 5, margin + 0, 0xFF0000) || pixels.isColor(margin + 5, margin - 5, 0xFF0000) || pixels.isColor(margin + 5, margin + 5, 0xFF0000))
        assertFalse("no white layer", pixels.isColor(margin + 50, margin + 30, 0xFFFFFF))
    }

    /** The touch area follows the placed and turned layer: it is layout and a layer transform, not a draw-time shift. */
    @Test fun aTurnedPressableOverlayCatchesTouchesWhereItIsDrawn() {
        val pings = ArrayList<String>()
        val press = ""","layer":{"rotate":-90}"""
        val stack = """{"id":"stack","type":"Stack","props":{},"children":[
            ${box("base", 200, 200, "#ffffff")}, ${box("tag", 120, 40, "#ff0000", press, """"label":"Ping","action":{"type":"deeplink","params":{"url":"app://ping"}}""")}]}"""
        val body = """{"id":"pad","type":"Row","props":{"align":"start"},"style":{"padding":$margin},"children":[$stack]}"""
        val document = Screens.document("a" to body, rootStyle = """{"background":{"kind":"color","color":{"value":"#000000"}}}""")
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, onAction = { action, _ -> pings.add(action.type) }) } }
        compose.waitForIdle()
        fun tap(x: Int, y: Int) {
            compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset((margin + x).toFloat(), (margin + y).toFloat())) }
            compose.waitForIdle()
        }
        // The unrotated box is 120 x 40 at (40, 80); turned a quarter, the drawing is 40 x 120 about (100, 100).
        tap(150, 100)
        assertEquals("inside the unrotated box, outside the drawing: no touch", emptyList<String>(), pings)
        tap(100, 50)
        assertEquals("inside the drawing: a touch", listOf("deeplink"), pings)
    }
}
