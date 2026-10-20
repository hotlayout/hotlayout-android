package com.hotlayout.actions

import com.hotlayout.JsonObject
import com.hotlayout.model.DocumentNode
import com.hotlayout.text.ShownText
import com.hotlayout.text.TextHeard

/**
 * Who takes a press (`x-style.layout.composition.pressable`) and what a long press is
 * (`x-longPress`): the rules that need no view, so that the shared tables can drive them.
 */
internal object PressTarget {
    /** The widgets that hold children and take the field `action` (and `longPress`). */
    val containerTypes: Set<String> = setOf("Column", "Row", "Container", "Stack")

    /** Widgets whose own part is a control: the strip of the tabs, the header of an accordion. */
    private val ownControlWidgets: Set<String> = setOf("Tabs", "Accordion")

    /** Widgets whose own text is the text of their control, which does not name a card around. */
    private val ownControlTextWidgets: Set<String> = setOf("Accordion", "Tab")

    /** Widgets that take the press as inputs (`input-value` fields). */
    private val inputWidgets: Set<String> = setOf("TextField", "Switch", "Checkbox")

    /** Widgets that take the field `action` without holding children: the leaves that are buttons. */
    private val actionLeaves: Set<String> = setOf("Button", "IconButton", "NavItem", "ListTile")

    /** Write actions are not published on a long press, and the reader does not run one there. */
    private val writeActions: Set<String> = setOf("setValue", "toggleValue")

    /** An action does something: a type that is not `none`, or `none` with `track`. */
    fun acts(action: JsonObject?): Boolean {
        val type = action?.get("type")?.string ?: return false
        return type != "none" || action["track"]?.obj != null
    }

    /** The action of a pressable container, or `null` when the node is no such control. */
    fun pressAction(node: DocumentNode): JsonObject? {
        if (node.type !in containerTypes) return null
        return node.props["action"]?.obj?.takeIf(::acts)
    }

    /** The action a node takes on a press: a pressable container, or a list row with an action (a choice option is not a button of its own). */
    fun press(node: DocumentNode): JsonObject? {
        if (node.type != "ListTile") return pressAction(node)
        if (isChoiceOption(node)) return null
        return node.props["action"]?.obj?.takeIf(::acts)
    }

    /** The long press of a container or a list row that does something, or `null`. */
    fun longPress(node: DocumentNode): JsonObject? {
        if (!acceptsLongPress(node.type)) return null
        val action = node.props["longPress"]?.obj?.takeIf(::acts) ?: return null
        val type = action["type"]?.string ?: return null
        return action.takeIf { type !in writeActions }
    }

    /** Whether the widget accepts the field `longPress` at all (a button, an icon button and a navigation item have one action; a text has none). */
    fun acceptsLongPress(type: String): Boolean = type in containerTypes || type == "ListTile"

    /** A container made a control by its action or by its long press. */
    fun isPressHost(node: DocumentNode): Boolean = pressAction(node) != null || (node.type in containerTypes && longPress(node) != null)

    /** The `value` of a choice option: a list row whose trailing slot is a radio. */
    fun isChoiceOption(node: DocumentNode): Boolean = node.type == "ListTile" && node.props["trailing"]?.get("kind")?.string == "radio"

    /** Whether the node takes the press for itself, so that the cards around get nothing. */
    fun ownsPress(node: DocumentNode): Boolean {
        if (node.type in containerTypes) return isPressHost(node)
        if (node.type in ownControlWidgets) return true
        if (isChoiceOption(node) || node.type in inputWidgets) return true
        return hasActionField(node) || longPress(node) != null
    }

    private fun hasActionField(node: DocumentNode) = node.type in actionLeaves && node.props["action"]?.obj != null

    /** Whether the node is a button on a finished screen (role and name from its content). */
    fun actsOnPress(node: DocumentNode): Boolean {
        if (node.type in containerTypes) return isPressHost(node)
        return hasActionField(node) || longPress(node) != null
    }

    /** A widget with an own control takes the press in its own part only, not in its children. */
    private fun ownsOnlyItsBox(node: DocumentNode) = node.type in ownControlWidgets && !isPressHost(node)

    /** The node that takes a press at a point: the innermost control of the chain from the root to the node under the point. */
    fun receiver(chain: List<DocumentNode>): DocumentNode? {
        if (chain.isEmpty()) return null
        val innermost = chain.size - 1
        for (index in innermost downTo 0) {
            val node = chain[index]
            if (!ownsPress(node)) continue
            if (index < innermost && ownsOnlyItsBox(node)) continue
            return node
        }
        return null
    }

    /** Fields of a widget that say something to a person; they name a card around them. */
    private val heardFields: Map<String, List<String>> = mapOf(
        "Text" to listOf("content"), "Badge" to listOf("label"), "ListTile" to listOf("title", "subtitle"),
        "Image" to listOf("alt"), "Icon" to listOf("alt"),
    )

    /**
     * The accessible name of a pressable container: its `label` first, then the texts of its
     * content in document order. A control inside names itself; the own text of an accordion or a
     * tab, and a list from data (no data, no text), are not part of it. A child that [isShown] says is
     * not shown (and everything under it) is not said; the publisher's «has a name without data» check
     * reads the document alone and keeps the default.
     */
    fun name(
        node: DocumentNode,
        text: (DocumentNode, String) -> String,
        isShown: (DocumentNode) -> Boolean = { true },
        paintOf: (List<DocumentNode>) -> SilentText.Paint? = { null },
    ): String {
        val parts = ArrayList<String>()
        if (node.props["label"] != null) {
            val label = ShownText.of(text(node, "label"))
            if (!TextHeard.isSilent(label)) parts.add(label)
        }
        collect(node.children, emptyList(), isShown, paintOf, text, parts)
        return parts.joinToString(", ")
    }

    /**
     * [above] is the nodes between the card and [nodes]: a text that paints nothing ([paintOf] gives the paint of a chain) is not
     * said, as a hidden one is not; a node at opacity 0 silences everything under it, a transparent colour only the text it reaches.
     */
    private fun collect(
        nodes: List<DocumentNode>,
        above: List<DocumentNode>,
        isShown: (DocumentNode) -> Boolean,
        paintOf: (List<DocumentNode>) -> SilentText.Paint?,
        text: (DocumentNode, String) -> String,
        into: MutableList<String>,
    ) {
        for (node in nodes) {
            // What the screen does not show (`visibleIf`, `showOn`, directly or under a hidden container) is not said.
            if (!isShown(node)) continue
            if (ownsPress(node) && !ownsOnlyItsBox(node)) continue
            if (node.type == "Repeater") continue
            val chain = above + node
            val paint = paintOf(chain)
            if (paint != null && paint.opacityIsZero) continue
            if (node.type !in ownControlTextWidgets && !(paint != null && SilentText.paintsNothing(paint))) {
                for (field in heardFields[node.type].orEmpty()) {
                    val own = node.props[field] ?: continue
                    // A description (`alt`) may be a plain string, a title is always a bindable one.
                    val value = ShownText.of(own.string ?: text(node, field))
                    if (!TextHeard.isSilent(value)) into.add(value)
                }
            }
            collect(node.children, chain, isShown, paintOf, text, into)
        }
    }

    /** Whether a pressable container is heard without data (what the publisher requires). */
    fun hasName(node: DocumentNode, text: (DocumentNode, String) -> String): Boolean = name(node, text).isNotEmpty()

    /** Whether a container has a control inside (then its children stay separate elements). */
    fun containsControl(node: DocumentNode): Boolean = node.children.any { ownsPress(it) || containsControl(it) }
}

/**
 * The text that paints nothing (`x-silentText`): the resolved `style.opacity` is exactly 0 on the text or on a node
 * between the card and it, or the colour the text is painted in resolves with opacity 0. Zero only: 0.01 is seen.
 */
internal object SilentText {
    /** The resolved `style.opacity` of each node from the first inside the card to the text, and the opacity of the text's colour (`null`: it did not resolve). */
    class Paint(val opacities: List<Double?>, val colorOpacity: Double?) {
        val opacityIsZero: Boolean get() = opacities.any { it == 0.0 }
    }

    /** The rule as a function of the resolved paint. */
    fun paintsNothing(paint: Paint): Boolean = paint.opacityIsZero || paint.colorOpacity == 0.0

    fun paintsNothing(opacities: List<Double?>, colorOpacity: Double?): Boolean = paintsNothing(Paint(opacities, colorOpacity))
}

/** The long press (`x-longPress`): threshold, slop, what a hold came to, the keys. */
internal object LongPressRules {
    const val THRESHOLD_MS = 450.0
    const val TOUCH_SLOP = 10.0

    /** A click that follows the release of a gesture that fired is the same touch (ms). */
    const val CLICK_AFTER_FIRE_WINDOW_MS = 400.0
    val order = listOf("haptic", "track", "action")

    enum class Outcome(val word: String) { LongPress("longPress"), Press("press"), None("none") }

    fun outcome(heldMs: Double, moved: Double, cancelled: Boolean, hasLongPress: Boolean, hasAction: Boolean): Outcome {
        if (cancelled || moved > TOUCH_SLOP) return Outcome.None
        if (hasLongPress && heldMs >= THRESHOLD_MS) return Outcome.LongPress
        return if (hasAction) Outcome.Press else Outcome.None
    }

    /** The keys that perform the long press at once on the focused node. */
    fun isLongPressKey(key: String, shift: Boolean): Boolean = key == "ContextMenu" || (key == "F10" && shift)

    /** A held key performs it once: the auto-repeat does nothing. */
    fun keyPerforms(key: String, shift: Boolean, repeating: Boolean): Boolean = !repeating && isLongPressKey(key, shift)

    /** Whether a click is swallowed because it is the click of the release of a gesture that fired. */
    fun swallowsClick(fired: Boolean, released: Boolean, sinceReleaseMs: Double, newPointerDown: Boolean): Boolean =
        fired && released && !newPointerDown && sinceReleaseMs <= CLICK_AFTER_FIRE_WINDOW_MS
}
