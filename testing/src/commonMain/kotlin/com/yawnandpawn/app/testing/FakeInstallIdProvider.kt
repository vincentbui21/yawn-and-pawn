package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.billing.InstallIdProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome

/** [InstallIdProvider] returning [id] (a fixed UUID v4), or [failure] when set; [calls] counts the reads. */
class FakeInstallIdProvider(
    var id: String = FakeIdGenerator.fakeUuid(n = 900),
) : InstallIdProvider {
    var failure: DomainError? = null

    var calls: Int = 0
        private set

    override suspend fun installId(): Outcome<String, DomainError> {
        calls++
        return failure?.let { Outcome.Failure(it) } ?: Outcome.Success(id)
    }
}
