package com.hotlayout.render.widgets

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.hotlayout.actions.InputOrder
import com.hotlayout.render.RenderContext
import com.hotlayout.theme.HotlayoutTheme
import com.hotlayout.theme.Rgba
import com.hotlayout.theme.TextStyleValue

/**
 * What the text fields of one screen share: the order of the fields for the «next» key and which
 * field can be asked to take the keyboard. A field inside a list from data is not in the order.
 */
internal class InputForm(
    val order: List<InputOrder.Entry>,
    val focus: InputFocusRegistry,
    /** The host turned autofill hints off. */
    val autofill: Boolean = true,
) {
    private fun index(nodeId: String): Int = order.indexOfFirst { it.nodeId == nodeId }

    /** The action key of the keyboard for a field; a field outside the order is `done` (`newline` when it has several lines). */
    fun keyAction(nodeId: String, multiline: Boolean): InputRules.KeyAction {
        val at = index(nodeId)
        if (at < 0) return if (multiline) InputRules.KeyAction.Newline else InputRules.KeyAction.Done
        return InputRules.keyAction(order.map { it.focusable }, at)
    }

    /** The field after [nodeId] that can take the focus, or `null`. */
    fun nextAfter(nodeId: String): String? {
        val at = index(nodeId).takeIf { it >= 0 } ?: return null
        return InputRules.nextIndex(order.map { it.focusable }, at)?.let { order[it].nodeId }
    }
}

/** The focus requesters of the fields of a screen, by node id. */
internal class InputFocusRegistry {
    private val requesters = HashMap<String, FocusRequester>()

    fun register(nodeId: String, requester: FocusRequester) {
        requesters[nodeId] = requester
    }

    fun unregister(nodeId: String, requester: FocusRequester) {
        if (requesters[nodeId] === requester) requesters.remove(nodeId)
    }

    fun request(nodeId: String): Boolean = requesters[nodeId]?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
}

/** One registry per screen of the document, so that equal node ids on different screens never meet. */
internal class InputFocusRegistries {
    private val registries = HashMap<String, InputFocusRegistry>()

    fun of(screenId: String): InputFocusRegistry = registries.getOrPut(screenId) { InputFocusRegistry() }
}

/** The theme values the three input widgets are drawn from (`x-style.layout.inputs`): colours and text styles by name. */
internal class InputLook(private val context: RenderContext) {
    private val theme: HotlayoutTheme get() = context.theme

    fun color(token: String): Rgba = theme.colorValue(token) ?: Rgba(0.5, 0.5, 0.5)

    fun spacing(token: String, fallback: Double): Double = theme.spacing[token] ?: fallback

    val label: TextStyleValue get() = theme.typography["bodyS"] ?: TextStyleValue(12.0, 4.0 / 3, 400)
    val body: TextStyleValue get() = theme.typography["bodyM"] ?: TextStyleValue(14.0, 10.0 / 7, 400)
    val radius: Double get() = theme.radius[InputPartsRules.DEFAULT_RADIUS] ?: 8.0
    val counter: TextStyleValue get() = theme.typography[InputPartsRules.COUNTER_TYPOGRAPHY] ?: label
}

/**
 * How far the on-screen keyboard reaches into the window, in dp (`x-style.layout.bottomZone.keyboard`).
 *
 * The reader reads its own `WindowInsets.ime`; the host does not pass it. The screen keeps its size
 * when the keyboard comes: the pinned bottom block rides above the keyboard by this much, the bar
 * of navigation stays under it. [LocalKeyboardInset] lets a test or a host that lays the screen out
 * itself state the inset.
 */
@Composable
internal fun keyboardInset(consumed: WindowInsets = WindowInsets(0, 0, 0, 0)): Dp =
    LocalKeyboardInset.current ?: with(LocalDensity.current) { WindowInsets.ime.exclude(consumed).getBottom(this).toDp() }

/** An inset stated from outside; `null` reads the real one. */
internal val LocalKeyboardInset = compositionLocalOf<Dp?> { null }

/** Provides a stated keyboard inset to the screen below (tests, and hosts that measure the keyboard themselves). */
@Composable
internal fun ProvideKeyboardInset(inset: Dp?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKeyboardInset provides inset, content = content)
}
