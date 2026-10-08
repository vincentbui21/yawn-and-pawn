package com.yawnandpawn.app.playcatalog

import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainTest {
    private val output = mutableListOf<String>()
    private val connected = mutableListOf<CredentialsSource>()
    private val api = FakePlayCatalogApi()
    private val repoRoot = File("build/not-a-repo").absolutePath
    private val env = mapOf("PLAY_SERVICE_ACCOUNT_JSON" to "{}")

    private fun run(
        vararg args: String,
        environment: Map<String, String> = env,
        connect: (CredentialsSource) -> PlayCatalogApi = {
            connected += it
            api
        },
    ): Int = PlayCatalogMain.run(args.toList(), environment, { output += it }, connect)

    @Test
    fun `a dry run with credentials from the environment runs against the API`() {
        val exit = run("--mode", "dry-run", "--repo-root", repoRoot)

        assertEquals(ExitCode.OK, exit)
        assertEquals(listOf<CredentialsSource>(CredentialsSource.FromEnvironment("{}")), connected)
        assertEquals("list", api.calls.first())
        assertTrue(api.writes.isEmpty())
    }

    @Test
    fun `a missing or unknown mode prints the usage and connects to nothing`() {
        assertEquals(ExitCode.USAGE, run("--repo-root", repoRoot))
        assertEquals("Missing or unknown mode ''.", output.first())
        assertEquals(PlayCatalogMain.USAGE, output.last())

        output.clear()
        assertEquals(ExitCode.USAGE, run("--mode", "delete", "--repo-root", repoRoot))
        assertEquals("Missing or unknown mode 'delete'.", output.first())
        assertEquals(ExitCode.USAGE, run("--mode", "", "--repo-root", repoRoot))
        assertEquals(ExitCode.USAGE, run("--mode", "apply", "--repo-root", repoRoot, "--force"))
        assertTrue(connected.isEmpty())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `missing credentials stop before connecting`() {
        val exit = run("--mode", "apply", "--repo-root", repoRoot, environment = emptyMap())

        assertEquals(ExitCode.USAGE, exit)
        assertTrue(output.single().startsWith("No Play credentials."), output.toString())
        assertTrue(connected.isEmpty())
    }

    @Test
    fun `an unreadable key stops with a message`() {
        val exit = run("--mode", "dry-run", "--repo-root", repoRoot, connect = { throw IOException("bad key") })

        assertEquals(ExitCode.USAGE, exit)
        assertEquals(listOf("Could not read the service-account key: bad key"), output)
    }

    @Test
    fun `options are name value pairs`() {
        assertEquals(
            mapOf("--mode" to "apply", "--credentials" to "C:/keys/play.json", "--repo-root" to "/repo"),
            PlayCatalogMain.parseOptions(listOf("--mode", "apply", "--credentials", "C:/keys/play.json", "--repo-root", "/repo")),
        )
        assertTrue(PlayCatalogMain.parseOptions(listOf("--mode")).keys == setOf("?"))
    }
}
