package com.yawnandpawn.app.buildlogic

/** A `group:artifact` resolved on a runtime classpath of [variant] (e.g. `debug`). */
data class ResolvedDependency(
    val variant: String,
    val coordinates: String,
)

/**
 * NFR-13 / AD-15: every `group:artifact` on the app's runtime classpaths must be listed in
 * `config/dependency-allowlist.txt`. Pure functions; the Gradle task only gathers the input.
 */
object DependencyAllowlist {
    private val coordinatesRegex = Regex("""^[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+$""")

    /** Allowlist entries: one `group:artifact` per line; `#` starts a comment; blank lines are ignored. */
    fun parse(text: String): Set<String> = entries(text).map { it.second }.toSet()

    /** Lines that are not a bare `group:artifact` (for example a coordinate with a version). */
    fun malformedLines(text: String): List<String> =
        entries(text)
            .filterNot { (_, entry) -> coordinatesRegex.matches(entry) }
            .map { (line, entry) -> "dependency allowlist line $line '$entry' is not group:artifact (no version)" }

    /** One message per unlisted coordinate, naming the variants whose runtime classpath resolved it. */
    fun verify(
        allowlist: Set<String>,
        resolved: List<ResolvedDependency>,
    ): List<String> =
        resolved
            .filterNot { it.coordinates in allowlist }
            .groupBy({ it.coordinates }, { it.variant })
            .toSortedMap()
            .map { (coordinates, variants) ->
                "unlisted runtime dependency '$coordinates' (${variants.distinct().sorted().joinToString()})"
            }

    private fun entries(text: String): List<Pair<Int, String>> =
        text
            .lines()
            .mapIndexed { index, line -> index + 1 to line.substringBefore('#').trim() }
            .filter { (_, entry) -> entry.isNotEmpty() }
}
