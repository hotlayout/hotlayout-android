package com.hotlayout.render.interaction

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.hotlayout.JsonObject
import com.hotlayout.actions.LongPressRules
import com.hotlayout.actions.PressTarget
import com.hotlayout.actions.SilentText
import com.hotlayout.theme.NodeStyle
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext
import com.hotlayout.render.dp
import com.hotlayout.text.ShownText
import com.hotlayout.text.TextHeard
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A container (or a list row) that is a button as a whole: the press of `action`, the long press
 * of `longPress`, the pose of its feedback, and one element for assistive technology named by
 * `label` first and then by its content.
 *
 * A control inside takes the touch first (its own gesture, deeper in the tree): the card then gets
 * nothing, neither the action nor the analytics event.
 */
@Composable
internal fun Modifier.pressableCard(node: DocumentNode, context: RenderContext, press: JsonObject?, long: JsonObject?): Modifier {
    if (press == null && long == null) return this
    val runPress = rememberUpdatedState({ press?.let { context.press(it, node) } })
    val runLong = rememberUpdatedState({ long?.let { context.press(it, node) } })
    var pressing by remember { mutableStateOf(false) }
    val name = if (node.type == "ListTile") {
        listOf("title", "subtitle").filter { node.props[it] != null }.map { ShownText.of(context.text(node, it)) }.filter { it.isNotEmpty() }.joinToString(", ")
    } else {
        PressTarget.name(node, { target, field -> context.text(target, field) }, { context.isShown(it) }) { chain -> paintOf(chain, context) }
    }
    val longLabel = if (long != null) ShownText.of(context.text(node, "longPressLabel")) else ""
    val hasPress = press != null
    val hasLong = long != null
    val separate = PressTarget.containsControl(node)
    val control = rememberWriteControl(node, context, press, LocalSiblings.current)
    return this
        .pressPose(PressFeedbackRules.kind(node), pressing)
        .pressInput(hasPress, hasLong, { runPress.value() }, { runLong.value() }) { pressing = it }
        .semantics(mergeDescendants = !separate) {
            role = Role.Button
            controlSemantics(node, control)
            if (name.isNotEmpty()) contentDescription = name
            if (hasPress) onClick { runPress.value(); true }
            if (hasLong) {
                if (TextHeard.isSilent(longLabel)) {
                    onLongClick { runLong.value(); true }
                } else {
                    onLongClick(longLabel) { runLong.value(); true }
                    customActions = listOf(CustomAccessibilityAction(longLabel) { runLong.value(); true })
                }
            }
        }
}

/**
 * The input of a pressable node, one for cards and buttons: a tap acts, a hold of 450 ms inside a
 * slop of 10 performs the long press once (the release after it is swallowed), Enter and Space
 * press at once, the context menu key and Shift+F10 perform the long press at once; a held key
 * repeats nothing (`keyAutoRepeat`).
 */
@Composable
internal fun Modifier.pressInput(hasPress: Boolean, hasLong: Boolean, press: () -> Unit, long: () -> Unit, onPressing: (Boolean) -> Unit): Modifier {
    // The gesture is not restarted when the document changes: it always calls the latest lambdas.
    val pressNow = rememberUpdatedState(press)
    val longNow = rememberUpdatedState(long)
    val pressingNow = rememberUpdatedState(onPressing)
    return this
    .cardGestures(hasPress, hasLong, { pressNow.value() }, { longNow.value() }) { pressingNow.value(it) }
    .focusable()
    .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val repeating = event.nativeKeyEvent.repeatCount > 0
        when {
            hasPress && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar) -> {
                if (!repeating) pressNow.value()
                true
            }
            hasLong && LongPressRules.isLongPressKey(keyName(event.key), event.isShiftPressed) -> {
                if (LongPressRules.keyPerforms(keyName(event.key), event.isShiftPressed, repeating)) longNow.value()
                true
            }
            else -> false
        }
    }
}

private fun keyName(key: Key): String = when (key) {
    Key.Menu -> "ContextMenu"
    Key.F10 -> "F10"
    else -> ""
}

/**
 * The touch of a card: a tap acts, a hold of 450 ms inside a slop of 10 performs the long press
 * once, and the release that follows it is swallowed (it is the same touch).
 */
private fun Modifier.cardGestures(hasPress: Boolean, hasLong: Boolean, press: () -> Unit, long: () -> Unit, onPressing: (Boolean) -> Unit): Modifier =
    pointerInput(hasPress, hasLong) {
        val slop = LongPressRules.TOUCH_SLOP.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            // The innermost control takes the touch: whoever sees it first in the main pass owns it.
            down.consume()
            onPressing(true)
            var fired = false
            var cancelled = false
            val result = withTimeoutOrNull(if (hasLong) LongPressRules.THRESHOLD_MS.toLong() else Long.MAX_VALUE) {
                var verdict: Release? = null
                while (verdict == null) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    verdict = when {
                        change == null -> Release.Cancelled
                        change.changedToUp() -> Release.Up
                        (change.position - down.position).getDistance() > slop -> Release.Cancelled
                        else -> null
                    }
                }
                verdict
            }
            when (result) {
                null -> {
                    fired = true
                    long()
                }
                Release.Up -> if (hasPress) press()
                Release.Cancelled -> cancelled = true
            }
            if (fired) {
                // The rest of the touch belongs to the gesture that fired: nothing else acts on it.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.all { !it.pressed }) break
                }
            } else if (cancelled) {
                // A drag that left the slop: wait for the end of the touch without acting.
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.all { !it.pressed }) break
                }
            }
            onPressing(false)
        }
    }

private enum class Release { Up, Cancelled }

/** The paint of a text from the resolved style of the nodes between the card and it (the card's own look is already in the context). */
private fun paintOf(chain: List<DocumentNode>, context: RenderContext): SilentText.Paint {
    var look = context.inherited
    val opacities = chain.map { node ->
        val style = NodeStyle.effective(node, context.scope)
        look = NodeStyle.inherited(look, style, context.resolver)
        style["opacity"]?.number?.coerceIn(0.0, 1.0)
    }
    return SilentText.Paint(opacities, look.color.alpha)
}
