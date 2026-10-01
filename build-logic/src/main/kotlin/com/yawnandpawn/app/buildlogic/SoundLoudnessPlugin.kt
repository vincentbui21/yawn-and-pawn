package com.yawnandpawn.app.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

const val CHECK_SOUND_LOUDNESS = "checkSoundLoudness"

/** Where the bundled alarm sounds live and which UI sounds are exempt; configured in the root build file. */
abstract class SoundLoudnessExtension {
    /** Every bundled alarm sound (`res/raw/alarm_*`): each is measured. */
    abstract val alarmSounds: ConfigurableFileCollection

    /** Bundled UI sounds that are deliberately quiet (the time-wheel tick): listed as exempt, never measured. */
    abstract val exemptSounds: ConfigurableFileCollection

    /** The python measurer (`yawnandpawn.loudnessMeasurer=python`). */
    abstract val pythonScript: RegularFileProperty
}

/**
 * Applied to the root project; registers `checkSoundLoudness` (FR-SND-1, Story 1.17, a `qualityGate` dependency).
 *
 * It measures every bundled alarm sound and fails, naming the file, when the sample peak is below -3 dBFS or the
 * integrated loudness below -14 LUFS. The measurer is ffmpeg's `ebur128` filter by default (`yawnandpawn.ffmpeg`, or
 * `ffmpeg` on PATH); with `yawnandpawn.loudnessMeasurer=python` it is `tools/sounds/measure_loudness.py` through uv
 * (`yawnandpawn.uv`, or `uv` on PATH), for machines that cannot run ffmpeg.
 */
class SoundLoudnessPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "Apply yawnandpawn.sound-loudness to the root project" }
        val extension = target.extensions.create("soundLoudness", SoundLoudnessExtension::class.java)
        val properties = target.providers
        target.tasks.register(CHECK_SOUND_LOUDNESS, CheckSoundLoudnessTask::class.java) {
            group = "verification"
            description = "Fails if a bundled alarm sound peaks below -3 dBFS or is quieter than -14 LUFS (FR-SND-1)."
            alarmSounds.from(extension.alarmSounds)
            exemptSounds.from(extension.exemptSounds)
            pythonScript.set(extension.pythonScript)
            measurer.set(properties.gradleProperty("yawnandpawn.loudnessMeasurer").orElse("ffmpeg"))
            ffmpeg.set(properties.gradleProperty("yawnandpawn.ffmpeg").orElse("ffmpeg"))
            uv.set(properties.gradleProperty("yawnandpawn.uv").orElse("uv"))
            rootDirectory.set(target.layout.projectDirectory)
            marker.set(target.layout.buildDirectory.file("checkSoundLoudness/ok.txt"))
        }
    }
}

abstract class CheckSoundLoudnessTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val alarmSounds: ConfigurableFileCollection

    /** The exempt UI sounds: only listed in the report. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exemptSounds: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pythonScript: RegularFileProperty

    /** `ffmpeg` (default) or `python`. */
    @get:Input
    abstract val measurer: Property<String>

    @get:Input
    abstract val ffmpeg: Property<String>

    @get:Input
    abstract val uv: Property<String>

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val tool = tool()
        val root = rootDirectory.get().asFile
        val report =
            try {
                SoundLoudnessCheck(tool, root).check(alarmSounds.files.toList())
            } catch (e: MeasurerUnavailableException) {
                throw GradleException(e.message ?: FfmpegMeasurer.FFMPEG_INSTALL_HINT, e)
            }
        val exempt = exemptSounds.files.map { it.relativeToOrSelf(root).invariantSeparatorsPath }.sorted()
        logger.lifecycle(
            "checkSoundLoudness (${measurer.get()}): sample peak >= ${LoudnessRule.MIN_PEAK_DBFS} dBFS, " +
                "integrated >= ${LoudnessRule.MIN_LUFS} LUFS",
        )
        report.lines.forEach { logger.lifecycle("  $it") }
        exempt.forEach { logger.lifecycle("  exempt (UI sound, not measured): $it") }
        if (report.failures.isNotEmpty()) {
            throw GradleException(
                "checkSoundLoudness found ${report.failures.size} problem(s) (FR-SND-1):\n" +
                    report.failures.joinToString("\n") { "  - $it" },
            )
        }
        writeMarker(report, exempt)
    }

    private fun tool(): LoudnessMeasurer =
        when (val kind = measurer.get()) {
            "ffmpeg" -> FfmpegMeasurer(ffmpeg.get())
            "python" -> PythonMeasurer(uv.get(), pythonScript.get().asFile)
            else -> throw GradleException("yawnandpawn.loudnessMeasurer must be ffmpeg or python, not '$kind'")
        }

    private fun writeMarker(
        report: LoudnessReport,
        exempt: List<String>,
    ) {
        marker.get().asFile.writeText((report.lines + exempt.map { "exempt: $it" }).joinToString("\n", postfix = "\n"))
    }
}
