package com.hotlayout.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotlayout.HotlayoutScreen
import com.hotlayout.actions.runStandardAction

/**
 * A small app that opens the contract's screen fixtures with the reader.
 *
 * Intent extras open one screen directly (used for frames and for driving the app from a shell):
 *
 *     adb shell am start -n com.hotlayout.demo/.MainActivity \
 *       --es fixture rewards-showcase-screens --es screen home --es locale en
 *
 * Without `fixture` the app lists every fixture.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fixture = intent.getStringExtra("fixture")
        val screen = intent.getStringExtra("screen")
        val locale = intent.getStringExtra("locale")
        setContent {
            Box(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
                if (fixture != null) FixtureScreen(fixture, screen, locale) else FixtureList()
            }
        }
    }
}

@Composable
private fun FixtureList() {
    val context = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    val chosen = open
    if (chosen != null) {
        FixtureScreen(chosen, null, null)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(Fixtures.names(context)) { name ->
            BasicText(
                name,
                Modifier.fillMaxWidth().clickable { open = name }.padding(horizontal = 16.dp, vertical = 14.dp),
                style = TextStyle(fontSize = 16.sp),
            )
        }
    }
}

/** One fixture drawn by the reader with the demo's data, fonts and theme. */
@Composable
fun FixtureScreen(name: String, screenId: String?, locale: String?, now: Long? = null) {
    val context = LocalContext.current
    val document = remember(name, screenId) { runCatching { Fixtures.document(context, name, screenId) } }
    document.onFailure {
        BasicText("Could not open $name: ${it.message}", Modifier.padding(16.dp))
        return
    }
    HotlayoutScreen(
        document = document.getOrThrow(),
        theme = remember(name) { Fixtures.theme(context, name) },
        data = remember(name) { SampleData.scope(context) },
        locale = locale ?: java.util.Locale.getDefault().toLanguageTag(),
        fonts = DemoFonts,
        images = remember { ContractSamples.policy(context) },
        now = now,
        onAction = { action, _ ->
            Log.i("HotLayoutDemo", "action ${action.type} standard=${action.standard} invalid=${action.invalid}")
            runStandardAction(context, action)
        },
        onTrack = { event, _ -> Log.i("HotLayoutDemo", "track ${event.event} ${event.params}") },
        onScreenChange = { event -> Log.i("HotLayoutDemo", "shown ${event.screenId} ${event.cause.word}") },
        onReport = { reports -> reports.forEach { Log.i("HotLayoutDemo", "report $it") } },
    )
}
