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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotlayout.HotlayoutScreen
import com.hotlayout.runStandardAction

/**
 * A small app that opens the contract's screen fixtures with the reader.
 *
 * Intent extras open one screen directly (used for frames and for driving the app from a shell):
 *
 *     adb shell am start -n com.hotlayout.demo/.MainActivity \
 *       --es fixture rewards-showcase-screens --es screen home --es locale en
 *
 * Without `fixture` the app opens the Obsidian showcase; `--es fixture list` lists everything. The showcase apps are
 * `showcase-obsidian`, `showcase-sunny` and `showcase-orbit` (the last one takes `--es scenario default|newMember|afterPayment`).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The keyboard is an inset the reader reads; the window must not be panned by the system.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val fixture = intent.getStringExtra("fixture")
        val screen = intent.getStringExtra("screen")
        val locale = intent.getStringExtra("locale")
        val scenario = intent.getStringExtra("scenario")
        setContent {
            // Edge to edge: the reader reads the window insets itself and paints the status bar and the navigation bar
            // areas with the background of the screen (the app bar, the bottom zone, the root), so nothing wraps it.
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                when (fixture) {
                    null -> FixtureScreen(Showcases.FIRST, screen, locale, scenario = scenario)
                    "list" -> FixtureList()
                    else -> FixtureScreen(fixture, screen, locale, scenario = scenario)
                }
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
        items(Showcases.names + Fixtures.names(context)) { name ->
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
fun FixtureScreen(name: String, screenId: String?, locale: String?, now: Long? = null, scenario: String? = null, safeAreaTop: androidx.compose.ui.unit.Dp? = null, safeAreaBottom: androidx.compose.ui.unit.Dp? = null) {
    val context = LocalContext.current
    val view = LocalView.current
    val showcase = Showcases.isShowcase(name)
    if (showcase) {
        val dark = remember(name) { Showcases.isDark(context, name) }
        LaunchedEffect(name) {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    val document = remember(name, screenId) { runCatching { if (showcase) Showcases.document(context, name, screenId) else Fixtures.document(context, name, screenId) } }
    document.onFailure {
        BasicText("Could not open $name: ${it.message}", Modifier.padding(16.dp))
        return
    }
    HotlayoutScreen(
        document = document.getOrThrow(),
        theme = remember(name) { if (showcase) Showcases.theme(context, name) else Fixtures.theme(context, name) },
        data = remember(name, locale, scenario) { if (showcase) Showcases.data(context, name, locale, scenario) else SampleData.scope(context) },
        locale = locale ?: java.util.Locale.getDefault().toLanguageTag(),
        fonts = DemoFonts,
        safeAreaTop = safeAreaTop,
        safeAreaBottom = safeAreaBottom,
        images = remember(showcase) { if (showcase) Showcases.images(context) else ContractSamples.policy(context) },
        now = now ?: if (showcase) Showcases.now(context, name) else null,
        onAction = { action, _ ->
            Log.i("HotLayoutDemo", "action ${action.type} standard=${action.standard} invalid=${action.invalid}")
            runStandardAction(context, action)
        },
        onTrack = { event, _ -> Log.i("HotLayoutDemo", "track ${event.event} ${event.params}") },
        onScreenChange = { event ->
            Log.i("HotLayoutDemo", "shown ${event.screenId} ${event.cause.word}")
            // The screen asks for light or dark status bar icons; the reader never touches the system bars, the host does.
            // A screen that names none gets the icons of the theme's brightness.
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val darkIcons = event.statusBarIcons?.let { it == "dark" } ?: (showcase && !Showcases.isDark(context, name))
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkIcons
            }
        },
        onReport = { reports -> reports.forEach { Log.i("HotLayoutDemo", "report $it") } },
    )
}
