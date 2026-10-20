package com.hotlayout.model

import com.hotlayout.HotlayoutDocument
import com.hotlayout.HotlayoutLoadException
import com.hotlayout.HotlayoutReport
import com.hotlayout.JsonObject
import com.hotlayout.JsonValue
import com.hotlayout.ScreenPresentation
import com.hotlayout.checkDocumentVersion
import com.hotlayout.json.JsonParser

/**
 * One node of a screen tree, read tolerantly.
 *
 * Props and the node fields stay JSON: each widget reads what it understands, so a field of a
 * newer minor (or one of the wrong shape) is simply not read, and the rest of the node works.
 */
internal data class DocumentNode(
    val id: String,
    val type: String,
    val props: JsonObject = JsonObject(),
    /** Every field of the node as it lies in the document (`style`, `styleWhen`, `visibleIf`, `weight`…). */
    val fields: JsonObject = JsonObject(),
    val children: List<DocumentNode> = emptyList(),
    /** `columns` came from a width layer (`propsOn`): its bounds are 1…6, not the base's 2…4. Set by `inWidthClass` only. */
    val columnsFromOverride: Boolean = false,
) {
    val style: JsonValue? get() = fields["style"]
    val styleWhen: JsonValue? get() = fields["styleWhen"]
    val visibleIf: JsonValue? get() = fields["visibleIf"]
}


/** An entry of `screens`: a screen and how it is shown. */
internal data class ScreenEntry(
    val screenId: String,
    val name: String,
    val presentation: ScreenPresentation,
    /** `bottom` or `center`; only known words are kept. */
    val modalPlacement: String?,
    /** `light` or `dark`: the screen's request about the system status bar icons, for the host. */
    val statusBarIcons: String?,
    /** Blur radius under a modal layer, 1…40; `null` without a valid field or on a page. */
    val backdropBlur: Int?,
    /** `false` only for a modal layer that waits for an action inside it. */
    val dismissible: Boolean,
    val root: DocumentNode,
)

/** `localization` of a document: the source language, the listed languages, the translations. */
internal data class DocumentLocalization(
    val defaultLocale: String,
    val locales: List<String>,
    val strings: Map<String, Map<String, String>>,
)






/** The tolerant reader of the document tree. */
internal object DocumentReader {
    /** Levels UNDER a screen root that are drawn. A deeper tree is refused before any recursion. */
    internal const val MAX_PARSE_DEPTH: Int = 32

    /** A key an open map cannot have. */
    internal const val FORBIDDEN_KEY = "__proto__"

    /** Widgets of the schema (`x-compatibility.widgets`); any other type is an unknown widget. */
    internal val knownWidgets: Set<String> = setOf(
        "Screen", "AppBar", "Column", "Row", "Container", "Grid", "Stack", "Tabs", "Tab", "Accordion",
        "ChoiceGroup", "Repeater", "Spacer", "Divider", "Text", "Image", "Icon", "Button", "IconButton",
        "ListTile", "ProgressBar", "CircularProgress", "Badge", "NavBar", "NavItem", "TextField", "Switch", "Checkbox",
    )

    internal fun read(raw: JsonValue): HotlayoutDocument {
        val json = withoutForbiddenKeys(raw)
        val document = json.obj ?: throw HotlayoutLoadException.NotADocument("the document is not an object")
        val verdict = checkDocumentVersion(json)
        if (!verdict.isReadable) throw HotlayoutLoadException.IncompatibleVersion(verdict)

        val sink = ReportSink()
        val screens = ArrayList<ScreenEntry>()
        val tooDeep = ArrayList<String>()
        document["screens"]?.array.orEmpty().forEachIndexed { index, item ->
            val entry = item.obj
            val screenId = entry?.get("screenId")?.string
            if (entry == null || screenId.isNullOrEmpty()) {
                sink.add(HotlayoutReport.Kind.IgnoredInput, "screens[$index]", "a screen entry without a screenId string is skipped")
                return@forEachIndexed
            }
            val rootValue = entry["root"]
            if (rootValue?.obj == null) {
                sink.add(HotlayoutReport.Kind.IgnoredInput, "screen “$screenId”", "a screen without a root object is skipped")
                return@forEachIndexed
            }
            if (treeDepth(rootValue) > MAX_PARSE_DEPTH + 1) {
                tooDeep.add(screenId)
                sink.add(HotlayoutReport.Kind.IgnoredInput, "screen “$screenId”", "a screen deeper than $MAX_PARSE_DEPTH levels is skipped")
                return@forEachIndexed
            }
            val root = node(rootValue, "screen “$screenId”", "root", sink) ?: return@forEachIndexed
            val placement = entry["modalPlacement"]?.string
            val isModal = entry["presentation"]?.string == "modal"
            screens.add(
                ScreenEntry(
                    screenId = screenId,
                    name = entry["name"]?.string ?: "",
                    presentation = if (isModal) ScreenPresentation.Modal else ScreenPresentation.Page,
                    modalPlacement = placement?.takeIf { it == "bottom" || it == "center" },
                    statusBarIcons = entry["statusBarIcons"]?.string?.takeIf { it == "light" || it == "dark" },
                    backdropBlur = if (isModal) BlurRules.radius(entry["backdropBlur"]) else null,
                    dismissible = !(isModal && entry["dismissible"]?.bool == false),
                    root = root,
                ),
            )
        }
        if (screens.isEmpty() && tooDeep.isNotEmpty()) throw HotlayoutLoadException.MaxDepthExceeded(tooDeep.first())
        if (screens.isEmpty()) throw HotlayoutLoadException.NotADocument("the document has no readable screen")

        val entryPoints = LinkedHashMap<String, String>()
        for ((name, target) in document["entryPoints"]?.obj ?: JsonObject()) target.string?.let { entryPoints[name] = it }

        return HotlayoutDocument(
            schemaVersion = document["schemaVersion"]?.string ?: "",
            documentId = document["documentId"]?.string ?: "",
            name = document["name"]?.string ?: "",
            initialScreenId = document["initialScreenId"]?.string,
            entryPoints = entryPoints,
            screens = screens,
            localization = localization(document["localization"], sink),
            reports = sink.reports,
        )
    }

    /**
     * Reads one node. A child that is not an object or has no type is skipped and reported; a node
     * without an id gets one from its position, so that the rest of it is drawn.
     */
    internal fun node(value: JsonValue, location: String, position: String, sink: ReportSink): DocumentNode? {
        val node = value.obj
        if (node == null) {
            sink.add(HotlayoutReport.Kind.IgnoredInput, "$location: $position", "a node that is not an object is skipped")
            return null
        }
        val type = node["type"]?.string
        if (type.isNullOrEmpty()) {
            sink.add(HotlayoutReport.Kind.IgnoredInput, "$location: $position", "a node without a type is skipped")
            return null
        }
        val own = node["id"]?.string
        val id = if (!own.isNullOrEmpty()) {
            own
        } else {
            sink.add(HotlayoutReport.Kind.IgnoredInput, "$location: $position", "a node without an id string; an id is made from its position")
            "#$position"
        }
        val here = "$location: node “$id”"
        var props = JsonObject()
        node["props"]?.let { value ->
            val read = value.obj
            if (read != null) props = read else sink.add(HotlayoutReport.Kind.IgnoredInput, here, "props are not an object; the widget draws with its defaults")
        }
        if (type !in knownWidgets) sink.add(HotlayoutReport.Kind.UnknownWidget, here, "unknown widget “$type”; its children are drawn")
        val children = ArrayList<DocumentNode>()
        node["children"]?.let { list ->
            val items = list.array
            if (items != null) {
                items.forEachIndexed { index, child -> node(child, here, "children[$index]", sink)?.let(children::add) }
            } else {
                sink.add(HotlayoutReport.Kind.IgnoredInput, here, "children are not a list; the node is drawn without them")
            }
        }
        val fields = JsonObject(node.filter { it.first != "children" && it.first != "props" })
        return DocumentNode(id, type, props, fields, children)
    }

    internal fun localization(value: JsonValue?, sink: ReportSink): DocumentLocalization? {
        if (value == null || value.isNull) return null
        val source = value["defaultLocale"]?.string
        if (value.obj == null || source == null) {
            sink.add(HotlayoutReport.Kind.IgnoredInput, "localization", "localization without a defaultLocale string is ignored; texts are shown as written")
            return null
        }
        val locales = value["locales"]?.array.orEmpty().mapNotNull { it.string }
        val strings = LinkedHashMap<String, Map<String, String>>()
        for ((locale, table) in value["strings"]?.obj ?: JsonObject()) {
            val entries = LinkedHashMap<String, String>()
            for ((key, text) in table.obj ?: JsonObject()) text.string?.let { entries[key] = it }
            strings[locale] = entries
        }
        return DocumentLocalization(source, locales, strings)
    }

    /**
     * Depth of a node tree where the root is 1. Iterative: a recursive measurement would crash on
     * the very input it exists to refuse.
     */
    internal fun treeDepth(root: JsonValue): Int {
        var deepest = 0
        val pending = ArrayDeque<Pair<JsonValue, Int>>()
        pending.addLast(root to 1)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            deepest = maxOf(deepest, depth)
            if (deepest > MAX_PARSE_DEPTH + 1) return deepest
            for (child in node["children"]?.array.orEmpty()) pending.addLast(child to depth + 1)
        }
        return deepest
    }

    /**
     * The same value without own `__proto__` keys at any depth. A value nested deeper than the parser
     * accepts (a host may build one by hand) is refused as malformed BEFORE any recursion over it.
     */
    internal fun withoutForbiddenKeys(value: JsonValue): JsonValue {
        if (nestingDepth(value) > JsonParser.MAX_PARSE_DEPTH) throw HotlayoutLoadException.MalformedJson("nesting too deep")
        return stripped(value)
    }

    private fun stripped(value: JsonValue): JsonValue = when (value) {
        is JsonValue.Obj -> JsonValue.Obj(JsonObject(value.fields.filter { it.first != FORBIDDEN_KEY }.map { it.first to stripped(it.second) }))
        is JsonValue.Arr -> JsonValue.Arr(value.items.map(::stripped))
        else -> value
    }

    /** Nesting depth of any value (a scalar is 0), measured iteratively and stopped once it passes the parser's bound. */
    internal fun nestingDepth(root: JsonValue): Int {
        var deepest = 0
        val pending = ArrayDeque<Pair<JsonValue, Int>>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            deepest = maxOf(deepest, depth)
            if (deepest > JsonParser.MAX_PARSE_DEPTH) return deepest
            when (value) {
                is JsonValue.Obj -> for ((_, child) in value.fields) pending.addLast(child to depth + 1)
                is JsonValue.Arr -> for (child in value.items) pending.addLast(child to depth + 1)
                else -> Unit
            }
        }
        return deepest
    }
}
