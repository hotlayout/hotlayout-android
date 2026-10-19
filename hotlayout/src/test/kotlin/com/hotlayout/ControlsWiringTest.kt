package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.hotlayout.actions.WritePress
import com.hotlayout.data.dataScopeOf
import com.hotlayout.json.JsonValue
import com.hotlayout.model.HotlayoutDocument
import com.hotlayout.model.loadDocument
import com.hotlayout.render.interaction.ChoicePlaceKey
import com.hotlayout.render.widgets.ButtonGeometry
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.ScreenSim
import com.hotlayout.support.TableJson
import com.hotlayout.support.name
import com.hotlayout.support.near
import com.hotlayout.theme.HotlayoutThemeSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the real screen does with the controls: buttons and icon buttons by size, press feedback,
 * the disabled state, auto-repeat of a key, the long press, swipe to confirm, the write actions,
 * the dashed border and the tinted shadow — read back from semantics and pixels through
 * `HotlayoutScreen`. The tables prove the rules; these prove the screen calls them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class ControlsWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    private fun android.graphics.Bitmap.rgbAt(x: Int, y: Int): Int = getPixel(x, y) and 0xFFFFFF

    private var current by mutableStateOf<HotlayoutDocument?>(null)
    private var contentSet = false

    /**
     * Draws [document]. The content is set once per test (a rule allows one `setContent`): a second
     * call in the same test changes the document the content shows.
     */
    private fun show(document: HotlayoutDocument, content: @androidx.compose.runtime.Composable (HotlayoutDocument) -> Unit = { HotlayoutScreen(it) }) {
        current = document
        if (!contentSet) {
            contentSet = true
            compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { current?.let { content(it) } } }
        }
        compose.waitForIdle()
    }

    private fun tag(id: String) = compose.onNodeWithTag("node:$id")

    private fun lit(value: String) = """{"kind":"literal","value":"$value"}"""

    private fun button(id: String, label: String, action: String, extra: String = "", fullWidth: Boolean = true, node: String = "") =
        """{"id":"$id","type":"Button","props":{"label":${lit(label)},"fullWidth":$fullWidth,"variant":"primary","action":$action$extra}$node}"""

    // Sizes

    @Test fun buttonsAndIconButtonsStandAtTheHeightOfTheirSize() {
        val document = loadDocument(Contract.bytes("fixtures/buttons-chips-screens.json").decodeToString())
        show(document)
        val theme = HotlayoutThemeSet.builtIn.theme()
        for ((id, size) in listOf("btn-sm" to "sm", "btn-md" to "md", "btn-lg" to "lg")) {
            val expected = ButtonGeometry.height(ButtonGeometry.size(size, theme), theme, 0.0)
            // The line box of the font rounds up to a whole pixel: a hair taller than the table's number.
            assertEquals("button $id", expected, tag(id).fetchSemanticsNode().boundsInRoot.height.toDouble(), 1.1)
        }
        // The `secondary` role has a border of one point, and a border adds to the side.
        val border = if (theme.roles["buttonVariant"]?.get("secondary")?.border != null) 2.0 else 0.0
        for ((id, side) in listOf("ib-back-sm" to 28.0, "ib-back-md" to 40.0, "ib-back-lg" to 48.0)) {
            val bounds = tag(id).fetchSemanticsNode().boundsInRoot
            assertEquals("icon button $id: height", side + border, bounds.height.toDouble(), 1.1)
            assertEquals("icon button $id: a square", side + border, bounds.width.toDouble(), 1.1)
        }
    }

    @Test fun anIconButtonIsNamedByItsLabelOrByItsIcon() {
        val named = """{"id":"a","type":"IconButton","props":{"icon":"share-2","label":${lit("Share")},"action":{"type":"none"}}}"""
        val unnamed = """{"id":"b","type":"IconButton","props":{"icon":"arrow-left","action":{"type":"none"}}}"""
        show(Screens.document("a" to "$named,$unnamed"))
        compose.onNodeWithContentDescription("Share").assertExists()
        compose.onNodeWithContentDescription("arrow left").assertExists()
    }

    @Test fun theIconSitsBesideTheLabelAndFollowsTheDirection() {
        val withIcon = """{"id":"b","type":"Button","props":{"label":${lit("Next")},"icon":"arrow-right","iconPosition":"end","fullWidth":false,"action":{"type":"none"}}}"""
        show(Screens.document("a" to withIcon)) { HotlayoutScreen(it, locale = "en") }
        val button = tag("b").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("icon:arrow-right", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the icon stands at the end of the button: $icon in $button", icon.left > button.center.x)
    }

    // Press feedback

    private fun sunkPixelAfterHold(extra: String): Int {
        val document = Screens.document("a" to button("b", "Go", """{"type":"none"}""", node = extra))
        show(document)
        assertTrue("at rest the edge is painted", near(capture().rgbAt(2, 20), 0x4f46e5))
        tag("b").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        return capture().rgbAt(2, 20)
    }

    @Test fun aPressedButtonSinksAndNoneTurnsItOff() {
        // The scale of a button is 0.96: its edge leaves the pixel; with `pressFeedback: none` it stays.
        assertTrue("sunk", near(sunkPixelAfterHold(""), 0xffffff))
    }

    @Test fun noneTurnsTheFeedbackOff() {
        assertTrue("not sunk", near(sunkPixelAfterHold(""","pressFeedback":"none""""), 0x4f46e5))
    }

    // The disabled state

    @Test fun aDisabledButtonDoesNothingAndStaysInTheOrder() {
        val node = button("b", "Pay", """{"type":"pay","haptic":"light","track":{"event":"paid"}}""", extra = ""","disabledIf":{"path":"form.locked","op":"isTrue"}""")
        val document = Screens.document("a" to node)
        val calls = ArrayList<String>()
        var data by mutableStateOf(dataScopeOf("form.locked" to true))
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                HotlayoutScreen(document, data = data, onAction = { a, _ -> calls.add("action:${a.type}") }, onTrack = { e, _ -> calls.add("track:${e.event}") }, onHaptic = { w, _ -> calls.add("haptic:$w") })
            }
        }
        compose.waitForIdle()
        tag("b").assertIsNotEnabled()
        // Half opacity: the white of the root shows through the fill.
        assertTrue("dimmed", !near(capture().rgbAt(20, 20), 0x4f46e5, 20))
        tag("b").performClick()
        assertEquals(emptyList<String>(), calls)
        data = dataScopeOf("form.locked" to false)
        compose.waitForIdle()
        tag("b").assertIsEnabled()
        assertTrue(near(capture().rgbAt(20, 20), 0x4f46e5))
        tag("b").performClick()
        assertEquals(listOf("haptic:light", "track:paid", "action:pay"), calls)
    }

    @Test fun aButtonThatRequiresAFilledFormWaitsForTheRequiredField() {
        val required = """{"id":"agree","type":"Checkbox","props":{"label":${lit("I agree")},"value":{"kind":"binding","path":"survey.agreed"},"required":true}}"""
        val next = button("next", "Next", """{"type":"next"}""", ""","requiresFilled":true""")
        val document = Screens.document("a" to "$required,$next")
        var data by mutableStateOf(dataScopeOf())
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(document, data = data) } }
        compose.waitForIdle()
        tag("next").assertIsNotEnabled()
        data = dataScopeOf("survey.agreed" to true)
        compose.waitForIdle()
        tag("next").assertIsEnabled()
    }

    // Key auto-repeat

    @Test fun aHeldKeyIsOnePress() {
        val calls = ArrayList<String>()
        show(Screens.document("a" to button("b", "Go", """{"type":"go"}"""))) { HotlayoutScreen(it, onAction = { a, _ -> calls.add(a.type) }) }
        tag("b").requestFocus()
        tag("b").performKeyInput {
            keyDown(Key.Enter)
            advanceEventTime(1000)
            keyUp(Key.Enter)
        }
        assertEquals(listOf("go"), calls)
    }

    // The long press

    private fun card(extra: String = ""): String =
        """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","label":${lit("Open the reward")},"longPressLabel":${lit("More options")},
            "action":{"type":"tap","haptic":"light","track":{"event":"tapped"}},
            "longPress":{"type":"hold","haptic":"heavy","track":{"event":"held"}}$extra},"children":[{"id":"t","type":"Text","props":{"content":${lit("Reward")}}}]}"""

    private fun runCard(gesture: androidx.compose.ui.test.SemanticsNodeInteraction.() -> Unit): List<String> {
        val calls = ArrayList<String>()
        show(Screens.document("a" to card())) {
            HotlayoutScreen(it, onAction = { a, _ -> calls.add("action:${a.type}") }, onTrack = { e, _ -> calls.add("track:${e.event}") }, onHaptic = { w, _ -> calls.add("haptic:$w") })
        }
        tag("card").gesture()
        compose.waitForIdle()
        return calls
    }

    @Test fun aHoldPerformsTheLongPressOnceAndTheReleaseDoesNotClick() {
        val calls = runCard { performTouchInput { down(center); advanceEventTime(600); up() } }
        assertEquals(listOf("haptic:heavy", "track:held", "action:hold"), calls)
    }

    @Test fun aShortTouchIsThePress() {
        val calls = runCard { performTouchInput { down(center); advanceEventTime(200); up() } }
        assertEquals(listOf("haptic:light", "track:tapped", "action:tap"), calls)
    }

    @Test fun aTouchThatMovesBeyondTheSlopIsNeither() {
        val calls = runCard { performTouchInput { down(center); moveBy(Offset(60f, 0f)); advanceEventTime(600); up() } }
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun theLongPressHasItsLabelForTheScreenReaderAndKeys() {
        val calls = ArrayList<String>()
        show(Screens.document("a" to card())) { HotlayoutScreen(it, onAction = { a, _ -> calls.add(a.type) }) }
        val node = tag("card").fetchSemanticsNode()
        val labels = node.config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
        assertTrue("custom action named by longPressLabel: $labels", "More options" in labels)
        assertEquals("Open the reward, Reward", node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull())
        tag("card").requestFocus()
        tag("card").performKeyInput { keyDown(Key.ShiftLeft); keyDown(Key.F10); keyUp(Key.F10); keyUp(Key.ShiftLeft) }
        assertEquals(listOf("hold"), calls)
        calls.clear()
        tag("card").performKeyInput { keyDown(Key.Enter); advanceEventTime(1000); keyUp(Key.Enter) }
        assertEquals("a held Enter is one press", listOf("tap"), calls)
    }

    @Test fun aControlInsideTheCardTakesTheTouchAndTheCardGetsNothing() {
        val inner = button("inner", "Inner", """{"type":"inner"}""")
        val outer = """{"id":"card","type":"Container","props":{"direction":"column","gap":"none","action":{"type":"outer"},"longPress":{"type":"outer-hold"}},"children":[$inner]}"""
        val calls = ArrayList<String>()
        show(Screens.document("a" to outer)) { HotlayoutScreen(it, onAction = { a, _ -> calls.add(a.type) }) }
        tag("inner").performTouchInput { down(center); advanceEventTime(600); up() }
        compose.waitForIdle()
        assertTrue("the card did not act: $calls", "outer" !in calls && "outer-hold" !in calls)
    }

    // Swipe to confirm

    private fun swipe(action: String = """{"type":"redeem","track":{"event":"swiped"},"haptic":"success"}""") =
        """{"id":"s","type":"Button","props":{"label":${lit("Slide to pay")},"confirm":"swipe","variant":"primary","action":$action}}"""

    private fun dragThumb(to: Float) {
        compose.onNodeWithTag("swipe-confirm", useUnmergedTree = true).performTouchInput {
            down(Offset(20f, height / 2f))
            moveTo(Offset(to, height / 2f))
            up()
        }
        compose.waitForIdle()
    }

    @Test fun aSwipePastTheThresholdActsOnceAndStaysBusyUntilTheTimeLimit() {
        val calls = ArrayList<String>()
        show(Screens.document("a" to swipe())) {
            HotlayoutScreen(it, onAction = { a, _ -> calls.add("action:${a.type}") }, onTrack = { e, _ -> calls.add("track:${e.event}") }, onHaptic = { w, _ -> calls.add("haptic:$w") })
        }
        // A tap with no drag does nothing.
        compose.onNodeWithTag("swipe-confirm", useUnmergedTree = true).performTouchInput { down(center); up() }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), calls)
        // A short drag returns.
        dragThumb(120f)
        assertEquals(emptyList<String>(), calls)
        // Past 0.9 of the travel (320): the event goes first, then the action.
        dragThumb(20f + 310f)
        assertEquals(listOf("haptic:success", "track:swiped", "action:redeem"), calls)
        // Busy: another swipe does nothing, until eight seconds pass.
        dragThumb(20f + 310f)
        assertEquals(3, calls.size)
        compose.mainClock.advanceTimeBy(8100)
        compose.waitForIdle()
        dragThumb(20f + 310f)
        assertEquals(6, calls.size)
    }

    @Test fun theKeyboardActsAtOnceAndAHeldKeyIsOnePress() {
        val calls = ArrayList<String>()
        show(Screens.document("a" to swipe())) { HotlayoutScreen(it, onAction = { a, _ -> calls.add(a.type) }) }
        compose.onNodeWithTag("swipe-confirm", useUnmergedTree = true).requestFocus()
        compose.onNodeWithTag("swipe-confirm", useUnmergedTree = true).performKeyInput { keyDown(Key.Enter); advanceEventTime(1000); keyUp(Key.Enter) }
        assertEquals(listOf("redeem"), calls)
    }

    @Test fun theScreenReaderActsAtOnce() {
        val calls = ArrayList<String>()
        show(Screens.document("a" to swipe())) { HotlayoutScreen(it, onAction = { a, _ -> calls.add(a.type) }) }
        compose.onNodeWithTag("swipe-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("redeem"), calls)
    }

    @Test fun theBusyStateEndsWhenTheHostsTaskEnds() {
        val task = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = ArrayList<String>()
        show(Screens.document("a" to swipe())) { HotlayoutScreen(it, onAction = { a, _ -> calls.add(a.type) }, onActionTask = { _, _ -> task }) }
        dragThumb(20f + 310f)
        assertEquals(1, calls.size)
        // Still busy a long way past the time limit while the task runs: there is no timeout with a promise.
        compose.mainClock.advanceTimeBy(9000)
        dragThumb(20f + 310f)
        assertEquals(1, calls.size)
        task.complete(Unit)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        dragThumb(20f + 310f)
        assertEquals(2, calls.size)
    }

    // Write actions

    @Test fun aWriteActionWritesIntoTheSessionAndToTheHostInOrderAndTheScreenFollows() {
        val month = """{"id":"month","type":"Button","props":{"label":${lit("Month")},"variant":"secondary","fullWidth":false,"action":{"type":"setValue","params":{"path":"account.period","value":"month"},"track":{"event":"period"},"haptic":"selection"}}}"""
        val week = """{"id":"week","type":"Button","props":{"label":${lit("Week")},"variant":"secondary","fullWidth":false,"action":{"type":"setValue","params":{"path":"account.period","value":"week"}}}}"""
        val calls = ArrayList<String>()
        show(Screens.document("a" to "$week,$month")) {
            HotlayoutScreen(
                it, onAction = { a, _ -> calls.add("action:${a.type}") }, onTrack = { e, _ -> calls.add("track:${e.event}") },
                onHaptic = { w, _ -> calls.add("haptic:$w") }, onWrite = { p, v -> calls.add("write:$p=${v.string}") },
            )
        }
        val before = tag("month").fetchSemanticsNode().config
        assertEquals(false, before.getOrNull(SemanticsProperties.Selected))
        assertEquals(2 to 2, before.getOrNull(ChoicePlaceKey))
        tag("month").performClick()
        assertEquals(listOf("haptic:selection", "track:period", "write:account.period=month"), calls)
        assertEquals(true, tag("month").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, tag("week").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        // Pressing the chosen one again: the event goes, nothing is written.
        calls.clear()
        tag("month").performClick()
        assertEquals(listOf("haptic:selection", "track:period"), calls)
    }

    @Test fun aWriteActionNeverReachesTheHostAsAnAction() {
        val toggle = """{"id":"eye","type":"IconButton","props":{"icon":"eye","label":${lit("Hide balance")},"action":{"type":"toggleValue","params":{"path":"account.hidden"}}}}"""
        val writes = ArrayList<Pair<String, JsonValue>>()
        var handed = 0
        show(Screens.document("a" to toggle)) { HotlayoutScreen(it, onAction = { _, _ -> handed++ }, onWrite = { p, v -> writes.add(p to v) }) }
        tag("eye").performClick()
        tag("eye").performClick()
        assertEquals(listOf("account.hidden" to JsonValue.Bool(true), "account.hidden" to JsonValue.Bool(false)), writes)
        assertEquals(0, handed)
    }

    @Test fun thePathOfAWriteMustPassTheHostsList() {
        val write = button("w", "Write", """{"type":"setValue","params":{"path":"user.name","value":"x"}}""")
        val writes = ArrayList<String>()
        show(Screens.document("a" to write)) {
            HotlayoutScreen(it, writablePaths = com.hotlayout.data.WritablePaths(setOf("survey.age")), onWrite = { p, _ -> writes.add(p) })
        }
        tag("w").performClick()
        assertEquals(emptyList<String>(), writes)
    }

    // The tables of the choice made of plain nodes, drawn by the real screen

    private fun press(sim: ScreenSim, id: String, how: String) {
        val actor = sim.find(id)?.let(WritePress::actor) ?: return
        val target = tag(actor.id)
        when (how) {
            "tap" -> target.performClick()
            else -> {
                target.requestFocus()
                target.performKeyInput {
                    val key = if (how == "space") Key.Spacebar else Key.Enter
                    keyDown(key)
                    if (how == "enter-held") advanceEventTime(1000)
                    keyUp(key)
                }
            }
        }
    }

    private fun controlOf(id: String): JsonValue {
        val nodes = compose.onAllNodesWithTagOrNull("node:$id") ?: return JsonValue.Str("absent")
        val config = nodes.config
        val role = config.getOrNull(SemanticsProperties.Role)
        val result = ArrayList<Pair<String, JsonValue>>()
        when (role) {
            androidx.compose.ui.semantics.Role.RadioButton -> {
                result.add("role" to JsonValue.Str("radio"))
                result.add("checked" to JsonValue.Bool(config.getOrNull(SemanticsProperties.Selected) == true))
                config.getOrNull(ChoicePlaceKey)?.let {
                    result.add("position" to JsonValue.Num(it.first.toDouble()))
                    result.add("size" to JsonValue.Num(it.second.toDouble()))
                }
            }
            androidx.compose.ui.semantics.Role.Button -> {
                result.add("role" to JsonValue.Str("button"))
                config.getOrNull(SemanticsProperties.ToggleableState)?.let { result.add("pressed" to JsonValue.Bool(it == androidx.compose.ui.state.ToggleableState.On)) }
            }
            else -> return JsonValue.Obj(com.hotlayout.json.JsonObject(listOf("role" to JsonValue.Null)))
        }
        if (config.contains(SemanticsProperties.Disabled)) result.add("disabled" to JsonValue.Bool(true))
        return JsonValue.Obj(com.hotlayout.json.JsonObject(result))
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTagOrNull(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false).firstOrNull()

    @Test fun theControlsOfTheTableAreWhatTheRealScreenSaysBeforeAndAfterThePresses() {
        val rows = Contract.rows(Contract.json("fixtures/inputs/input-cases.json"), "choiceControlCases")
        assertEquals(15, rows.size)
        for (row in rows) {
            val screen = """{"schemaVersion":"9.27.0","documentId":"d","name":"n","initialScreenId":"a","screens":[{"screenId":"a","name":"A","root":{"id":"r","type":"Screen","props":{},"children":${TableJson.text(row["nodes"] ?: JsonValue.Null)}}}]}"""
            val document = loadDocument(screen.replace("\"documentId\":\"d\"", "\"documentId\":\"d${rows.indexOf(row)}\""))
            val sim = ScreenSim(row["nodes"]?.array.orEmpty())
            hostData = Contract.scope(row["session"])
            show(document) { HotlayoutScreen(it, data = hostData) }
            for ((id, expected) in row["expect"]?.obj ?: com.hotlayout.json.JsonObject()) {
                // A plain node of no control (a text, a row) is not tagged: the table says its role is none.
                val seen = if (expected.obj?.get("role")?.isNull == true) null else controlOf(id)
                if (seen != null) assertEquals("before $id: ${row.name()}", expected, seen)
            }
            if (row["presses"] == null) continue
            for (id in row["presses"]?.array.orEmpty().mapNotNull { it.string ?: it["node"]?.string }) press(sim, id, "tap")
            compose.waitForIdle()
            for ((id, expected) in row["after"]?.obj ?: com.hotlayout.json.JsonObject()) {
                assertEquals("after $id: ${row.name()}", expected, controlOf(id))
            }
        }
    }

    private var hostData by mutableStateOf<com.hotlayout.data.DataScope>(emptyMap())
    private var hostLog = ArrayList<List<JsonValue>>()
    private var hostSession = HashMap<String, JsonValue>()

    @Test fun thePressesOfTheTableWriteWhatTheHostSeesThroughTheRealScreen() {
        val rows = Contract.rows(Contract.json("fixtures/actions/action-cases.json"), "writePressCases")
        assertEquals(15, rows.size)
        for (row in rows) {
            val screen = """{"schemaVersion":"9.27.0","documentId":"d","name":"n","initialScreenId":"a","screens":[{"screenId":"a","name":"A","root":{"id":"r","type":"Screen","props":{},"children":${TableJson.text(row["nodes"] ?: JsonValue.Null)}}}]}"""
            val sim = ScreenSim(row["nodes"]?.array.orEmpty())
            hostLog = ArrayList()
            hostSession = HashMap()
            show(loadDocument(screen.replace("\"documentId\":\"d\"", "\"documentId\":\"d${rows.indexOf(row)}\""))) {
                HotlayoutScreen(
                    it, onTrack = { e, _ -> hostLog.add(listOf(JsonValue.Str("track"), JsonValue.Str(e.event))) },
                    onWrite = { p, v -> hostLog.add(listOf(JsonValue.Str("write"), JsonValue.Str(p), v)); hostSession[p] = v },
                )
            }
            for (item in row["presses"]?.array.orEmpty()) press(sim, item.string ?: item["node"]?.string ?: "", item["how"]?.string ?: "tap")
            compose.waitForIdle()
            assertEquals("log: ${row.name()}", row["log"]?.array.orEmpty().map { it.array.orEmpty() }, hostLog)
            assertEquals("session: ${row.name()}", Contract.scope(row["session"]), hostSession.toMap())
        }
    }

    // Paint

    @Test fun aDashedBorderIsDashesAndASolidOneIsNot() {
        fun cardWith(style: String) = """{"id":"c","type":"Container","props":{"direction":"column","gap":"none"},"style":{"width":200,"height":60,"border":{"width":2,"style":"$style","color":{"value":"#000000"}}},"children":[]}"""
        fun topEdgeRuns(style: String): Int {
            show(Screens.document("a" to cardWith(style)))
            val image = capture()
            var runs = 0
            var inside = false
            for (x in 0 until 200) {
                val dark = image.rgbAt(x, 1) < 0x808080
                if (dark && !inside) runs++
                inside = dark
            }
            return runs
        }
        assertEquals("solid is one line", 1, topEdgeRuns("solid"))
        val dashes = topEdgeRuns("dashed")
        assertTrue("dashed is many dashes: $dashes", dashes in 10..40)
    }

    @Test fun aShadowTakesTheHueOfTheBackgroundWhenTheFieldAsks() {
        fun cardWith(shadowColor: String) = """{"id":"c","type":"Container","props":{"direction":"column","gap":"none"},"style":{"width":100,"height":100,"margin":{"top":20,"start":20,"bottom":40,"end":20},"background":{"kind":"color","color":{"value":"#ff0000"}},
            "shadow":[{"offsetX":0,"offsetY":12,"blur":8,"spread":0,"color":{"value":"#0000ff"}}]$shadowColor},"children":[]}"""
        show(Screens.document("a" to cardWith("")))
        val plain = capture().rgbAt(70, 135)
        show(Screens.document("a" to cardWith(""","shadowColor":"background"""")))
        val tinted = capture().rgbAt(70, 135)
        assertTrue("without the field the shadow keeps its own colour (blue): ${plain.toString(16)}", (plain and 0xFF) > (plain shr 16 and 0xFF))
        assertTrue("with it the shadow is the hue of the background (red): ${tinted.toString(16)}", (tinted shr 16 and 0xFF) > (tinted and 0xFF))
    }

    // Fixtures

    @Test fun theFixturesOfTheControlsAreDrawnByTheRealScreenInBothDirections() {
        for (fixture in listOf("buttons-chips-screens", "choice-controls-screens", "glow-press-screens", "actions-screens")) {
            val document = loadDocument(Contract.bytes("fixtures/$fixture.json").decodeToString())
            for (locale in listOf("en", "ar")) {
                show(document) { HotlayoutScreen(it, locale = locale) }
                assertNotNull("$fixture $locale", compose.onNodeWithTag("screen:${document.initialScreenId}").fetchSemanticsNode())
            }
        }
    }
}
