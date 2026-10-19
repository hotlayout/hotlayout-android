package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.hotlayout.actions.ScreenShownCause
import com.hotlayout.actions.ScreenShownEvent
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.ScreenPresentation
import com.hotlayout.model.loadDocument
import com.hotlayout.render.modal.ModalLayerBlurKey
import com.hotlayout.render.modal.ModalPageBlurKey
import com.hotlayout.render.modal.ModalRules
import com.hotlayout.support.near
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the real screen does with modal layers, read back from pixels and from the semantics tree:
 * the box of a sheet and of a window, the page under staying drawn and inert, the dim, the blur,
 * the drag, the keys, the focus, and the stack when the document changes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(ExperimentalTestApi::class)
class ModalWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val events = ArrayList<ScreenShownEvent>()
    private val actions = ArrayList<String>()

    private fun lit(value: String) = """{"kind":"literal","value":"$value"}"""

    private fun button(id: String, label: String, action: String) =
        """{"id":"$id","type":"Button","props":{"label":${lit(label)},"fullWidth":true,"variant":"primary","action":$action}}"""

    private fun box(id: String, height: Int) = """{"id":"$id","type":"Container","props":{"direction":"column","gap":"none"},"style":{"height":$height},"children":[]}"""

    private fun background(hex: String) = """{"background":{"kind":"color","color":{"value":"$hex"}}}"""

    private fun screen(id: String, color: String, children: String, entry: String = "") =
        """{"screenId":"$id","name":"$id"$entry,"root":{"id":"$id-root","type":"Screen","props":{"gap":"none"},"style":${background(color)},"children":[$children]}}"""

    /** A red page with a button that opens the blue layer `m` and a button the page keeps for itself. */
    private fun document(
        entry: String = ""","presentation":"modal"""",
        layer: String = box("m-box", 176),
        extraScreens: String = "",
        version: Int = 0,
    ): HotlayoutDocument {
        val page = screen("a", "#ff0000", button("open", "Open", """{"type":"navigate","params":{"screenId":"m"}}""") + "," + button("page-btn", "Page", """{"type":"ping"}"""))
        val sheet = screen("m", "#0000ff", layer + "," + button("m-back", "Back", """{"type":"navigate.back"}""") + "," + button("m-close", "Close", """{"type":"close"}"""), entry)
        return loadDocument(
            """{"schemaVersion":"9.27.0","documentId":"modals","name":"Modals$version","initialScreenId":"a","screens":[$page,$sheet$extraScreens]}""",
        )
    }

    private fun show(document: HotlayoutDocument, handleSystemBack: Boolean = true) {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                HotlayoutScreen(document, handleSystemBack = handleSystemBack, onScreenChange = { events.add(it) }, onAction = { a, _ -> actions.add(a.type) })
            }
        }
        compose.waitForIdle()
    }

    private fun open() {
        compose.onNodeWithTag("node:open").performClick()
        compose.waitForIdle()
    }

    private fun layerShown() = compose.onAllNodes(hasTestTag("modal:m")).fetchSemanticsNodes().isNotEmpty()

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFFFFFF

    // The box of the layer

    @Test fun aSheetStandsAtTheBottomUnderTheDimAndTheFirstScreenIsOnlyAPage() {
        show(document())
        assertFalse("a layer is not shown before the press", layerShown())
        open()
        assertTrue(layerShown())
        val pixels = capture()
        assertTrue("the sheet: its own blue", near(pixels.rgbAt(100, 640 - 100), 0x0000ff, 3))
        assertFalse("176 high: nothing above", near(pixels.rgbAt(100, 640 - 400), 0x0000ff, 20))
        val page = pixels.rgbAt(300, 300)
        assertEquals("the page is red under the dim", 255 * (1 - ModalRules.SCRIM_OPACITY), ((page shr 16) and 0xFF).toDouble(), 6.0)
        assertTrue((page and 0xFF) < 8)
    }

    @Test fun aWindowStandsInTheMiddleOfTheArea() {
        show(document(entry = ""","presentation":"modal","modalPlacement":"center"""", layer = box("m-box", 200)))
        open()
        val pixels = capture()
        val margin = com.hotlayout.theme.StyleResolver(com.hotlayout.theme.HotlayoutThemeSet.builtIn.theme(null)).spacing(com.hotlayout.json.JsonValue.Str("xl"))!!.toInt()
        assertTrue("the middle", near(pixels.rgbAt(180, 320 - 20), 0x0000ff, 3))
        assertFalse("above the window", near(pixels.rgbAt(180, 320 - 190), 0x0000ff, 30))
        assertFalse("below the window", near(pixels.rgbAt(180, 640 - 5), 0x0000ff, 30))
        assertTrue("inside the margin", near(pixels.rgbAt(margin + 3, 320 - 20), 0x0000ff, 3))
        assertFalse("outside the margin", near(pixels.rgbAt(margin - 3, 320 - 20), 0x0000ff, 30))
    }

    /** The rules speak logical pixels: on a 3x screen the window is still 345 dp wide (393 minus two margins), not 400 px. */
    @Config(qualifiers = "w393dp-h852dp-xxhdpi")
    @Test fun aWindowIsMeasuredInLogicalPixelsOnADenseScreen() {
        show(document(entry = ""","presentation":"modal","modalPlacement":"center"""", layer = box("m-box", 200)))
        open()
        val bounds = compose.onNodeWithTag("screen:m").fetchSemanticsNode().boundsInRoot
        assertEquals(345f * 3, bounds.width, 2f)
        assertEquals((393f * 3 - bounds.width) / 2, bounds.left, 2f)
    }

    @Test fun aLayerTallerThanTheAreaStopsAtTheTopAndItsBodyScrolls() {
        show(document(layer = box("m-box", 900)))
        open()
        val bounds = compose.onNodeWithTag("screen:m").fetchSemanticsNode().boundsInRoot
        assertEquals(640f, bounds.height, 1f)
        compose.onNodeWithTag("node:m-close").performScrollTo()
    }

    // The page under

    @Test fun thePageUnderALayerIsInertAndHiddenFromAccessibility() {
        show(document(entry = ""","presentation":"modal","dismissible":false"""))
        compose.onNodeWithTag("node:page-btn").assertExists()
        open()
        compose.onAllNodes(hasTestTag("node:page-btn")).assertCountEquals(0)
        // A touch on the page where its button lies goes to the dim and nothing more.
        val before = capture().rgbAt(180, 60)
        compose.onNodeWithTag("modal-scrim:m").performTouchInput { click(androidx.compose.ui.geometry.Offset(180f, 60f)) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), actions)
        assertTrue("a layer that waits for an action stays", layerShown())
        assertEquals(before, capture().rgbAt(180, 60))
    }

    // Dismissing: the dim, the system back key, Escape, the drag

    @Test fun aTapOnTheDimIsOneStepBackAsNavigateBackIs() {
        show(document())
        open()
        compose.onNodeWithTag("modal-scrim:m").performTouchInput { click(androidx.compose.ui.geometry.Offset(180f, 60f)) }
        compose.waitForIdle()
        assertFalse(layerShown())
        assertEquals(listOf("entry", "forward", "back"), events.map { it.cause.word })
        compose.onNodeWithTag("node:page-btn").assertExists()
    }

    @Test fun navigateBackInsideTheLayerClosesItAndCloseGoesToTheHostOnly() {
        show(document())
        open()
        compose.onNodeWithTag("node:m-close").performClick()
        compose.waitForIdle()
        assertTrue("close is the host's: the stack does not move", layerShown())
        assertEquals(listOf("close"), actions)
        compose.onNodeWithTag("node:m-back").performClick()
        compose.waitForIdle()
        assertFalse(layerShown())
        assertEquals(ScreenShownCause.Back, events.last().cause)
        assertEquals(ScreenPresentation.Page, events.last().presentation)
    }

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun theSystemBackKeyIsOneStepBackWhileTheStackHasMoreThanOneScreen() {
        val host = ArrayList<String>()
        compose.activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { host.add("back") } })
        show(document())
        back()
        assertEquals("the first screen: the key is the host's", listOf("back"), host)
        open()
        back()
        compose.waitForIdle()
        assertFalse(layerShown())
        assertEquals("the reader took it", listOf("back"), host)
        assertEquals(ScreenShownCause.Back, events.last().cause)
    }

    @Test fun theHostMayKeepTheSystemBackKey() {
        val host = ArrayList<String>()
        compose.activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { host.add("back") } })
        show(document(), handleSystemBack = false)
        open()
        back()
        compose.waitForIdle()
        assertTrue(layerShown())
        assertEquals(listOf("back"), host)
    }

    @Test fun aLayerThatWaitsForAnActionDoesNotTakeTheSystemBackKey() {
        val host = ArrayList<String>()
        compose.activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { host.add("back") } })
        show(document(entry = ""","presentation":"modal","dismissible":false"""))
        open()
        back()
        compose.waitForIdle()
        assertTrue(layerShown())
        assertEquals(listOf("back"), host)
    }

    @Test fun escapeIsOneStepBackAndAHeldKeyClosesNoMoreThanOneLayer() {
        val document = twoLayers("")
        show(document)
        open()
        compose.onNodeWithTag("node:open-n").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("modal:n").assertIsFocused()
        fun escape(repeat: Int) = compose.runOnUiThread {
            val now = android.os.SystemClock.uptimeMillis()
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ESCAPE, repeat))
        }
        escape(repeat = 3)
        compose.waitForIdle()
        assertEquals("a held key closes nothing", listOf("a", "m", "n"), events.map { it.screenId })
        escape(repeat = 0)
        compose.waitForIdle()
        assertEquals(listOf("a", "m", "n", "m"), events.map { it.screenId })
        assertTrue("the layer below is still there", layerShown())
    }

    /** A red page, a blue layer `m` over it and a green layer `n` over that; [blurs] is the `backdropBlur` fields of the two. */
    private fun twoLayers(blurs: String, blurN: String = ""): HotlayoutDocument = loadDocument(
        """{"schemaVersion":"9.27.0","documentId":"modals","name":"two","initialScreenId":"a","screens":[
            ${screen("a", "#ff0000", button("open", "Open", """{"type":"navigate","params":{"screenId":"m"}}"""))},
            ${screen("m", "#0000ff", box("m-box", 120) + "," + button("open-n", "More", """{"type":"navigate","params":{"screenId":"n"}}"""), ""","presentation":"modal"$blurs""")},
            ${screen("n", "#00ff00", box("n-box", 100), ""","presentation":"modal"$blurN""")}]}""",
    )

    @Test fun escapeOnALayerThatWaitsForAnActionIsLeftToTheHost() {
        show(document(entry = ""","presentation":"modal","dismissible":false"""))
        open()
        compose.onNodeWithTag("modal:m").performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }
        compose.waitForIdle()
        assertTrue(layerShown())
    }

    @Test fun aDragDownPastAThirdOfTheSheetDismissesItAndAShortOneReturns() {
        show(document(layer = box("m-box", 300)))
        open()
        // The sheet is 300 + the buttons high; a short drag lets go and the sheet comes back.
        compose.onNodeWithTag("screen:m").performTouchInput { swipeDown(startY = 100f, endY = 130f, durationMillis = 400) }
        compose.waitForIdle()
        assertTrue("returned", layerShown())
        assertEquals(listOf("entry", "forward"), events.map { it.cause.word })
        compose.onNodeWithTag("screen:m").performTouchInput { swipeDown(startY = 100f, endY = 350f, durationMillis = 600) }
        compose.waitForIdle()
        assertFalse("dismissed", layerShown())
        assertEquals(ScreenShownCause.Back, events.last().cause)
    }

    @Test fun aFlingDownDismissesAShortDrag() {
        show(document(layer = box("m-box", 300)))
        open()
        compose.onNodeWithTag("screen:m").performTouchInput { swipeDown(startY = 100f, endY = 140f, durationMillis = 40) }
        compose.waitForIdle()
        assertFalse(layerShown())
    }

    @Test fun aDragThatStartsOnAButtonDragsTheSheetAndDoesNotPressTheButton() {
        show(document(layer = box("m-box", 300)))
        open()
        compose.onNodeWithTag("node:m-close").performTouchInput { swipeDown(startY = 0f, endY = 250f, durationMillis = 600) }
        compose.waitForIdle()
        assertFalse(layerShown())
        assertFalse("the button was not pressed", actions.contains("close"))
    }

    @Test fun noDragWithoutDismissibleAndNoDragOfAWindow() {
        show(document(entry = ""","presentation":"modal","dismissible":false""", layer = box("m-box", 300)))
        open()
        compose.onNodeWithTag("screen:m").performTouchInput { swipeDown(startY = 100f, endY = 350f, durationMillis = 600) }
        compose.waitForIdle()
        assertTrue(layerShown())
    }

    @Test fun aWindowIsNotDragged() {
        show(document(entry = ""","presentation":"modal","modalPlacement":"center"""", layer = box("m-box", 200)))
        open()
        compose.onNodeWithTag("screen:m").performTouchInput { swipeDown(startY = 20f, endY = 190f, durationMillis = 600) }
        compose.waitForIdle()
        assertTrue(layerShown())
    }

    // The blur

    private fun pageBlur(): Float? =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(ModalPageBlurKey)).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(ModalPageBlurKey)

    @Test fun theBlurOfALayerIsDrawnOnThePageUnderIt() {
        show(document(entry = ""","presentation":"modal","backdropBlur":16"""))
        assertEquals("no layer, no blur", null, pageBlur())
        open()
        assertEquals(16f, pageBlur()!!, 0f)
    }

    @Test fun aLayerWithoutABlurLeavesThePageSharp() {
        show(document())
        open()
        assertEquals(0f, pageBlur()!!, 0f)
    }

    @Test fun theBlurOfLayersAddsAsVariancesAndTheLowerLayerIsBlurredByTheOnesAbove() {
        val document = twoLayers(""","backdropBlur":3""", ""","backdropBlur":4""")
        show(document)
        open()
        compose.onNodeWithTag("node:open-n").performClick()
        compose.waitForIdle()
        assertEquals(5f, pageBlur()!!, 1e-4f)
        val lower = compose.onAllNodes(hasTestTag("modal:m")).fetchSemanticsNodes().single().config.getOrNull(ModalLayerBlurKey)
        assertEquals("the layer below is blurred by the one above it", 4f, lower!!, 0f)
        assertEquals("the top layer is sharp", 0f, compose.onAllNodes(hasTestTag("modal:n")).fetchSemanticsNodes().single().config.getOrNull(ModalLayerBlurKey)!!, 0f)
        compose.onAllNodes(hasTestTag("node:m-box")).assertCountEquals(0)
    }

    /**
     * A page with a hard vertical edge at 180 and a layer; the pixel just left of the edge. The software drawing these
     * tests read does not apply a render effect, so the blur itself is read from the semantics ([theBlurOfALayerIsDrawnOnThePageUnderIt])
     * and looked at in the recorded frame `motion-blur-screens-sheet`.
     */
    private fun pixelNextToTheEdge(blur: String): Int {
        val edge = """{"id":"edge","type":"Container","props":{"direction":"column","gap":"none"},"style":{"width":180,"height":640,"background":{"kind":"color","color":{"value":"#000000"}}},"children":[]}"""
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                HotlayoutScreen(
                    loadDocument(
                        """{"schemaVersion":"9.27.0","documentId":"edge","name":"edge","initialScreenId":"a","screens":[
                            ${screen("a", "#ffffff", button("open", "Open", """{"type":"navigate","params":{"screenId":"m"}}""") + "," + edge)},
                            ${screen("m", "#0000ff", box("m-box", 100), ""","presentation":"modal"$blur""")}]}""",
                    ),
                )
            }
        }
        compose.waitForIdle()
        open()
        return capture().rgbAt(173, 300) and 0xFF
    }

    @Test fun noBlurLeavesTheEdgeOfThePageHard() {
        assertTrue("the black side stays black under the dim", pixelNextToTheEdge("") < 8)
    }

    // Focus

    @Test fun focusMovesOnlyWhenThePersonMovedTheStack() {
        show(document())
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        open()
        compose.onNodeWithTag("modal:m").assertIsFocused()
        compose.onNodeWithTag("node:m-back").performClick()
        compose.waitForIdle()
        assertFalse(layerShown())
    }

    @Test fun aNewEntryFromTheHostTakesNoFocus() {
        val document = document(extraScreens = "," + screen("b", "#00ff00", button("b-btn", "B", """{"type":"none"}""")))
        var entry by mutableStateOf<String?>(null)
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, entry = entry) } }
        compose.waitForIdle()
        entry = "b"
        compose.waitForIdle()
        compose.onAllNodes(isFocused()).assertCountEquals(0)
    }

    // The stack when the document changes

    @Test fun aNewVersionWithoutAScreenOfTheStackStartsTheFlowAgain() {
        var current by mutableStateOf(document())
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(current, onScreenChange = { events.add(it) }) } }
        compose.waitForIdle()
        open()
        assertTrue(layerShown())
        // The same document with the layer's screen gone.
        current = loadDocument("""{"schemaVersion":"9.27.0","documentId":"modals","name":"v2","initialScreenId":"a","screens":[${screen("a", "#ff0000", button("page-btn", "Page", """{"type":"ping"}"""))}]}""")
        compose.waitForIdle()
        assertFalse(layerShown())
        assertEquals("entry", events.last().cause.word)
        assertEquals("a", events.last().screenId)
    }

    @Test fun aNewVersionThatKeepsTheStackKeepsTheLayer() {
        var current by mutableStateOf(document(version = 1))
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(current, onScreenChange = { events.add(it) }) } }
        compose.waitForIdle()
        open()
        current = document(version = 2)
        compose.waitForIdle()
        assertTrue(layerShown())
        assertEquals(listOf("entry", "forward"), events.map { it.cause.word })
    }
}
