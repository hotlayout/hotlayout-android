package com.hotlayout.render.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hotlayout.json.JsonValue
import com.hotlayout.render.RenderContext
import com.hotlayout.render.StyledText
import com.hotlayout.render.WidgetCall
import com.hotlayout.render.color
import com.hotlayout.render.composeTextStyle
import com.hotlayout.text.BindableText
import com.hotlayout.text.ShownText

/**
 * `TextField`: a label above the box, the box with the field, a line under it (the error that can
 * be heard, else the helper). The value is read from the data layers and written on every change;
 * what the person types is held until the layer value changes ([InputRules]).
 *
 * The control is Compose's `BasicTextField`; the text value's `composition` range says that a
 * character is still being composed (IME): nothing is written until it is committed.
 */
@Composable
internal fun TextFieldWidget(call: WidgetCall) {
    val node = call.node
    val context = call.context
    val look = InputLook(context)
    val numeric = InputRules.isNumber(node.props["valueType"])
    val path = InputRules.path(node.props["value"])
    val bound = path?.let { context.scope[it] }
    val disabled = InputRules.isDisabled(node.props["disabledIf"], context.scope)
    val lines = InputRules.effectiveLines(node.props["lines"], numeric)

    val write = rememberUpdatedState({ value: JsonValue -> if (path != null) context.write?.invoke(path, value) })
    val driver = remember { InputFieldDriver { write.value(it) } }
    driver.numeric = numeric
    driver.bound = bound
    driver.maxLength = node.props["maxLength"]
    driver.disabled = disabled
    driver.hasPath = path != null
    val shown = driver.shown()
    val boundText = InputRules.text(bound)

    // The value of the layers changed: the held text is released (an event, so «A, B, A» is not «unchanged»).
    LaunchedEffect(if (numeric) null else boundText) { driver.layerChanged() }
    SideEffect { driver.follow(shown) }

    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val registry = context.form?.focus
    DisposableEffect(registry, node.id) {
        registry?.register(node.id, requester)
        onDispose { registry?.unregister(node.id, requester) }
    }
    LaunchedEffect(disabled) {
        if (disabled) {
            driver.disable()
            focusManager.clearFocus()
        }
    }

    val label = context.text(node, "label")
    val errorText = InputRules.error(if (node.props["error"] == null) null else context.text(node, "error"))
    val helper = if (node.props["helper"] == null) "" else context.text(node, "helper")
    val invalid = errorText != null || driver.invalid()
    val required = InputRules.isLiteralTrue(node.props["required"])
    val note = errorText ?: helper
    val counter = InputPartsRules.counterText(node.props["counter"], node.props["maxLength"], shown)
    val xs = look.spacing("xs", 4.0)

    Column(
        (if (call.placement.stretchWidth) Modifier.fillMaxWidth() else Modifier)
            .alpha(if (disabled) InputRules.DISABLED_OPACITY.toFloat() else 1f)
            .semantics(mergeDescendants = true) {
                testTag = "node:${node.id}"
                errorText?.let { error(it) }
                if (disabled) disabled()
            },
        verticalArrangement = Arrangement.spacedBy(xs.dp),
    ) {
        LabelRow(label, required, look, context)
        FieldBox(call, driver, requester, look, lines, numeric, invalid, disabled, shown)
        NoteRow(note, errorText != null, counter, look, context)
    }
}

@Composable
private fun LabelRow(label: String, required: Boolean, look: InputLook, context: RenderContext) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(look.spacing("xs", 4.0).dp)) {
        StyledText(label, look.label, look.color("muted"), context)
        if (required) Box(Modifier.clearAndSetSemantics {}) { StyledText("*", look.label, look.color("danger"), context) }
    }
}

/** The row under the box: the note (helper or error) from the start takes the rest; the counter stands at the end and does not shrink. */
@Composable
private fun NoteRow(note: String, isError: Boolean, counter: String?, look: InputLook, context: RenderContext) {
    if (note.isEmpty() && counter == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(look.spacing(InputPartsRules.GAP_TOKEN, 8.0).dp)) {
        if (note.isEmpty()) {
            Spacer(Modifier.weight(1f))
        } else {
            StyledText(note, look.label, look.color(if (isError) "danger" else "muted"), context, Modifier.weight(1f))
        }
        if (counter != null) StyledText(counter, look.counter, look.color(InputPartsRules.QUIET_COLOR), context, maxLines = 1)
    }
}

/** A mark of the field that is drawn: a text that is not empty, or an icon (a name outside the set is an empty box). */
private sealed class Mark {
    data class Text(val text: String) : Mark()
    data class Icon(val name: String) : Mark()
}

private fun markOf(context: RenderContext, node: com.hotlayout.model.DocumentNode, prop: String): Mark? =
    when (val affix = InputPartsRules.affix(node.props[prop])) {
        is InputPartsRules.Affix.Text -> {
            val text = InputPartsRules.affixText(
                ShownText.of(
                    BindableText.text(affix.value, context.language(node, "$prop.text"), context.textEnvironment, context.sink, "${context.location(node)}: $prop.text"),
                ),
            )
            if (text.isEmpty()) null else Mark.Text(text)
        }
        is InputPartsRules.Affix.Icon -> Mark.Icon(affix.name)
        null -> null
    }

@Composable
private fun MarkView(mark: Mark, look: InputLook, context: RenderContext, focus: () -> Unit, disabled: Boolean) {
    val body = look.body
    val color = look.color(InputPartsRules.QUIET_COLOR)
    // A press on a mark puts the focus in the field; a mark has no action of its own.
    val press = Modifier.pointerInput(disabled) { detectTapGestures { if (!disabled) focus() } }
    when (mark) {
        is Mark.Text -> StyledText(mark.text, body, color, context, press.height(body.lineBox.dp), maxLines = 1)
        is Mark.Icon -> com.hotlayout.render.widgets.IconGlyph(mark.name, body.fontSize, body.fontSize, body.lineBox, color.color, context.rightToLeft, press)
    }
}

@Composable
private fun FieldBox(
    call: WidgetCall,
    driver: InputFieldDriver,
    requester: FocusRequester,
    look: InputLook,
    lines: Int,
    numeric: Boolean,
    invalid: Boolean,
    disabled: Boolean,
    shown: String,
) {
    val node = call.node
    val context = call.context
    val resolver = context.resolver
    val md = look.spacing("md", 12.0)
    val body = look.body
    val shape = RoundedCornerShape((resolver.radius(node.props["fieldRadius"]) ?: look.radius).dp)
    val borderWidth = InputPartsRules.borderWidth(node.props["fieldBorderWidth"])
    val named = InputPartsRules.namedHeight(node.props["fieldHeight"], lines) != null
    val boxHeight = InputPartsRules.boxHeight(body.lineBox, md, lines, node.props["fieldHeight"], node.props["fieldBorderWidth"])
    val fill = resolver.color(node.props["fieldBackground"]) ?: look.color(InputPartsRules.DEFAULT_BACKGROUND)
    val ownBorder = resolver.color(node.props["fieldBorderColor"]) ?: look.color(InputPartsRules.DEFAULT_BORDER_COLOR)
    val focused = driver.state.focused
    val borderColor = if (invalid) look.color("danger") else if (focused) look.color("primary") else ownBorder
    val prefix = markOf(context, node, "prefix")
    val suffix = markOf(context, node, "suffix")
    val contentLtr = InputRules.contentIsLeftToRight(node.props["keyboard"], node.props["lines"], numeric)
    val keyboard = InputRules.effectiveKeyboard(node.props["keyboard"], node.props["lines"], numeric)
    val form = context.form
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val action = form?.keyAction(node.id, lines > 1) ?: if (lines > 1) InputRules.KeyAction.Newline else InputRules.KeyAction.Done
    val placeholder = if (node.props["placeholder"] == null) "" else context.text(node, "placeholder")
    val alignEnd = contentLtr && context.rightToLeft
    val textStyle = composeTextStyle(body, look.color("onSurface"), context, align = if (alignEnd) TextAlign.Right else TextAlign.Start)
        .let { if (contentLtr) it.copy(textDirection = TextDirection.Ltr) else it }
    val placeholderStyle = composeTextStyle(body, look.color("muted"), context, align = if (alignEnd) TextAlign.Right else TextAlign.Start)
        .let { if (contentLtr) it.copy(textDirection = TextDirection.Ltr) else it }
    val displayed = driver.value.let { if (it.text == shown) it else it.copy(text = shown) }
    val wasFocused = remember { booleanArrayOf(false) }

    val frame = Modifier
        .fillMaxWidth()
        .height(boxHeight.dp)
        .background(fill.color, shape)
        .border((if (focused && !invalid) borderWidth + 1 else borderWidth).dp, borderColor.color, shape)
        .padding(horizontal = (md + borderWidth).dp, vertical = if (named) 0.dp else (md + borderWidth).dp)
    Row(
        frame,
        horizontalArrangement = Arrangement.spacedBy(look.spacing(InputPartsRules.GAP_TOKEN, 8.0).dp),
        verticalAlignment = if (lines > 1) Alignment.Top else Alignment.CenterVertically,
    ) {
        if (prefix != null) MarkView(prefix, look, context, { requester.requestFocus() }, disabled)
        val fieldModifier = Modifier
            .weight(1f)
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused != wasFocused[0]) {
                    wasFocused[0] = it.isFocused
                    driver.focusChanged(it.isFocused)
                }
            }
            .semantics { testTag = "field:${node.id}" }
        androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides if (contentLtr) LayoutDirection.Ltr else LocalLayoutDirection.current) {
            BasicTextField(
                value = displayed,
                onValueChange = { driver.onValueChange(it, shown) },
                modifier = fieldModifier,
                enabled = !disabled,
                textStyle = textStyle,
                cursorBrush = SolidColor(look.color("primary").color),
                singleLine = lines == 1,
                minLines = lines,
                maxLines = lines,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (keyboard == InputRules.Keyboard.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                    autoCorrectEnabled = keyboard == null || keyboard == InputRules.Keyboard.Text,
                    keyboardType = when (keyboard) {
                        InputRules.Keyboard.Email -> KeyboardType.Email
                        InputRules.Keyboard.Number -> KeyboardType.Decimal
                        InputRules.Keyboard.Phone -> KeyboardType.Phone
                        InputRules.Keyboard.Url -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    },
                    imeAction = when (action) {
                        InputRules.KeyAction.Next -> ImeAction.Next
                        InputRules.KeyAction.Done -> ImeAction.Done
                        InputRules.KeyAction.Newline -> ImeAction.Default
                    },
                ),
                // The key of the keyboard never performs an action: «next» moves the focus, «done» lets go of it.
                keyboardActions = KeyboardActions(
                    onNext = { if (!(form?.nextAfter(node.id)?.let { id -> form.focus.request(id) } ?: false)) focusManager.clearFocus() },
                    onDone = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    },
                ),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (lines > 1) Alignment.TopStart else Alignment.CenterStart) {
                        if (shown.isEmpty() && placeholder.isNotEmpty()) {
                            androidx.compose.foundation.text.BasicText(placeholder, Modifier.fillMaxWidth().clearAndSetSemantics {}, style = placeholderStyle, maxLines = lines)
                        }
                        inner()
                    }
                },
            )
        }
        if (suffix != null) MarkView(suffix, look, context, { requester.requestFocus() }, disabled)
    }
}
