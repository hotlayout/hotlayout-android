package com.hotlayout

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.hotlayout.render.widgets.InputFieldDriver
import com.hotlayout.support.Contract
import com.hotlayout.support.Mismatch
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The glue between the text value of Compose and the rules: the rows of the text and number
 * groups, driven the way a keyboard drives a field — by values whose `composition` range says that a
 * character is still being composed. A test process has no IME that could produce such a value, so
 * this is where the composition path of the real field is proven.
 */
class InputFieldDriverTest {
    private companion object {
        const val COMPOSING_BLURRED = "composing cancelled by losing focus — the intermediate text is not written"
    }

    private val table get() = Contract.json("fixtures/inputs/input-cases.json")

    private fun visible(text: String) = Mismatch.visible(text)

    /** Plays the steps of a row on a driver the way the field's callbacks do; returns the writes and the driver. */
    private fun play(row: JsonObject, numeric: Boolean): Pair<List<JsonValue>, InputFieldDriver> {
        val writes = ArrayList<JsonValue>()
        val driver = InputFieldDriver { writes.add(it) }
        driver.numeric = numeric
        driver.bound = row["bound"]?.let { if (numeric) it else it }
        driver.maxLength = row["maxLength"]
        driver.disabled = row["disabled"]?.bool == true
        driver.hasPath = row["hasPath"]?.bool != false
        driver.follow(driver.shown())
        var composing = false
        for (step in row["steps"]?.array.orEmpty().mapNotNull { it.obj }) {
            when (step["type"]?.string) {
                "focus" -> driver.focusChanged(true)
                "blur" -> driver.focusChanged(false)
                "compositionStart" -> composing = true
                "input", "platformFill" -> {
                    val text = step["text"]?.string ?: ""
                    driver.onValueChange(TextFieldValue(text, TextRange(text.length), if (composing) TextRange(0, text.length) else null), driver.shown())
                }
                "compositionEnd" -> {
                    composing = false
                    val text = step["text"]?.string ?: ""
                    driver.onValueChange(TextFieldValue(text, TextRange(text.length)), driver.shown())
                }
                "data" -> {
                    driver.bound = step["bound"]
                    driver.layerChanged()
                }
            }
            driver.follow(driver.shown())
        }
        return writes to driver
    }

    @Test fun theTextRowsPlayedAsValuesOfTheComposeFieldGiveTheSameWritesAndText() {
        for (row in Contract.rows(table, "textCases")) {
            // A platform fill is a value of the field that arrived before any touch; the field reports it as a plain change.
            val (writes, driver) = play(row, numeric = false)
            val name = row["name"]?.string
            // The rule alone writes nothing when the focus is lost mid-composition; the field commits the open
            // composition at that moment (as the platform does), and the commit is the one write.
            val expected = if (name == COMPOSING_BLURRED) listOf<String?>("ka") else row["writes"]?.array.orEmpty().map { it.string?.let(::visible) }
            assertEquals("writes: $name", expected, writes.map { it.string?.let(::visible) })
            assertEquals("shown: $name", visible(row["shown"]?.string ?: ""), visible(driver.shown()))
            assertEquals("the field holds what it shows: $name", visible(driver.shown()), visible(driver.value.text))
        }
    }

    @Test fun theNumberRowsPlayedAsValuesOfTheComposeFieldGiveTheSameWritesAndText() {
        for (row in Contract.rows(table, "numberFieldCases")) {
            val (writes, driver) = play(row, numeric = true)
            val name = row["name"]?.string
            assertEquals("writes: $name", row["writes"]?.array.orEmpty(), writes)
            assertEquals("shown: $name", visible(row["shown"]?.string ?: ""), visible(driver.shown()))
        }
    }

    @Test fun aCharacterStillComposedIsNotWrittenUntilItIsCommittedAndAFocusLostCommitsIt() {
        val writes = ArrayList<JsonValue>()
        val driver = InputFieldDriver { writes.add(it) }
        driver.bound = JsonValue.Str("")
        driver.focusChanged(true)
        driver.onValueChange(TextFieldValue("k", TextRange(1), TextRange(0, 1)))
        driver.onValueChange(TextFieldValue("ka", TextRange(2), TextRange(0, 2)))
        assertEquals(emptyList<JsonValue>(), writes)
        assertEquals("ka", driver.shown())
        driver.onValueChange(TextFieldValue("か", TextRange(1)))
        assertEquals(listOf<JsonValue>(JsonValue.Str("か")), writes)
        // A new composition left open when the focus goes: the platform commits it, and the commit is written once.
        driver.bound = JsonValue.Str("か")
        driver.onValueChange(TextFieldValue("かk", TextRange(2), TextRange(1, 2)))
        driver.focusChanged(false)
        assertEquals(listOf<JsonValue>(JsonValue.Str("か"), JsonValue.Str("かk")), writes)
    }

    @Test fun aKeyboardThatCommitsTheTextItLastShowedWritesIt() {
        // The committed text is compared with the text before the composition, not with the last intermediate text.
        val writes = ArrayList<JsonValue>()
        val driver = InputFieldDriver { writes.add(it) }
        driver.bound = JsonValue.Str("")
        driver.focusChanged(true)
        driver.onValueChange(TextFieldValue("hello", TextRange(5), TextRange(0, 5)))
        driver.onValueChange(TextFieldValue("hello", TextRange(5)))
        assertEquals(listOf<JsonValue>(JsonValue.Str("hello")), writes)
    }

    @Test fun aMoveOfTheCursorIsNoEvent() {
        val writes = ArrayList<JsonValue>()
        val driver = InputFieldDriver { writes.add(it) }
        driver.bound = JsonValue.Str("Alex")
        driver.follow(driver.shown())
        driver.onValueChange(TextFieldValue("Alex", TextRange(1)))
        assertEquals(emptyList<JsonValue>(), writes)
        assertEquals(false, driver.state.touched)
    }
}
