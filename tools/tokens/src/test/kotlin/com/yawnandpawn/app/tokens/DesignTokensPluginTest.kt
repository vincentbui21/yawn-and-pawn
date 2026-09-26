package com.yawnandpawn.app.tokens

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Functional tests: generateTokens writes the file, checkTokens fails on drift. */
class DesignTokensPluginTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fixture = checkNotNull(javaClass.getResource("/fixture-design.md")).readText()

    private fun project(): File {
        val root = folder.root
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        File(root, "build.gradle.kts").writeText(
            """
            plugins { id("yawnandpawn.design-tokens") }
            designTokens {
                designFile.set(layout.projectDirectory.file("DESIGN.md"))
                outputFile.set(layout.projectDirectory.file("src/Tokens.kt"))
                packageName.set("com.example.theme")
            }
            """.trimIndent(),
        )
        File(root, "DESIGN.md").writeText(fixture)
        return root
    }

    private fun run(
        root: File,
        vararg tasks: String,
    ) = GradleRunner
        .create()
        .withProjectDir(root)
        .withPluginClasspath()
        .withArguments(*tasks, "--stacktrace")

    @Test
    fun `generateTokens writes the file and checkTokens then passes`() {
        val root = project()

        run(root, "generateTokens").build()
        val result = run(root, "checkTokens").build()

        assertTrue(File(root, "src/Tokens.kt").readText().contains("val accent = Color(0xFFD96F14)"))
        assertEquals(TaskOutcome.SUCCESS, result.task(":checkTokens")?.outcome)
    }

    @Test
    fun `checkTokens fails when DESIGN_md changed but the file was not regenerated`() {
        val root = project()
        run(root, "generateTokens").build()
        File(root, "DESIGN.md").writeText(fixture.replace("#d96f14", "#D96F15"))

        val result = run(root, "checkTokens").buildAndFail()

        assertTrue(result.output.contains("Tokens.kt is out of date with DESIGN.md"), result.output)
        assertTrue(result.output.contains("Run ./gradlew generateTokens"), result.output)
    }

    @Test
    fun `checkTokens fails when the generated file was edited by hand`() {
        val root = project()
        run(root, "generateTokens").build()
        run(root, "checkTokens").build()
        val output = File(root, "src/Tokens.kt")
        output.writeText(output.readText().replace("0xFFD96F14", "0xFF000000"))

        val result = run(root, "checkTokens").buildAndFail()

        assertTrue(result.output.contains("first difference at line"), result.output)
    }

    @Test
    fun `checkTokens fails when the file is missing`() {
        val result = run(project(), "checkTokens").buildAndFail()

        assertTrue(result.output.contains("the file does not exist"), result.output)
    }

    @Test
    fun `a bad colour fails generation naming the key`() {
        val root = project()
        File(root, "DESIGN.md").writeText(fixture.replace("'#d96f14'", "orange"))

        val result = run(root, "generateTokens").buildAndFail()

        assertTrue(result.output.contains("'colors.accent' has value 'orange'"), result.output)
    }
}
