package com.hotlayout.actions

import com.hotlayout.data.Conditions
import com.hotlayout.data.DataScope
import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonValue
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.ReportSink
import com.hotlayout.model.HotlayoutReport
import com.hotlayout.text.TextHeard

/** What a press hands over, in the order the contract states: the haptic request, the event, then the action. */
public object HapticRules {
    public val words: List<String> = listOf("light", "medium", "heavy", "selection", "success", "warning")
}

/**
 * Whether a press reaches the action of a control: a disabled control does nothing at all —
 * neither the action, nor the analytics event, nor the request for haptic feedback.
 */
public object PressRules {
    /** The haptic request a press hands to the host: the word of an enabled control's action. */
    public fun haptic(action: JsonObject, disabled: Boolean): String? = if (disabled) null else ActionCatalog.haptic(action)

    /** Opacity of a disabled control, whatever its role. */
    public const val DISABLED_OPACITY: Double = 0.5
}

/** What a press of a node with a write action does, in the order the host sees it. */
internal data class WritePressEffects(val haptic: String?, val track: TrackEvent?, val step: WriteActions.Step)

internal object WritePress {
    /**
     * The effects of a press of [action], or `null` when it is no write action (the screen goes on
     * with the flow). An action that is not usable writes nothing; the reason goes to the report and
     * the analytics event still goes (once per press).
     */
    fun effects(action: JsonObject, read: (String) -> JsonValue?, sink: ReportSink? = null, location: String = "action"): WritePressEffects? {
        val step = WriteActions.step(action, read) ?: return null
        if (step is WriteActions.Step.Invalid) {
            sink?.add(HotlayoutReport.Kind.IgnoredInput, location, "a write action that cannot be used (${step.problem.word}); nothing was written")
        }
        return WritePressEffects(ActionCatalog.haptic(action), ActionCatalog.track(action, sink, location), step)
    }

    /**
     * Whether a `Button` or an `IconButton` is disabled: `requiresFilled` on an unfilled form of
     * its screen, or a `disabledIf` that holds. Other nodes are never disabled.
     */
    fun isDisabled(node: DocumentNode, scope: DataScope, screenRoot: DocumentNode?, screenScope: DataScope): Boolean {
        if (node.type != "Button" && node.type != "IconButton") return false
        if (Conditions.understoodHolds(node.props["disabledIf"], scope)) return true
        return node.props["requiresFilled"]?.bool == true && screenRoot != null && !ScreenForm.isFilled(screenRoot, screenScope)
    }

    /** The nodes from the root to the node with this id (inclusive), or `null` when there is none. */
    fun path(id: String, node: DocumentNode): List<DocumentNode>? {
        if (node.id == id) return listOf(node)
        for (child in node.children) path(id, child)?.let { return listOf(node) + it }
        return null
    }

    /**
     * The node that acts when the node at the end of [path] is pressed: the nearest of itself and
     * its ancestors that has an action which acts (an inner button wins over the card around it).
     * A row that is an option of a choice group belongs to the choice, not to its action.
     */
    fun actor(path: List<DocumentNode>): DocumentNode? {
        for (node in path.asReversed()) {
            val action = WriteActions.pressAction(node) ?: continue
            if (!PressTarget.acts(action)) continue
            if (PressTarget.isChoiceOption(node)) continue
            return node
        }
        return null
    }
}

/**
 * The form of a screen: a «required» field and whether the form is filled
 * (`x-style.layout.form`). «Filled» is a question about the value UNDER the path of the field,
 * read through the data layers, not about the state of a widget.
 */
internal object ScreenForm {
    private enum class Kind { Text, Number, Flag, Choice }

    private class Field(val kind: Kind, val path: String?, val visibleIf: List<JsonValue>, val disabledIf: JsonValue?, val fallback: String?)

    private fun pathOf(value: JsonValue?): String? {
        if (value?.obj == null || value["kind"]?.string != "binding") return null
        return value["path"]?.string?.takeIf { it.isNotEmpty() }
    }

    private fun required(node: DocumentNode) = node.props["required"]?.bool == true

    private fun fields(root: DocumentNode): List<Field> {
        val found = ArrayList<Field>()
        fun walk(node: DocumentNode, inherited: List<JsonValue>) {
            val own = node.visibleIf?.takeIf { !it.isNull }?.let { inherited + it } ?: inherited
            val value = node.props["value"]
            when {
                node.type == "TextField" && required(node) -> {
                    val numeric = node.props["valueType"]?.string == "number"
                    found.add(Field(if (numeric) Kind.Number else Kind.Text, pathOf(value), own, node.props["disabledIf"], null))
                }
                node.type == "Checkbox" && required(node) -> found.add(Field(Kind.Flag, pathOf(value), own, node.props["disabledIf"], null))
                node.type == "ChoiceGroup" && required(node) -> {
                    val fallback = value?.takeIf { it["kind"]?.string == "binding" }?.get("fallback")?.string
                    found.add(Field(Kind.Choice, pathOf(value), own, null, fallback))
                }
            }
            if (node.type == "Repeater") return
            for (child in node.children) walk(child, own)
        }
        walk(root, emptyList())
        return found
    }

    private fun filled(field: Field, value: JsonValue?): Boolean = when (field.kind) {
        Kind.Text -> !TextHeard.isSilent(value?.string ?: "")
        Kind.Choice -> {
            val text = value?.string ?: ""
            !TextHeard.isSilent(if (text.isEmpty()) field.fallback ?: "" else text)
        }
        Kind.Number -> WriteNumber.read(value) != null
        Kind.Flag -> value?.bool == true
    }

    /** Whether a value counts as an answer for a field of this kind (`text`, `number`, `flag`, `choice`). */
    fun isValueFilled(kind: String, value: JsonValue?): Boolean {
        val which = Kind.entries.firstOrNull { it.name.lowercase() == kind } ?: return false
        return filled(Field(which, null, emptyList(), null, null), value)
    }

    /** Whether the form of the screen is filled; hidden and disabled fields do not count. No required fields: filled. */
    fun isFilled(root: DocumentNode, scope: DataScope): Boolean {
        for (field in fields(root)) {
            val shown = field.visibleIf.all { Conditions.isVisible(it, scope) }
            if (!shown || Conditions.understoodHolds(field.disabledIf, scope)) continue
            val path = field.path ?: return false
            if (!filled(field, scope[path])) return false
        }
        return true
    }
}

/** What a choice in a group writes: the value at the binding's path; a group whose value is not a binding writes nowhere. */
public object ChoiceRules {
    public fun write(path: String?, picked: String, shown: String?): Pair<String, String>? =
        if (path == null || picked == shown) null else path to picked
}
