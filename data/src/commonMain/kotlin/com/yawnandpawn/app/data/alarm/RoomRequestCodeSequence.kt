package com.yawnandpawn.app.data.alarm

import com.yawnandpawn.app.core.alarm.RequestCodeSequence
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlin.coroutines.cancellation.CancellationException

/** [RequestCodeSequence] over the `request_code_sequence` row of `app.db`. Storage exceptions become `StorageFailure`. */
class RoomRequestCodeSequence(
    private val dao: RequestCodeSequenceDao,
) : RequestCodeSequence {
    // Same boundary as RoomAlarmRepository: every storage exception is a StorageFailure, cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun next(): Outcome<Int, DomainError> =
        try {
            Outcome.Success(dao.next())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure(e.message ?: e::class.simpleName ?: "storage error"))
        }
}
