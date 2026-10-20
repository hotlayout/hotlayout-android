package com.hotlayout.demo

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutScreen
import com.hotlayout.ImagePolicy
import com.hotlayout.dataScopeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Documents written by someone else: the reader draws what it can, never crashes, never acts without a press. */
@RunWith(AndroidJUnit4::class)
class HostileDocumentsOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun screen(children: String, rootProps: String = """{"gap":"md"}""") =
        """{"schemaVersion":"9.27.0","documentId":"hostile","initialScreenId":"s","screens":[{"screenId":"s","name":"S","root":
        {"id":"r","type":"Screen","props":$rootProps,"children":[$children]}}]}"""

    private fun nested(depth: Int): String {
        var node = """{"id":"leaf","type":"Text","props":{"content":{"kind":"literal","value":"Deep"}}}"""
        for (i in 0 until depth) node = """{"id":"c$i","type":"Column","props":{"gap":"none","align":"start"},"children":[$node]}"""
        return node
    }

    private val documents: List<Pair<String, String>> = listOf(
        "nested 40" to screen(nested(40)),
        "nested 2000" to screen(nested(2000)),
        "huge numbers" to screen(
            """{"id":"p","type":"ProgressBar","props":{"value":1e308,"max":-1e308,"color":"primary","segments":1000000}},
               {"id":"q","type":"CircularProgress","props":{"value":{"kind":"literal","value":1e308},"max":0,"color":"primary","size":"lg","labelFormat":"percent"}},
               {"id":"t","type":"Text","props":{"content":{"kind":"binding","path":"user.points","format":"number","fallback":"x"}},"style":{"typography":{"token":"bodyM","fontSize":1e300},"padding":{"top":1e9},"width":1e12,"opacity":1e9}},
               {"id":"i","type":"Icon","props":{"name":"star","glyphSize":1e9}},
               {"id":"g","type":"Repeater","props":{"source":"items","item":"item","direction":"column","columns":1e9,"limit":-5},"children":[{"id":"gt","type":"Text","props":{"content":{"kind":"binding","path":"item.name","fallback":"-"}}}]}""",
        ),
        "proto keys" to screen(
            """{"id":"__proto__","type":"Text","props":{"__proto__":{"x":1},"constructor":"y","content":{"kind":"binding","path":"__proto__.polluted","fallback":"proto"}}},
               {"id":"b","type":"Button","props":{"label":{"kind":"literal","value":"Write"},"variant":"primary","action":{"type":"setValue","params":{"path":"__proto__.x","value":"1"}}}}""",
        ),
        "hostile colours" to screen(
            """{"id":"c","type":"Container","props":{"direction":"column","gap":"none"},"style":{"background":{"kind":"color","color":{"value":"red;position:fixed"}},"color":{"value":"javascript:alert(1)"},"border":{"width":1,"color":"#zzzzzz"},"shadow":[{"offsetX":1e9,"offsetY":"x","blur":-4,"spread":1e9,"color":"url(https://evil.example/x)"}]},"children":[{"id":"ct","type":"Text","props":{"content":{"kind":"literal","value":"Colours"}}}]}""",
        ),
        "wrong types" to screen(
            """{"id":"w1","type":"Text"},{"id":"w2","type":"Text","props":"string"},{"id":"w3","type":"Column","props":{},"children":{"a":1}},
               {"id":"w4","type":"Button","props":{"label":42,"action":"navigate"}},{"id":"w5","type":"Image","props":{"source":{"kind":"url","url":"javascript:alert(1)"}}},
               {"id":"w6","type":"NoSuchWidget","props":{}},null,7,"x",{"type":"Text"},{"id":"w7","type":"Stack","props":{"alignHorizontal":42},"children":[null]}""",
        ),
        "links" to screen(
            """{"id":"u1","type":"Button","props":{"label":{"kind":"literal","value":"Script"},"variant":"primary","action":{"type":"openUrl","params":{"url":"javascript:alert(1)"}}}},
               {"id":"u2","type":"Button","props":{"label":{"kind":"literal","value":"Number host"},"variant":"primary","action":{"type":"openUrl","params":{"url":"https://2130706433/"}}}},
               {"id":"u3","type":"Image","props":{"source":{"kind":"url","url":"https://2130706433/pixel.png"},"aspectRatio":"1:1"}}""",
        ),
    )

    @Test
    fun hostileDocumentsDrawWithoutACrashOrAnAction() {
        val recorder = Recorder()
        var current by mutableStateOf<HotlayoutDocument?>(null)
        rule.setContent { current?.let { FixtureHost(it, recorder) } }
        for ((name, json) in documents) {
            val document = runCatching { hostile(json) }.getOrNull()
            if (document == null) continue // refused at load: nothing to draw, nothing to crash
            rule.runOnIdle { current = document }
            rule.waitForIdle()
            assertEquals("$name fired an action without a press", 0, recorder.actions.size)
        }
    }

    /** `openUrl` with a script address or a host of digits is handed as invalid, never as a standard action. */
    @Test
    fun linksOfTheWrongFormAreHandedInvalid() {
        val recorder = Recorder()
        rule.setContent { FixtureHost(hostile(documents.first { it.first == "links" }.second), recorder) }
        rule.onNodeWithText("Script").performClick()
        rule.onNodeWithText("Number host").performClick()
        rule.waitForIdle()
        assertEquals(2, recorder.actions.size)
        for (action in recorder.actions) {
            assertNull(action.standard)
            assertNotNull(action.invalid)
        }
    }

    /**
     * The default picture policy lets `https://2130706433/…` through: a document can make every phone open a
     * connection to its own 127.0.0.1:443 (the action forms refuse such a host for `openUrl`).
     */
    @Test
    fun defaultImagePolicyRefusesAHostOfDigits() {
        assertNull(ImagePolicy.httpsOnly("https://2130706433/pixel.png"))
        assertNull(ImagePolicy.httpsOnly("https://127.0.0.1/pixel.png"))
    }

    /** A list of ten thousand records from the host: the screen shows within a few seconds (the list is not lazy). */
    @Test
    fun tenThousandRecordsShowInTime() {
        val items = (1..10_000).map { mapOf("id" to "i$it", "name" to "Item $it") }
        val document = hostile(
            screen("""{"id":"l","type":"Repeater","props":{"source":"items","item":"item","direction":"column","gap":"none"},"children":[{"id":"lt","type":"Text","props":{"content":{"kind":"binding","path":"item.name","fallback":"-"}}}]}"""),
        )
        val started = System.currentTimeMillis()
        rule.setContent { HotlayoutScreen(document, data = dataScopeOf("items" to items), locale = "en") }
        rule.waitForIdle()
        val took = System.currentTimeMillis() - started
        rule.onNodeWithText("Item 1").assertExists()
        assertTrue("ten thousand rows took $took ms to the first frame", took < 5000)
    }
}
