package com.yawnandpawn.app.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyAllowlistTest {
    private val allowlistText =
        """
        # Kotlin
        org.jetbrains.kotlin:kotlin-stdlib
        io.insert-koin:koin-core  # DI

        androidx.core:core
        """.trimIndent()

    private val resolved =
        listOf(
            ResolvedDependency("debug", "org.jetbrains.kotlin:kotlin-stdlib"),
            ResolvedDependency("release", "org.jetbrains.kotlin:kotlin-stdlib"),
            ResolvedDependency("debug", "io.insert-koin:koin-core"),
            ResolvedDependency("debug", "androidx.core:core"),
            ResolvedDependency("release", "androidx.core:core"),
        )

    @Test
    fun `parsing skips comments, trailing comments and blank lines`() {
        assertEquals(
            setOf("org.jetbrains.kotlin:kotlin-stdlib", "io.insert-koin:koin-core", "androidx.core:core"),
            DependencyAllowlist.parse(allowlistText),
        )
    }

    @Test
    fun `resolved dependencies that are all listed pass`() {
        assertEquals(emptyList(), DependencyAllowlist.verify(DependencyAllowlist.parse(allowlistText), resolved))
    }

    @Test
    fun `removing a line from the allowlist fails naming the coordinate and its variants`() {
        val withoutCore = allowlistText.lines().filterNot { it == "androidx.core:core" }.joinToString("\n")

        val violations = DependencyAllowlist.verify(DependencyAllowlist.parse(withoutCore), resolved)

        assertEquals(listOf("unlisted runtime dependency 'androidx.core:core' (debug, release)"), violations)
    }

    @Test
    fun `each unlisted coordinate is reported once, sorted`() {
        val violations =
            DependencyAllowlist.verify(
                emptySet(),
                resolved + ResolvedDependency("debug", "com.example:network-sdk"),
            )

        assertEquals(4, violations.size)
        assertTrue(violations.first().contains("'androidx.core:core'"))
        assertTrue(violations.any { it == "unlisted runtime dependency 'com.example:network-sdk' (debug)" })
    }

    @Test
    fun `an allowlist line with a version is malformed`() {
        val malformed = DependencyAllowlist.malformedLines("androidx.core:core\nio.insert-koin:koin-core:4.2.2\n")

        assertEquals(listOf("dependency allowlist line 2 'io.insert-koin:koin-core:4.2.2' is not group:artifact (no version)"), malformed)
    }

    @Test
    fun `a well-formed allowlist has no malformed lines`() {
        assertEquals(emptyList(), DependencyAllowlist.malformedLines(allowlistText))
    }
}
