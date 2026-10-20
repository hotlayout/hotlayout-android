package com.hotlayout.hardening

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hotlayout.HotlayoutScreen
import com.hotlayout.JsonValue
import com.hotlayout.support.Screens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accessible name of a pressable card is made from its content. What the screen does not show
 * — a child hidden by `visibleIf` or by `showOn` — must not be said: it may be exactly what the
 * condition hides (a balance behind «hide balance», a state that does not hold).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class AccessibleNameHardeningTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun lit(value: String) = Screens.literal(value)

    private fun nameOfCard(data: Map<String, JsonValue>, label: Boolean): String {
        val card = """{"id":"card","type":"Container","props":{"direction":"column","gap":"none",${if (label) """"label":${lit("Account")},""" else ""}"action":{"type":"open"}},"children":[
            ${Screens.text("shown", lit("Visible"))},
            ${Screens.text("hiddenByData", lit("Balance 9000"), ""","visibleIf":{"path":"account.balanceShown","op":"isTrue"}""")},
            ${Screens.text("hiddenByWidth", lit("Wide only"), ""","showOn":["medium","expanded"]""")},
            {"id":"box","type":"Container","props":{"direction":"column","gap":"none"},"visibleIf":{"path":"account.locked","op":"isTrue"},
             "children":[${Screens.text("nested", lit("Locked"))}]}]}"""
        compose.setContent { Box(Modifier.size(360.dp, 640.dp)) { HotlayoutScreen(Screens.document("a" to card), data = data) } }
        compose.waitForIdle()
        val config = compose.onNodeWithTag("node:card").fetchSemanticsNode().config
        val description = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
        val texts = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text }
        return "$description | $texts"
    }

    @Test fun childrenHiddenByAConditionOrByTheWidthAreNotSaid() {
        val name = nameOfCard(mapOf("account.balanceShown" to JsonValue.Bool(false), "account.locked" to JsonValue.Bool(false)), label = false)
        assertTrue(name, "Visible" in name)
        for (hidden in listOf("Balance 9000", "Wide only", "Locked")) assertFalse("«$hidden» is said: $name", hidden in name)
    }

    @Test fun alsoAfterTheLabel() {
        val name = nameOfCard(mapOf("account.balanceShown" to JsonValue.Bool(false)), label = true)
        assertTrue(name, name.startsWith("Account"))
        assertFalse(name, "Balance 9000" in name)
    }

    @Test fun aChildShownAgainIsSaidAgain() {
        val name = nameOfCard(mapOf("account.balanceShown" to JsonValue.Bool(true), "account.locked" to JsonValue.Bool(true)), label = false)
        assertEquals(name, true, "Balance 9000" in name && "Locked" in name)
    }
}
