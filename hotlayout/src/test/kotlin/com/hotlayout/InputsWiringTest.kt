package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.TableJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the real screen does with the input fields: typing writes on every change, the held text is
 * let go by a change of the layers, the limit counts code points, a number field writes numbers,
 * the toggles flip and write, the form enables the button, the key of the keyboard moves the focus,
 * and the documents of the table are drawn in both directions. Read back from semantics through
 * `HotlayoutScreen`; the tables prove the rules, these prove the screen calls them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class InputsWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var current by mutableStateOf<HotlayoutDocument?>(null)
    private var hostData by mutableStateOf<DataScope>(emptyMap())
    private var locale by mutableStateOf("en")
    private var writable: WritablePaths? = null
    private var contentSet = false
    private val log = ArrayList<Pair<String, JsonValue>>()

    private fun show(document: HotlayoutDocument) {
        current = document
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                Box(Modifier.size(360.dp, 640.dp)) {
                    current?.let { HotlayoutScreen(it, data = hostData, locale = locale, writablePaths = writable, onWrite = { path, value -> log.add(path to value) }) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun lit(value: String) = """{"kind":"literal","value":"$value"}"""

    private fun bind(path: String, fallback: String? = null) =
        """{"kind":"binding","path":"$path"${fallback?.let { ""","fallback":"$it"""" } ?: ""}}"""

    private fun field(id: String, path: String, extra: String = "") =
        """{"id":"$id","type":"TextField","props":{"label":${lit("Name")},"value":${bind(path)}$extra}}"""

    private fun input(id: String) = compose.onNodeWithTag("field:$id", useUnmergedTree = true)

    private fun shown(id: String): String =
        input(id).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: "<none>"

    private fun writes(path: String): List<JsonValue> = log.filter { it.first == path }.map { it.second }

    private fun str(vararg values: String) = values.map { JsonValue.Str(it) }

    // Typing

    @Test fun typingWritesOnEveryChangeAndTheFieldShowsWhatWasTyped() {
        show(Screens.document("a" to field("f", "form.name")))
        for (letter in listOf("A", "l", "e")) input("f").performTextInput(letter)
        compose.waitForIdle()
        assertEquals(str("A", "Al", "Ale"), writes("form.name"))
        assertEquals("Ale", shown("f"))
    }

    @Test fun theLimitCountsCodePointsAndTheCutTextIsWrittenOnce() {
        show(Screens.document("a" to field("f", "form.name", ""","maxLength":2""")))
        input("f").performTextInput("😀😀😀")
        compose.waitForIdle()
        assertEquals(str("😀😀"), writes("form.name"))
        assertEquals("😀😀", shown("f"))
    }

    @Test fun theSessionHoldsWhatWasTypedAndBeatsTheHostsData() {
        hostData = mapOf("form.name" to JsonValue.Str("Alex"))
        show(Screens.document("a" to field("f", "form.name") + "," + field("g", "form.other")))
        assertEquals("Alex", shown("f"))
        input("f").performTextReplacement("Alexa")
        input("g").requestFocus()
        compose.waitForIdle()
        hostData = mapOf("form.name" to JsonValue.Str("FROM HOST"))
        compose.waitForIdle()
        assertEquals("what the person typed is in the session, and the session is stronger than the host", "Alexa", shown("f"))
    }

    @Test fun aValueTheLayersChangeWhileTheFieldIsFocusedLeavesTheTypedTextAndAfterLeavingTheFieldShowsTheLayers() {
        // The host forbids the write, so what was typed lives only in the field: the layers are what the host says.
        writable = WritablePaths(emptySet())
        hostData = mapOf("form.name" to JsonValue.Str("Alex"))
        show(Screens.document("a" to field("f", "form.name") + "," + field("g", "form.other")))
        input("f").performTextReplacement("Alexa")
        compose.waitForIdle()
        hostData = mapOf("form.name" to JsonValue.Str("FROM HOST"))
        compose.waitForIdle()
        assertEquals("the typed text stays in focus", "Alexa", shown("f"))
        input("g").requestFocus()
        compose.waitForIdle()
        assertEquals("FROM HOST", shown("f"))
        // «A → B → A»: back to what the data held before — the field shows it as a new value, not as «unchanged».
        hostData = mapOf("form.name" to JsonValue.Str("Alex"))
        compose.waitForIdle()
        assertEquals("Alex", shown("f"))
    }

    @Test fun erasingWritesAnEmptyString() {
        hostData = mapOf("form.name" to JsonValue.Str("Alex"))
        show(Screens.document("a" to field("f", "form.name")))
        input("f").performTextClearance()
        compose.waitForIdle()
        assertEquals(str(""), writes("form.name"))
        assertEquals("", shown("f"))
    }

    @Test fun textThatArrivesBeforeThePersonTouchedTheFieldIsFilledByThePlatformAndNotWritten() {
        show(Screens.document("a" to field("f", "form.email", ""","keyboard":"email"""")))
        input("f").performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("saved@example.com")) }
        compose.waitForIdle()
        assertEquals(emptyList<JsonValue>(), writes("form.email"))
        assertEquals("", shown("f"))
    }

    @Test fun aFieldWithoutAPathTakesInputAndWritesNothing() {
        show(Screens.document("a" to """{"id":"f","type":"TextField","props":{"label":${lit("Name")}}}"""))
        input("f").performTextInput("Al")
        compose.waitForIdle()
        assertTrue(log.isEmpty())
        assertEquals("Al", shown("f"))
    }

    @Test fun aDisabledFieldTakesNoFocusAndWritesNothing() {
        hostData = mapOf("form.locked" to JsonValue.Bool(true), "form.name" to JsonValue.Str("Alex"))
        show(Screens.document("a" to field("f", "form.name", ""","disabledIf":{"path":"form.locked","op":"isTrue"}""")))
        assertTrue(input("f").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
        assertEquals("Alex", shown("f"))
        assertTrue(log.isEmpty())
    }

    // The number field

    @Test fun aNumberFieldWritesNumbersAndAnEmptyStringForWhatIsNotOne() {
        show(Screens.document("a" to field("n", "form.age", ""","valueType":"number"""")))
        input("n").performTextInput("1")
        input("n").performTextInput("2")
        input("n").performTextInput("a")
        compose.waitForIdle()
        assertEquals(listOf(JsonValue.Num(1.0), JsonValue.Num(12.0), JsonValue.Str("")), writes("form.age"))
        assertEquals("12a", shown("n"))
    }

    @Test fun aNumberFromTheDataIsShownInItsCanonicalFormAndAStringIsNotANumber() {
        hostData = mapOf("form.age" to JsonValue.Num(12.0), "form.name" to JsonValue.Str("12"))
        show(Screens.document("a" to field("n", "form.age", ""","valueType":"number"""") + "," + field("m", "form.name", ""","valueType":"number"""")))
        assertEquals("12", shown("n"))
        assertEquals("", shown("m"))
    }

    private fun capture(): android.graphics.Bitmap {
        compose.waitForIdle()
        val view = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        return bitmap
    }

    /** The colour of the border of the box of a field, on its left edge halfway down the box (a border of one point at mdpi). */
    private fun borderOf(id: String): Int {
        val bounds = compose.onNodeWithTag("node:$id").fetchSemanticsNode().boundsInRoot
        return capture().getPixel(bounds.left.toInt(), (bounds.top + 20 + 23).toInt()) and 0xFFFFFF
    }

    private fun rgb(token: String): Int = com.hotlayout.HotlayoutThemeSet.builtIn.theme().colorValue(token)!!.argb() and 0xFFFFFF

    @Test fun aNonNumberLeftInTheFieldIsInTheErrorStateAfterLeavingAndTheFocusIsTheTokenOfFocus() {
        show(Screens.document("a" to field("n", "form.age", ""","valueType":"number"""") + "," + field("g", "form.other")))
        input("n").performTextInput("25 years")
        assertEquals("in focus: the colour of focus, no error yet", rgb("primary"), borderOf("n"))
        input("g").requestFocus()
        compose.waitForIdle()
        assertEquals("after leaving: the error colour", rgb("danger"), borderOf("n"))
        input("n").performTextReplacement("25")
        input("g").requestFocus()
        compose.waitForIdle()
        assertEquals("a number again: the border of the field", rgb("border"), borderOf("n"))
    }

    // Error, helper, parts

    private fun errorOf(id: String): Boolean = compose.onNodeWithTag("node:$id").fetchSemanticsNode().config.contains(SemanticsProperties.Error)

    @Test fun theErrorOfTheDataIsAnErrorStateAndASilentOneIsNone() {
        show(Screens.document("a" to field("e", "form.a", ""","error":${lit("Enter a valid email")}""") + "," + field("s", "form.b", ""","error":${lit("   ")}""")))
        assertTrue(errorOf("e"))
        assertFalse(errorOf("s"))
        compose.onNode(hasText("Enter a valid email"), useUnmergedTree = true).assertExists()
    }

    @Test fun theCounterAndTheMarksAreDrawn() {
        hostData = mapOf("form.name" to JsonValue.Str("Alex"))
        show(Screens.document("a" to field("f", "form.name", ""","counter":true,"maxLength":280,"suffix":{"kind":"text","text":${lit("123456789")}},"prefix":{"kind":"icon","icon":"mail"}""")))
        compose.onNode(hasText("4 / 280"), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("12345678"), useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("icon:mail"), useUnmergedTree = true).assertExists()
    }

    @Test fun aNamedHeightIsTheWholeBoxOfAOneLineField() {
        show(Screens.document("a" to field("f", "form.name", ""","fieldHeight":64""") + "," + field("g", "form.other")))
        val named = compose.onNodeWithTag("field:f", useUnmergedTree = true).fetchSemanticsNode()
        val plain = compose.onNodeWithTag("field:g", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the named box is taller than the natural one", named.boundsInRoot.top >= 0f)
        val namedBox = compose.onNodeWithTag("node:f").fetchSemanticsNode().boundsInRoot.height
        val plainBox = compose.onNodeWithTag("node:g").fetchSemanticsNode().boundsInRoot.height
        // The label (16) and the gap (4) around the box: 20 + 64 against 20 + 46 (`fieldBoxCases`).
        assertEquals(18.0, (namedBox - plainBox).toDouble(), 2.5)
        assertNotNull(plain)
    }

    // Switch and checkbox

    @Test fun aSwitchFlipsWritesAndSaysItsState() {
        show(Screens.document("a" to """{"id":"s","type":"Switch","props":{"label":${lit("Notify")},"value":${bind("form.notify")}}}"""))
        fun state() = compose.onNodeWithTag("node:s").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
        assertEquals(ToggleableState.Off, state())
        compose.onNodeWithTag("node:s").performClick()
        compose.waitForIdle()
        assertEquals(ToggleableState.On, state())
        compose.onNodeWithTag("node:s").performClick()
        compose.waitForIdle()
        assertEquals(listOf(JsonValue.Bool(true), JsonValue.Bool(false)), writes("form.notify"))
        assertEquals(ToggleableState.Off, state())
    }

    @Test fun aCheckboxFromTheDataIsOnAndADisabledOneDoesNotToggle() {
        hostData = mapOf("form.agreed" to JsonValue.Bool(true), "form.locked" to JsonValue.Bool(true))
        show(
            Screens.document(
                "a" to """{"id":"c","type":"Checkbox","props":{"label":${lit("I agree")},"value":${bind("form.agreed")}}},
                   {"id":"d","type":"Checkbox","props":{"label":${lit("Locked")},"value":${bind("form.agreed")},"disabledIf":{"path":"form.locked","op":"isTrue"}}}""",
            ),
        )
        assertEquals(ToggleableState.On, compose.onNodeWithTag("node:c").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        compose.onNodeWithTag("node:d").performClick()
        compose.waitForIdle()
        assertTrue(log.isEmpty())
    }

    // The form

    @Test fun aRequiredFieldEnablesTheButtonOnceItHoldsAnAnswer() {
        val button = """{"id":"go","type":"Button","props":{"label":${lit("Go")},"requiresFilled":true,"action":{"type":"none"}}}"""
        show(Screens.document("a" to field("f", "form.name", ""","required":true""") + "," + button))
        fun disabled() = compose.onNodeWithTag("node:go").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        assertTrue(disabled())
        input("f").performTextInput("Al")
        compose.waitForIdle()
        assertFalse(disabled())
        input("f").performTextClearance()
        compose.waitForIdle()
        assertTrue(disabled())
    }

    @Test fun aHiddenRequiredFieldIsNotCounted() {
        val button = """{"id":"go","type":"Button","props":{"label":${lit("Go")},"requiresFilled":true,"action":{"type":"none"}}}"""
        val hidden = field("f", "form.name", ""","required":true""").dropLast(1) + ""","visibleIf":{"path":"form.shown","op":"isTrue"}}"""
        show(Screens.document("a" to "$hidden,$button"))
        // The only required field is hidden by a condition that does not hold: the form is filled.
        assertFalse(compose.onNodeWithTag("node:go").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
        hostData = mapOf("form.shown" to JsonValue.Bool(true))
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("node:go").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
    }

    @Test fun theRadioRowsOfAChoiceGroupSelectWriteAndFillTheForm() {
        fun row(id: String, option: String) =
            """{"id":"$id","type":"ListTile","props":{"leading":{"kind":"none"},"title":${lit(option)},"trailing":{"kind":"radio","optionValue":"$option"}}}"""
        val group = """{"id":"group","type":"ChoiceGroup","props":{"value":${bind("survey.goal", "")},"required":true,"gap":"none"},"children":[${row("gold", "gold")},${row("silver", "silver")}]}"""
        val button = """{"id":"go","type":"Button","props":{"label":${lit("Go")},"requiresFilled":true,"action":{"type":"none"}}}"""
        show(Screens.document("a" to "$group,$button"))
        fun selected(id: String) = compose.onNodeWithTag("node:$id").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected)
        fun formDisabled() = compose.onNodeWithTag("node:go").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        assertEquals(false, selected("gold"))
        assertTrue(formDisabled())
        compose.onNodeWithTag("node:silver").performClick()
        compose.waitForIdle()
        assertEquals(str("silver"), writes("survey.goal"))
        assertEquals(true, selected("silver"))
        assertEquals(false, selected("gold"))
        assertFalse(formDisabled())
        // The same option again writes nothing: it is already the shown one.
        compose.onNodeWithTag("node:silver").performClick()
        compose.waitForIdle()
        assertEquals(str("silver"), writes("survey.goal"))
    }

    // The key of the keyboard

    @Test fun theNextKeyMovesTheFocusToTheNextFieldAndTheLastOneLetsGo() {
        show(Screens.document("a" to field("f", "form.a") + "," + field("g", "form.b")))
        input("f").requestFocus()
        input("f").performImeAction()
        compose.waitForIdle()
        input("g").assertIsFocused()
        input("f").assertIsNotFocused()
        input("g").performImeAction()
        compose.waitForIdle()
        input("g").assertIsNotFocused()
        // The key never performs an action: nothing was written by it.
        assertTrue(log.isEmpty())
    }

    // The fixtures and the documents of the table, in both directions

    private fun screen(node: JsonValue, index: Int): HotlayoutDocument = loadDocument(
        """{"schemaVersion":"9.27.0","documentId":"d$index","name":"n","initialScreenId":"a","screens":[{"screenId":"a","name":"A","root":{"id":"r","type":"Screen","props":{},"children":[${TableJson.text(node)}]}}]}""",
    )

    @Test fun theFixturesOfTheFormAreDrawnInBothDirections() {
        for (fixture in listOf("inputs-screens", "survey-screens")) {
            val document = loadDocument(Contract.bytes("fixtures/$fixture.json").decodeToString())
            for (code in listOf("en", "ar", "uk")) {
                locale = code
                show(document)
                assertNotNull("$fixture $code", compose.onNodeWithTag("screen:${document.initialScreenId}").fetchSemanticsNode())
            }
        }
    }

    @Test fun theDocumentsOfTheTableAreDrawnByTheRealScreenInBothDirections() {
        val table = Contract.json("fixtures/inputs/input-cases.json")
        var drawn = 0
        for (group in listOf("documentCases", "formDocumentCases", "partsDocumentCases")) {
            for (row in Contract.rows(table, group)) {
                if (group == "partsDocumentCases" && row["schema"]?.bool != true) continue
                val node = row["node"] ?: row["props"]?.let {
                    JsonValue.parse("""{"id":"f","type":"TextField","props":{"label":{"kind":"literal","value":"Field"},"value":{"kind":"binding","path":"form.name"}}}""").let { base ->
                        val props = (base["props"]?.obj ?: continue).let { p -> it.obj?.fold(p) { acc, (k, v) -> acc.with(k, v) } ?: p }
                        JsonValue.Obj(base.obj!!.with("props", JsonValue.Obj(props)))
                    }
                } ?: continue
                val document = runCatching { screen(node, drawn) }.getOrNull() ?: continue
                for (code in listOf("en", "ar")) {
                    locale = code
                    show(document)
                    assertNotNull(compose.onNodeWithTag("screen:a").fetchSemanticsNode())
                }
                drawn++
            }
        }
        assertTrue("drawn: $drawn", drawn > 40)
    }

    @Suppress("unused") private val keep = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
}
