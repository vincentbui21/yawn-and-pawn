package com.yawnandpawn.app.data.sound

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.sound.CustomSound
import com.yawnandpawn.app.core.sound.CustomSoundRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [CustomSoundRepository] over `custom_sound` in `app.db` and the copies in [files] (Story 7.4). A row is returned with
 * `missing = true` when its copy is not there (a restored phone, or storage locked before the first unlock). Every
 * exception is a `StorageFailure` holding only the exception class, never its message (which can hold a file path).
 * Exceptions in [observeAll] reach its collector.
 */
class RoomCustomSoundRepository(
    private val dao: CustomSoundDao,
    private val files: SoundFiles,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CustomSoundRepository {
    override fun observeAll(): Flow<List<CustomSound>> = dao.observeAll().map { rows -> rows.map { it.withFile() } }.flowOn(io)

    override suspend fun get(id: String): Outcome<CustomSound?, DomainError> = storage { dao.get(id)?.withFile() }

    override suspend fun add(sound: CustomSound): Outcome<Unit, DomainError> = storage { dao.insert(CustomSoundEntity.of(sound)) }

    private fun CustomSoundEntity.withFile(): CustomSound = toCustomSound(missing = !files.exists(fileName))

    // Every storage exception is a StorageFailure with its class only (a message can hold a path); cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> storage(block: suspend () -> T): Outcome<T, DomainError> =
        try {
            Outcome.Success(withContext(io) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failure(DomainError.StorageFailure("sound storage: ${e::class.simpleName}"))
        }
}
