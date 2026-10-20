package com.hotlayout.support

import com.hotlayout.DataScope
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.actions.ActionCatalog
import com.hotlayout.actions.WriteActions
import com.hotlayout.actions.WritePress
import com.hotlayout.data.Conditions
import com.hotlayout.data.DataLayers
import com.hotlayout.data.RecordScope
import com.hotlayout.model.DocumentNode

/**
 * A screen of the tables without a view: the nodes of one `screens[]` entry, the data layers and the
 * session. A press goes through the same functions the views call, so what the table says about a
 * press is checked against the reader's rules and not against a copy of them.
 */
internal class ScreenSim(nodes: List<JsonValue>, var layers: DataLayers = DataLayers(session = emptyMap())) {
    constructor(nodes: List<JsonValue>, session: DataScope) : this(nodes, DataLayers(session = session))

    var session: DataScope
        get() = layers.session.orEmpty()
        set(value) {
            layers = layers.copy(session = value)
        }

    internal val root = DocumentNode("root", "Screen", children = nodes.map(::node))

    /** What the host got, in order: `["track", event]`, `["action", type]` and `["write", path, value]`. */
    val log = ArrayList<List<JsonValue>>()

    val scope: DataScope get() = RecordScope.merge(layers)

    fun isFilled(): Boolean = com.hotlayout.actions.ScreenForm.isFilled(root, scope)

    internal fun find(id: String): List<DocumentNode>? = WritePress.path(id, root)

    fun isDisabled(id: String): Boolean {
        val node = find(id)?.last() ?: return false
        return WritePress.isDisabled(node, scope, root, scope)
    }

    fun isShown(id: String): Boolean = find(id)?.all { Conditions.isVisible(it.visibleIf, scope) } ?: false

    /** A press of the node [id] (or of a node inside it). */
    fun press(id: String) {
        val actor = find(id)?.let(WritePress::actor) ?: return
        if (WritePress.isDisabled(actor, scope, root, scope)) return
        val action = WriteActions.pressAction(actor) ?: return
        val effects = WritePress.effects(action, { scope[it] })
        if (effects == null) {
            // Any other action: the analytics event goes first, then the action itself.
            ActionCatalog.track(action)?.let { log.add(listOf(JsonValue.Str("track"), JsonValue.Str(it.event))) }
            log.add(listOf(JsonValue.Str("action"), JsonValue.Str(action["type"]?.string ?: "")))
            return
        }
        effects.track?.let { log.add(listOf(JsonValue.Str("track"), JsonValue.Str(it.event))) }
        val step = effects.step
        if (step is WriteActions.Step.Write && WritePath.permits(step.path)) {
            session = session + (step.path to step.value)
            log.add(listOf(JsonValue.Str("write"), JsonValue.Str(step.path), step.value))
        }
    }

    internal fun siblings(id: String): List<DocumentNode> = find(id)?.takeIf { it.size >= 2 }?.let { it[it.size - 2].children } ?: emptyList()

    /** What the finished screen says about a node, in the words of the table. */
    fun control(id: String): JsonValue {
        val path = find(id)
        if (!isShown(id) || path == null) return JsonValue.Str("absent")
        val node = path.last()
        val result = ArrayList<Pair<String, JsonValue>>()
        if (node.type == "ListTile" && node.props["trailing"]?.get("kind")?.string == "radio") {
            val group = path.lastOrNull { it.type == "ChoiceGroup" }
            val selected = group?.let(::groupSelection)
            result.add("role" to JsonValue.Str("radio"))
            result.add("checked" to JsonValue.Bool(selected != null && selected == node.props["trailing"]?.get("optionValue")?.string))
            return JsonValue.Obj(JsonObject(result))
        }
        val action = WriteActions.pressAction(node)
        val control = action?.let { WriteActions.control(it) { scope[it] } }
        if (control != null) {
            when (control.role) {
                WriteActions.Role.Option -> {
                    result.add("role" to JsonValue.Str("radio"))
                    result.add("checked" to JsonValue.Bool(control.on))
                    WriteActions.place(node, siblings(id)) { isShown(it.id) }?.let {
                        result.add("position" to JsonValue.Num(it.position.toDouble()))
                        result.add("size" to JsonValue.Num(it.size.toDouble()))
                    }
                }
                WriteActions.Role.Toggle -> {
                    result.add("role" to JsonValue.Str("button"))
                    result.add("pressed" to JsonValue.Bool(control.on))
                }
            }
            if (isDisabled(id)) result.add("disabled" to JsonValue.Bool(true))
            return JsonValue.Obj(JsonObject(result))
        }
        if (node.type == "Button" || node.type == "IconButton") {
            result.add("role" to JsonValue.Str("button"))
            if (isDisabled(id)) result.add("disabled" to JsonValue.Bool(true))
            return JsonValue.Obj(JsonObject(result))
        }
        return JsonValue.Obj(JsonObject(listOf("role" to JsonValue.Null)))
    }

    /** The option a group shows as selected: the bound value, or the fallback of the binding. */
    private fun groupSelection(group: DocumentNode): String? {
        val value = group.props["value"]
        val path = value?.takeIf { it["kind"]?.string == "binding" }?.get("path")?.string
        val text = path?.let { scope[it] }?.string ?: ""
        val fallback = value?.takeIf { it["kind"]?.string == "binding" }?.get("fallback")?.string ?: ""
        val shown = text.ifEmpty { fallback }
        return shown.ifEmpty { null }
    }

    companion object {
        /** A node of a table row (`id`, `type`, `props`, `visibleIf`, `children`) as the reader holds it. */
        internal fun node(json: JsonValue): DocumentNode {
            val obj = json.obj ?: JsonObject()
            val fields = JsonObject(obj.filter { it.first !in setOf("id", "type", "props", "children") })
            return DocumentNode(
                obj["id"]?.string ?: "", obj["type"]?.string ?: "", obj["props"]?.obj ?: JsonObject(), fields,
                obj["children"]?.array.orEmpty().map(::node),
            )
        }
    }
}

private object WritePath {
    fun permits(path: String): Boolean = ActionCatalog.isDataPath(path)
}
