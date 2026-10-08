package com.yawnandpawn.app.playcatalog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PackageIdTest {
    private val repoRoot = File(checkNotNull(System.getProperty("yawnandpawn.repoRoot")) { "run through Gradle" })

    @Test
    fun `the tool, the package-id decision and the applicationId name the same package`() {
        val decision = repoRoot.resolve("docs/decisions/package-id.md").readText()
        val appBuild = repoRoot.resolve("androidApp/build.gradle.kts").readText()

        assertTrue("**Package name:** `${SnoozeCatalog.PACKAGE_NAME}`" in decision, "docs/decisions/package-id.md")
        assertTrue("applicationId = \"${SnoozeCatalog.PACKAGE_NAME}\"" in appBuild, "androidApp/build.gradle.kts")
    }
}
