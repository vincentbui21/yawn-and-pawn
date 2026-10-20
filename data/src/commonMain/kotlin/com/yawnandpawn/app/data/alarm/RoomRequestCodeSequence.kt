package com.yawnandpawn.app.data.alarm

import com.yawnandpawn.app.core.alarm.RequestCodeHighWaterMark
import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.alarm.RequestCodes
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * [RequestCodeSequence] over the `request_code_sequence` row of `app.db`, and its [RequestCodeHighWaterMark] read
 * ("Delete all data", Story 5.9). Storage exceptions become `StorageFailure`.
 */
class RoomRequestCodeSequence(
    private val dao: RequestCodeSequenceDao,
) : RequestCodeSequence,
    RequestCodeHighWaterMark {
    override suspend fun next(): Outcome<Int, DomainError> = storage { dao.next() }

    // The stored mark; with no row yet, the mark next() would seed from the alarms (none: the initial mark).
    override suspend fun highWaterMark(): Outcome<Int, DomainError> =
        storage { dao.get()?.lastUsed ?: RequestCodes.highWaterMark(dao.alarmCodes()) }

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
