package com.hotlayout.hardening

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.hotlayout.HotlayoutScreen
import com.hotlayout.HotlayoutThemeSet
import com.hotlayout.JsonValue
import com.hotlayout.loadDocument
import com.hotlayout.support.Screens
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * The library writes nothing to the system log or to standard output: data values (names,
 * balances, entered text) would end up in a log any app with the right to read it can collect.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class LogsHardeningTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theSourcesHaveNoLogCalls() {
        val sources = File("src/main/kotlin")
        assertTrue(sources.isDirectory)
        val writers = Regex("""\bLog\.(v|d|i|w|e|wtf|println)\(|android\.util\.Log\b|\bprintln\(|\bprint\(|printStackTrace\(|System\.(out|err)|Timber\.|Logger\.getLogger""")
        val offenders = sources.walk().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line -> "${file.name}:${index + 1}: ${line.trim()}".takeIf { writers.containsMatchIn(line) } }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun noDataValueReachesTheLogWhileAScreenIsUsed() {
        val secrets = listOf("SECRET-NAME-4417", "SECRET-BALANCE-90210", "secret-token-55", "TYPED-SECRET-91")
        val lit = Screens::literal
        val document = Screens.document(
            "a" to listOf(
                Screens.text("name", """{"kind":"binding","path":"user.name"}"""),
                Screens.text("balance", """{"kind":"binding","path":"user.balance"}"""),
                """{"id":"field","type":"TextField","props":{"label":${lit("Name")},"value":{"kind":"binding","path":"survey.name"}}}""",
                """{"id":"open","type":"Button","props":{"label":${lit("Open")},"action":{"type":"openUrl","paramBindings":{"url":"user.link"},"track":{"event":"open","params":{"k":"v"}}}}}""",
                """{"id":"bad","type":"Button","props":{"label":${lit("Bad")},"action":{"type":"deeplink","paramBindings":{"url":"user.badLink"}}}}""",
                """{"id":"img","type":"Image","props":{"source":{"kind":"binding","path":"user.picture"},"aspectRatio":"1:1"}}""",
                """{"id":"broken","type":"Text","props":"not an object","style":{"color":"red;position:fixed"}}""",
            ).joinToString(","),
        )
        val out = ByteArrayOutputStream()
        val (stdout, stderr) = System.out to System.err
        ShadowLog.clear()
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(out, true))
        try {
            compose.setContent {
                Box(Modifier.size(360.dp, 640.dp)) {
                    HotlayoutScreen(
                        document, theme = HotlayoutThemeSet.read("""{"themes":{"x":{"colors":{"primary":"${secrets[0]}"}}}}""") ?: HotlayoutThemeSet.builtIn,
                        data = mapOf(
                            "user.name" to JsonValue.Str(secrets[0]), "user.balance" to JsonValue.Str(secrets[1]),
                            "user.link" to JsonValue.Str("https://example.com/${secrets[2]}"), "user.badLink" to JsonValue.Str("javascript:${secrets[2]}"),
                            "user.picture" to JsonValue.Str("https://${secrets[2]}.invalid/a.png"),
                        ),
                        onAction = { _, _ -> }, onTrack = { _, _ -> }, onWrite = { _, _ -> }, onReport = { },
                    )
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("field:field", useUnmergedTree = true).performTextInput(secrets[3])
            compose.onNodeWithTag("node:open").performClick()
            compose.onNodeWithTag("node:bad").performClick()
            compose.waitForIdle()
            // A document that fails to load says why without echoing data.
            runCatching { loadDocument("""{"schemaVersion":"9.27.0","screens":[{"screenId":"${secrets[0]}"}]}""") }
            runCatching { loadDocument("""{"x":"${secrets[1]}" """) }
        } finally {
            System.setOut(stdout)
            System.setErr(stderr)
        }
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" } + "\n" + out.toString()
        val leaked = secrets.filter { it in logged }
        assertEquals("data values found in the log or on standard output", emptyList<String>(), leaked)
    }
}
