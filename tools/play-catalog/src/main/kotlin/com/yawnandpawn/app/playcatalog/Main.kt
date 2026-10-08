package com.yawnandpawn.app.playcatalog

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.AndroidPublisherScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import kotlin.system.exitProcess

/** Entry point of `./gradlew playCatalog` (the root build passes `--mode`, `--repo-root` and, if given, `--credentials`). */
fun main(args: Array<String>) {
    exitProcess(PlayCatalogMain.run(args.toList(), System.getenv(), ::println, PlayCatalogMain::connect))
}

object PlayCatalogMain {
    const val USAGE =
        "Usage: ./gradlew playCatalog [-PplayCatalogMode=dry-run|apply] [-Pcredentials=<service-account key file outside the repo>] " +
            "(or set ${Credentials.ENV_VAR} to the key JSON)"

    private const val APPLICATION_NAME = "yawn-and-pawn-play-catalog"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val UNKNOWN = "?"

    /** Parses [args], resolves the credentials, connects through [connect] and runs. Returns the exit code. */
    fun run(
        args: List<String>,
        environment: Map<String, String>,
        out: (String) -> Unit,
        connect: (CredentialsSource) -> PlayCatalogApi,
    ): Int {
        val options = parseOptions(args)
        val mode = Mode.parse(options["--mode"])
        val repoRoot = options["--repo-root"]?.let(::File)
        val api =
            when {
                mode == null -> usageError(out, "Missing or unknown mode '${options["--mode"].orEmpty()}'.", USAGE)
                options.containsKey(UNKNOWN) || repoRoot == null -> usageError(out, "Unexpected arguments.", USAGE)
                else -> connectOrReport(options["--credentials"], environment, repoRoot, out, connect)
            }
        return if (mode == null || api == null) ExitCode.USAGE else CatalogRunner(api, out).run(mode)
    }

    private fun usageError(
        out: (String) -> Unit,
        vararg lines: String,
    ): PlayCatalogApi? {
        lines.forEach(out)
        return null
    }

    /** Resolves the credentials and connects; prints the reason and returns null when either fails. */
    private fun connectOrReport(
        credentialsPath: String?,
        environment: Map<String, String>,
        repoRoot: File,
        out: (String) -> Unit,
        connect: (CredentialsSource) -> PlayCatalogApi,
    ): PlayCatalogApi? =
        try {
            connect(Credentials.resolve(credentialsPath, environment, repoRoot))
        } catch (e: CredentialsException) {
            usageError(out, e.message.orEmpty())
        } catch (e: IOException) {
            usageError(out, "Could not read the service-account key: ${e.message}")
        } catch (e: GeneralSecurityException) {
            usageError(out, "Could not set up a secure connection: ${e.message}")
        }

    /** `--name value` pairs; anything else is recorded under [UNKNOWN]. */
    fun parseOptions(args: List<String>): Map<String, String> {
        val known = setOf("--mode", "--credentials", "--repo-root")
        val options = mutableMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val name = args[index]
            val value = args.getOrNull(index + 1)
            if (name in known && value != null) options[name] = value else options[UNKNOWN] = name
            index += 2
        }
        return options
    }

    /** The real API client: service-account credentials scoped to `androidpublisher`, no automatic retries of writes. */
    fun connect(source: CredentialsSource): PlayCatalogApi {
        val credentials =
            when (source) {
                is CredentialsSource.FromEnvironment -> readKey(source.json.byteInputStream())
                is CredentialsSource.FromFile -> source.file.inputStream().use(::readKey)
            }
        return GooglePlayCatalogApi(publisher(credentials, GoogleNetHttpTransport.newTrustedTransport()))
    }

    /**
     * The API client. Every request is sent exactly once: [HttpCredentialsAdapter] would re-send a request after a 401
     * (refreshing the token), and the client could retry on an IO error, so both handlers are removed. A write is
     * never repeated blindly; the token is still refreshed before each request when it is about to expire.
     */
    fun publisher(
        credentials: GoogleCredentials,
        transport: HttpTransport,
    ): AndroidPublisher {
        val adapter = HttpCredentialsAdapter(credentials)
        val initializer =
            HttpRequestInitializer { request ->
                adapter.initialize(request)
                request.unsuccessfulResponseHandler = null
                request.ioExceptionHandler = null
                request.numberOfRetries = 0
                request.connectTimeout = CONNECT_TIMEOUT_MS
                request.readTimeout = READ_TIMEOUT_MS
            }
        return AndroidPublisher
            .Builder(transport, GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName(APPLICATION_NAME)
            .build()
    }

    private fun readKey(stream: InputStream) =
        ServiceAccountCredentials.fromStream(stream).createScoped(listOf(AndroidPublisherScopes.ANDROIDPUBLISHER))
}
