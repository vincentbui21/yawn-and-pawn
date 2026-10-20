package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.InstallIdProvider
import com.yawnandpawn.app.core.billing.InstallIdReset
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome

/**
 * [InstallIdProvider] returning [id] (a fixed UUID v4), or [failure] when set; [calls] counts the reads. Also the
 * [InstallIdReset] of "Delete all data" (Story 5.9): a reset counts in [resets] and gives a new [id].
 */
class FakeInstallIdProvider(
    var id: String = FakeIdGenerator.fakeUuid(n = 900),
) : InstallIdProvider,
    InstallIdReset {
    var failure: DomainError? = null

    var calls: Int = 0
        private set

    override suspend fun installId(): Outcome<String, DomainError> {
        calls++
        return failure?.let { Outcome.Failure(it) } ?: Outcome.Success(id)
    }

    var resets: Int = 0
        private set

    override suspend fun resetInstallId(): Outcome<Unit, DomainError> {
        failure?.let { return Outcome.Failure(it) }
        resets++
        id = FakeIdGenerator.fakeUuid(n = 900L + resets)
        return Outcome.Success(Unit)
    }
}
