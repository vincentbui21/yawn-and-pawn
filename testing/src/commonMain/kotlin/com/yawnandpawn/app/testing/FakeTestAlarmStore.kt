package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.SessionConfig
import com.yawnandpawn.app.core.session.TestAlarmStore

/** In-memory [TestAlarmStore]. Set [putFailure] or [takeFailure] to make those calls fail (nothing changes). */
class FakeTestAlarmStore(
    var pending: SessionConfig? = null,
) : TestAlarmStore {
    var putFailure: DomainError? = null
    var takeFailure: DomainError? = null

    override suspend fun put(config: SessionConfig): Outcome<Unit, DomainError> {
        putFailure?.let { return Outcome.Failure(it) }
        pending = config
        return Outcome.Success(Unit)
    }

    override suspend fun take(): Outcome<SessionConfig?, DomainError> {
        takeFailure?.let { return Outcome.Failure(it) }
        return Outcome.Success(pending).also { pending = null }
    }
}
