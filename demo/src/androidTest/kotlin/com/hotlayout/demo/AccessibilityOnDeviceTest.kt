package com.hotlayout.demo

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What TalkBack is handed: the merged semantics tree of the real screen on a device. */
@RunWith(AndroidJUnit4::class)
class AccessibilityOnDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val buttons = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun SemanticsNode.name(): String =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
            .joinToString(" ").trim()

    /** Every button and icon button has a name; the required «Next» is disabled and still reachable. */
    @Test
    fun buttonsAreNamedAndDisabledIsSaid() {
        rule.setContent { FixtureHost(fixture("buttons-chips-screens"), Recorder(), "buttons-chips-screens") }
        val nodes = rule.onAllNodes(buttons).fetchSemanticsNodes()
        assertTrue(nodes.size > 5)
        for (node in nodes) assertTrue("a button without a name: ${node.config}", node.name().isNotEmpty())
    }

    @Test
    fun requiredButtonIsDisabled() {
        rule.setContent { FixtureHost(fixture("survey-screens"), Recorder(), "survey-screens", data = emptyMap()) }
        rule.onNodeWithText("Next").assertIsNotEnabled()
    }

    /** `survey-screens` / `goal`: the options are radio buttons, not selected, in a group TalkBack can count. */
    @Test
    fun optionsAreRadioButtonsInAGroup() {
        rule.setContent { FixtureHost(fixture("survey-screens"), Recorder(), "survey-screens", data = emptyMap()) }
        val radios = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).fetchSemanticsNodes()
        assertEquals(3, radios.size)
        for (radio in radios) assertEquals(false, radio.config.getOrNull(SemanticsProperties.Selected))
        rule.onNodeWithText("Savings").performClick()
        val chosen = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes()
        assertEquals(listOf("Savings"), chosen.map { it.config[SemanticsProperties.Text].first().text })
    }

    /**
     * `choice-controls-screens` / `wallet`: «Week / Month / Planned» are options of a write action. Each says it is a
     * radio button and its place, but no ancestor is a collection or a selectable group, so TalkBack cannot say
     * «1 of 3» (a `CollectionItemInfo` is only read inside a `CollectionInfo`).
     */
    @Test
    fun writeOptionsAreAGroupTalkBackCanCount() {
        rule.setContent { FixtureHost(fixture("choice-controls-screens"), Recorder(), "choice-controls-screens") }
        val week = rule.onNodeWithText("Week").fetchSemanticsNode()
        assertEquals(Role.RadioButton, week.config.getOrNull(SemanticsProperties.Role))
        assertNotNull(week.config.getOrNull(SemanticsProperties.CollectionItemInfo))
        var parent = week.parent
        var grouped = false
        while (parent != null) {
            if (parent.config.getOrNull(SemanticsProperties.CollectionInfo) != null || parent.config.contains(SemanticsProperties.SelectableGroup)) grouped = true
            parent = parent.parent
        }
        assertTrue("no CollectionInfo / selectableGroup above the options", grouped)
    }

    /** The card with a long press offers it to TalkBack under its own name. */
    @Test
    fun longPressIsOfferedUnderItsName() {
        rule.setContent { FixtureHost(fixture("longpress-groups-sheet-screens"), Recorder(), "longpress-groups-sheet-screens") }
        val card = rule.onNodeWithText("Hold for options").fetchSemanticsNode()
        val action = card.config.getOrNull(SemanticsActions.OnLongClick)
        assertEquals("Mission options", action?.label)
    }

    /** Carousel cards are items of one row collection. */
    @Test
    fun carouselIsACollection() {
        rule.setContent { FixtureHost(fixture("carousel-steps-screens"), Recorder(), "carousel-steps-screens") }
        val card = rule.onNodeWithText("Spend 500 with your card", useUnmergedTree = true).fetchSemanticsNode()
        var node: SemanticsNode? = card
        var info = false
        while (node != null) {
            if (node.config.getOrNull(SemanticsProperties.CollectionInfo) != null) info = true
            node = node.parent
        }
        assertTrue(info)
    }

    /** An empty `alt` hides a picture from TalkBack only: it is still drawn, with its box. */
    @Test
    fun emptyAltHidesFromAccessibilityOnly() {
        val document = hostile(
            """{"schemaVersion":"9.27.0","documentId":"alt","initialScreenId":"s","screens":[{"screenId":"s","name":"S","root":
            {"id":"r","type":"Screen","props":{"gap":"md"},"children":[
              {"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"Above"}}},
              {"id":"i","type":"Image","props":{"source":{"kind":"asset","asset":"banner"},"alt":"","aspectRatio":"16:9","fit":"cover"}},
              {"id":"u","type":"Text","props":{"content":{"kind":"literal","value":"Below"}}}
            ]}}]}""",
        )
        rule.setContent { FixtureHost(document, Recorder()) }
        val above = rule.onNodeWithText("Above").getBoundsInRoot()
        val below = rule.onNodeWithText("Below").getBoundsInRoot()
        assertTrue("the picture took no room", (below.top - above.bottom).value > 150f)
        assertFalse(rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)).fetchSemanticsNodes().isNotEmpty())
    }

    /** A swipe-to-confirm button is an ordinary button for TalkBack: its click runs the action at once. */
    @Test
    fun swipeConfirmIsAPlainButtonForTalkBack() {
        val recorder = Recorder()
        rule.setContent { FixtureHost(fixture("float-nav-swipe-screens", "missions"), recorder, "float-nav-swipe-screens") }
        rule.onNodeWithText("Swipe to open the week").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("redeem", recorder.shown.last().screenId)
    }

    /**
     * `inputs-screens` / `profile`: with `autofill = true` (the default) the email and phone fields hand the platform
     * their autofill hint (`x-style.layout.inputs` keyboards: email → `emailAddress`, phone → `telephoneNumber`,
     * as the Flutter reader's `autofillHints`). No field carries a content type, so autofill offers nothing.
     */
    @Test
    fun emailAndPhoneFieldsCarryAutofillHints() {
        rule.setContent { FixtureHost(fixture("inputs-screens"), Recorder(), "inputs-screens") }
        for (id in listOf("email", "phone")) {
            val node = rule.onNodeWithTag("field:$id", useUnmergedTree = true).fetchSemanticsNode()
            var found = false
            var current: SemanticsNode? = node
            while (current != null && !found) {
                found = current.config.getOrNull(SemanticsProperties.ContentType) != null
                current = current.parent
            }
            if (!found) found = node.children.any { it.config.getOrNull(SemanticsProperties.ContentType) != null }
            assertTrue("field $id has no autofill content type", found)
        }
    }
}
