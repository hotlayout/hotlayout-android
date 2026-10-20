package com.hotlayout.actions

import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.model.DocumentNode

/**
 * The write actions (`x-actions.write`): `setValue` puts a literal of the document under a data
 * path, `toggleValue` flips the flag under it. The reader performs both itself, through the same
 * write as a field's: into the session of the screen and to the host (`onWrite`) at the same
 * moment. Neither reaches `onAction`.
 *
 * Nothing here reads data except the CURRENT value under the node's own path (the caller passes the
 * lookup of the node's data scope). The path and the value are literals of the document:
 * `paramBindings` and `carryRecord` are not read.
 */
internal object WriteActions {
    internal const val SET_VALUE: String = "setValue"
    internal const val TOGGLE_VALUE: String = "toggleValue"
    private const val PATH_MAX_LENGTH = 128
    private val valueTypes = listOf("string", "boolean", "number")

    /** Why a write action does nothing (`x-actions.write.problems`). */
    internal enum class Problem(internal val word: String) {
        MissingPath("missing-path"),
        PathTooLong("path-too-long"),
        UnknownValueType("unknown-value-type"),
        NotAFlag("not-a-flag"),
        NotANumber("not-a-number"),
    }

    /** What a press of a write action does. */
    internal sealed class Step {
        /** Put the value under the path. */
        internal data class Write(val path: String, val value: JsonValue) : Step()

        /** The value is already there: nothing is written, the analytics event still goes. */
        internal data class Same(val path: String, val value: JsonValue) : Step()

        /** The action is not usable: nothing is written, nothing goes to the host. */
        internal data class Invalid(val problem: Problem) : Step()
    }

    internal fun isWriteType(type: String?): Boolean = type == SET_VALUE || type == TOGGLE_VALUE

    private fun param(action: JsonObject, name: String): String? = action["params"]?.get(name)?.string

    /** The path of a write action, or the reason there is none. Lengths count UTF-16 units. */
    internal fun path(action: JsonObject): Pair<String?, Problem?> {
        val path = param(action, "path")
        if (path.isNullOrEmpty()) return null to Problem.MissingPath
        if (path.length > PATH_MAX_LENGTH) return null to Problem.PathTooLong
        return path to null
    }

    /** The value a `setValue` writes, typed by `valueType`; the empty string for an absent or empty number. */
    internal fun literal(action: JsonObject): Pair<JsonValue?, Problem?> {
        val word = param(action, "valueType") ?: "string"
        if (word !in valueTypes) return null to Problem.UnknownValueType
        val literal = param(action, "value") ?: ""
        return when (word) {
            "string" -> JsonValue.Str(literal) to null
            "boolean" -> when (literal) {
                "true" -> JsonValue.Bool(true) to null
                "false" -> JsonValue.Bool(false) to null
                else -> null to Problem.NotAFlag
            }
            else -> {
                if (literal.isEmpty()) return JsonValue.Str("") to null
                val number = WriteNumber.parse(literal) ?: return null to Problem.NotANumber
                JsonValue.Num(number) to null
            }
        }
    }

    /**
     * Whether `current` already IS `value`: equality by type, no conversion. A number is read the
     * way a number field reads it (`7.0` is `7`), `"7"` is not `7`; the empty string equals an
     * absent value and `null`.
     */
    internal fun holdsWritten(current: JsonValue?, value: JsonValue): Boolean = when {
        value is JsonValue.Num -> WriteNumber.read(current) == value.value
        value is JsonValue.Bool -> current == value
        value is JsonValue.Str && value.value.isEmpty() -> current == JsonValue.Str("") || current == null || current == JsonValue.Null
        else -> current == value
    }

    /** What a press of [action] does with the data it reads through [read]; `null` when it is no write action. */
    internal fun step(action: JsonObject, read: (String) -> JsonValue?): Step? {
        if (!isWriteType(action["type"]?.string)) return null
        val (path, problem) = path(action)
        if (path == null) return Step.Invalid(problem ?: Problem.MissingPath)
        // A toggle writes the negation of what is SHOWN: on only for the flag `true`. It always
        // writes, and it does not read `value` or `valueType`.
        if (action["type"]?.string == TOGGLE_VALUE) return Step.Write(path, JsonValue.Bool(!flag(read(path))))
        val (value, valueProblem) = literal(action)
        if (value == null) return Step.Invalid(valueProblem ?: Problem.NotAFlag)
        return if (holdsWritten(read(path), value)) Step.Same(path, value) else Step.Write(path, value)
    }

    /** The flag of a layer value: on only for `true`. */
    internal fun flag(value: JsonValue?): Boolean = value?.bool == true

    // A node with a write action, as a control.

    internal enum class Role(internal val word: String) { Option("option"), Toggle("toggle") }

    internal data class Control(val role: Role, val path: String, val on: Boolean)

    /**
     * The control a press of [action] makes of its node, or `null` for a plain button: not a write
     * action, an invalid one, or a `setValue` that clears (an empty value is a command, not an option).
     */
    internal fun control(action: JsonObject, read: (String) -> JsonValue?): Control? {
        if (!isWriteType(action["type"]?.string)) return null
        val path = path(action).first ?: return null
        if (action["type"]?.string == TOGGLE_VALUE) return Control(Role.Toggle, path, flag(read(path)))
        val value = literal(action).first ?: return null
        if (value == JsonValue.Str("")) return null
        return Control(Role.Option, path, holdsWritten(read(path), value))
    }

    /** The path an option writes: a valid `setValue` with a non-empty value. */
    internal fun optionPath(action: JsonObject): String? {
        if (action["type"]?.string != SET_VALUE) return null
        val path = path(action).first ?: return null
        val value = literal(action).first ?: return null
        return path.takeIf { value != JsonValue.Str("") }
    }

    /** The types whose `action` is a press (a button, an icon button, a row, a pressable container). */
    internal val pressTypes: Set<String> = setOf("Button", "IconButton", "ListTile", "Column", "Row", "Container", "Stack")

    /** The action a node performs on a press; `null` for the rest. */
    internal fun pressAction(node: DocumentNode): JsonObject? =
        if (node.type in pressTypes) node.props["action"]?.obj else null

    /** The place of an option in its group: the position (from 1) and the size. */
    internal data class Place(val position: Int, val size: Int)

    /**
     * The place of [node] among its [siblings] (the children of ONE parent in document order): the
     * group is the SHOWN options that write the same path. One option, a node that is not an option
     * and an option under another parent have no place.
     */
    internal fun place(node: DocumentNode, siblings: List<DocumentNode>, isShown: (DocumentNode) -> Boolean = { true }): Place? {
        val path = pressAction(node)?.let(::optionPath) ?: return null
        val group = siblings.filter { sibling -> pressAction(sibling)?.let(::optionPath) == path && isShown(sibling) }
        val at = group.indexOfFirst { it.id == node.id }
        if (at < 0 || group.size < 2) return null
        return Place(at + 1, group.size)
    }

    /** Whether [children] (of ONE parent) hold a group of options: the parent is then a selectable group for the screen reader. */
    internal fun holdsOptionGroup(children: List<DocumentNode>, isShown: (DocumentNode) -> Boolean = { true }): Boolean =
        children.any { place(it, children, isShown) != null }
}

/** Number rules a write action shares with the number field: the typed form and the safe range. */
internal object WriteNumber {
    private val digitZeros = intArrayOf(0x30, 0x660, 0x6F0)
    private val separators = setOf(46, 44, 1643)
    private val minus = setOf(45, 8722)
    private val trimmed = setOf(32, 160, 8239, 9)
    private const val MAX_DIGITS = 15
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

    private fun digit(code: Int): Int? {
        for (zero in digitZeros) if (code in zero..zero + 9) return code - zero
        return null
    }

    /**
     * Typed text to a number, or `null` for «not a number»: edges trimmed; an optional minus;
     * digits; an optional single separator and digits; at least one digit; at most fifteen digits
     * in all. No groups, no exponent. Minus zero is zero.
     */
    fun parse(text: String): Double? {
        val chars = text.codePoints().toArray()
        var start = 0
        var end = chars.size
        while (start < end && chars[start] in trimmed) start++
        while (end > start && chars[end - 1] in trimmed) end--
        var negative = false
        if (start < end && chars[start] in minus) {
            negative = true
            start++
        }
        val whole = StringBuilder()
        val fraction = StringBuilder()
        var separated = false
        for (index in start until maxOf(start, end)) {
            val code = chars[index]
            val digit = digit(code)
            if (digit != null) {
                (if (separated) fraction else whole).append(digit)
                continue
            }
            if (!separated && code in separators) {
                separated = true
                continue
            }
            return null
        }
        val digits = whole.length + fraction.length
        if (digits == 0 || digits > MAX_DIGITS) return null
        val form = (if (whole.isEmpty()) "0" else whole.toString()) + (if (fraction.isEmpty()) "" else ".$fraction")
        val value = form.toDoubleOrNull() ?: return null
        if (value == 0.0) return 0.0
        return if (negative) -value else value
    }

    /** A number from a layer value: a finite number within the safe-integer range is itself; everything else is no number. */
    fun read(value: JsonValue?): Double? {
        val number = value?.number ?: return null
        if (Math.abs(number) > MAX_SAFE_INTEGER) return null
        return if (number == 0.0) 0.0 else number
    }
}
