package com.yawnandpawn.app.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Functional tests: the tasks resolve a real classpath (from a local Maven repo fixture) and read
 * manifest files, then fail the build with one line per violation. AGP wiring is exercised by
 * `./gradlew qualityGate` on the real app.
 */
class AllowlistsPluginTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fullAllowlist = "# fixture\ncom.example:network-free-lib\ncom.example:transitive-lib\n"

    private val cleanManifest =
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.INTERNET" />
            <application />
        </manifest>
        """.trimIndent()

    private fun pom(
        artifact: String,
        dependency: String? = null,
    ): String =
        """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>$artifact</artifactId>
          <version>1.0</version>
          <packaging>pom</packaging>
          ${dependency?.let {
            "<dependencies><dependency><groupId>com.example</groupId><artifactId>$it</artifactId>" +
                "<version>1.0</version></dependency></dependencies>"
        } ?: ""}
        </project>
        """.trimIndent()

    private fun fixture(
        dependencyAllowlist: String = fullAllowlist,
        permissionAllowlist: String = "INTERNET\n",
        manifest: String = cleanManifest,
        wireDependencies: Boolean = true,
        wireManifests: Boolean = true,
    ): File {
        val root = folder.root
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"\ninclude(\":androidApp\")\n")
        File(root, "build.gradle.kts").writeText("plugins { id(\"yawnandpawn.allowlists\") }\n")
        File(root, "config").mkdirs()
        File(root, "config/dependency-allowlist.txt").writeText(dependencyAllowlist)
        File(root, "config/permission-allowlist.txt").writeText(permissionAllowlist)
        listOf("network-free-lib" to "transitive-lib", "transitive-lib" to null).forEach { (artifact, dependency) ->
            val dir = File(root, "repo/com/example/$artifact/1.0").apply { mkdirs() }
            File(dir, "$artifact-1.0.pom").writeText(pom(artifact, dependency))
        }
        val app = File(root, "androidApp").apply { mkdirs() }
        File(app, "debug.xml").writeText(manifest)
        val dependencyWiring =
            if (wireDependencies) {
                "resolvedDependencies.addAll(runtimeCoordinates(\"debug\", configurations.getByName(\"runtimeClasspath\")))"
            } else {
                ""
            }
        val manifestWiring = if (wireManifests) "manifest(\"debug\", provider { layout.projectDirectory.file(\"debug.xml\") })" else ""
        File(app, "build.gradle.kts").writeText(
            """
            import com.yawnandpawn.app.buildlogic.CheckDependencyAllowlistTask
            import com.yawnandpawn.app.buildlogic.CheckPermissionAllowlistTask
            import com.yawnandpawn.app.buildlogic.runtimeCoordinates

            plugins { `java-library` }
            repositories { maven { url = uri("../repo") } }
            dependencies { implementation("com.example:network-free-lib:1.0") }

            tasks.named<CheckDependencyAllowlistTask>("checkDependencyAllowlist") {
                expectedVariants.set(setOf("debug"))
                $dependencyWiring
            }
            tasks.named<CheckPermissionAllowlistTask>("checkPermissionAllowlist") {
                expectedVariants.set(setOf("debug"))
                $manifestWiring
            }
            """.trimIndent(),
        )
        return root
    }

    private fun runner(
        root: File,
        task: String,
    ) = GradleRunner
        .create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(task, "--stacktrace")

    @Test
    fun `resolved dependencies that are all listed pass`() {
        val result = runner(fixture(), "checkDependencyAllowlist").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":androidApp:checkDependencyAllowlist")?.outcome)
    }

    @Test
    fun `removing a transitive dependency from the allowlist fails naming it`() {
        val allowlist = fullAllowlist.lines().filterNot { it == "com.example:transitive-lib" }.joinToString("\n")

        val result = runner(fixture(dependencyAllowlist = allowlist), "checkDependencyAllowlist").buildAndFail()

        assertTrue(result.output.contains("  - unlisted runtime dependency 'com.example:transitive-lib' (debug)"))
        assertFalse(result.output.contains("'com.example:network-free-lib'"))
    }

    @Test
    fun `an unwired dependency check fails instead of passing silently`() {
        val result = runner(fixture(wireDependencies = false), "checkDependencyAllowlist").buildAndFail()

        assertTrue(result.output.contains("checkDependencyAllowlist gathered runtime classpaths for variants (none) but expected [debug]"))
    }

    @Test
    fun `a dependency check missing an expected variant fails`() {
        val root = fixture()
        File(root, "androidApp/build.gradle.kts").appendText(
            "\ntasks.named<CheckDependencyAllowlistTask>(\"checkDependencyAllowlist\") { " +
                "expectedVariants.set(setOf(\"debug\", \"release\")) }\n",
        )

        val result = runner(root, "checkDependencyAllowlist").buildAndFail()

        assertTrue(result.output.contains("gathered runtime classpaths for variants [debug] but expected [debug, release]"))
    }

    @Test
    fun `a versioned allowlist line fails the build naming it`() {
        val allowlist = fullAllowlist.replace("com.example:transitive-lib", "com.example:transitive-lib:1.0")

        val result = runner(fixture(dependencyAllowlist = allowlist), "checkDependencyAllowlist").buildAndFail()

        assertTrue(
            result.output.contains("dependency allowlist line 3 'com.example:transitive-lib:1.0' is not group:artifact (no version)"),
        )
    }

    @Test
    fun `an unwired permission check fails instead of passing silently`() {
        val result = runner(fixture(wireManifests = false), "checkPermissionAllowlist").buildAndFail()

        assertTrue(result.output.contains("checkPermissionAllowlist gathered merged manifests for variants (none) but expected [debug]"))
    }

    @Test
    fun `a manifest with only listed permissions passes`() {
        val result = runner(fixture(), "checkPermissionAllowlist").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":androidApp:checkPermissionAllowlist")?.outcome)
    }

    @Test
    fun `READ_PHONE_STATE in a merged manifest fails the build naming it`() {
        val manifest =
            cleanManifest.replace(
                "<application />",
                "<uses-permission android:name=\"android.permission.READ_PHONE_STATE\" />\n<application />",
            )

        val result = runner(fixture(manifest = manifest), "checkPermissionAllowlist").buildAndFail()

        assertTrue(
            result.output.contains(
                "  - debug: uses-permission 'android.permission.READ_PHONE_STATE' is not in config/permission-allowlist.txt",
            ),
        )
    }
}
