package com.yawnandpawn.app.ui.theme

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * docs/decisions/geist-tnum.md: every bundled Geist weight has the OpenType `tnum` feature, so
 * `fontFeatureSettings = "tnum"` works and Geist Mono is not needed for `clock-xl`.
 */
class GeistFontTest {
    private val fontDir =
        File(checkNotNull(System.getProperty("yawnandpawn.composeResources")) { "yawnandpawn.composeResources not set" }, "font")

    @Test
    fun `the four ramp weights of Geist are bundled`() {
        assertEquals(
            listOf("geist_light.ttf", "geist_medium.ttf", "geist_regular.ttf", "geist_semibold.ttf"),
            fontDir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted(),
        )
    }

    @Test
    fun `every bundled Geist weight has tabular figures`() {
        fontDir.listFiles().orEmpty().forEach { font ->
            assertTrue("tnum" in gsubFeatureTags(font.readBytes()), "${font.name} has no tnum feature")
        }
    }

    /** Feature tags of the OpenType GSUB table (table directory -> GSUB header -> FeatureList). */
    private fun gsubFeatureTags(bytes: ByteArray): Set<String> {
        val buffer = ByteBuffer.wrap(bytes)
        val tableCount = buffer.getShort(4).toInt() and 0xFFFF
        val gsub =
            (0 until tableCount)
                .map { 12 + 16 * it }
                .firstOrNull { String(bytes, it, 4, Charsets.US_ASCII) == "GSUB" }
                ?.let { buffer.getInt(it + 8) }
                ?: return emptySet()
        val featureList = gsub + (buffer.getShort(gsub + 6).toInt() and 0xFFFF)
        val featureCount = buffer.getShort(featureList).toInt() and 0xFFFF
        return (0 until featureCount).map { String(bytes, featureList + 2 + 6 * it, 4, Charsets.US_ASCII) }.toSet()
    }
}
