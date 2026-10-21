package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Screens
import org.junit.Assert.assertEquals
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
 * What the real screen does for a button's minimum width, the indicator of the open tab, the glyph in
 * a named icon box, the autofill hints and the group of write options; plus the default picture policy.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ButtonTabIconFieldWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(document: HotlayoutDocument, autofill: Boolean = true) {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, locale = "en", autofill = autofill) }
        }
        compose.waitForIdle()
    }

    private fun lit(value: String) = Screens.literal(value)

    @Test fun aButtonSurfaceFillsTheMinimumWidthItsStyleNames() {
        show(Screens.document("a" to """{"id":"b","type":"Button","props":{"label":${lit("Go")},"fullWidth":false,"action":{"type":"none"}},"style":{"minWidth":220}}"""))
        val bounds = compose.onNodeWithText("Go").getBoundsInRoot()
        assertTrue("width ${bounds.right - bounds.left}", (bounds.right - bounds.left).value >= 219.5f)
    }

    @Test fun theOpenTabDrawsItsIndicatorUnderItsTitle() {
        val tabs = """{"id":"t","type":"Tabs","props":{"initialTab":0},"children":[
            {"id":"t1","type":"Tab","props":{"title":${lit("Rewards")}},"children":[]},
            {"id":"t2","type":"Tab","props":{"title":${lit("History")}},"children":[]}]}"""
        show(Screens.document("a" to tabs))
        val title = compose.onNode(hasText("Rewards").and(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))).getBoundsInRoot()
        val indicator = compose.onNodeWithTag("tab-indicator:open", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("indicator ${indicator.right - indicator.left}", (indicator.right - indicator.left).value > 20f)
        assertEquals((title.right - title.left).value, (indicator.right - indicator.left).value, 0.5f)
        val closed = compose.onNodeWithTag("tab-indicator:closed", useUnmergedTree = true).getBoundsInRoot()
        assertTrue((closed.right - closed.left).value > 20f)
    }

    @Test fun aNamedIconBoxClampsTheOwnGlyphSize() {
        show(Screens.document("a" to """{"id":"i","type":"Icon","props":{"name":"gift","glyphSize":64},"style":{"width":48,"height":48}}"""))
        val box = compose.onNodeWithTag("icon:gift", useUnmergedTree = true).getBoundsInRoot()
        assertEquals(48f, (box.right - box.left).value, 0.5f)
        // The drawing is the 48 box, not the 64 of the field: a named side clamps it.
        val geometry = com.hotlayout.render.screen.GlyphGeometry.of(
            com.hotlayout.render.screen.GlyphGeometry.Input(64.0, 18.0, 0.0, 0.0, 0.0, 48.0, 48.0, 64.0, 64.0),
        )
        assertEquals(48.0, geometry.glyph, 0.0)
    }

    private fun contentTypeOf(id: String): Any? {
        var node: SemanticsNode? = compose.onNodeWithTag("field:$id", useUnmergedTree = true).fetchSemanticsNode()
        while (node != null) {
            node.config.getOrNull(SemanticsProperties.ContentType)?.let { return it }
            node = node.parent
        }
        return null
    }

    private fun field(id: String, keyboard: String) =
        """{"id":"$id","type":"TextField","props":{"label":${lit(id)},"keyboard":"$keyboard","value":{"kind":"binding","path":"form.$id"}}}"""

    @Test fun emailAndPhoneFieldsCarryAutofillHintsAndOthersNone() {
        show(Screens.document("a" to listOf("email", "phone", "text", "number").joinToString(",") { field(it, it) }))
        assertNotNull(contentTypeOf("email"))
        assertNotNull(contentTypeOf("phone"))
        assertNull(contentTypeOf("text"))
        assertNull(contentTypeOf("number"))
    }

    @Test fun noHintsWhenTheHostTurnsAutofillOff() {
        show(Screens.document("a" to listOf("email", "phone").joinToString(",") { field(it, it) }), autofill = false)
        assertNull(contentTypeOf("email"))
        assertNull(contentTypeOf("phone"))
    }

    @Test fun writeOptionsOfOneParentAreASelectableGroup() {
        fun option(id: String, value: String) =
            """{"id":"$id","type":"Button","props":{"label":${lit(value)},"fullWidth":false,"action":{"type":"setValue","params":{"path":"range","value":"$value"}}}}"""
        show(Screens.document("a" to """{"id":"g","type":"Row","props":{"gap":"sm"},"children":[${option("w", "Week")},${option("m", "Month")},${option("p", "Planned")}]}"""))
        var parent: SemanticsNode? = compose.onNodeWithText("Week").fetchSemanticsNode().parent
        var grouped = false
        while (parent != null) {
            if (parent.config.getOrNull(SemanticsProperties.CollectionInfo) != null || parent.config.contains(SemanticsProperties.SelectableGroup)) grouped = true
            parent = parent.parent
        }
        assertTrue(grouped)
    }

    @Test fun theDefaultImagePolicyRefusesHostsOfDigitsLocalhostAndBareNames() {
        for (address in listOf(
            "https://2130706433/x.png", "https://127.0.0.1/x.png", "https://0x7f.1/x.png", "https://017700000001/x.png",
            "https://localhost/x.png", "https://intranet/x.png", "https://[::1]/x.png", "https://10.0.0.1:8443/x.png", "https://user@127.0.0.1/x.png",
            "http://example.com/x.png", "https://example.123/x.png",
        )) assertNull(address, ImagePolicy.httpsOnly(address))
        for (address in listOf("https://example.com/x.png", "https://cdn.example.co.uk:8443/a/b.webp?v=1", "https://xn--80ak6aa92e.xn--p1ai/x.png")) {
            assertEquals(address, ImagePolicy.httpsOnly(address))
        }
    }

    @Test fun thePictureDownloadIsCappedAndTheHostCanChangeTheCap() {
        assertEquals(10L * 1024 * 1024, ImagePolicy().maxDownloadBytes)
        assertEquals(1024L, ImagePolicy(maxDownloadBytes = 1024).maxDownloadBytes)
        val big = java.io.ByteArrayInputStream(ByteArray(2048))
        assertNull(com.hotlayout.render.widgets.ImageStore.readCapped(big, 1024))
        assertEquals(1024, com.hotlayout.render.widgets.ImageStore.readCapped(java.io.ByteArrayInputStream(ByteArray(1024)), 1024)?.size)
    }
}
