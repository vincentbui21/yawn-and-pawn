package com.yawnandpawn.app.tokens

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Configured in the root build file: where DESIGN.md is and where `PpsTokens.kt` goes. */
abstract class DesignTokensExtension {
    abstract val designFile: RegularFileProperty
    abstract val outputFile: RegularFileProperty
    abstract val packageName: Property<String>
}

/**
 * Applied to the root project; registers `generateTokens` (writes the committed `PpsTokens.kt`)
 * and `checkTokens` (regenerates into `build/` and fails on any difference) (AD-10).
 */
class DesignTokensPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "Apply yawnandpawn.design-tokens to the root project" }
        val extension = target.extensions.create("designTokens", DesignTokensExtension::class.java)
        val rootDir = target.rootDir
        target.tasks.register("generateTokens", GenerateTokensTask::class.java) {
            group = "build"
            description = "Generates PpsTokens.kt from the DESIGN.md frontmatter."
            designFile.set(extension.designFile)
            packageName.set(extension.packageName)
            sourcePath.set(extension.designFile.map { it.asFile.relativeTo(rootDir).invariantSeparatorsPath })
            outputFile.set(extension.outputFile)
        }
        target.tasks.register("checkTokens", CheckTokensTask::class.java) {
            group = "verification"
            description = "Fails if the committed PpsTokens.kt differs from what DESIGN.md generates."
            designFile.set(extension.designFile)
            packageName.set(extension.packageName)
            sourcePath.set(extension.designFile.map { it.asFile.relativeTo(rootDir).invariantSeparatorsPath })
            committedFile.set(extension.outputFile)
            regeneratedFile.set(target.layout.buildDirectory.file("tokens/PpsTokens.kt"))
        }
    }
}

abstract class TokenSourceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val designFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    /** DESIGN.md path relative to the repo root, written into the file header. */
    @get:Input
    abstract val sourcePath: Property<String>

    protected fun renderTokens(): String {
        val design = designFile.get().asFile
        val tokens =
            try {
                DesignTokenParser.parse(design.readText())
            } catch (e: TokenParseException) {
                throw GradleException("${design.name}: ${e.message}", e)
            }
        return TokenSourceWriter.render(tokens, packageName.get(), sourcePath.get())
    }
}

abstract class GenerateTokensTask : TokenSourceTask() {
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        outputFile
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(renderTokens())
    }
}

abstract class CheckTokensTask : TokenSourceTask() {
    /** The committed file. @InputFiles (not @InputFile) so a missing file reaches our message instead of a Gradle error. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val committedFile: RegularFileProperty

    @get:OutputFile
    abstract val regeneratedFile: RegularFileProperty

    @TaskAction
    fun check() {
        val expected = renderTokens()
        regeneratedFile
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(expected)
        val committed = committedFile.get().asFile
        val difference = TokenDrift.firstDifference(expected, committed.takeIf(File::isFile)?.readText())
        if (difference != null) {
            throw GradleException(
                "${committed.name} is out of date with ${designFile.get().asFile.name} ($difference). " +
                    "Run ./gradlew generateTokens and commit the result; never edit ${committed.name} by hand.",
            )
        }
    }
}

/** Compares generated and committed sources, ignoring only CRLF vs LF. */
object TokenDrift {
    fun firstDifference(
        expected: String,
        committed: String?,
    ): String? {
        if (committed == null) return "the file does not exist"
        val want = expected.replace("\r\n", "\n").lines()
        val have = committed.replace("\r\n", "\n").lines()
        val index = (0 until maxOf(want.size, have.size)).firstOrNull { want.getOrNull(it) != have.getOrNull(it) }
        return index?.let {
            "first difference at line ${it + 1}: expected '${want.getOrNull(it).orEmpty()}', found '${have.getOrNull(it).orEmpty()}'"
        }
    }
}
