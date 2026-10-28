package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.data.recording.RecordingFiles
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeRecordingRepository
import com.yawnandpawn.app.testing.aRecording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 7.5: the recording a "Mix into alarm" ring plays, found from its row and its file. */
class MixRecordingSourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val logger = FakeLogger()
    private val recording = aRecording(id = "r1", name = "Secret name")

    private fun folder(): File = File(temp.root, RecordingFiles.FOLDER).apply { mkdirs() }

    private fun source(repository: RecordingRepository) =
        MixRecordingSource(repository, RecordingFiles { folder() }, logger, io = Dispatchers.Unconfined)

    private fun find(repository: RecordingRepository): File? = runBlocking { source(repository).file("r1") }

    private fun writeFile(): File = File(folder(), recording.fileName).apply { writeText("audio") }

    @Test
    fun `a readable file of the row is returned`() {
        val file = writeFile()

        assertEquals(file, find(FakeRecordingRepository(listOf(recording))))
        assertTrue(logger.events.isEmpty())
    }

    @Test
    fun `no row, a row marked missing or an absent file is no recording`() {
        writeFile()
        assertNull(find(FakeRecordingRepository()))
        assertNull(find(FakeRecordingRepository(listOf(recording)).apply { markMissing("r1") }))

        File(folder(), recording.fileName).delete()
        assertNull(find(FakeRecordingRepository(listOf(recording))), "the row is there, the file is not")
    }

    @Test
    fun `a failed read or a thrown exception is no recording, logged without a name or a path`() {
        writeFile()
        val failing = FakeRecordingRepository(listOf(recording)).apply { failure = DomainError.StorageFailure("locked") }
        assertNull(find(failing))
        assertNull(find(Throwing))
        val lockedFiles = MixRecordingSource(FakeRecordingRepository(listOf(recording)), RecordingFiles { error("locked") }, logger)
        assertNull(runBlocking { lockedFiles.file("r1") })

        assertEquals(3, logger.events.size, "${logger.events}")
        logger.events.forEach { event ->
            val text = event.toString()
            assertTrue("Secret" !in text && "r1" !in text && "/" !in text && "\\" !in text, text)
            assertTrue(event is LogEvent.OperationFailed)
        }
    }

    /** A repository whose read throws a message holding a path. */
    private object Throwing : RecordingRepository {
        override fun observeAll(): Flow<List<Recording>> = emptyFlow()

        override suspend fun get(id: String): Outcome<Recording?, DomainError> = throw IllegalStateException("/data/recordings/r1.m4a")

        override suspend fun save(
            recording: Recording,
            take: RecordedTake,
        ): Outcome<Unit, DomainError> = Outcome.Success(Unit)

        override suspend fun delete(id: String): Outcome<Unit, DomainError> = Outcome.Success(Unit)
    }
}
