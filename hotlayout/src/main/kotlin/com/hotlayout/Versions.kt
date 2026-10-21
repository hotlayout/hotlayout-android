package com.hotlayout

import com.hotlayout.model.checkVersion

/**
 * The contract versions this reader is written against.
 *
 * The versions of the LIBRARY and of the contract are independent: a library release may fix a
 * bug without touching the contract, and a contract minor may need no code here.
 */
public object HotlayoutVersions {
    /** Widget schema (`x-schemaVersion` of `screen-schema.json`). */
    public const val SCHEMA: String = "9.28.0"

    /** Theme contract (`x-themeContractVersion` of `theme.json`). */
    public const val THEME: String = "4.0.1"

    /** Icon set (`iconSetVersion` of `icons.json`). */
    public const val ICON_SET: String = "1.0.0"
}

/**
 * Whether this reader can read a document of a given schema version.
 *
 * The rule is the contract's (`x-compatibility.policy`): the MAJOR must match, a newer minor
 * only adds optional fields, so it is read and what the reader does not know is skipped; a patch
 * takes no part.
 */
public sealed class VersionVerdict {
    /** The same major, a minor this reader knows. */
    public data object Ok : VersionVerdict()

    /** The same major, a newer minor: read, unknown fields skipped. */
    public data class NewerMinor(val documentVersion: String) : VersionVerdict()

    /** A newer major: not readable. */
    public data class NewerMajor(val documentVersion: String) : VersionVerdict()

    /** An older major: not readable. */
    public data class OlderMajor(val documentVersion: String) : VersionVerdict()

    /** Not a `major.minor.patch` version, or no version at all. */
    public data class Unparsable(val documentVersion: String?) : VersionVerdict()

    /** Whether the reader can draw the document: the same major. */
    public val isReadable: Boolean get() = this is Ok || this is NewerMinor

    /** Words for a log line; never shown to the end user. */
    public val reason: String
        get() = when (this) {
            Ok -> "readable"
            is NewerMinor -> "newer minor $documentVersion: read, unknown fields skipped"
            is NewerMajor -> "major of $documentVersion is newer than this reader's ${HotlayoutVersions.SCHEMA}"
            is OlderMajor -> "major of $documentVersion is older than this reader's ${HotlayoutVersions.SCHEMA}"
            is Unparsable -> "“${documentVersion ?: ""}” is not a version (expected major.minor.patch)"
        }
}

/**
 * The version gate for a whole document: reads `schemaVersion` and judges it. A document
 * without a string version is unparsable, never "assume the latest".
 */
public fun checkDocumentVersion(document: JsonValue): VersionVerdict {
    val version = document["schemaVersion"]?.string ?: return VersionVerdict.Unparsable(null)
    return checkVersion(version)
}

/** The version gate for a document given as JSON text; text that is not JSON is [VersionVerdict.Unparsable]. */
public fun checkDocumentVersion(text: String): VersionVerdict = checkDocumentVersion(text.toByteArray(Charsets.UTF_8))

/** The version gate for a document given as UTF-8 JSON bytes; bytes that are not JSON are [VersionVerdict.Unparsable]. */
public fun checkDocumentVersion(bytes: ByteArray): VersionVerdict {
    val json = try {
        JsonValue.parse(bytes)
    } catch (_: JsonParseException) {
        return VersionVerdict.Unparsable(null)
    }
    return checkDocumentVersion(json)
}
