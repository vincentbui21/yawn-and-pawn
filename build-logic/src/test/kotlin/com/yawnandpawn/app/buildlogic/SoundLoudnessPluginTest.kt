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

/** Story 1.17: `checkSoundLoudness` as a task, on a fixture project with generated sounds. */
class SoundLoudnessPluginTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture(quiet: Boolean): File {
        val root = folder.root
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        val script = File(System.getProperty("yawnandpawn.measureScript")).invariantSeparatorsPath
        File(root, "build.gradle.kts").writeText(
            """
            plugins { id("yawnandpawn.sound-loudness") }
            soundLoudness {
                alarmSounds.from(fileTree("app/res/raw") { include("alarm_*") })
                exemptSounds.from("ui/res/raw/wheel_tick.wav")
                pythonScript.set(file("$script"))
            }
            """.trimIndent(),
        )
        writeToneWav(File(root, "app/res/raw/alarm_loud.wav"), amplitude = 0.9)
        if (quiet) writeToneWav(File(root, "app/res/raw/alarm_quiet.wav"), amplitude = 0.1)
        writeToneWav(File(root, "ui/res/raw/wheel_tick.wav"), amplitude = 0.05, seconds = 0.012)
        return root
    }

    private fun runner(
        root: File,
        vararg properties: String,
    ): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments(listOf("checkSoundLoudness", "--stacktrace") + properties)

    private val configuredTool: Array<String>
        get() =
            listOf("loudnessMeasurer", "ffmpeg", "uv")
                .map { "-Pyawnandpawn.$it=${System.getProperty("yawnandpawn.$it")}" }
                .toTypedArray()

    @Test
    fun `loud alarm sounds pass, their values are printed and the UI tick is listed as exempt, not measured`() {
        val result = runner(fixture(quiet = false), *configuredTool).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkSoundLoudness")?.outcome)
        assertTrue("app/res/raw/alarm_loud.wav: sample peak" in result.output, result.output)
        assertTrue("exempt (UI sound, not measured): ui/res/raw/wheel_tick.wav" in result.output, result.output)
        assertFalse("wheel_tick.wav: sample peak" in result.output, "the tick is not measured")
    }

    @Test
    fun `a quiet alarm sound fails the task, naming the file`() {
        val result = runner(fixture(quiet = true), *configuredTool).buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":checkSoundLoudness")?.outcome)
        assertTrue("app/res/raw/alarm_quiet.wav: sample peak" in result.output, result.output)
        assertTrue("app/res/raw/alarm_quiet.wav: integrated loudness" in result.output, result.output)
        assertFalse("alarm_loud.wav: integrated loudness" in result.output)
    }

    @Test
    fun `without ffmpeg the task fails with an install hint`() {
        val missing = File(folder.root, "no-such-ffmpeg").invariantSeparatorsPath
        val result = runner(fixture(quiet = false), "-Pyawnandpawn.loudnessMeasurer=ffmpeg", "-Pyawnandpawn.ffmpeg=$missing").buildAndFail()

        assertTrue("checkSoundLoudness needs ffmpeg" in result.output, result.output)
        assertTrue("sudo apt-get install ffmpeg" in result.output, result.output)
    }
}
