package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.stats.CheckKey
import com.yawnandpawn.app.core.stats.CheckRegistrations
import com.yawnandpawn.app.core.stats.ReRegisterDismissals
import com.yawnandpawn.app.core.stats.ReRegisterSuggestions
import com.yawnandpawn.app.core.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Instant

/** In-memory [ReRegisterDismissals] (Story 3.13). Set [dismissFailure] to make [dismiss] fail (nothing is stored). */
class FakeReRegisterDismissals(
    initial: Map<CheckKey, Instant> = emptyMap(),
) : ReRegisterDismissals {
    private val times = MutableStateFlow(initial)

    var dismissFailure: DomainError? = null

    /** When each check's banner was last dismissed, right now. */
    val current: Map<CheckKey, Instant>
        get() = times.value

    override fun dismissed(): Flow<Map<CheckKey, Instant>> = times

    override suspend fun dismiss(
        key: CheckKey,
        at: Instant,
    ): Outcome<Unit, DomainError> {
        dismissFailure?.let { return Outcome.Failure(it) }
        times.update { it + (key to at) }
        return Outcome.Success(Unit)
    }
}

/** Home's re-register source with nothing registered, as in production before Story 3.10: it never suggests anything. */
fun noReRegisterSuggestions(clock: Clock = FakeClock()): ReRegisterSuggestions =
    ReRegisterSuggestions(FakeSessionHistoryRepository(), CheckRegistrations.None, FakeReRegisterDismissals(), clock)
