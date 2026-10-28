package com.hotlayout.render.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import com.hotlayout.model.DocumentNode
import com.hotlayout.model.WidgetType
import com.hotlayout.model.WidthRules
import com.hotlayout.render.RenderContext
import androidx.compose.runtime.withFrameNanos

/**
 * The fades of the nodes of the screens in the stack: one entry per node that fades (`visibilityTransition`), kept per
 * frame of the stack. The entry holds the timeline state, the condition it follows and the language and width class it
 * was made for; a change of either puts the node at rest of the new condition at once. Parents ask [inLayout] through
 * `RenderContext.isShown`, so a leaving node keeps its place and an appearing one takes it only after the wait.
 */
@Stable
internal class VisibilityFadeBoard {
    internal class Entry(val state: FadeState, val goal: Boolean, val signature: String)

    private val entries = mutableStateMapOf<String, Entry>()

    /** The frame time in milliseconds; read only while drawing, so a frame redraws and does not recompose. */
    private val clock = mutableDoubleStateOf(0.0)

    /** The index of the top frame of the stack: a change under a covered frame is not seen, so it is at once. */
    var top: Int = 0

    fun entry(key: String): Entry? = entries[key]

    /** Whether the node is in the layout now: its condition until it has an entry, then the phase of its timeline. */
    fun inLayout(key: String, condition: Boolean, signature: String): Boolean {
        val entry = entries[key] ?: return condition
        if (entry.signature != signature) return condition
        return entry.state.phase.let { it == FadePhase.Shown || it == FadePhase.Leaving || it == FadePhase.Entering }
    }

    /** Takes the new condition of a node: a first show, a change of language or class and a covered frame are at once. */
    suspend fun reconcile(key: String, condition: Boolean, signature: String, animate: Boolean) {
        val entry = entries[key]
        if (entry == null || entry.signature != signature) {
            entries[key] = Entry(VisibilityFadeRules.rest(condition), condition, signature)
            return
        }
        if (entry.goal == condition) return
        val now = if (animate) frame() else 0.0
        entries[key] = Entry(VisibilityFadeRules.change(entry.state, condition, now, animate), condition, signature)
    }

    /** Drives a running phase to its end, frame by frame; the end is written, which starts the next phase. */
    suspend fun run(key: String) {
        while (true) {
            val entry = entries[key] ?: return
            val end = VisibilityFadeRules.endsAt(entry.state) ?: return
            val now = frame()
            if (now >= end) {
                entries[key] = Entry(VisibilityFadeRules.settle(entry.state, now), entry.goal, entry.signature)
                return
            }
        }
    }

    private suspend fun frame(): Double = withFrameNanos { it / 1_000_000.0 }.also { clock.doubleValue = it }

    /** What a node in a transition looks like: its opacity follows the timeline, a leaving node catches nothing and is not heard. */
    fun modifierFor(key: String, id: String): Modifier {
        val entry = entries[key] ?: return Modifier
        val layer = Modifier.graphicsLayer { alpha = opacity(key).toFloat() }
        return when (entry.state.phase) {
            FadePhase.Leaving -> layer.then(inert(id))
            FadePhase.Entering -> layer.testTag("fade:entering:$id")
            else -> Modifier
        }
    }

    private fun opacity(key: String): Double = entries[key]?.let { VisibilityFadeRules.at(it.state, clock.doubleValue).opacity } ?: 1.0

    /** A node fading out: no press reaches it and the screen reader does not hear it. */
    private fun inert(id: String): Modifier = Modifier
        .clearAndSetSemantics { testTag = "fade:leaving:$id" }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }

    /** Keeps the entries of the frames still in the stack (frame index and screen). */
    fun prune(frames: List<String>) {
        val live = frames.mapIndexed { index, screen -> "$index$SEPARATOR$screen$SEPARATOR" }
        for (key in entries.keys.toList()) if (live.none { key.startsWith(it) }) entries.remove(key)
    }

    companion object {
        const val SEPARATOR = '\u001F'

        fun key(frame: Int, screenId: String, nodeId: String): String = "$frame$SEPARATOR$screenId$SEPARATOR$nodeId"
    }
}

/** The nodes of a resolved screen tree that fade, outside list templates (a template is drawn once per record). */
internal fun fadeNodesOf(root: DocumentNode): List<DocumentNode> {
    val found = ArrayList<DocumentNode>()
    fun walk(node: DocumentNode) {
        if (VisibilityFadeRules.reads(node, root)) found.add(node)
        if (node.widget != WidgetType.Repeater) node.children.forEach(::walk)
    }
    root.children.forEach(::walk)
    return found
}

/**
 * Follows the conditions of the nodes that fade: starts their transitions when a condition changes and drives them to
 * their end. Composed once per screen, outside the nodes: a node that is out of the layout draws nothing, but its
 * condition must still be watched.
 */
@Composable
internal fun VisibilityFadeDriver(root: DocumentNode, context: RenderContext) {
    val board = context.fades ?: return
    val nodes = remember(root) { fadeNodesOf(root) }
    if (nodes.isEmpty()) return
    val reduced = rememberReducedMotion()
    for (node in nodes) key(node.id) { FadeNodeDriver(node, context, board, !reduced) }
}

@Composable
private fun FadeNodeDriver(node: DocumentNode, context: RenderContext, board: VisibilityFadeBoard, motion: Boolean) {
    val key = VisibilityFadeBoard.key(context.frameIndex, context.screenId, node.id)
    val condition = WidthRules.isShown(node, context.widthClass, context.scope)
    val signature = context.fadeSignature
    val animate = motion && context.frameIndex == board.top
    LaunchedEffect(key, condition, signature, animate) { board.reconcile(key, condition, signature, animate) }
    val state = board.entry(key)?.state
    LaunchedEffect(key, state) { board.run(key) }
}
