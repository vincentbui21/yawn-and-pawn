package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome

/**
 * This install's id (AD-7, Story 4.8): the `obfuscatedAccountId` of every launch and `ReconcileInput.installId`. A
 * random UUID v4 made on first use and kept for the life of the install; never derived from a device or account
 * identifier, never backed up or moved to another phone, and never logged.
 */
fun interface InstallIdProvider {
    /** The install id, created and stored on the first call. Failure only for a storage error. */
    suspend fun installId(): Outcome<String, DomainError>
}
