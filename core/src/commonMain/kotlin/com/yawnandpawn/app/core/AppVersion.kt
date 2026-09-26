package com.yawnandpawn.app.core

/**
 * A semantic app version. The Android versionCode is `major * 10000 + minor * 100 + patch`,
 * so minor and patch stay within 0..99.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    init {
        require(major >= 0) { "major must be >= 0, was $major" }
        require(minor in PART_RANGE) { "minor must be in $PART_RANGE, was $minor" }
        require(patch in PART_RANGE) { "patch must be in $PART_RANGE, was $patch" }
    }

    val versionCode: Int
        get() = major * MAJOR_FACTOR + minor * MINOR_FACTOR + patch

    val versionName: String
        get() = "$major.$minor.$patch"

    companion object {
        private const val MAJOR_FACTOR = 10_000
        private const val MINOR_FACTOR = 100
        private const val PART_COUNT = 3
        private val PART_RANGE = 0..99

        /** Parses `major.minor.patch`; returns null for anything else. */
        fun parseOrNull(versionName: String): AppVersion? {
            val parts = versionName.split(".")
            val numbers = parts.mapNotNull { it.toIntOrNull() }
            if (parts.size != PART_COUNT || numbers.size != PART_COUNT) return null
            val (major, minor, patch) = numbers
            val inRange = major >= 0 && minor in PART_RANGE && patch in PART_RANGE
            return if (inRange) AppVersion(major, minor, patch) else null
        }
    }
}
