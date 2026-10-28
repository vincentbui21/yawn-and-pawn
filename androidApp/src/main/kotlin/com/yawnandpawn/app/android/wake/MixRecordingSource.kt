package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.data.recording.RecordingFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finds the recording a "Mix into alarm" ring plays (Story 7.5), for [AndroidAlarmPlayer]'s `recordingSource`: the
 * `recording` row, then its file in credential-protected `recordings/` ([RecordingFiles]). A missing row, a row marked
 * missing, a file that is not there or cannot be read (storage locked before the first unlock) or a failed read is null,
 * and the ring plays the alarm sound alone. Logged by error code only, never a name or a path. The file is checked on
 * [io], never on the caller's thread.
 */
class MixRecordingSource(
    private val repository: RecordingRepository,
    private val files: RecordingFiles,
    private val logger: Logger,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    // Whatever the repository or the file system throws means "no recording": the alarm sound plays alone.
    @Suppress("TooGenericExceptionCaught")
    suspend fun file(recordingId: String): File? =
        try {
            when (val read = repository.get(recordingId)) {
                is Outcome.Success -> {
                    read.value?.takeUnless { it.missing }?.let { readable(it.fileName) }
                }

                is Outcome.Failure -> {
                    logger.log(LogEvent.OperationFailed.of(OPERATION, read.error))
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName.orEmpty()))
            null
        }

    private suspend fun readable(fileName: String): File? = withContext(io) { files.file(fileName)?.takeIf { it.isFile && it.canRead() } }

    private companion object {
        const val OPERATION = "find mix message"
    }
}
