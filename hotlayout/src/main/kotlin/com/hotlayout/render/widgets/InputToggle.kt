package com.hotlayout.render.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.hotlayout.JsonValue
import com.hotlayout.render.RenderContext
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.render.dp
import com.hotlayout.render.interaction.pressInput

/**
 * `Switch` and `Checkbox`: one row, the whole row is the press area. The geometry and the colours
 * are data of the contract (`x-style.layout.inputs`), not the platform's control. The value is read
 * from the layers (`true` is on), a press flips the SHOWN value and writes it at once; the screen
 * reader gets a real toggle (`Role.Switch` / `Role.Checkbox` with its state).
 */
@Composable
internal fun SwitchWidget(call: WidgetCall) = ToggleRow(call, checkbox = false)

@Composable
internal fun CheckboxWidget(call: WidgetCall) = ToggleRow(call, checkbox = true)

@Composable
private fun ToggleRow(call: WidgetCall, checkbox: Boolean) {
    val node = call.node
    val context = call.context
    val look = InputLook(context)
    val path = InputRules.path(node.props["value"])
    val bound = InputRules.flag(path?.let { context.scope[it] })
    val disabled = InputRules.isDisabled(node.props["disabledIf"], context.scope)
    val label = context.text(node, "label")
    val required = checkbox && InputRules.isLiteralTrue(node.props["required"])

    var state by remember { mutableStateOf(InputRules.ToggleState()) }
    val on = InputRules.shownToggle(state, bound)
    // The value of the layers changed: what the person set is let go (an event, not a comparison at paint time).
    LaunchedEffect(bound) { state = InputRules.syncToggle(state, bound) }
    val press = rememberUpdatedState {
        val step = InputRules.pressToggle(state, bound, disabled, path != null)
        state = step.state
        val write = step.write
        if (write != null && path != null) context.write?.invoke(path, JsonValue.Bool(write))
    }

    val row = (if (call.placement.stretchWidth) Modifier.fillMaxWidth() else Modifier)
        .alpha(if (disabled) InputRules.DISABLED_OPACITY.toFloat() else 1f)
        .semantics(mergeDescendants = true) {
            testTag = "node:${node.id}"
            role = if (checkbox) Role.Checkbox else Role.Switch
            toggleableState = if (on) ToggleableState.On else ToggleableState.Off
            if (label.isNotEmpty()) contentDescription = label
            if (disabled) disabled()
            onClick { press.value(); true }
        }
        .pressInput(true, false, { press.value() }, {}) {}
    if (checkbox) CheckboxRow(row, look, label, on, required, context) else SwitchRow(row, look, label, on, context)
}

@Composable
private fun SwitchRow(modifier: Modifier, look: InputLook, label: String, on: Boolean, context: RenderContext) {
    val track = look.color(if (on) "primary" else "muted").color
    val thumb = look.color(if (on) "onPrimary" else "surface").color
    Row(
        modifier.heightIn(min = InputRules.SWITCH_TRACK_HEIGHT.dp),
        horizontalArrangement = Arrangement.spacedBy(look.spacing("md", 12.0).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StyledText(label, look.body, look.color("onSurface"), context, Modifier.weight(1f))
        Box(
            Modifier.size(InputRules.SWITCH_TRACK_WIDTH.dp, InputRules.SWITCH_TRACK_HEIGHT.dp).background(track, CircleShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            // `offset` follows the layout direction: the thumb is measured from the start of the track.
            Box(Modifier.offset(x = InputRules.thumbOffset(on).dp).size(InputRules.SWITCH_THUMB_SIZE.dp).background(thumb, CircleShape))
        }
    }
}

@Composable
private fun CheckboxRow(modifier: Modifier, look: InputLook, label: String, on: Boolean, required: Boolean, context: RenderContext) {
    val side = InputRules.CHECKBOX_BOX_SIZE
    val shape = RoundedCornerShape(InputRules.CHECKBOX_BOX_RADIUS.dp)
    val accent = look.color(if (on) "primary" else "muted").color
    Row(
        modifier.heightIn(min = side.dp),
        horizontalArrangement = Arrangement.spacedBy(look.spacing("sm", 8.0).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = InputRules.checkboxBoxOffset(look.body.lineBox).dp).size(side.dp)
                .then(if (on) Modifier.background(look.color("primary").color, shape) else Modifier)
                .border(InputRules.CHECKBOX_BORDER_WIDTH.dp, accent, shape),
            contentAlignment = Alignment.Center,
        ) {
            // The check mark is not mirrored in right-to-left text.
            if (on) IconGlyph("check", InputRules.CHECKBOX_MARK_SIZE, side, side, look.color("onPrimary").color, false)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(look.spacing("xs", 4.0).dp)) {
            StyledText(label, look.body, look.color("onSurface"), context, Modifier.weight(1f, fill = false))
            if (required) Box(Modifier.clearAndSetSemantics {}) { StyledText("*", look.label, look.color("danger"), context) }
        }
    }
}
