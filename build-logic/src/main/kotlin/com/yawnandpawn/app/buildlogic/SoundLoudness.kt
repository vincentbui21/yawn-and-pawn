package com.yawnandpawn.app.buildlogic

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What `ffmpeg -af ebur128=peak=sample` reports for one file: integrated loudness (LUFS) and sample peak (dBFS). */
data class LoudnessMeasurement(
    val integratedLufs: Double,
    val samplePeakDbfs: Double,
)

/** The loudness rule of FR-SND-1 for bundled alarm sounds. */
object LoudnessRule {
    const val MIN_PEAK_DBFS = -3.0
    const val MIN_LUFS = -14.0

    /** One line per rule [measurement] misses, each naming [name]; empty when it passes. */
    fun violations(
        name: String,
        measurement: LoudnessMeasurement,
    ): List<String> =
        buildList {
            if (!(measurement.samplePeakDbfs >= MIN_PEAK_DBFS)) {
                add("$name: sample peak ${measurement.samplePeakDbfs.format()} dBFS is below $MIN_PEAK_DBFS dBFS")
            }
            if (!(measurement.integratedLufs >= MIN_LUFS)) {
                add("$name: integrated loudness ${measurement.integratedLufs.format()} LUFS is below $MIN_LUFS LUFS")
            }
        }
}

internal fun Double.format(): String = if (isInfinite()) (if (this < 0) "-inf" else "inf") else "%.1f".format(java.util.Locale.ROOT, this)

/** Parses the summary that ffmpeg's `ebur128` filter (and `tools/sounds/measure_loudness.py`) prints at the end. */
object Ebur128Summary {
    private const val NUMBER = """(-?inf|-?\d+(?:\.\d+)?)"""
    private val integrated = Regex("""I:\s+$NUMBER\s+LUFS""")
    private val peak = Regex("""Peak:\s+$NUMBER\s+dBFS""")

    /** The measurement in [output], or null when it has no complete summary (for example a file ffmpeg could not decode). */
    fun parse(output: String): LoudnessMeasurement? {
        // Empty when there is no summary, so neither value is found.
        val summary = output.substringAfterLast("Summary:", missingDelimiterValue = "")
        val lufs = integrated.find(summary)?.groupValues?.get(1)
        val dbfs = peak.find(summary.substringAfter("Sample peak:", missingDelimiterValue = ""))?.groupValues?.get(1)
        return if (lufs == null || dbfs == null) null else LoudnessMeasurement(lufs.toDecibels(), dbfs.toDecibels())
    }

    private fun String.toDecibels(): Double =
        when (this) {
            "-inf" -> Double.NEGATIVE_INFINITY
            "inf" -> Double.POSITIVE_INFINITY
            else -> toDouble()
        }
}

/** The measuring tool could not be started: the message says how to install or configure it. */
class MeasurerUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Measures one sound file; returns the tool's output, which holds an ebur128 summary. */
fun interface LoudnessMeasurer {
    /** Throws [MeasurerUnavailableException] when the tool cannot be started. */
    fun measure(file: File): String
}

/** The default measurer: `ffmpeg -af ebur128=peak=sample` (CI installs ffmpeg). */
class FfmpegMeasurer(
    private val executable: String,
) : LoudnessMeasurer {
    override fun measure(file: File): String =
        runMeasurer(
            listOf(executable, "-hide_banner", "-nostats", "-i", file.absolutePath, "-af", "ebur128=peak=sample", "-f", "null", "-"),
            unavailable = FFMPEG_INSTALL_HINT,
        )

    companion object {
        const val FFMPEG_INSTALL_HINT =
            "checkSoundLoudness needs ffmpeg to measure the bundled alarm sounds. Install it " +
                "(Ubuntu: sudo apt-get install ffmpeg; macOS: brew install ffmpeg; Windows: a portable build from " +
                "https://ffmpeg.org/download.html, no admin rights needed) and put it on PATH, or point the Gradle " +
                "property yawnandpawn.ffmpeg at the executable (for example in ~/.gradle/gradle.properties). Where " +
                "ffmpeg cannot run, set yawnandpawn.loudnessMeasurer=python to measure with " +
                "tools/sounds/measure_loudness.py through uv (yawnandpawn.uv, or uv on PATH)."
    }
}

/** The fallback measurer: `tools/sounds/measure_loudness.py` run by uv, printing the same summary as ffmpeg. */
class PythonMeasurer(
    private val uv: String,
    private val script: File,
) : LoudnessMeasurer {
    override fun measure(file: File): String =
        runMeasurer(
            listOf(uv, "run", "--quiet", "--with", "soundfile", "--with", "numpy", script.absolutePath, file.absolutePath),
            unavailable =
                "checkSoundLoudness (yawnandpawn.loudnessMeasurer=python) needs uv to run ${script.name}. Install uv " +
                    "in user scope (https://docs.astral.sh/uv/) and put it on PATH, or point the Gradle property " +
                    "yawnandpawn.uv at the executable.",
        )
}

private const val MEASURE_TIMEOUT_MINUTES = 5L

private fun runMeasurer(
    command: List<String>,
    unavailable: String,
): String {
    val process =
        try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply { environment()["PYTHONDONTWRITEBYTECODE"] = "1" }
                .start()
        } catch (e: IOException) {
            throw MeasurerUnavailableException("$unavailable (${e.message})", e)
        }
    val output = process.inputStream.bufferedReader().readText()
    // The output is read to its end, so the tool has finished or closed its output; a hung one is stopped.
    if (!process.waitFor(MEASURE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) process.destroyForcibly()
    return output
}

/** The outcome of [SoundLoudnessCheck.check]: a report line per file and every rule a file misses. */
data class LoudnessReport(
    val lines: List<String>,
    val failures: List<String>,
)

/** Measures every alarm sound with [measurer] and applies [LoudnessRule]; names are paths relative to [root]. */
class SoundLoudnessCheck(
    private val measurer: LoudnessMeasurer,
    private val root: File,
) {
    fun check(sounds: List<File>): LoudnessReport {
        val lines = mutableListOf<String>()
        val failures = mutableListOf<String>()
        if (sounds.isEmpty()) failures += "no bundled alarm sounds (res/raw/alarm_*) were found to measure"
        sounds.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            val name = file.relativeToOrSelf(root).invariantSeparatorsPath
            val measurement = Ebur128Summary.parse(measurer.measure(file))
            if (measurement == null) {
                failures += "$name: could not be measured (no ebur128 summary; is it a readable audio file?)"
            } else {
                lines += "$name: sample peak ${measurement.samplePeakDbfs.format()} dBFS, " +
                    "integrated ${measurement.integratedLufs.format()} LUFS"
                failures += LoudnessRule.violations(name, measurement)
            }
        }
        return LoudnessReport(lines, failures)
    }
}
