package com.yawnandpawn.app.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Functional tests: the plugin gathers real Gradle inputs and fails the build on violations. */
class VerifyCoreDependenciesPluginTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val modules = listOf("core", "data", "composeApp", "androidApp", "testing")

    private fun fixture(
        extraSettings: String = "",
        buildFiles: Map<String, String> = emptyMap(),
    ): File {
        val root = folder.root
        File(root, "settings.gradle.kts").writeText(
            "rootProject.name = \"fixture\"\n" +
                modules.joinToString("\n") { "include(\":$it\")" } + "\n" + extraSettings,
        )
        File(root, "build.gradle.kts").writeText("plugins { id(\"yawnandpawn.verify-core-dependencies\") }\n")
        val defaults =
            mapOf(
                "core" to "",
                "data" to "implementation(project(\":core\"))\ntestImplementation(project(\":testing\"))",
                "composeApp" to "implementation(project(\":core\"))\ntestImplementation(project(\":testing\"))",
                "androidApp" to
                    "implementation(project(\":composeApp\"))\nimplementation(project(\":data\"))\n" +
                    "implementation(project(\":core\"))\ntestImplementation(project(\":testing\"))",
                "testing" to "api(project(\":core\"))",
            )
        modules.forEach { module ->
            val dir = File(root, module).apply { mkdirs() }
            File(dir, "build.gradle.kts").writeText(
                "plugins { `java-library` }\ndependencies {\n${buildFiles[module] ?: defaults.getValue(module)}\n}\n",
            )
        }
        File(root, "core/src/commonMain/kotlin").mkdirs()
        File(root, "core/src/commonMain/kotlin/AppVersion.kt").writeText("package core\n\nimport kotlin.math.max\n")
        return root
    }

    private fun runner(root: File) =
        GradleRunner
            .create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments("verifyCoreDependencies", "--stacktrace")

    @Test
    fun `the architecture graph passes`() {
        val result = runner(fixture()).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyCoreDependencies")?.outcome)
    }

    @Test
    fun `a forbidden core dependency fails the build naming it`() {
        val root =
            fixture(
                buildFiles =
                    mapOf(
                        "core" to
                            "implementation(\"org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0\")\n" +
                            "implementation(\"io.insert-koin:koin-core:4.2.2\")",
                    ),
            )

        val result = runner(root).buildAndFail()

        assertTrue(
            result.output.contains(":core declares forbidden dependency 'io.insert-koin:koin-core' in configuration 'implementation'"),
        )
        assertTrue(!result.output.contains("kotlinx-coroutines-core'"))
    }

    @Test
    fun `an annotation processor on core fails the build`() {
        val root = fixture(buildFiles = mapOf("core" to "annotationProcessor(\"androidx.room3:room3-compiler:3.0.3\")"))

        val result = runner(root).buildAndFail()

        assertTrue(result.output.contains("'androidx.room3:room3-compiler' in configuration 'annotationProcessor'"))
    }

    @Test
    fun `composeApp depending on data fails the build naming the edge`() {
        val root =
            fixture(
                buildFiles = mapOf("composeApp" to "implementation(project(\":core\"))\nimplementation(project(\":data\"))"),
            )

        val result = runner(root).buildAndFail()

        assertTrue(result.output.contains("Forbidden module dependency 'composeApp -> data'"))
    }

    @Test
    fun `an unknown included module fails the build`() {
        val root = fixture(extraSettings = "include(\":feature\")\n")
        File(root, "feature").mkdirs()

        val result = runner(root).buildAndFail()

        assertTrue(result.output.contains("Unknown module 'feature'"))
    }
}
