package com.hotlayout.render.widgets

import com.hotlayout.actions.WriteNumber
import com.hotlayout.data.Conditions
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonValue
import com.hotlayout.text.DecimalText
import com.hotlayout.text.ShownText
import com.hotlayout.text.TextHeard

/**
 * Input fields (`TextField`, `Switch`, `Checkbox`): everything the readers must do identically and
 * that could be read two ways. No views here, so the shared table runs against it directly
 * (`x-style.layout.inputs`, `x-style.layout.form.number`).
 *
 * There is no second write mechanism: the value of a field is a data path, reading goes through
 * the data layers, writing goes to the session of the reader and to the host at the same moment.
 */
internal object InputRules {
    // Value

    /** The path of a field: a `binding` with a non-empty string `path`; anything else means «no path». */
    fun path(value: JsonValue?): String? = boundPath(value)

    /** The field text of a layer value: a string as is, everything else is an empty field. */
    fun text(value: JsonValue?): String = value?.string ?: ""

    /** The flag of a layer value: on only for `true`. */
    fun flag(value: JsonValue?): Boolean = value?.bool == true

    /** `required` / `requiresFilled` count only as the literal `true`. */
    fun isLiteralTrue(value: JsonValue?): Boolean = value?.bool == true

    /** The `fallback` string of a binding. */
    fun fallback(value: JsonValue?): String? =
        if (value?.obj == null || value["kind"]?.string != "binding") null else value["fallback"]?.string

    // Limits

    const val MIN_LINES = 1
    const val MAX_LINES = 8

    private fun whole(value: JsonValue?): Int? {
        val number = value?.number ?: return null
        if (number != Math.rint(number) || Math.abs(number) >= 1e15) return null
        return number.toInt().takeIf { Math.abs(number) <= Int.MAX_VALUE } ?: Int.MAX_VALUE
    }

    /**
     * Cuts typed text to the limit in code points. A limit that is not a whole number above zero
     * means no limit. A surrogate pair is never split; a composed character may be.
     */
    fun clamp(text: String, maxLength: JsonValue?): String {
        val limit = whole(maxLength)?.takeIf { it >= 1 } ?: return text
        if (text.codePointCount(0, text.length) <= limit) return text
        return text.substring(0, text.offsetByCodePoints(0, limit))
    }

    /** How many lines the field has: a whole number within bounds is itself, otherwise one. */
    fun lines(value: JsonValue?): Int = whole(value)?.takeIf { it in MIN_LINES..MAX_LINES } ?: 1

    // Keyboard

    enum class Keyboard(val word: String) { Text("text"), Email("email"), Number("number"), Phone("phone"), Url("url") }

    /** The intent for a document word; absent or unknown is `text`. */
    fun keyboard(word: JsonValue?): Keyboard = Keyboard.entries.firstOrNull { it.word == word?.string } ?: Keyboard.Text

    /** The effective keyboard: a numeric field is always `number`, a multi-line field reads no intent (`null`). */
    fun effectiveKeyboard(word: JsonValue?, lines: JsonValue?, numeric: Boolean): Keyboard? {
        if (numeric) return Keyboard.Number
        return if (lines(lines) > 1) null else keyboard(word)
    }

    fun effectiveLines(lines: JsonValue?, numeric: Boolean): Int = if (numeric) 1 else lines(lines)

    fun isNumber(valueType: JsonValue?): Boolean = valueType?.string == "number"

    /** Whether the content is left to right whatever the screen says: email, phone, address and number. */
    fun contentIsLeftToRight(word: JsonValue?, lines: JsonValue?, numeric: Boolean): Boolean =
        effectiveKeyboard(word, lines, numeric).let { it != null && it != Keyboard.Text }

    // The action key of the keyboard

    enum class KeyAction(val word: String) { Newline("newline"), Next("next"), Done("done") }

    data class Focusable(val multiline: Boolean, val reachable: Boolean)

    fun keyAction(fields: List<Focusable>, index: Int): KeyAction {
        if (index !in fields.indices) return KeyAction.Done
        if (fields[index].multiline) return KeyAction.Newline
        return if (nextIndex(fields, index) == null) KeyAction.Done else KeyAction.Next
    }

    /** The next reachable field after this one; the search never goes backwards. */
    fun nextIndex(fields: List<Focusable>, after: Int): Int? {
        if (after + 1 >= fields.size) return null
        return ((after + 1) until fields.size).firstOrNull { fields[it].reachable }
    }

    // Error

    /** The error text that can be heard, in its shown form; `null` is no error. */
    fun error(text: String?): String? = if (text == null || TextHeard.isSilent(text)) null else ShownText.of(text)

    // Text field state

    /** What a text field remembers between events. */
    data class TextState(
        val focused: Boolean = false,
        /** The person touched the field (focus or typing); before that it writes nothing. */
        val touched: Boolean = false,
        /** A character is being composed (IME): intermediate text is not written. */
        val composing: Boolean = false,
        /** What the field showed when the composition started: the committed text is compared with it. */
        val composeFrom: String? = null,
        val held: Held? = null,
    )

    /**
     * Text typed by a person. [released]: the layer value changed since, so the typed text is shown
     * while focused and need not be held afterwards.
     */
    data class Held(val text: String, val from: String, val released: Boolean = false)

    sealed class Event {
        object Focus : Event()
        object Blur : Event()
        object CompositionStart : Event()
        data class Input(val text: String) : Event()
        data class CompositionEnd(val text: String) : Event()

        /** The platform filled a value without the person (autofill before the first touch). */
        data class PlatformFill(val text: String) : Event()
    }

    data class TextStep(val state: TextState, val write: String?)

    /** One step of a text field: the new state and, maybe, a write. */
    fun step(state: TextState, event: Event, bound: String, maxLength: JsonValue? = null, disabled: Boolean = false, hasPath: Boolean = true): TextStep {
        if (disabled) return TextStep(TextState(touched = state.touched), null)
        val shown = shownText(state, bound)

        fun accept(raw: String, against: String? = null): TextStep {
            val text = clamp(raw, maxLength)
            val next = state.copy(touched = true, composing = false, composeFrom = null, held = Held(text, bound))
            return TextStep(next, if (text == (against ?: shown) || !hasPath) null else text)
        }

        return when (event) {
            Event.Focus -> TextStep(state.copy(focused = true, touched = true, held = state.held ?: Held(shown, bound)), null)
            Event.Blur -> TextStep(
                state.copy(focused = false, composing = false, composeFrom = null, held = if (state.held?.released == true) null else state.held),
                null,
            )
            Event.CompositionStart -> TextStep(state.copy(composing = true, composeFrom = shown), null)
            is Event.Input ->
                if (state.composing) TextStep(state.copy(touched = true, held = Held(event.text, bound)), null) else accept(event.text)
            is Event.CompositionEnd -> accept(event.text, state.composeFrom)
            is Event.PlatformFill -> if (state.touched) accept(event.text) else TextStep(state, null)
        }
    }

    /** What the field shows: while focused, the typed text; without focus, the typed text until the layer value changes, then the layers. */
    fun shownText(state: TextState, bound: String): String {
        val held = state.held ?: return bound
        if (state.focused) return held.text
        return if (held.released || held.from != bound) bound else held.text
    }

    /**
     * The layer value CHANGED. Called as an event each time the value under the path becomes
     * different, not as a comparison at paint time: that could not tell «A, B, A» from «unchanged».
     */
    fun sync(state: TextState, bound: String): TextState {
        val held = state.held ?: return state
        if (held.released || held.from == bound) return state
        return if (state.focused) state.copy(held = held.copy(released = true)) else state.copy(held = null)
    }

    // Switch and checkbox state

    data class ToggleState(val heldOn: Boolean? = null, val heldFrom: Boolean? = null)

    fun shownToggle(state: ToggleState, bound: Boolean): Boolean {
        val on = state.heldOn ?: return bound
        return if (state.heldFrom == bound) on else bound
    }

    fun syncToggle(state: ToggleState, bound: Boolean): ToggleState =
        if (state.heldOn != null && state.heldFrom != bound) ToggleState() else state

    data class ToggleStep(val state: ToggleState, val write: Boolean?)

    /** A press: the value flips relative to the SHOWN one and is written at once. A disabled control neither changes nor writes. */
    fun pressToggle(state: ToggleState, bound: Boolean, disabled: Boolean = false, hasPath: Boolean = true): ToggleStep {
        if (disabled) return ToggleStep(state, null)
        val on = !shownToggle(state, bound)
        return ToggleStep(ToggleState(on, bound), if (hasPath) on else null)
    }

    // Numeric field

    /** Typed text to a number, or `null` for «not a number» (the rule of the contract, not `toDouble`). */
    fun parseNumber(text: String): Double? = WriteNumber.parse(text)

    /** A number from a layer value: a finite number within the safe-integer range is itself; everything else, even `"12"`, is none. */
    fun readNumber(value: JsonValue?): Double? = WriteNumber.read(value)

    /**
     * What a numeric field shows for a number from data: ASCII digits, a full stop, no groups and
     * no language; a whole number has no `.0`. A value that would need an exponent (magnitude
     * below 10^-6) and «no number» both give an empty field.
     */
    fun numberText(value: JsonValue?): String {
        val number = readNumber(value) ?: return ""
        if (number == Math.rint(number)) return number.toLong().toString()
        if (Math.abs(number) < 1e-6) return ""
        return (if (number < 0) "-" else "") + DecimalText.plain(number)
    }

    /** The number of typed text, or `""`, as it goes into the record. */
    fun writtenNumber(text: String): JsonValue = parseNumber(text)?.let(JsonValue::Num) ?: JsonValue.Str("")

    /**
     * What a numeric field shows. While focused, the typed text. Without focus, the typed text
     * until its number equals the layers' number; otherwise the layers' number. NUMBERS are
     * compared, not texts, so a numeric field needs no sync events.
     */
    fun shownNumber(state: TextState, bound: JsonValue?): String {
        val held = state.held ?: return numberText(bound)
        if (state.focused) return held.text
        val layers = readNumber(bound)?.let(JsonValue::Num) ?: JsonValue.Str("")
        return if (writtenNumber(held.text) == layers) held.text else numberText(bound)
    }

    /** A numeric field holding a non-number: in the error state when it is not focused and the shown text is non-empty and not a number. */
    fun numberInvalid(state: TextState, bound: JsonValue?): Boolean {
        if (state.focused) return false
        val shown = shownNumber(state, bound)
        if (shown.codePoints().allMatch { it in TRIMMED }) return false
        return parseNumber(shown) == null
    }

    private val TRIMMED = setOf(32, 160, 8239, 9)

    data class NumberStep(val state: TextState, val write: JsonValue?)

    /** One step of a numeric field, a mirror of [step]: a number is written when the typed text is one, `""` when erased or not a number. */
    fun stepNumber(state: TextState, event: Event, bound: JsonValue?, maxLength: JsonValue? = null, disabled: Boolean = false, hasPath: Boolean = true): NumberStep {
        if (disabled) return NumberStep(TextState(touched = state.touched), null)
        val shown = shownNumber(state, bound)

        fun accept(raw: String, against: String? = null): NumberStep {
            val text = clamp(raw, maxLength)
            val next = state.copy(touched = true, composing = false, composeFrom = null, held = Held(text, shown))
            val value = writtenNumber(text)
            return NumberStep(next, if (value == writtenNumber(against ?: shown) || !hasPath) null else value)
        }

        return when (event) {
            Event.Focus -> NumberStep(state.copy(focused = true, touched = true, held = state.held ?: Held(shown, shown)), null)
            Event.Blur -> NumberStep(state.copy(focused = false, composing = false), null)
            Event.CompositionStart -> NumberStep(state.copy(composing = true, composeFrom = shown), null)
            is Event.Input ->
                if (state.composing) NumberStep(state.copy(touched = true, held = Held(event.text, shown)), null) else accept(event.text)
            is Event.CompositionEnd -> accept(event.text, state.composeFrom)
            is Event.PlatformFill -> if (state.touched) accept(event.text) else NumberStep(state, null)
        }
    }

    // Disabled

    /** The opacity of a disabled field, button or control: one value, the whole widget. */
    const val DISABLED_OPACITY = 0.5

    /** Whether `disabledIf` holds. No condition means enabled; an operator the reader does not know means enabled. */
    fun isDisabled(condition: JsonValue?, scope: DataScope): Boolean = Conditions.understoodHolds(condition, scope)

    // Geometry (`x-style.layout.inputs`)

    const val FIELD_BORDER_WIDTH = 1.0
    const val SWITCH_TRACK_WIDTH = 52.0
    const val SWITCH_TRACK_HEIGHT = 32.0
    const val SWITCH_THUMB_SIZE = 24.0
    const val SWITCH_THUMB_INSET = 4.0
    const val CHECKBOX_BOX_SIZE = 20.0
    const val CHECKBOX_BOX_RADIUS = 4.0
    const val CHECKBOX_BORDER_WIDTH = 2.0
    const val CHECKBOX_MARK_SIZE = 16.0

    data class Metrics(val labelLine: Double, val textLine: Double, val noteLine: Double, val gap: Double, val paddingBlock: Double, val border: Double)

    fun boxHeight(metrics: Metrics, lines: JsonValue?): Double =
        2 * metrics.border + 2 * metrics.paddingBlock + lines(lines) * metrics.textLine

    fun fieldHeight(metrics: Metrics, lines: JsonValue?, labelLines: Int, noteLines: Int): Double {
        val label = labelLines * metrics.labelLine
        val note = if (noteLines == 0) 0.0 else metrics.gap + noteLines * metrics.noteLine
        return label + metrics.gap + boxHeight(metrics, lines) + note
    }

    fun switchHeight(labelLine: Double, labelLines: Int): Double = maxOf(SWITCH_TRACK_HEIGHT, labelLine * labelLines)

    fun switchLabelWidth(row: Double, gap: Double): Double = maxOf(0.0, row - SWITCH_TRACK_WIDTH - gap)

    fun checkboxHeight(labelLine: Double, labelLines: Int): Double = maxOf(CHECKBOX_BOX_SIZE, labelLine * labelLines)

    fun checkboxLabelWidth(row: Double, gap: Double): Double = maxOf(0.0, row - CHECKBOX_BOX_SIZE - gap)

    /** The offset of the checkbox square from the top: centred on the first line of the label; a shorter line leaves it at the top. */
    fun checkboxBoxOffset(labelLine: Double): Double = maxOf(0.0, (labelLine - CHECKBOX_BOX_SIZE) / 2)

    /** The distance of the thumb from the start of the track in the writing direction. */
    fun thumbOffset(on: Boolean): Double = if (on) SWITCH_TRACK_WIDTH - SWITCH_THUMB_INSET - SWITCH_THUMB_SIZE else SWITCH_THUMB_INSET
}

/**
 * The parts of a text field (`x-style.layout.inputParts`): the look of the field's own box, a mark
 * at each end of the box (`prefix`, `suffix`) and the character counter (`counter`). The `style`
 * of the node stays the box of the whole widget.
 */
internal object InputPartsRules {
    val BORDER_WIDTH_RANGE = 1..4
    val HEIGHT_RANGE = 32..120

    /** The longest text of a mark, in code points; the reader draws the first ones. */
    const val AFFIX_TEXT_MAX = 8
    const val COUNTER_SEPARATOR = " / "
    const val DEFAULT_RADIUS = "sm"
    const val DEFAULT_BACKGROUND = "surface"
    const val DEFAULT_BORDER_COLOR = "border"
    const val GAP_TOKEN = "sm"
    const val AFFIX_TYPOGRAPHY = "bodyM"
    const val COUNTER_TYPOGRAPHY = "bodyS"
    const val QUIET_COLOR = "muted"

    private fun whole(value: JsonValue?, range: IntRange): Int? {
        val number = value?.number ?: return null
        if (number != Math.rint(number) || Math.abs(number) >= 1e15) return null
        return number.toInt().takeIf { it in range }
    }

    /** The border the reader draws: a whole number from 1 to 4, else the built-in 1. */
    fun borderWidth(value: JsonValue?): Double = (whole(value, BORDER_WIDTH_RANGE) ?: 1).toDouble()

    /** The named height of a field of one line (a whole number from 32 to 120); else natural. */
    fun namedHeight(value: JsonValue?, lines: Int): Double? =
        if (lines != 1) null else whole(value, HEIGHT_RANGE)?.toDouble()

    /** The height of the box with its border: the named one, or two borders, two paddings and the lines. */
    fun boxHeight(textLine: Double, paddingBlock: Double, lines: Int, height: JsonValue?, borderWidth: JsonValue?): Double =
        namedHeight(height, lines) ?: (2 * borderWidth(borderWidth) + 2 * paddingBlock + lines * textLine)

    /** The text of a mark as drawn: the first code points up to the limit. */
    fun affixText(text: String): String = InputRules.clamp(text, JsonValue.Num(AFFIX_TEXT_MAX.toDouble()))

    sealed class Affix {
        data class Text(val value: JsonValue?) : Affix()
        data class Icon(val name: String) : Affix()
    }

    /** The mark a prop names: a text or an icon; any other form is no mark. */
    fun affix(value: JsonValue?): Affix? {
        if (value?.obj == null) return null
        return when (value["kind"]?.string) {
            "text" -> value["text"]?.let(Affix::Text)
            "icon" -> value["icon"]?.string?.let(Affix::Icon)
            else -> null
        }
    }

    data class AffixRow(val prefixStart: Double?, val textStart: Double, val textInline: Double, val suffixStart: Double?)

    /** The row inside the box: edge, mark, gap, text, gap, mark, edge. A mark never shrinks; the text takes the rest. */
    fun affixRow(boxInline: Double, border: Double, paddingInline: Double, gap: Double, prefix: Double?, suffix: Double?): AffixRow {
        val edge = border + paddingInline
        val before = prefix?.let { it + gap } ?: 0.0
        val after = suffix?.let { it + gap } ?: 0.0
        val textStart = edge + before
        val textInline = maxOf(0.0, boxInline - 2 * edge - before - after)
        return AffixRow(if (prefix == null) null else edge, textStart, textInline, if (suffix == null) null else textStart + textInline + gap)
    }

    /**
     * The counter `3 / 280`, or `null` when the word `counter` is not the literal `true` or there is
     * no limit (a whole number from 1). The count is code points of the SHOWN text, cut by the limit.
     */
    fun counterText(counter: JsonValue?, maxLength: JsonValue?, shown: String): String? {
        if (counter?.bool != true) return null
        val number = maxLength?.number ?: return null
        if (number != Math.rint(number) || number < 1 || number >= 1e15) return null
        val count = InputRules.clamp(shown, maxLength).let { it.codePointCount(0, it.length) }
        return "$count$COUNTER_SEPARATOR${number.toLong()}"
    }

    data class NoteRow(val noteInline: Double, val counterStart: Double?)

    /** The row under the box: the note from the start, the counter at the end. The counter does not shrink; the note takes the rest. */
    fun noteRow(rowInline: Double, gap: Double, counter: Double?): NoteRow =
        if (counter == null) NoteRow(rowInline, null) else NoteRow(maxOf(0.0, rowInline - gap - counter), maxOf(0.0, rowInline - counter))
}

/**
 * Turns what a text control reports (its text and whether part of it is still being composed, the
 * `composition` range of a Compose text value) into the events of [InputRules]: while a character
 * is composed the intermediate text is only kept, and the committed text arrives once as
 * [InputRules.Event.CompositionEnd].
 */
internal class CompositionTracker {
    var composing: Boolean = false
        private set

    /** The text of the control changed, or its composition did. */
    fun changed(text: String, marked: Boolean): List<InputRules.Event> = when {
        marked && !composing -> {
            composing = true
            listOf(InputRules.Event.CompositionStart, InputRules.Event.Input(text))
        }
        marked -> listOf(InputRules.Event.Input(text))
        composing -> {
            composing = false
            listOf(InputRules.Event.CompositionEnd(text))
        }
        else -> listOf(InputRules.Event.Input(text))
    }

    /** The control lost the focus: a composition still open is committed by the platform. */
    fun ended(text: String): List<InputRules.Event> {
        if (!composing) return emptyList()
        composing = false
        return listOf(InputRules.Event.CompositionEnd(text))
    }
}
