package com.yawnandpawn.app.buildlogic

private const val MAJOR_FACTOR = 10_000
private const val MINOR_FACTOR = 100
private const val MAX_PART = 99
private const val PART_COUNT = 3

/**
 * Computes the Android versionCode from a semantic `major.minor.patch` versionName:
 * `major * 10000 + minor * 100 + patch`. Same formula as `AppVersion` in `:core`.
 */
fun versionCodeOf(versionName: String): Int {
    val parts = versionName.split(".")
    require(parts.size == PART_COUNT) { "versionName must be major.minor.patch, was '$versionName'" }
    val (major, minor, patch) =
        parts.map { part ->
            requireNotNull(part.toIntOrNull()) { "versionName part '$part' in '$versionName' is not a number" }
        }
    require(major >= 0 && minor in 0..MAX_PART && patch in 0..MAX_PART) {
        "versionName '$versionName' is out of range (minor and patch must be 0..$MAX_PART)"
    }
    return major * MAJOR_FACTOR + minor * MINOR_FACTOR + patch
}
