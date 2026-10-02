package com.yawnandpawn.app.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 1.18, functional: `checkReleaseContent` reads a manifest, class directories, class jars and resource
 * directories, and fails the build on debug-only content or on wiring that gathered nothing. The AGP wiring itself is
 * exercised by `./gradlew qualityGate` on the real app.
 */
class CheckReleaseContentTaskTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cleanManifest =
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:label="@string/app_name" />
        </manifest>
        """.trimIndent()

    private val cleanStrings = "<resources><string name=\"app_name\">Yawn &amp; Pawn</string></resources>"

    private fun fixture(
        dirClasses: List<String> = listOf("com/yawnandpawn/app/MainActivity.class"),
        jarClasses: List<String> = listOf("com/yawnandpawn/app/core/Clock.class"),
        strings: String = cleanStrings,
        wireClasses: Boolean = true,
        wireResources: Boolean = true,
    ): File {
        val root = folder.root
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"\ninclude(\":androidApp\")\n")
        File(root, "build.gradle.kts").writeText("plugins { id(\"yawnandpawn.allowlists\") }\n")
        val app = File(root, "androidApp").apply { mkdirs() }
        File(app, "release.xml").writeText(cleanManifest)
        dirClasses.forEach { path -> File(app, "classes/$path").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(0)) }
        ZipOutputStream(File(app, "classes.jar").outputStream()).use { jar ->
            jarClasses.forEach { path ->
                jar.putNextEntry(ZipEntry(path))
                jar.write(0)
                jar.closeEntry()
            }
        }
        File(app, "res/values").mkdirs()
        File(app, "res/values/strings.xml").writeText(strings)
        val classWiring =
            if (wireClasses) {
                "classDirs.add(layout.projectDirectory.dir(\"classes\"))\nclassJars.add(layout.projectDirectory.file(\"classes.jar\"))"
            } else {
                ""
            }
        val resourceWiring = if (wireResources) "resourceDirs.from(\"res\")" else ""
        File(app, "build.gradle.kts").writeText(
            """
            import com.yawnandpawn.app.buildlogic.CheckReleaseContentTask

            tasks.named<CheckReleaseContentTask>("checkReleaseContent") {
                manifest.set(layout.projectDirectory.file("release.xml"))
                $classWiring
                $resourceWiring
            }
            """.trimIndent(),
        )
        return root
    }

    private fun runner(root: File) =
        GradleRunner
            .create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments(CHECK_RELEASE_CONTENT, "--stacktrace")

    @Test
    fun `a clean release build passes`() {
        val result = runner(fixture()).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":androidApp:$CHECK_RELEASE_CONTENT")?.outcome)
    }

    @Test
    fun `a debug class in a class directory fails the build`() {
        val result = runner(fixture(dirClasses = listOf("com/yawnandpawn/app/debug/X.class"))).buildAndFail()

        assertTrue("debug-only item(s)" in result.output, result.output)
        assertTrue("com/yawnandpawn/app/debug/X.class" in result.output, result.output)
    }

    @Test
    fun `a debug class in a class jar fails the build`() {
        val result = runner(fixture(jarClasses = listOf("com/yawnandpawn/app/debug/preview/X.class"))).buildAndFail()

        assertTrue("debug-only item(s)" in result.output, result.output)
        assertTrue("com/yawnandpawn/app/debug/preview/X.class" in result.output, result.output)
    }

    @Test
    fun `the preview launcher label in a resource directory fails the build`() {
        val strings = "<resources><string name=\"preview_launcher_label\">Yawn &amp; Pawn Preview</string></resources>"

        val result = runner(fixture(strings = strings)).buildAndFail()

        assertTrue("debug-only item(s)" in result.output, result.output)
        assertTrue("preview_launcher_label" in result.output, result.output)
    }

    @Test
    fun `wiring that gathered no classes fails the build`() {
        val result = runner(fixture(wireClasses = false)).buildAndFail()

        assertTrue("gathered no release classes" in result.output, result.output)
    }

    @Test
    fun `wiring that gathered no resources fails the build`() {
        val result = runner(fixture(wireResources = false)).buildAndFail()

        assertTrue("gathered no release resources" in result.output, result.output)
    }
}
