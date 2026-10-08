package com.yawnandpawn.app.playcatalog

import java.io.File

/** Where the service-account key comes from. Never from the repository. */
sealed interface CredentialsSource {
    /** The key JSON itself, from the [Credentials.ENV_VAR] environment variable (as in CI). */
    data class FromEnvironment(
        val json: String,
    ) : CredentialsSource {
        override fun toString(): String = "FromEnvironment(${Credentials.ENV_VAR})"
    }

    /** A key file outside the repository, from `-Pcredentials=<path>`. */
    data class FromFile(
        val file: File,
    ) : CredentialsSource
}

class CredentialsException(
    message: String,
) : Exception(message)

object Credentials {
    const val ENV_VAR = "PLAY_SERVICE_ACCOUNT_JSON"

    /**
     * `-Pcredentials` ([path]) wins over the environment variable. The file must exist and lie outside [repoRoot];
     * the environment variable must hold the key JSON itself. Throws [CredentialsException] with a message for the owner.
     */
    fun resolve(
        path: String?,
        environment: Map<String, String>,
        repoRoot: File,
    ): CredentialsSource {
        if (!path.isNullOrBlank()) return fromFile(File(path.trim()), repoRoot)
        val json = environment[ENV_VAR]
        if (!json.isNullOrBlank()) {
            if (!json.trimStart().startsWith("{")) {
                throw CredentialsException(
                    "$ENV_VAR must hold the service-account key JSON itself. For a key file, pass -Pcredentials=<path> instead.",
                )
            }
            return CredentialsSource.FromEnvironment(json)
        }
        throw CredentialsException(
            "No Play credentials. Set $ENV_VAR to the service-account key JSON, or pass -Pcredentials=<path to the key file, " +
                "outside the repository>. The service account needs \"Manage store presence\" in Play Console.",
        )
    }

    private fun fromFile(
        given: File,
        repoRoot: File,
    ): CredentialsSource {
        val file = given.absoluteFile.normalize()
        val root = repoRoot.absoluteFile.normalize()
        if (file.startsWith(root)) {
            throw CredentialsException(
                "The credentials file $file is inside the repository. Keep the service-account key outside it and never commit it.",
            )
        }
        if (!file.isFile) throw CredentialsException("The credentials file $file does not exist.")
        return CredentialsSource.FromFile(file)
    }
}
