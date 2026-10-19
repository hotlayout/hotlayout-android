package com.hotlayout.model

import com.hotlayout.json.JsonValue

/**
 * The contract versions this reader is written against.
 *
 * The versions of the LIBRARY and of the contract are independent: a library release may fix a
 * bug without touching the contract, and a contract minor may need no code here.
 */
public object HotlayoutVersions {
    /** Widget schema (`x-schemaVersion` of `screen-schema.json`). */
    public const val SCHEMA: String = "9.27.0"

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
    public object Ok : VersionVerdict() {
        override fun toString(): String = "Ok"
    }

    /** The same major, a newer minor: read, unknown fields skipped. */
    public data class NewerMinor(val documentVersion: String) : VersionVerdict()

    /** A newer major: not readable. */
    public data class NewerMajor(val documentVersion: String) : VersionVerdict()

    /** An older major: not readable. */
    public data class OlderMajor(val documentVersion: String) : VersionVerdict()

    /** Not a `major.minor.patch` version, or no version at all. */
    public data class Unparsable(val documentVersion: String?) : VersionVerdict()

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

internal class Semver private constructor(val major: Int, val minor: Int, val patch: Int) {
    companion object {
        fun parse(text: String): Semver? {
            val parts = text.split('.')
            if (parts.size != 3) return null
            // Digits only: "+1" or " 1" is not a version, and `toInt()` would accept "+1".
            val numbers = parts.map { part ->
                if (part.isEmpty() || part.length > 9 || !part.all { it in '0'..'9' }) return null
                part.toInt()
            }
            return Semver(numbers[0], numbers[1], numbers[2])
        }
    }
}

/** Compares a version with the one this reader supports. Numbers, not text: 9.10.0 is newer than 9.9.0. */
public fun checkVersion(documentVersion: String, readerVersion: String = HotlayoutVersions.SCHEMA): VersionVerdict {
    val document = Semver.parse(documentVersion) ?: return VersionVerdict.Unparsable(documentVersion)
    val reader = Semver.parse(readerVersion) ?: error("HotLayout: malformed reader version $readerVersion")
    if (document.major > reader.major) return VersionVerdict.NewerMajor(documentVersion)
    if (document.major < reader.major) return VersionVerdict.OlderMajor(documentVersion)
    if (document.minor > reader.minor) return VersionVerdict.NewerMinor(documentVersion)
    return VersionVerdict.Ok
}

/**
 * The version gate for a whole document: reads `schemaVersion` and judges it. A document
 * without a string version is unparsable, never "assume the latest".
 */
public fun checkDocumentVersion(document: JsonValue): VersionVerdict {
    val version = document["schemaVersion"]?.string ?: return VersionVerdict.Unparsable(null)
    return checkVersion(version)
}
