package com.hotlayout.demo

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Layout on a real device: what the JVM frames cannot show (window insets, real fonts, real text shaping). */
@RunWith(AndroidJUnit4::class)
class LayoutOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /**
     * A host that already padded its content by the safe drawing insets (`safeDrawingPadding`, a `Scaffold`) has
     * consumed them: Compose tells descendants so. The reader adds the status bar inset a second time, so every
     * screen starts one status bar lower than with `safeAreaTop = 0`.
     */
    @Test
    fun insetsConsumedByTheHostAreNotAddedAgain() {
        var explicitZero = false
        lateinit var flip: (Boolean) -> Unit
        rule.setContent {
            val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            flip = { state.value = it }
            Box(Modifier.fillMaxSize().safeDrawingPadding().testTag("host")) {
                FixtureHost(
                    fixture("buttons-chips-screens"), Recorder(), "buttons-chips-screens",
                    safeAreaTop = if (state.value) 0.dp else null,
                )
            }
        }
        val host = rule.onNodeWithTag("host").getBoundsInRoot().top
        val byDefault = rule.onNodeWithText("Buttons and chips").getBoundsInRoot().top - host
        rule.runOnIdle { flip(true) }
        explicitZero = true
        val withZero = rule.onNodeWithText("Buttons and chips").getBoundsInRoot().top - host
        assertTrue(explicitZero)
        assertEquals("title offset under a host that consumed the insets", withZero.value, byDefault.value, 1f)
    }

    /** `bottom-zone-screens`, Arabic: the trailing texts «+20» and «+200» of the task rows are cut to «…+» / «2…+». */
    @Test
    fun trailingTextOfARightToLeftRowIsNotCut() {
        rule.setContent { FixtureHost(fixture("bottom-zone-screens"), Recorder(), "bottom-zone-screens", locale = "ar") }
        for (value in listOf("+50", "+20", "+200", "+80")) {
            val node = rule.onNodeWithText(value, useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            assertFalse("“$value” is ellipsized", layout.isLineEllipsized(0))
        }
    }

    /**
     * `rewards-showcase-screens` / `mission`: «Claim reward» has `style.minWidth: 220` in a centred row. Flutter draws
     * a 220 wide pill in the middle; the box is 220 wide here too, but its surface is drawn at the content's width at
     * the start of the box, so the button stands left of the centre.
     */
    @Test
    fun buttonSurfaceFillsItsMinimumWidth() {
        rule.setContent { FixtureHost(fixture("rewards-showcase-screens", "mission"), Recorder(), "rewards-showcase-screens") }
        val button = rule.onNodeWithText("Claim reward")
        val bounds = button.getBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue("width $width", width.value >= 219.5f)
        val pixels = button.captureToImage().toPixelMap()
        val density = targetContext.resources.displayMetrics.density
        val y = pixels.height / 2
        // Both ends of the box are inside the pill (the radius is half the height, so 1.2 heights in is clear of the curve).
        val inset = (pixels.height * 0.6f).toInt()
        val start = pixels[inset, y]
        val end = pixels[pixels.width - 1 - inset, y]
        fun isSurface(c: androidx.compose.ui.graphics.Color) = c.red + c.green + c.blue < 2.4f
        assertTrue("start of the box is not the button surface: $start (density $density)", isSurface(start))
        assertTrue("end of the box is not the button surface: $end", isSurface(end))
    }

    /**
     * `app-bar-screens` / `icons`: «Icon named 48 × 48 … then 64 clamped to 48» (`x-style.layout.ownGlyphSize.namedBox`:
     * the glyph is not larger than the content box). The drawing is made at 64 and cut by the 48 box.
     */
    @Test
    fun namedBoxClampsTheOwnGlyphSize() {
        rule.setContent { FixtureHost(fixture("app-bar-screens", "icons"), Recorder(), "app-bar-screens") }
        val gifts = rule.onAllNodesWithTag("icon:gift", useUnmergedTree = true)
        val clamped = gifts[1].captureToImage().toPixelMap()
        // Lucide's gift lies inside 3…21 of the 24 grid: at a 48 glyph the outer 6 points of the box hold no ink.
        val edge = (clamped.height * 0.06f).toInt().coerceAtLeast(1)
        var ink = 0
        for (x in 0 until clamped.width) for (y in 0 until edge) if (clamped[x, y].alpha > 0.5f && clamped[x, y].red < 0.4f) ink++
        assertEquals("ink in the top rows of the clamped glyph", 0, ink)
    }

    /**
     * `style.backdropBlur` (glass) on a device with render effects (API 31+): the page under the card must be blurred.
     * On `motion-blur-screens` the drifting dot under «Your points» stays sharp; here a white block half under a
     * glass card keeps a hard edge.
     */
    @Test
    fun glassBlursThePageUnderIt() {
        val document = hostile(
            """{"schemaVersion":"9.27.0","documentId":"glass","initialScreenId":"s","screens":[{"screenId":"s","name":"S","root":
            {"id":"r","type":"Screen","props":{"gap":"md"},"style":{"background":{"kind":"color","color":{"value":"#000000"}}},"children":[
              {"id":"st","type":"Stack","props":{"alignHorizontal":"start","alignVertical":"top"},"children":[
                {"id":"white","type":"Container","props":{"direction":"column","gap":"none"},
                 "style":{"width":100,"height":200,"background":{"kind":"color","color":{"value":"#ffffff"}}},"children":[]},
                {"id":"glass","type":"Container","props":{"direction":"column","gap":"none"},
                 "style":{"width":200,"height":200,"backdropBlur":20,"background":{"kind":"color","color":{"value":"#000000","opacity":0.02}}},
                 "children":[{"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Glass"}},"style":{"color":{"value":"#ff0000"}}}]}
              ]}
            ]}}]}""",
        )
        rule.setContent { FixtureHost(document, Recorder()) }
        rule.waitForIdle()
        val text = rule.onNodeWithText("Glass").getBoundsInRoot()
        val image = rule.onRoot().captureToImage().toPixelMap()
        val density = targetContext.resources.displayMetrics.density
        val left = (text.left.value * density).toInt()
        val top = (text.top.value * density).toInt()
        val y = top + (100 * density).toInt()
        var grey = 0
        var white = 0
        for (x in left until left + (200 * density).toInt()) {
            val c = image[x, y]
            val l = (c.red + c.green + c.blue) / 3
            if (l in 0.15f..0.85f) grey++
            if (l > 0.85f) white++
        }
        assertTrue("the white block under the glass is not on the line: $white", white > 0)
        assertTrue("pixels between black and white across the edge under the glass: $grey", grey >= (10 * density).toInt())
    }

    private fun lineWidth(text: String): Float {
        val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val layout = results.single()
        return layout.getLineRight(0) - layout.getLineLeft(0)
    }

    private fun weightDocument(regular: String, bold: String) = hostile(
        """{"schemaVersion":"9.27.0","documentId":"weight","initialScreenId":"s","screens":[{"screenId":"s","name":"S","root":
        {"id":"r","type":"Screen","props":{"gap":"md"},"children":[
          {"id":"a","type":"Text","props":{"content":{"kind":"literal","value":"$regular"},"align":"start"},"style":{"typography":{"token":"titleM","fontWeight":400}}},
          {"id":"b","type":"Text","props":{"content":{"kind":"literal","value":"$bold"},"align":"start"},"style":{"typography":{"token":"titleM","fontWeight":800}}}
        ]}}]}""",
    )

    /**
     * Arabic text in a bold style: the theme names Inter, which has no Arabic letters, so the platform falls back to
     * its Arabic font — and draws it regular. Every Arabic heading, button and bold word of `text-emphasis`,
     * `money`, `bottom-zone`, `hero` (ar) is regular on Android and bold in Flutter. The same pair in Latin differs.
     */
    @Test
    fun boldArabicTextIsBold() {
        // Two strings that differ only by an invisible mark, so both can be found.
        rule.setContent { FixtureHost(weightDocument("الحساب الجاري", "الحساب الجاري\u200F"), Recorder(), locale = "ar") }
        val regular = lineWidth("الحساب الجاري")
        val bold = lineWidth("الحساب الجاري\u200F")
        assertTrue("regular $regular, bold $bold", bold > regular * 1.03f)
    }

    @Test
    fun boldLatinTextIsBold() {
        rule.setContent { FixtureHost(weightDocument("Current account", "Current account\u200F"), Recorder()) }
        val regular = lineWidth("Current account")
        val bold = lineWidth("Current account\u200F")
        assertTrue("regular $regular, bold $bold", bold > regular * 1.03f)
    }

    /**
     * The line box is `lineHeight × size` whatever font draws the letters: `money-screens`, the «add» button is
     * 37.3 high in Arabic and 34 in English (Flutter: 34 in both).
     */
    @Test
    fun arabicTextKeepsTheLineBox() {
        var locale by androidx.compose.runtime.mutableStateOf("en")
        rule.setContent { FixtureHost(fixture("money-screens"), Recorder(), "money-screens", locale = locale) }
        val buttons = rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        val english = buttons.fetchSemanticsNodes().map { it.boundsInRoot.height }
        rule.runOnIdle { locale = "ar" }
        rule.waitForIdle()
        val arabic = buttons.fetchSemanticsNodes().map { it.boundsInRoot.height }
        assertEquals("button heights en $english, ar $arabic", english, arabic)
    }

    /** `composition-screens` / `home`: the open tab «Rewards» carries the indicator under its title (Flutter draws it). */
    @Test
    fun openTabShowsItsIndicator() {
        rule.setContent { FixtureHost(fixture("composition-screens"), Recorder(), "composition-screens") }
        val tab = rule.onNode(androidx.compose.ui.test.hasText("Rewards").and(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)))
        val pixels = tab.captureToImage().toPixelMap()
        var primary = 0
        for (y in pixels.height - (4 * targetContext.resources.displayMetrics.density).toInt() until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c.blue > 0.6f && c.red < 0.5f && c.green < 0.5f) primary++
            }
        }
        assertTrue("primary pixels at the bottom of the open tab: $primary", primary > pixels.width)
    }

    /**
     * `text-emphasis-screens` / `article`, `ar-EG`: a numbered item reads «٣.» (`list-marker-cases.json`, textCases).
     * The glyph is wider than its column and is clipped, so the dot is not drawn («overflow: visible-toward-start»).
     */
    @Test
    fun numberMarkerIsNotClipped() {
        rule.setContent { FixtureHost(fixture("text-emphasis-screens"), Recorder(), "text-emphasis-screens", locale = "ar-EG") }
        val marker = rule.onNodeWithTag("marker:٣.", useUnmergedTree = true)
        val box = marker.getBoundsInRoot()
        val density = targetContext.resources.displayMetrics.density
        val image = rule.onRoot().captureToImage().toPixelMap()
        // Ink runs along the marker's line from 6 points past the start of its column (the gap side) to 12 past its end.
        val left = ((box.left.value - 4) * density).toInt()
        val right = ((box.right.value + 12) * density).toInt().coerceAtMost(image.width - 1)
        var runs = 0
        var inInk = false
        for (x in left..right) {
            var ink = false
            for (y in (box.top.value * density).toInt() until (box.bottom.value * density).toInt()) {
                val c = image[x, y]
                if (c.red + c.green + c.blue < 1.2f) { ink = true; break }
            }
            if (ink && !inInk) runs++
            inInk = ink
        }
        assertTrue("separate marks along the marker (digit and dot): $runs", runs >= 2)
    }
}
