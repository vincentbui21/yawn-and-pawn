package com.yawnandpawn.app.data.session

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.RuntimeWrite
import com.yawnandpawn.app.core.session.SessionJson
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.StoredSession
import com.yawnandpawn.app.core.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * [ActiveSessionStore] over `active_session` in `runtime.db`. States are stored only as [SessionJson] text; each
 * commit replaces the row and inserts its writes (the purchase intent of a Pay, Story 4.8) in one transaction, and
 * `Idle` deletes the row. Storage exceptions become `StorageFailure`.
 */
class RoomActiveSessionStore(
    private val dao: ActiveSessionDao,
    private val clock: Clock,
) : ActiveSessionStore {
    override suspend fun load(): Outcome<StoredSession, DomainError> =
        storage { dao.get()?.let { SessionJson.decode(it.stateJson) } ?: StoredSession.Empty }

    override suspend fun commit(
        state: SessionState,
        writes: List<RuntimeWrite>,
    ): Outcome<Unit, DomainError> =
        storage {
            val row =
                when (state) {
                    SessionState.Idle -> {
                        null
                    }

                    is SessionState.Active -> {
                        ActiveSessionEntity(state.session.sessionId, SessionJson.encode(state), clock.now().toEpochMilliseconds())
                    }
                }
            dao.commit(row, writes.map(::entityOf))
        }

    private fun entityOf(write: RuntimeWrite): PurchaseIntentEntity =
        when (write) {
            is RuntimeWrite.PutPurchaseIntent -> PurchaseIntentEntity.of(write.intent)
        }

    override suspend fun clear(): Outcome<Unit, DomainError> = storage { dao.deleteAll() }

    // Same boundary as RoomAlarmRepository: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }
}
