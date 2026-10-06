package com.yawnandpawn.app.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 3.7: `checkWordList` rules, one fixture per failure, and the task on a fixture project. */
class WordListTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A valid list: [MIN_PER_GROUP] distinct words in each length group, from letters only. */
    private val valid: List<String> =
        WordListRules.GROUPS.flatMap { group -> List(WordListRules.MIN_PER_GROUP) { n -> word(n, group.first) } }

    /** A distinct lowercase word of [length] letters for [n] (base-26 digits padded with 'a'). */
    private fun word(
        n: Int,
        length: Int,
    ): String {
        var value = n
        val letters = CharArray(length) { 'a' }
        for (i in length - 1 downTo 0) {
            letters[i] = 'a' + value % 26
            value /= 26
        }
        return "q" + String(letters).drop(1)
    }

    private fun violations(
        words: List<String>,
        blocked: Set<String> = setOf("zzzz"),
    ): List<String> = WordListRules.violations(words.joinToString("\n", postfix = "\n"), blocked)

    @Test
    fun `a clean list passes`() {
        assertEquals(emptyList(), violations(valid))
    }

    @Test
    fun `an entry that is not lowercase a-z fails, a blank line too`() {
        listOf("Apple", "café", "two words", "dash-y", "").forEach { bad ->
            assertTrue(violations(valid + bad).any { "is not lowercase a-z" in it }, bad)
        }
    }

    @Test
    fun `a duplicate fails`() {
        assertEquals(listOf("line ${valid.size + 1}: '${valid[0]}' is listed twice"), violations(valid + valid[0]))
    }

    @Test
    fun `a word shorter than 4 or longer than 10 letters fails`() {
        assertTrue(violations(valid + "cat").single().contains("'cat' has 3 letters"))
        assertTrue(violations(valid + "abcdefghijk").single().contains("has 11 letters"))
    }

    @Test
    fun `a blocklisted word fails`() {
        assertEquals(listOf("line ${valid.size + 1}: 'zzzz' is on the blocklist"), violations(valid + "zzzz"))
    }

    @Test
    fun `a word with a blocklisted word's letters fails, since a scramble could spell it (review fix)`() {
        assertEquals(
            listOf("line ${valid.size + 1}: 'abcd' has the letters of blocklisted 'dcba', so a scramble could spell it"),
            violations(valid + "abcd", blocked = setOf("dcba")),
        )
    }

    @Test
    fun `line endings and encoding, CRLF and no final newline pass, a BOM fails line 1, a blank middle line is named`() {
        val blocked = setOf("zzzz")
        assertEquals(emptyList(), WordListRules.violations(valid.joinToString("\r\n", postfix = "\r\n"), blocked), "CRLF")
        assertEquals(emptyList(), WordListRules.violations(valid.joinToString("\n"), blocked), "no final newline")
        assertTrue(
            WordListRules.violations("﻿" + valid.joinToString("\n", postfix = "\n"), blocked).any { it.startsWith("line 1: ") },
            "a byte order mark",
        )
        val blank = valid.take(10) + "" + valid.drop(10)
        assertTrue("line 11: '' is not lowercase a-z" in WordListRules.violations(blank.joinToString("\n", postfix = "\n"), blocked))
    }

    @Test
    fun `a length group under 300 words fails`() {
        val short = valid.filterNot { it.length in 8..10 }.toMutableList() + valid.filter { it.length in 8..10 }.drop(1)

        assertEquals(listOf("only 299 words of 8-10 letters, need 300"), violations(short))
    }

    @Test
    fun `the blocklist ignores comments and blank lines and lowercases`() {
        assertEquals(setOf("abcd", "efgh"), WordListRules.blocklist("# heading\n\nABCD # note\n  efgh\n"))
    }

    @Test
    fun `the task fails on a bad list and passes on a clean one`() {
        val root = folder.root
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")
        File(root, "build.gradle.kts").writeText(
            """
            plugins { id("yawnandpawn.word-list") }
            wordList {
                wordList.set(file("words_en.txt"))
                blocklist.set(file("word-blocklist.txt"))
            }
            """.trimIndent(),
        )
        File(root, "word-blocklist.txt").writeText("# test\nzzzz\n")
        val list = File(root, "words_en.txt")
        val runner =
            GradleRunner
                .create()
                .withProjectDir(root)
                .withPluginClasspath()
                .withArguments(CHECK_WORD_LIST)

        list.writeText((valid + "zzzz").joinToString("\n", postfix = "\n"))
        val failed = runner.buildAndFail()
        assertTrue("'zzzz' is on the blocklist" in failed.output, failed.output)

        list.writeText(valid.joinToString("\n", postfix = "\n"))
        assertEquals(TaskOutcome.SUCCESS, runner.build().task(":$CHECK_WORD_LIST")?.outcome)
    }
}
