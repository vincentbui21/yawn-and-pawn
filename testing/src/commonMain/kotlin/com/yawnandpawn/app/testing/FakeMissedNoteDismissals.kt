package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.MissedNoteDismissals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [MissedNoteDismissals]. Set [dismissFailure] to make [dismiss] fail (nothing is stored). */
class FakeMissedNoteDismissals(
    initial: Set<String> = emptySet(),
) : MissedNoteDismissals {
    private val ids = MutableStateFlow(initial)

    var dismissFailure: DomainError? = null

    /** The dismissed session ids right now. */
    val current: Set<String>
        get() = ids.value

    override fun dismissed(): Flow<Set<String>> = ids

    override suspend fun dismiss(sessionId: String): Outcome<Unit, DomainError> {
        dismissFailure?.let { return Outcome.Failure(it) }
        ids.update { it + sessionId }
        return Outcome.Success(Unit)
    }
}
