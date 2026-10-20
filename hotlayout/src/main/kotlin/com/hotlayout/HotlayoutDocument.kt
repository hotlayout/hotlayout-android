package com.hotlayout

import com.hotlayout.model.DocumentLocalization
import com.hotlayout.model.DocumentReader
import com.hotlayout.model.ScreenEntry

/** A document as the reader holds it: the screens it could read and what it skipped. */
public class HotlayoutDocument internal constructor(
    /** The schema version the document declares. */
    public val schemaVersion: String,
    /** The identity of the document: the screen stack is bound to it. */
    public val documentId: String,
    /** The name of the document. */
    public val name: String,
    /** The screen the flow starts on without an entry point. */
    public val initialScreenId: String?,
    /** Named entry points: a public name to a screen id. */
    public val entryPoints: Map<String, String>,
    internal val screens: List<ScreenEntry>,
    internal val localization: DocumentLocalization?,
    /** What the reader skipped or replaced while reading the document. */
    public val reports: List<HotlayoutReport>,
) {
    internal fun screen(screenId: String): ScreenEntry? = screens.firstOrNull { it.screenId == screenId }

    override fun equals(other: Any?): Boolean = other is HotlayoutDocument && other.documentId == documentId &&
        other.schemaVersion == schemaVersion && other.screens == screens && other.localization == localization &&
        other.entryPoints == entryPoints && other.initialScreenId == initialScreenId

    override fun hashCode(): Int = listOf(documentId, schemaVersion, screens, initialScreenId).hashCode()
}

/** Why a document could not be read at all. Everything smaller than this is a report. */
public sealed class HotlayoutLoadException(message: String) : Exception(message) {
    /** The text is not JSON, or it is nested deeper than the parser accepts. */
    public class MalformedJson(public val reason: String) : HotlayoutLoadException("malformed JSON: $reason")
    /** The JSON is not a document, or it has no readable screen. */
    public class NotADocument(public val reason: String) : HotlayoutLoadException("not a document: $reason")
    /** The schema version is not readable by this reader. */
    public class IncompatibleVersion(public val verdict: VersionVerdict) : HotlayoutLoadException("incompatible version: ${verdict.reason}")
    /** Every screen is deeper than the reader draws. */
    public class MaxDepthExceeded(public val screenId: String) :
        HotlayoutLoadException("screen “$screenId” is deeper than ${DocumentReader.MAX_PARSE_DEPTH} levels")
}

/** Loads a document: JSON, the version gate, the guards, then a tolerant read of the tree. */
public fun loadDocument(text: String): HotlayoutDocument = loadDocument(text.toByteArray(Charsets.UTF_8))

/** Loads a document from UTF-8 JSON bytes; see [loadDocument] for text. Throws [HotlayoutLoadException]. */
public fun loadDocument(bytes: ByteArray): HotlayoutDocument {
    val json = try {
        JsonValue.parse(bytes)
    } catch (error: JsonParseException) {
        throw HotlayoutLoadException.MalformedJson(error.message ?: "")
    }
    return loadDocument(json)
}

/** Loads a document from a parsed value. Throws [HotlayoutLoadException]. */
public fun loadDocument(json: JsonValue): HotlayoutDocument = DocumentReader.read(json)
