package com.hotlayout.render.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import com.hotlayout.actions.WriteActions
import com.hotlayout.json.JsonObject
import com.hotlayout.model.DocumentNode
import com.hotlayout.render.RenderContext

/** The children of the node being drawn: what its own children see as their siblings. */
internal val LocalSiblings = compositionLocalOf<List<DocumentNode>> { emptyList() }

/** The siblings of the node being drawn (the children of its parent, itself among them). */
internal val LocalPeers = compositionLocalOf<List<DocumentNode>> { emptyList() }

/**
 * Hands the children of [node] to the nodes drawn inside it as their siblings (the place of an
 * option in its group), and what [node] itself received to its own widget as [LocalPeers].
 */
@Composable
internal fun ProvideSiblings(node: DocumentNode, content: @Composable () -> Unit) {
    val peers = LocalSiblings.current
    CompositionLocalProvider(LocalPeers provides peers, LocalSiblings provides node.children, content = content)
}

/** The place of an option in its group as a semantic property: position (from 1) and size. */
internal val ChoicePlaceKey = SemanticsPropertyKey<Pair<Int, Int>>("hotlayoutChoicePlace")

/** What a node with a write action says to the screen reader: an option is a radio, a toggle is a pressed button. */
internal class WriteControl(val control: WriteActions.Control, val place: WriteActions.Place?)

@Composable
internal fun rememberWriteControl(node: DocumentNode, context: RenderContext, action: JsonObject?, siblings: List<DocumentNode> = LocalPeers.current): WriteControl? {
    if (action == null) return null
    val control = WriteActions.control(action) { context.scope[it] } ?: return null
    val place = if (control.role == WriteActions.Role.Option) WriteActions.place(node, siblings) { context.isShown(it) } else null
    return WriteControl(control, place)
}

internal fun SemanticsPropertyReceiver.controlSemantics(node: DocumentNode, state: WriteControl?) {
    testTag = "node:${node.id}"
    if (state == null) return
    when (state.control.role) {
        WriteActions.Role.Option -> {
            role = Role.RadioButton
            selected = state.control.on
            state.place?.let {
                collectionItemInfo = CollectionItemInfo(rowIndex = it.position - 1, rowSpan = 1, columnIndex = 0, columnSpan = 1)
                this[ChoicePlaceKey] = it.position to it.size
            }
        }
        WriteActions.Role.Toggle -> {
            role = Role.Button
            toggleableState = if (state.control.on) ToggleableState.On else ToggleableState.Off
        }
    }
}
