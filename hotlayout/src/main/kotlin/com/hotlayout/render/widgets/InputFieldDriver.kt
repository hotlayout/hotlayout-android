package com.hotlayout.render.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.hotlayout.JsonValue

/**
 * The state of one text field and what its events do: the glue between the text value of Compose
 * (its `composition` range says that a character is still being composed) and the rules of
 * [InputRules]. It has no views, so a test can drive it with composing text that no keyboard of the
 * test process could produce.
 *
 * What the widget sets on every composition: [bound] (the value of the layers under the path),
 * [disabled], [maxLength], [hasPath], [numeric]. Writes leave through [write].
 */
internal class InputFieldDriver(private val write: (JsonValue) -> Unit) {
    var numeric: Boolean = false
    var bound: JsonValue? = null
    var maxLength: JsonValue? = null
    var disabled: Boolean = false
    var hasPath: Boolean = true

    /** The rules' state: focus, touch, composition, what the person typed. */
    var state by mutableStateOf(InputRules.TextState())
        private set

    /** What the Compose field holds (the selection and the composition range live here). */
    var value by mutableStateOf(TextFieldValue(""))

    private val tracker = CompositionTracker()

    private val boundText: String get() = InputRules.text(bound)

    /** What the field shows now. */
    fun shown(): String = if (numeric) InputRules.shownNumber(state, bound) else InputRules.shownText(state, boundText)

    /** A numeric field holding a non-number, not in focus: in the error state. */
    fun invalid(): Boolean = numeric && InputRules.numberInvalid(state, bound)

    /** The value under the path CHANGED (an event, not a comparison at paint time). */
    fun layerChanged() {
        if (!numeric) state = InputRules.sync(state, boundText)
    }

    private fun apply(event: InputRules.Event) {
        if (numeric) {
            val step = InputRules.stepNumber(state, event, bound, maxLength, disabled, hasPath)
            state = step.state
            step.write?.let(write)
        } else {
            val step = InputRules.step(state, event, boundText, maxLength, disabled, hasPath)
            state = step.state
            step.write?.let { write(JsonValue.Str(it)) }
        }
    }

    /** The field took or lost the focus. A composition still open when the focus is lost is committed by the platform. */
    fun focusChanged(focused: Boolean) {
        if (focused == state.focused) return
        if (focused) {
            apply(InputRules.Event.Focus)
        } else {
            tracker.ended(value.text).forEach(::apply)
            apply(InputRules.Event.Blur)
            if (value.composition != null) value = value.copy(composition = null)
        }
        reflect(value)
    }

    /** The disabled field gives up the focus and what it held. */
    fun disable() {
        apply(InputRules.Event.Blur)
    }

    /**
     * The Compose field reports a new value. A report that changes neither the text nor whether it
     * is composed (the cursor moved) is no event; [displayed] is the text the field showed.
     */
    fun onValueChange(new: TextFieldValue, displayed: String = value.text) {
        val marked = new.composition != null
        if (new.text == displayed && marked == tracker.composing) {
            value = new
            return
        }
        for (event in tracker.changed(new.text, marked)) {
            // Text that arrives before the person touched the field is filled in by the platform:
            // it is neither kept nor written.
            val platform = event is InputRules.Event.Input && !state.composing && !state.touched && !state.focused
            apply(if (platform) InputRules.Event.PlatformFill((event as InputRules.Event.Input).text) else event)
        }
        reflect(new)
    }

    /** Puts what the rules show into the Compose field, keeping the cursor and the composition while the text is the same. */
    private fun reflect(candidate: TextFieldValue) {
        val text = shown()
        value = if (candidate.text == text) candidate else TextFieldValue(text, TextRange(minOf(candidate.selection.end, text.length)))
    }

    /** The shown text is not what the field holds (the layers changed, or a limit cut the text): the field follows. */
    fun follow(shown: String) {
        if (value.text != shown) value = TextFieldValue(shown, TextRange(shown.length))
    }
}
