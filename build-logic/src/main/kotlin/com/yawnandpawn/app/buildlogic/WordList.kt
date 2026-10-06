package com.yawnandpawn.app.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

const val CHECK_WORD_LIST = "checkWordList"

/**
 * Story 3.7 (NFR-10): the Word Unscramble list `words_en.txt` is plain, everyday English. Pure rules; the task only
 * reads the two files.
 *
 * Fails on an entry that is not lowercase a–z (a blank line too), a duplicate, a word outside 4–10 letters, a word on
 * the blocklist or with a blocklisted word's letters (a scramble could spell it), and a length group (4–5, 6–7, 8–10)
 * with fewer than [MIN_PER_GROUP] words.
 */
object WordListRules {
    const val MIN_PER_GROUP = 300
    private val LENGTHS = 4..10
    val GROUPS: List<IntRange> = listOf(4..5, 6..7, 8..10)
    private val LETTERS = Regex("[a-z]+")

    /** The blocklist's words: one per line, `#` comments and blank lines ignored, lowercased. */
    fun blocklist(text: String): Set<String> =
        text
            .lineSequence()
            .map { it.substringBefore('#').trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Every problem of the list [text] against [blocked]; empty when it passes. */
    fun violations(
        text: String,
        blocked: Set<String>,
    ): List<String> {
        val found = mutableListOf<String>()
        val entries =
            text
                .removeSuffix("\n")
                .removeSuffix("\r")
                .lines()
                .map { it.removeSuffix("\r") }
        val seen = mutableSetOf<String>()
        // A scramble is a permutation of a listed word: one with a blocklisted word's letters could spell it (review fix).
        val blockedByLetters = blocked.associateBy { it.sortedLetters() }
        entries.forEachIndexed { index, word ->
            val line = index + 1
            val anagram = blockedByLetters[word.sortedLetters()]
            when {
                !LETTERS.matches(word) -> found += "line $line: '$word' is not lowercase a-z"
                word.length !in LENGTHS -> found += "line $line: '$word' has ${word.length} letters, not 4 to 10"
                word in blocked -> found += "line $line: '$word' is on the blocklist"
                anagram != null -> found += "line $line: '$word' has the letters of blocklisted '$anagram', so a scramble could spell it"
            }
            if (!seen.add(word)) found += "line $line: '$word' is listed twice"
        }
        GROUPS.forEach { group ->
            val size = seen.count { LETTERS.matches(it) && it.length in group }
            if (size < MIN_PER_GROUP) found += "only $size words of ${group.first}-${group.last} letters, need $MIN_PER_GROUP"
        }
        return found
    }

    private fun String.sortedLetters(): String = toCharArray().sorted().joinToString("")
}

/** Checks the word list against the blocklist and fails with every problem it finds. */
abstract class CheckWordListTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wordList: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val blocklist: RegularFileProperty

    @TaskAction
    fun check() {
        val problems =
            WordListRules.violations(
                wordList.get().asFile.readText(),
                WordListRules.blocklist(blocklist.get().asFile.readText()),
            )
        if (problems.isNotEmpty()) throw GradleException("Word list ${wordList.get().asFile.name}:\n" + problems.joinToString("\n"))
    }
}

/** The `wordList { }` settings of [WordListPlugin]. */
abstract class WordListExtension {
    abstract val wordList: RegularFileProperty
    abstract val blocklist: RegularFileProperty
}

/** Registers [CHECK_WORD_LIST] (a `qualityGate` dependency) on the root project. */
class WordListPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("wordList", WordListExtension::class.java)
        project.tasks.register(CHECK_WORD_LIST, CheckWordListTask::class.java) {
            group = "verification"
            description = "Checks the Word Unscramble list: a-z, unique, 4-10 letters, not blocklisted, 300 per length group."
            wordList.set(extension.wordList)
            blocklist.set(extension.blocklist)
        }
    }
}
