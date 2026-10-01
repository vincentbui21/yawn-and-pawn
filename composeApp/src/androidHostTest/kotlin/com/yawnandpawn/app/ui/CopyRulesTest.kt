package com.yawnandpawn.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FR-MSG-4: every Compose Multiplatform string resource follows the copy rules. */
class CopyRulesTest {
    private val composeResources =
        File(checkNotNull(System.getProperty("yawnandpawn.composeResources")) { "yawnandpawn.composeResources not set" })

    private val androidRes =
        File(checkNotNull(System.getProperty("yawnandpawn.androidRes")) { "yawnandpawn.androidRes not set" })

    private val emDash = Char(0x2014).toString()
    private val sun = String(Character.toChars(0x1F31E))

    private fun violations(
        key: String,
        text: String,
    ) = CopyRules.violations(UiString(key, text))

    private fun words(count: Int) = List(count) { "word" }.joinToString(" ")

    @Test
    fun `all string resources follow the copy rules`() {
        val composeFiles = CopyRules.stringFiles(composeResources)
        val androidFiles = CopyRules.stringFiles(androidRes, onlyStringsFiles = true)
        val strings = (composeFiles + androidFiles).flatMap { CopyRules.read(it) }

        assertTrue(composeFiles.isNotEmpty(), "no strings.xml under $composeResources")
        assertTrue(androidFiles.isNotEmpty(), "no strings.xml under $androidRes")
        assertEquals(2, strings.count { it.key == "app_name" }, "app_name in both resource sets")
        // The ringing notification's Android strings (Story 1.14) are checked like every other string.
        listOf("notification_channel_alarms", "notification_ringing_text").forEach { key ->
            assertTrue(androidFiles.flatMap { CopyRules.read(it) }.any { it.key == key }, "$key in androidApp strings.xml")
        }
        val violations = strings.flatMap { CopyRules.violations(it) }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun `plain supportive copy passes`() {
        assertEquals(emptyList(), violations("mission_body", "This app makes money only when you snooze. We hope you never pay us."))
        assertEquals(emptyList(), violations("snooze_label", "Snooze · %1\$s"))
    }

    @Test
    fun `an em dash fails`() {
        assertEquals(listOf("'x': em dash (use a period, comma or '·')"), violations("x", "Nice try $emDash keep going."))
    }

    @Test
    fun `banned hype words fail in any case`() {
        listOf(
            "Elevate your mornings",
            "elevated",
            "elevating",
            "a SEAMLESS start",
            "seamlessly",
            "unleash it",
            "unleashed",
            "Supercharged wake-ups",
            "supercharging",
        ).forEach { text -> assertTrue(violations("x", text).single().contains("banned word"), text) }
    }

    @Test
    fun `words that only start like a banned word pass`() {
        listOf("Take the elevator.", "Elevation 300 m.", "Unleashable").forEach { text -> assertEquals(emptyList(), violations("x", text)) }
    }

    @Test
    fun `hard-coded currency symbols fail`() {
        listOf("$", "€", "£", "¥").forEach { symbol ->
            assertTrue(violations("x", "Snooze · ${symbol}3").single().contains("currency"), symbol)
        }
    }

    @Test
    fun `backup next to check fails in either order`() {
        listOf(
            "Use a backup check.",
            "Backup checks help.",
            "The check backup is here.",
            "A back-up check.",
            "A back up check.",
            "Check back up.",
        ).forEach { text ->
            assertTrue(violations("x", text).single().contains("fallback check"), text)
        }
        assertEquals(emptyList(), violations("x", "Use a fallback check."))
    }

    @Test
    fun `an emoji fails outside success_zero_snooze`() {
        assertTrue(violations("home_zero_paid", "Nothing paid $sun").single().contains("emoji"))
        assertEquals(emptyList(), violations("success_zero_snooze", "Up on time. $sun"))
    }

    @Test
    fun `success_zero_snooze allows at most one emoji`() {
        assertTrue(violations("success_zero_snooze", "Up on time. $sun$sun").single().contains("2 emoji"))
    }

    @Test
    fun `double exclamation, interrobang and joiner emoji are detected`() {
        listOf(0x203C, 0x2049, 0x200D).forEach { cp ->
            val text = "Up " + String(Character.toChars(cp))
            assertTrue(violations("x", text).single().contains("emoji"), Integer.toHexString(cp))
        }
    }

    @Test
    fun `escaped newlines and tabs separate words`() {
        val nine = List(9) { "word" }.joinToString("\\n")
        assertTrue(violations("x_headline", nine).single().contains("headline has 9 words"))
        assertTrue(violations("x_headline", List(9) { "word" }.joinToString("\\t")).single().contains("headline has 9 words"))
    }

    @Test
    fun `a headline or title over 8 words fails`() {
        assertEquals(emptyList(), violations("mission_headline", words(8)))
        assertTrue(violations("mission_headline", words(9)).single().contains("headline has 9 words"))
        assertTrue(violations("dialog_title", words(9)).single().contains("headline has 9 words"))
    }

    @Test
    fun `a body over 25 words fails`() {
        assertEquals(emptyList(), violations("disclosure_body", words(25)))
        assertTrue(violations("disclosure_body", words(26)).single().contains("body has 26 words"))
    }

    @Test
    fun `the fixture resource file fails naming each key and rule`() {
        val fixture = File(checkNotNull(javaClass.getResource("/copy-fixture/values/strings.xml")).toURI())

        val violations = CopyRules.read(fixture).flatMap { CopyRules.violations(it) }

        assertEquals(
            listOf(
                "values/strings.xml: 'bad_em_dash': em dash (use a period, comma or '·')",
                "values/strings.xml: 'bad_hype': banned word 'Seamless'",
                "values/strings.xml: 'bad_currency': hard-coded currency symbol (use a {price} argument)",
                "values/strings.xml: 'bad_backup': say 'fallback check', never 'backup check'",
                "values/strings.xml: 'bad_emoji': emoji (only 'success_zero_snooze' may have one)",
                "values/strings.xml: 'bad_long_headline': headline has 10 words (max 8)",
                "values/strings.xml: 'bad_plural': hard-coded currency symbol (use a {price} argument)",
            ),
            violations,
        )
    }
}
