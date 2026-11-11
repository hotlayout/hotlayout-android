package com.hotlayout

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hotlayout.render.motion.ValueTransition
import com.hotlayout.support.Contract
import com.hotlayout.support.Screens
import com.hotlayout.support.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A text or a bar label whose value runs is written in figures of equal width ALWAYS (`value-transition/value-transition-cases.json`,
 * `figureCases`): at rest, between values and in the place that is held.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class TabularFiguresCasesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val table get() = Contract.json("fixtures/value-transition/value-transition-cases.json")

    @Test fun rows() {
        val rows = Contract.rows(table, "figureCases")
        assertEquals(8, rows.size)
        for (row in rows) {
            val tabular = ValueTransition.tabularFigures(row["type"]?.string ?: "", row["props"]?.obj ?: JsonObject())
            assertEquals(row.name(), row["expected"]?.get("tabular")?.bool, tabular)
        }
        // The label of a ring is tabular whatever its fields say: it is not asked here.
        assertFalse(ValueTransition.tabularFigures("Container", JsonValue.parse("""{"animateIn":true}""").obj!!))
    }

    /** The style a node was drawn with, read from the text layout its semantics hand out. */
    private fun drawnFeature(document: HotlayoutDocument, text: String): String? {
        compose.setContent { androidx.compose.foundation.layout.Box { HotlayoutScreen(document) } }
        compose.waitForIdle()
        val results = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style.fontFeatureSettings
    }

    private fun text(extra: String) = Screens.document("a" to """{"id":"t","type":"Text","props":{"content":{"kind":"literal","value":"1250"}$extra}}""")

    /** A text with a field is in equal figures at rest, whatever the clock says; a text without keeps the figures of its font. */
    @Test fun textWithAFieldIsInEqualFiguresAtRest() {
        assertEquals("tnum", drawnFeature(text(""","animateIn":true"""), "1250"))
    }

    @Test fun textWithAChangesFieldIsInEqualFiguresAtRest() {
        assertEquals("tnum", drawnFeature(text(""","animateChanges":true"""), "1250"))
    }

    @Test fun textWithNeitherFieldKeepsTheFiguresOfItsFont() {
        assertEquals(null, drawnFeature(text(""), "1250"))
    }

    private fun bar(extra: String) = Screens.document(
        "a" to """{"id":"b","type":"ProgressBar","props":{"value":{"kind":"literal","value":50},"max":{"kind":"literal","value":100},"color":"primary","labelFormat":"valueOfMax"$extra}}""",
    )

    @Test fun barLabelIsInEqualFiguresWhenItsValueRuns() {
        assertEquals("tnum", drawnFeature(bar(""","animateIn":true"""), "50 / 100"))
    }

    @Test fun barLabelOfAValueThatDoesNotRunKeepsTheFiguresOfItsFont() {
        assertEquals(null, drawnFeature(bar(""), "50 / 100"))
    }
}
