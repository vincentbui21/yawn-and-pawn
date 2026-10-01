package com.yawnandpawn.app.buildlogic

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A 1 kHz mono 48 kHz 16-bit WAV tone of [seconds] at [amplitude] (1.0 = full scale), for the loudness fixtures. */
fun writeToneWav(
    file: File,
    amplitude: Double,
    seconds: Double = 3.0,
) {
    val rate = 48_000
    val frames = (rate * seconds).roundToInt()
    file.parentFile.mkdirs()
    DataOutputStream(FileOutputStream(file).buffered()).use { out ->
        fun int(value: Int) = out.writeInt(Integer.reverseBytes(value))

        fun short(value: Int) =
            out.writeShort(
                java.lang.Short
                    .reverseBytes(value.toShort())
                    .toInt(),
            )
        out.writeBytes("RIFF")
        int(36 + frames * 2)
        out.writeBytes("WAVEfmt ")
        int(16)
        short(1) // PCM
        short(1) // mono
        int(rate)
        int(rate * 2)
        short(2)
        short(16)
        out.writeBytes("data")
        int(frames * 2)
        repeat(frames) { i -> short((amplitude * Short.MAX_VALUE * sin(2 * PI * 1000.0 * i / rate)).roundToInt()) }
    }
}

/** The measurer the fixture tests run: the same tool and settings as `checkSoundLoudness` on this machine. */
fun configuredMeasurer(): LoudnessMeasurer =
    when (System.getProperty("yawnandpawn.loudnessMeasurer", "ffmpeg")) {
        "python" -> PythonMeasurer(System.getProperty("yawnandpawn.uv", "uv"), File(System.getProperty("yawnandpawn.measureScript")))
        else -> FfmpegMeasurer(System.getProperty("yawnandpawn.ffmpeg", "ffmpeg"))
    }

/** Story 1.17: the loudness rule, the ebur128 summary parser and the check over real fixture files. */
class SoundLoudnessTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The end of a real `ffmpeg -nostats -i alarm_default.ogg -af ebur128=peak=sample -f null -` run (ffmpeg 7). */
    private val ffmpegOutput =
        """
        [Parsed_ebur128_0 @ 0000021d8e0c4a40] t: 2.9      TARGET:-23 LUFS    M:  -7.9 S:-120.7     I:  -8.1 LUFS       LRA:   0.0 LU  SPK:  -0.5 dBFS
        [out#0/null @ 0000021d8e0b2e00] video:0KiB audio:563KiB subtitle:0KiB other streams:0KiB global headers:0KiB muxing overhead: unknown
        size=N/A time=00:00:03.00 bitrate=N/A speed= 412x
        [Parsed_ebur128_0 @ 0000021d8e0c4a40] Summary:

          Integrated loudness:
            I:          -8.1 LUFS
            Threshold: -18.1 LUFS

          Loudness range:
            LRA:         0.0 LU
            Threshold:   0.0 LUFS
            LRA low:     0.0 LUFS
            LRA high:    0.0 LUFS

          Sample peak:
            Peak:       -0.5 dBFS
        """.trimIndent()

    @Test
    fun `the parser reads the integrated loudness and the sample peak from the ffmpeg summary`() {
        assertEquals(LoudnessMeasurement(integratedLufs = -8.1, samplePeakDbfs = -0.5), Ebur128Summary.parse(ffmpegOutput))
    }

    @Test
    fun `the parser reads a silent file as minus infinity and needs a complete summary`() {
        val silent =
            "[Parsed_ebur128_0 @ python] Summary:\n  Integrated loudness:\n    I:  -70.0 LUFS\n  Sample peak:\n    Peak:  -inf dBFS\n"
        assertEquals(LoudnessMeasurement(-70.0, Double.NEGATIVE_INFINITY), Ebur128Summary.parse(silent))
        assertNull(Ebur128Summary.parse("Invalid data found when processing input"))
        assertNull(Ebur128Summary.parse("Summary:\n  Integrated loudness:\n    I: -9.0 LUFS\n"), "no peak")
    }

    @Test
    fun `each rule names the file and what it misses`() {
        assertEquals(emptyList(), LoudnessRule.violations("a.ogg", LoudnessMeasurement(-14.0, -3.0)), "the limits pass")
        assertEquals(
            listOf("a.ogg: sample peak -3.1 dBFS is below -3.0 dBFS"),
            LoudnessRule.violations("a.ogg", LoudnessMeasurement(-8.0, -3.1)),
        )
        assertEquals(
            listOf("a.ogg: integrated loudness -14.2 LUFS is below -14.0 LUFS"),
            LoudnessRule.violations("a.ogg", LoudnessMeasurement(-14.2, -1.0)),
        )
        assertEquals(2, LoudnessRule.violations("a.ogg", LoudnessMeasurement(-70.0, Double.NEGATIVE_INFINITY)).size)
    }

    @Test
    fun `the check reports every file by its path, and fails on an unreadable file or no files at all`() {
        val root = folder.root
        val loud = File(root, "res/raw/alarm_loud.ogg")
        val broken = File(root, "res/raw/alarm_broken.ogg")
        val outputs = mapOf(loud to ffmpegOutput, broken to "Invalid data found when processing input")

        val report = SoundLoudnessCheck({ outputs.getValue(it) }, root).check(listOf(loud, broken))

        assertEquals(listOf("res/raw/alarm_loud.ogg: sample peak -0.5 dBFS, integrated -8.1 LUFS"), report.lines)
        assertEquals(1, report.failures.size)
        assertTrue(report.failures.single().startsWith("res/raw/alarm_broken.ogg: could not be measured"))
        assertEquals(1, SoundLoudnessCheck({ ffmpegOutput }, root).check(emptyList()).failures.size)
    }

    @Test
    fun `a measurer that cannot start says how to install it`() {
        val error =
            assertFailsWith<MeasurerUnavailableException> {
                FfmpegMeasurer(File(folder.root, "no-such-ffmpeg").absolutePath).measure(File(folder.root, "a.ogg"))
            }
        assertTrue("sudo apt-get install ffmpeg" in error.message.orEmpty())
        assertTrue("yawnandpawn.ffmpeg" in error.message.orEmpty())
    }

    @Test
    fun `fixture files - a compliant tone passes and a quiet tone fails naming the file`() {
        val root = folder.root
        val loud = File(root, "raw/alarm_loud.wav").also { writeToneWav(it, amplitude = 0.9) }
        val quiet = File(root, "raw/alarm_quiet.wav").also { writeToneWav(it, amplitude = 0.1) }
        val check = SoundLoudnessCheck(configuredMeasurer(), root)

        val passing = check.check(listOf(loud))
        assertEquals(emptyList(), passing.failures, passing.lines.toString())
        val measured = assertNotNull(Ebur128Summary.parse(configuredMeasurer().measure(loud)))
        assertEquals(-0.9, measured.samplePeakDbfs, 0.2)

        val failing = check.check(listOf(quiet))
        assertEquals(2, failing.failures.size, failing.failures.toString())
        assertTrue(failing.failures.all { it.startsWith("raw/alarm_quiet.wav: ") }, failing.failures.toString())
    }
}
