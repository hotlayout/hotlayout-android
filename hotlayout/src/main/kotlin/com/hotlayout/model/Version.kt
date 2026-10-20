package com.hotlayout.model

import com.hotlayout.HotlayoutVersions
import com.hotlayout.VersionVerdict

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
internal fun checkVersion(documentVersion: String, readerVersion: String = HotlayoutVersions.SCHEMA): VersionVerdict {
    val document = Semver.parse(documentVersion) ?: return VersionVerdict.Unparsable(documentVersion)
    val reader = Semver.parse(readerVersion) ?: error("HotLayout: malformed reader version $readerVersion")
    if (document.major > reader.major) return VersionVerdict.NewerMajor(documentVersion)
    if (document.major < reader.major) return VersionVerdict.OlderMajor(documentVersion)
    if (document.minor > reader.minor) return VersionVerdict.NewerMinor(documentVersion)
    return VersionVerdict.Ok
}

