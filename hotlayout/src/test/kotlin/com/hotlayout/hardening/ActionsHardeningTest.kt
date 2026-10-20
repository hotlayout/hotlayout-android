package com.hotlayout.hardening

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hotlayout.ActionParamProblem
import com.hotlayout.HotlayoutAction
import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutScreen
import com.hotlayout.InvalidParam
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.StandardAction
import com.hotlayout.StandardActions
import com.hotlayout.actions.ActionCatalog
import com.hotlayout.actions.WriteActions
import com.hotlayout.actions.WritePress
import com.hotlayout.runStandardAction
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The action limits: the forms of `x-actions.forms`, the catalog (who executes, which parameter
 * may come from data), that the library never opens anything itself, and that one gesture is one
 * action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ActionsHardeningTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val artifact get() = Contract.json("screen-schema.json")["x-actions"]!!

    // ---------------------------------------------------------------- the catalog and the forms

    @Test fun theCatalogIsTheArtifactsCatalogEntryForEntry() {
        val expected = artifact["catalog"]!!.array!!.map { entry ->
            val params = entry["params"]!!.array!!.joinToString(";") {
                "${it["name"]!!.string}/${it["required"]!!.bool}/${it["form"]!!.string}/${it["bindable"]!!.bool}"
            }
            "${entry["type"]!!.string} ${entry["executor"]!!.string} [$params]"
        }
        val actual = ActionCatalog.entries.map { entry ->
            val params = entry.params.joinToString(";") { "${it.name}/${it.required}/${it.form}/${it.bindable}" }
            "${entry.type} ${if (entry.hostExecutes) "host" else "reader"} [$params]"
        }
        assertEquals(expected, actual)
    }

    @Test fun theFormsAreTheArtifactsForms() {
        val forms = artifact["forms"]!!
        assertEquals(forms["httpsUrl"]!!["pattern"]!!.string, ActionCatalog.HTTPS_URL_PATTERN)
        assertEquals(forms["appLink"]!!["pattern"]!!.string, ActionCatalog.APP_LINK_PATTERN)
        assertEquals(forms["httpsUrl"]!!["maxLength"]!!.number!!.toInt(), ActionCatalog.URL_MAX_LENGTH)
        assertEquals(forms["appLink"]!!["maxLength"]!!.number!!.toInt(), ActionCatalog.URL_MAX_LENGTH)
        assertEquals(forms["appLink"]!!["forbiddenSchemes"]!!.array!!.map { it.string!! }.toSet(), ActionCatalog.forbiddenSchemes)
        assertEquals(artifact["problems"]!!.array!!.map { it.string }, ActionParamProblem.values().map { it.word })
    }

    /** Addresses that make a phone talk to itself, to its network or to a script; each is `invalid`, never `standard`. */
    @Test fun hostileAddressesAreNeverStandard() {
        val hostile = listOf(
            "https://127.0.0.1/", "https://2130706433/", "https://0x7f.0.0.1/", "https://0177.0.0.1/", "https://[::1]/",
            "https://localhost/", "https://localhost:8080/", "https://intranet/", "https://10.0.0.1/admin", "https://192.168.1.1/",
            "https://bank.example.com@evil.example/", "https://evil.example\\@bank.example.com/", "https://example.com/\n",
            "https://example.com/ x", "https://exa mple.com/", "HTTPS://example.com/", " https://example.com/", "https://example.com/‮",
            "https://exаmple.com/", "https://example.com:0/", "https://example.com:65536/", "https://example.com:/",
            "https://-example.com/", "https://example.com./", "https://example.123/", "https://example.com#\u0000",
            "http://example.com/", "javascript:alert(1)//https://example.com/", "file:///etc/hosts", "data:text/html,x",
            "https://" + "a".repeat(2040) + ".com/",
        )
        for (url in hostile) {
            val handed = ActionCatalog.handed(JsonObject.of("type" to "openUrl", "params" to mapOf("url" to url)), emptyMap())
            assertNull("«$url» became standard", handed.standard)
            assertNotNull(url, handed.invalid)
        }
        val links = listOf(
            "javascript:alert(1)", "JAVASCRIPT:alert(1)", "intent://scan/#Intent;scheme=zxing;end", "android-app://com.x/y",
            "content://contacts/people", "file:///sdcard/x", "data:,x", "https://example.com", "http://example.com",
            "myapp://a b", "myapp:", "m:x", "myapp://x\n", "my_app://x", "",
        )
        for (url in links) {
            val handed = ActionCatalog.handed(JsonObject.of("type" to "deeplink", "params" to mapOf("url" to url)), emptyMap())
            assertNull("«$url» became standard", handed.standard)
        }
    }

    /** Every parameter the artifact marks `bindable: false` keeps its literal whatever the data say. */
    @Test fun aParameterTheCatalogDoesNotLetBindNeverComesFromData() {
        var checked = 0
        for (entry in artifact["catalog"]!!.array!!) {
            val type = entry["type"]!!.string!!
            for (param in entry["params"]!!.array!!) {
                if (param["bindable"]!!.bool != false) continue
                val name = param["name"]!!.string!!
                val literal = when (name) { "path" -> "survey.answer"; "valueType" -> "string"; else -> "home" }
                val action = JsonObject.of(
                    "type" to type,
                    "params" to mapOf("path" to "survey.answer", name to literal),
                    "paramBindings" to mapOf(name to "evil.value"),
                )
                val data = mapOf("evil.value" to JsonValue.Str("admin.secret"))
                if (WriteActions.isWriteType(type)) {
                    val step = WritePress.effects(action, { data[it] })?.step
                    val written = step as? WriteActions.Step.Write
                    assertTrue("$type.$name from data: $step", written == null || (written.path == "survey.answer" && written.value != JsonValue.Str("admin.secret")))
                } else {
                    val handed = ActionCatalog.handed(action, data)
                    assertEquals("$type.$name came from data", literal, handed.params?.get(name))
                }
                checked++
            }
        }
        assertTrue(checked >= 6)
    }

    // ---------------------------------------------------------------- nothing is opened by the library

    private val mainSources = File("src/main/kotlin")

    /** The only code that may start an activity or open an address is the host's helper `runStandardAction`. */
    @Test fun onlyTheHostsHelperCanOpenAnything() {
        assertTrue(mainSources.absolutePath, mainSources.isDirectory)
        val openers = Regex("""startActivit|Intent\(|PendingIntent|LocalUriHandler|UriHandler|openUri|CustomTabs|Linkify|LinkAnnotation|autoLink|WebView|ACTION_VIEW|Uri\.parse|sendBroadcast|startService""")
        val offenders = mainSources.walk().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                "${file.name}:${index + 1}: ${line.trim()}".takeIf { openers.containsMatchIn(line) && !line.trim().startsWith("*") && !line.trim().startsWith("//") }
            }
        }.filter { !it.startsWith("StandardActions.kt:") }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    private fun handedOf(type: String, url: String): HotlayoutAction =
        ActionCatalog.handed(JsonObject.of("type" to type, "params" to mapOf("url" to url)), emptyMap())

    @Test fun theHelperOpensAStandardAddressAsABrowsableView() {
        assertTrue(runStandardAction(app, handedOf("openUrl", "https://example.com/o/42")))
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals("https://example.com/o/42", intent.dataString)
        assertNull(intent.component)
    }

    @Test fun theHelperOpensNothingForAnInvalidOrNonStandardAction() {
        val cases = listOf(
            handedOf("openUrl", "javascript:alert(1)"), handedOf("openUrl", "https://127.0.0.1/"),
            handedOf("deeplink", "intent://x#Intent;end"), handedOf("deeplink", "myapp://x"), // scheme not allowed by the host
            ActionCatalog.handed(JsonObject.of("type" to "close"), emptyMap()),
            ActionCatalog.handed(JsonObject.of("type" to "share", "params" to mapOf("url" to "https://example.com")), emptyMap()),
        )
        for (action in cases) {
            assertFalse(action.toString(), runStandardAction(app, action))
            assertNull(action.toString(), started())
        }
        assertTrue(runStandardAction(app, handedOf("deeplink", "myapp://x"), setOf("myapp")))
        assertEquals("myapp://x", started()!!.dataString)
    }

    /**
     * `HotlayoutAction` and `StandardAction` have public constructors, so the type alone does not
     * prove the form was checked: a host that assembles or deserialises an action by hand must not
     * get an unchecked address opened (the React reader re-checks the form over the values it opens).
     */
    @Test fun theHelperRechecksAHandAssembledAction() {
        val forged = listOf(
            HotlayoutAction("openUrl", standard = StandardAction.OpenUrl("javascript:alert(document.cookie)")),
            HotlayoutAction("openUrl", standard = StandardAction.OpenUrl("https://127.0.0.1/admin")),
            HotlayoutAction("openUrl", standard = StandardAction.OpenUrl("intent://scan/#Intent;scheme=zxing;end")),
            HotlayoutAction("deeplink", standard = StandardAction.Deeplink("intent://scan/#Intent;end", "myapp")),
            HotlayoutAction("deeplink", standard = StandardAction.Deeplink("javascript:alert(1)", "myapp")),
            HotlayoutAction(
                "openUrl", standard = StandardAction.OpenUrl("https://example.com"),
                invalid = InvalidParam("url", ActionParamProblem.Malformed),
            ),
        )
        val opened = forged.filter { action ->
            val url = StandardActions.url(action, setOf("myapp"))
            val ran = runStandardAction(app, action, setOf("myapp"))
            started()
            url != null || ran
        }
        assertEquals("hand-assembled actions that would be opened", emptyList<HotlayoutAction>(), opened)
    }

    // ---------------------------------------------------------------- one gesture, one action

    private var current by mutableStateOf<HotlayoutDocument?>(null)
    private val calls = ArrayList<String>()

    private fun show(document: HotlayoutDocument, data: Map<String, JsonValue> = emptyMap()) {
        current = document
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                current?.let {
                    HotlayoutScreen(
                        it, data = data,
                        onAction = { a, _ -> calls.add("action:${a.type}") }, onTrack = { e, _ -> calls.add("track:${e.event}") },
                        onWrite = { p, _ -> calls.add("write:$p") }, onHaptic = { w, _ -> calls.add("haptic:$w") },
                        onScreenChange = { s -> calls.add("shown:${s.screenId}") },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun lit(value: String) = Screens.literal(value)

    private fun button(id: String, action: String) =
        """{"id":"$id","type":"Button","props":{"label":${lit(id)},"fullWidth":true,"variant":"primary","action":$action}}"""

    private val open = """{"type":"openUrl","params":{"url":"https://example.com"},"paramBindings":{"url":"offer.url"},"track":{"event":"opened"},"haptic":"light"}"""

    private fun page(): HotlayoutDocument = Screens.document(
        "a" to listOf(
            button("open", open),
            button("go", """{"type":"navigate","params":{"screenId":"b"}}"""),
            button("custom", """{"type":"share","params":{"text":"x"}}"""),
            button("set", """{"type":"setValue","params":{"path":"survey.answer","value":"yes"}}"""),
            """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","label":${lit("Card")},"longPressLabel":${lit("More")},
               "action":{"type":"tap"},"longPress":{"type":"hold"}},"children":[${Screens.text("t", lit("Card"))}]}""",
        ).joinToString(","),
        "b" to Screens.text("tb", lit("B")),
    )

    private fun tag(id: String) = compose.onNodeWithTag("node:$id")

    @Test fun nothingActsWithoutAPress() {
        show(page(), mapOf("offer.url" to JsonValue.Str("https://partner.example.com/x")))
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertEquals(listOf("shown:a"), calls)
    }

    @Test fun oneTapIsOneAction() {
        show(page())
        calls.clear()
        tag("open").performClick()
        compose.waitForIdle()
        assertEquals(listOf("haptic:light", "track:opened", "action:openUrl"), calls)
    }

    @Test fun aHeldKeyAndTheScreenReaderAreOnePressEach() {
        show(page())
        calls.clear()
        tag("custom").requestFocus()
        tag("custom").performKeyInput {
            keyDown(Key.Enter)
            advanceEventTime(2_000)
            keyUp(Key.Enter)
        }
        assertEquals(listOf("action:share"), calls)
        calls.clear()
        tag("custom").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("action:share"), calls)
        calls.clear()
        tag("set").performClick()
        tag("set").performClick() // the same value again: the write is not repeated
        assertEquals(listOf("write:survey.answer"), calls)
    }

    @Test fun aHoldIsTheLongPressOnceAndNotAlsoTheTap() {
        show(page())
        calls.clear()
        tag("card").performTouchInput {
            down(center)
            advanceEventTime(1_500)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("action:hold"), calls)
    }

    @Test fun aNavigationIsOneShowAndNotAHandedAction() {
        show(page())
        calls.clear()
        tag("go").performClick()
        compose.waitForIdle()
        assertEquals(listOf("shown:b"), calls)
    }
}
