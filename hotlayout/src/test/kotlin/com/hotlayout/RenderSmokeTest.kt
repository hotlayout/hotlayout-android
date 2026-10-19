package com.hotlayout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.loadDocument
import com.hotlayout.support.Contract
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every screen of every fixture is drawn once: the reader must not crash on any document of this major. */
@RunWith(RobolectricTestRunner::class)
class RenderSmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyFixtureScreenDraws() {
        val documents = ArrayList<Pair<String, HotlayoutDocument>>()
        for (name in Contract.fixtureNames) {
            val json = JsonValue.parse(Contract.bytes("fixtures/$name.json"))
            for (screen in loadDocument(json).screens) {
                documents.add("$name: ${screen.screenId}" to loadDocument(JsonValue.Obj(json.obj!!.with("initialScreenId", JsonValue.Str(screen.screenId)))))
            }
        }
        // Endless motion asks for a frame forever and the test would never be idle: the system asks for no motion.
        android.provider.Settings.Global.putFloat(androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var current by mutableStateOf(documents.first())
        compose.setContent {
            key(current.first) { Box(Modifier.size(393.dp, 852.dp)) { HotlayoutScreen(current.second, locale = "en", now = 1_791_000_000_000) } }
        }
        for (entry in documents) {
            current = entry
            compose.waitForIdle()
            compose.onNodeWithTag("screen:${entry.second.initialScreenId}").assertExistsOrModal(entry)
        }
        assertTrue(documents.size >= 90)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsOrModal(entry: Pair<String, HotlayoutDocument>) {
        // The first shown screen is drawn as a page even when the document marks it modal.
        try {
            assertExists(entry.first)
        } catch (error: AssertionError) {
            throw AssertionError("not drawn: ${entry.first}", error)
        }
    }
}
