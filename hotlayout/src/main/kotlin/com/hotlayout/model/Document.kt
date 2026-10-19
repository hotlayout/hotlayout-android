package com.hotlayout.model

import com.hotlayout.json.JsonObject
import com.hotlayout.json.JsonParseException
import com.hotlayout.json.JsonValue

/**
 * One node of a screen tree, read tolerantly.
 *
 * Props and the node fields stay JSON: each widget reads what it understands, so a field of a
 * newer minor (or one of the wrong shape) is simply not read, and the rest of the node works.
 */
public data class DocumentNode(
    val id: String,
    val type: String,
    val props: JsonObject = JsonObject(),
    /** Every field of the node as it lies in the document (`style`, `styleWhen`, `visibleIf`, `weight`…). */
    val fields: JsonObject = JsonObject(),
    val children: List<DocumentNode> = emptyList(),
) {
    val style: JsonValue? get() = fields["style"]
    val styleWhen: JsonValue? get() = fields["styleWhen"]
    val visibleIf: JsonValue? get() = fields["visibleIf"]
}

public enum class ScreenPresentation(public val word: String) { Page("page"), Modal("modal") }

/** An entry of `screens`: a screen and how it is shown. */
public data class ScreenEntry(
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
public data class DocumentLocalization(
    val defaultLocale: String,
    val locales: List<String>,
    val strings: Map<String, Map<String, String>>,
)

/** A document as the reader holds it: the screens it could read and what it skipped. */
public class HotlayoutDocument(
    public val schemaVersion: String,
    public val documentId: String,
    public val name: String,
    public val initialScreenId: String?,
    public val entryPoints: Map<String, String>,
    public val screens: List<ScreenEntry>,
    public val localization: DocumentLocalization?,
    public val reports: List<HotlayoutReport>,
) {
    public fun screen(screenId: String): ScreenEntry? = screens.firstOrNull { it.screenId == screenId }

    override fun equals(other: Any?): Boolean = other is HotlayoutDocument && other.documentId == documentId &&
        other.schemaVersion == schemaVersion && other.screens == screens && other.localization == localization &&
        other.entryPoints == entryPoints && other.initialScreenId == initialScreenId

    override fun hashCode(): Int = listOf(documentId, schemaVersion, screens, initialScreenId).hashCode()
}

/** Why a document could not be read at all. Everything smaller than this is a report. */
public sealed class HotlayoutLoadException(message: String) : Exception(message) {
    public class MalformedJson(public val reason: String) : HotlayoutLoadException("malformed JSON: $reason")
    public class NotADocument(public val reason: String) : HotlayoutLoadException("not a document: $reason")
    public class IncompatibleVersion(public val verdict: VersionVerdict) : HotlayoutLoadException("incompatible version: ${verdict.reason}")
    public class MaxDepthExceeded(public val screenId: String) :
        HotlayoutLoadException("screen “$screenId” is deeper than ${DocumentReader.MAX_PARSE_DEPTH} levels")
}

/** Loads a document: JSON, the version gate, the guards, then a tolerant read of the tree. */
public fun loadDocument(text: String): HotlayoutDocument = loadDocument(text.toByteArray(Charsets.UTF_8))

public fun loadDocument(bytes: ByteArray): HotlayoutDocument {
    val json = try {
        JsonValue.parse(bytes)
    } catch (error: JsonParseException) {
        throw HotlayoutLoadException.MalformedJson(error.message ?: "")
    }
    return loadDocument(json)
}

public fun loadDocument(json: JsonValue): HotlayoutDocument = DocumentReader.read(json)

/** The tolerant reader of the document tree. */
public object DocumentReader {
    /** Levels UNDER a screen root that are drawn. A deeper tree is refused before any recursion. */
    public const val MAX_PARSE_DEPTH: Int = 32

    /** A key an open map cannot have. */
    internal const val FORBIDDEN_KEY = "__proto__"

    /** Widgets of the schema (`x-compatibility.widgets`); any other type is an unknown widget. */
    public val knownWidgets: Set<String> = setOf(
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
            if (treeDepth(rootValue) > MAX_PARSE_DEPTH + 1) throw HotlayoutLoadException.MaxDepthExceeded(screenId)
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
                    backdropBlur = if (isModal) blurRadius(entry["backdropBlur"]) else null,
                    dismissible = !(isModal && entry["dismissible"]?.bool == false),
                    root = root,
                ),
            )
        }
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

    /** A blur radius of a layer, a whole number 1…40. */
    internal fun blurRadius(value: JsonValue?): Int? {
        val number = value?.number ?: return null
        if (number != Math.rint(number) || number < 1 || number > 40) return null
        return number.toInt()
    }

    /**
     * Reads one node. A child that is not an object or has no type is skipped and reported; a node
     * without an id gets one from its position, so that the rest of it is drawn.
     */
    public fun node(value: JsonValue, location: String, position: String, sink: ReportSink): DocumentNode? {
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

    /** The same value without own `__proto__` keys at any depth. */
    public fun withoutForbiddenKeys(value: JsonValue): JsonValue = when (value) {
        is JsonValue.Obj -> JsonValue.Obj(JsonObject(value.fields.filter { it.first != FORBIDDEN_KEY }.map { it.first to withoutForbiddenKeys(it.second) }))
        is JsonValue.Arr -> JsonValue.Arr(value.items.map(::withoutForbiddenKeys))
        else -> value
    }
}
