package com.yawnandpawn.app.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

const val CHECK_RELEASE_CONTENT = "checkReleaseContent"

/** One compiled class of the release build: its path inside the classes output and its bytes. */
class ReleaseClass(
    val path: String,
    val bytes: ByteArray,
)

/**
 * Story 1.18 (and the Story 1.3 deferred item): the release build ships no debug-only code. Pure rules; the Gradle task
 * only gathers the merged release manifest, the release project classes and the release resource files.
 *
 * Fails on: the debug fire-now hook (`DebugFireReceiver`, `DebugFireProvider`, the `com.yawnandpawn.app.debug.FIRE`
 * action), any class in the `com.yawnandpawn.app.debug` package (the design preview `debug.preview`, the
 * `ThemeShowcase`), a `ThemeShowcase` or `.debug.preview.` component, and the preview launcher label
 * ("Yawn & Pawn Preview", `preview_launcher_label`).
 */
object ReleaseContent {
    const val DEBUG_PACKAGE_PATH = "com/yawnandpawn/app/debug/"

    private val manifestMarkers =
        listOf(
            "DebugFireReceiver",
            "DebugFireProvider",
            "com.yawnandpawn.app.debug.FIRE",
            "ThemeShowcase",
            ".debug.preview.",
            "preview_launcher_label",
            "Yawn &amp; Pawn Preview",
            "Yawn & Pawn Preview",
        )

    private val classNameMarkers = listOf("DebugFire", "ThemeShowcase")

    private val classTextMarkers = listOf("com.yawnandpawn.app.debug.FIRE", "Yawn & Pawn Preview")

    private val resourceMarkers = listOf("preview_launcher_label", "Yawn &amp; Pawn Preview", "Yawn & Pawn Preview")

    /** Every debug-only item found; empty when the release build is clean. */
    fun violations(
        manifestXml: String,
        classes: List<ReleaseClass>,
        resources: Map<String, String>,
    ): List<String> {
        val found = mutableListOf<String>()
        manifestMarkers.filter { it in manifestXml }.forEach { found += "release manifest contains '$it'" }
        classes.forEach { compiled ->
            when {
                DEBUG_PACKAGE_PATH in compiled.path -> found += "release class ${compiled.path} is in the debug package"
                classNameMarkers.any { it in compiled.path } -> found += "release class ${compiled.path} is debug-only"
            }
            val text = String(compiled.bytes, Charsets.ISO_8859_1)
            classTextMarkers.filter { it in text }.forEach { found += "release class ${compiled.path} contains '$it'" }
        }
        resources.forEach { (path, text) ->
            resourceMarkers.filter { it in text }.forEach { found += "release resource $path contains '$it'" }
        }
        return found
    }
}

/** Reads the release build's merged manifest, classes and resources and fails on any debug-only content. */
abstract class CheckReleaseContentTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    /** The release project classes (AGP's scoped `CLASSES` artifact): jars and directories. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classJars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirs: ListProperty<Directory>

    /** The release variant's resource directories. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDirs: ConfigurableFileCollection

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val classes = classJars.get().flatMap { jarClasses(it.asFile) } + classDirs.get().flatMap { dirClasses(it.asFile) }
        if (classes.isEmpty()) {
            throw GradleException("$CHECK_RELEASE_CONTENT gathered no release classes; the variant wiring is broken")
        }
        val resources =
            resourceDirs.files
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "xml" }.toList() }
                .associate { it.path to it.readText() }
        val violations = ReleaseContent.violations(manifest.get().asFile.readText(), classes, resources)
        if (violations.isNotEmpty()) {
            throw GradleException(
                "$CHECK_RELEASE_CONTENT found ${violations.size} debug-only item(s) in the release build:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
        marker.get().asFile.writeText("ok ${classes.size} classes\n")
    }

    private fun jarClasses(jar: File): List<ReleaseClass> =
        ZipFile(jar).use { zip ->
            zip
                .entries()
                .asSequence()
                .filter { it.name.endsWith(".class") }
                .map { ReleaseClass(it.name, zip.getInputStream(it).readBytes()) }
                .toList()
        }

    private fun dirClasses(dir: File): List<ReleaseClass> =
        dir
            .walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { ReleaseClass(it.relativeTo(dir).invariantSeparatorsPath, it.readBytes()) }
            .toList()
}
