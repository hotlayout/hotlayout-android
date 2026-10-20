package com.hotlayout.actions

import com.hotlayout.DataScope
import com.hotlayout.JsonValue
import com.hotlayout.data.Conditions
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.WidthClass
import com.hotlayout.model.WidthRules
import com.hotlayout.render.widgets.InputRules
import com.hotlayout.text.TextHeard

/**
 * The form of a screen: a «required» field, whether the form is filled, and a button that is
 * disabled until the form is filled or while a condition holds (`x-style.layout.form`).
 *
 * «Filled» is a question about the value UNDER the path of the field, read through the data
 * layers, not about the state of a widget: the field writes on every change, so the session and
 * the field say the same thing, and the answer is a function of the document and the layers.
 * A screen is the root of one `screens[]` entry; a modal layer is its own form.
 */
internal object ScreenForm {
    /** What «filled» asks about. A switch has no kind: it always has a value, so a «required switch» would require nothing. */
    enum class Kind(val word: String) { Text("text"), Number("number"), Flag("flag"), Choice("choice") }

    /** A required field of a screen: just enough to answer «is it filled and does it count». */
    class Field(
        val nodeId: String,
        val kind: Kind,
        /** `null`: a field without a path is never filled. */
        val path: String?,
        /** The `visibleIf` of the node and of all its ancestors; one that fails hides the field. */
        val visibleIf: List<JsonValue>,
        val disabledIf: JsonValue?,
        /** The fallback text of a choice group's binding: the option of that name is shown as selected. */
        val fallback: String? = null,
        /** The node or an ancestor is not listed for the width class (`showOn`): the field does not count. */
        val hiddenByClass: Boolean = false,
    )

    /**
     * The required fields of a screen in document order. The subtree of a list from data is not
     * included; a field in a tab that is not open IS included.
     */
    fun requiredFields(root: DocumentNode, widthClass: WidthClass = WidthClass.Compact): List<Field> {
        val found = ArrayList<Field>()
        fun walk(node: DocumentNode, inherited: List<JsonValue>, hidden: Boolean) {
            val own = node.visibleIf?.takeIf { !it.isNull }?.let { inherited + it } ?: inherited
            val off = hidden || !WidthRules.showsOn(node, widthClass)
            val required = InputRules.isLiteralTrue(node.props["required"])
            val path = InputRules.path(node.props["value"])
            when {
                node.type == "TextField" && required -> {
                    val numeric = InputRules.isNumber(node.props["valueType"])
                    found.add(Field(node.id, if (numeric) Kind.Number else Kind.Text, path, own, node.props["disabledIf"], hiddenByClass = off))
                }
                node.type == "Checkbox" && required -> found.add(Field(node.id, Kind.Flag, path, own, node.props["disabledIf"], hiddenByClass = off))
                node.type == "ChoiceGroup" && required ->
                    found.add(Field(node.id, Kind.Choice, path, own, null, InputRules.fallback(node.props["value"]), off))
            }
            if (node.type == "Repeater") return
            for (child in node.children) walk(child, own, off)
        }
        walk(root, emptyList(), false)
        return found
    }

    /** Whether a value counts as an answer for a field of this kind. */
    fun isFilled(kind: Kind, value: JsonValue?): Boolean = when (kind) {
        Kind.Text, Kind.Choice -> !TextHeard.isSilent(InputRules.text(value))
        Kind.Number -> InputRules.readNumber(value) != null
        Kind.Flag -> InputRules.flag(value)
    }

    /** Whether a value counts as an answer for a field of the kind named by the table (`text`, `number`, `flag`, `choice`). */
    fun isValueFilled(kind: String, value: JsonValue?): Boolean {
        val which = Kind.entries.firstOrNull { it.word == kind } ?: return false
        return isFilled(which, value)
    }

    /**
     * The value «filled» asks about. For a choice group an empty string and an absent value give the
     * binding's fallback text, which is exactly the option the group shows as selected.
     */
    private fun shownValue(field: Field, value: JsonValue?): JsonValue? {
        val fallback = field.fallback
        if (field.kind != Kind.Choice || fallback == null) return value
        return if (InputRules.text(value).isEmpty()) JsonValue.Str(fallback) else value
    }

    /** Whether the form of the screen is filled; hidden and disabled fields do not count. No required fields: filled. */
    fun isFilled(root: DocumentNode, scope: DataScope, widthClass: WidthClass = WidthClass.Compact): Boolean {
        for (field in requiredFields(root, widthClass)) {
            if (field.hiddenByClass) continue
            val shown = field.visibleIf.all { Conditions.isVisible(it, scope) }
            if (!shown || InputRules.isDisabled(field.disabledIf, scope)) continue
            val path = field.path ?: return false
            if (!isFilled(field.kind, shownValue(field, scope[path]))) return false
        }
        return true
    }
}

/** The text fields of a screen in document order, for the key of the keyboard («next» / «done»). */
internal object InputOrder {
    class Entry(val nodeId: String, val focusable: InputRules.Focusable)

    /**
     * The text fields of a screen: reachable means shown and not disabled. A field inside a list
     * from data is not part of the order.
     */
    fun fields(root: DocumentNode, scope: DataScope, widthClass: WidthClass = WidthClass.Compact): List<Entry> {
        val found = ArrayList<Entry>()
        fun walk(node: DocumentNode, visible: Boolean) {
            val shown = visible && WidthRules.isShown(node, widthClass, scope)
            if (node.type == "TextField") {
                val numeric = InputRules.isNumber(node.props["valueType"])
                val multiline = InputRules.effectiveLines(node.props["lines"], numeric) > 1
                found.add(Entry(node.id, InputRules.Focusable(multiline, shown && !InputRules.isDisabled(node.props["disabledIf"], scope))))
            }
            if (node.type == "Repeater") return
            for (child in node.children) walk(child, shown)
        }
        walk(root, true)
        return found
    }
}
