package com.yawnandpawn.app.playcatalog

import org.junit.Assume
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialsTest {
    private val temp: File = Files.createTempDirectory("play-catalog-credentials").toFile()
    private val repoRoot = temp.resolve("repo").apply { mkdirs() }
    private val keyOutside = temp.resolve("keys/play.json").apply { parentFile.mkdirs() }.apply { writeText("{}") }
    private val json = """{"type": "service_account"}"""

    private fun real(file: File): File = file.toPath().toRealPath().toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun failure(
        path: String?,
        environment: Map<String, String> = emptyMap(),
    ): String = assertFailsWith<CredentialsException> { Credentials.resolve(path, environment, repoRoot) }.message.orEmpty()

    @Test
    fun `neither the variable nor a path fails with a clear message`() {
        val message = failure(null)

        assertTrue("No Play credentials." in message, message)
        assertTrue("PLAY_SERVICE_ACCOUNT_JSON" in message && "-Pcredentials=" in message, message)
        assertEquals(message, failure("  ", mapOf("PLAY_SERVICE_ACCOUNT_JSON" to " ")))
    }

    @Test
    fun `the environment variable holds the key JSON`() {
        val source = Credentials.resolve(null, mapOf("PLAY_SERVICE_ACCOUNT_JSON" to json), repoRoot)

        assertEquals(CredentialsSource.FromEnvironment(json), source)
        assertFalse("service_account" in source.toString(), "the key must never be printed")
    }

    @Test
    fun `a path in the environment variable is refused with a hint`() {
        val message = failure(null, mapOf("PLAY_SERVICE_ACCOUNT_JSON" to keyOutside.path))

        assertTrue("must hold the service-account key JSON itself" in message, message)
    }

    @Test
    fun `a key file outside the repository is used, and wins over the variable`() {
        val source = Credentials.resolve(keyOutside.path, mapOf("PLAY_SERVICE_ACCOUNT_JSON" to json), repoRoot)

        assertEquals(CredentialsSource.FromFile(real(keyOutside)), source)
    }

    @Test
    fun `a key file inside the repository is refused`() {
        val inside = repoRoot.resolve("androidApp/key.json").apply { parentFile.mkdirs() }.apply { writeText("{}") }

        val message = failure(inside.path)

        assertTrue("is inside the repository" in message, message)
        assertTrue("inside the repository" in failure(repoRoot.resolve("androidApp/../androidApp/key.json").path))
    }

    @Test
    fun `a key reached through a junction or link into the repository is refused`() {
        val target = repoRoot.resolve("secrets").apply { mkdirs() }
        target.resolve("key.json").writeText("{}")
        val link = temp.resolve("link")
        val linked =
            runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess ||
                (
                    System.getProperty("os.name").startsWith("Windows") &&
                        ProcessBuilder("cmd", "/c", "mklink", "/J", link.path, target.path).start().waitFor() == 0
                )
        Assume.assumeTrue("cannot create a link or junction here", linked && link.resolve("key.json").isFile)

        val message = failure(link.resolve("key.json").path)

        assertTrue("is inside the repository" in message, message)
    }

    @Test
    fun `from a worktree, a key in the main checkout is refused too`() {
        val main = temp.resolve("main").apply { resolve(".git/worktrees/w1").mkdirs() }
        val worktree = main.resolve(".claude/worktrees/w1").apply { mkdirs() }
        worktree.resolve(".git").writeText("gitdir: ${main.resolve(".git/worktrees/w1").path}\n")
        val keyInMain = main.resolve("play.json").apply { writeText("{}") }

        assertEquals(listOf(real(worktree), real(main)), Credentials.checkouts(worktree))
        val message = assertFailsWith<CredentialsException> { Credentials.resolve(keyInMain.path, emptyMap(), worktree) }.message.orEmpty()
        assertTrue("is inside the repository (${real(main)})" in message, message)
        assertEquals(CredentialsSource.FromFile(real(keyOutside)), Credentials.resolve(keyOutside.path, emptyMap(), worktree))
    }

    @Test
    fun `a missing key file is refused`() {
        val message = failure(temp.resolve("nope.json").path)

        assertTrue("does not exist" in message, message)
    }
}
