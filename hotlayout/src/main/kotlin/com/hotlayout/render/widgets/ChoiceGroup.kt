package com.hotlayout.render.widgets

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import com.hotlayout.actions.ChoiceRules
import com.hotlayout.json.JsonValue
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.layout.ColumnWidget
import com.hotlayout.text.BindableText

/** The state a `ChoiceGroup` hands to the rows inside it: which option is selected and how to pick one. */
internal class ChoiceScope(val selected: String?, val select: (String) -> Unit)

/** The path a bound value reads and writes, or `null` for a literal (a literal has nowhere to write). */
internal fun boundPath(value: JsonValue?): String? {
    if (value?.obj == null || value["kind"]?.string != "binding") return null
    return value["path"]?.string?.takeIf { it.isNotEmpty() }
}

/**
 * `ChoiceGroup`: the rows inside it that carry a `radio` slot choose one option. The group owns the
 * state; a group with a BOUND `value` also writes the choice under that path (to the session and the
 * host at the same moment). A literal `value` has nowhere to write: the tap stays local.
 */
@Composable
internal fun ChoiceGroupWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    // The name of the option the document says is selected: not translated, not trimmed — it is
    // compared with the `optionValue` of a row, not shown to a person.
    val document = BindableText.text(
        node.props["value"], context.language(node, "value"), context.textEnvironment, context.sink,
        "screen “${context.screenId}”: node “${node.id}”: value",
    )
    val path = boundPath(node.props["value"])
    var selected by remember(node.id) { mutableStateOf<String?>(null) }
    var seen by remember(node.id) { mutableStateOf<String?>(null) }
    val shown: String? = if (seen == document) selected else document.ifEmpty { null }
    val scope = ChoiceScope(shown) { option ->
        selected = option
        seen = document
        // The write goes after the local state. An option already SHOWN as selected (also by the
        // fallback text of the binding) is not written a second time.
        ChoiceRules.write(path, option, shown)?.let { (target, value) -> context.write?.invoke(target, JsonValue.Str(value)) }
    }
    val gap = context.resolver.spacing(node.props["gap"] ?: JsonValue.Str("none")) ?: 0.0
    Box(Modifier.selectableGroup()) {
        ColumnWidget(node.children, gap, null, context.copy(choice = scope), call.depth, call.placement.stretchWidth)
    }
}
