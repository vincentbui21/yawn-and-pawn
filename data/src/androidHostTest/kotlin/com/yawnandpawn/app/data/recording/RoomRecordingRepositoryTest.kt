package com.yawnandpawn.app.data.recording

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.AppDatabaseConstructor
import com.yawnandpawn.app.testing.aRecording
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 7.1: `recording` rows in `app.db` with their audio files in `recordings/`, renamed from a temporary take. */
@RunWith(RobolectricTestRunner::class)
class RoomRecordingRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>(context, factory = { AppDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val folder = File(context.filesDir, RecordingFiles.FOLDER).apply { mkdirs() }
    private val files = RecordingFiles { folder }
    private val repository = RoomRecordingRepository(database.recordingDao(), files)

    @After
    fun tearDown() {
        database.close()
        folder.deleteRecursively()
    }

    /** A finished take in its temporary file, holding [audio]. */
    private fun take(
        takeId: String,
        durationMs: Long = 9_000,
        audio: String = "audio of $takeId",
    ): RecordedTake {
        files.takeFile(takeId).writeText(audio)
        return RecordedTake(takeId, durationMs)
    }

    @Test
    fun `a saved take is renamed to the recording's file, the temporary file is gone and the row is stored`() =
        runTest {
            val recording = aRecording(id = "r1")

            assertEquals(Outcome.Success(Unit), repository.save(recording, take("t1")))

            assertEquals("audio of t1", File(folder, "r1.m4a").readText())
            assertFalse(files.takeFile("t1").exists(), "the take is moved, not copied")
            assertEquals(Outcome.Success(recording), repository.get("r1"))
            assertEquals(listOf(recording), repository.observeAll().first())
        }

    @Test
    fun `re-recording replaces the file of the same id and stores the new duration`() =
        runTest {
            val recording = aRecording(id = "r1", durationMs = 9_000)
            repository.save(recording, take("t1"))

            val again = recording.copy(durationMs = 12_000)
            assertEquals(Outcome.Success(Unit), repository.save(again, take("t2", durationMs = 12_000, audio = "new take")))

            assertEquals("new take", File(folder, "r1.m4a").readText())
            assertEquals(listOf("r1.m4a"), folder.list()?.toList())
            assertEquals(Outcome.Success(again), repository.get("r1"))
        }

    @Test
    fun `observe all lists every recording oldest first and emits again after a change`() =
        runTest {
            val later = aRecording(id = "b", name = "Message 2", createdAt = Instant.parse("2027-03-04T06:00:00Z"))
            val earlier = aRecording(id = "a", name = "Morning", createdAt = Instant.parse("2027-03-02T06:00:00Z"))
            repository.save(later, take("t1"))
            repository.save(earlier, take("t2"))

            assertEquals(listOf(earlier, later), repository.observeAll().first())
            repository.delete("a")
            assertEquals(listOf(later), repository.observeAll().first())
        }

    @Test
    fun `a row whose file is absent is returned as missing by get and observe all`() =
        runTest {
            val recording = aRecording(id = "r1")
            repository.save(recording, take("t1"))
            // A restored phone: the row came back with app.db, the audio did not.
            File(folder, "r1.m4a").delete()

            assertEquals(Outcome.Success(recording.copy(missing = true)), repository.get("r1"))
            assertEquals(listOf(recording.copy(missing = true)), repository.observeAll().first())
        }

    @Test
    fun `before the first unlock every file reads as missing and saving fails with the exception class only`() =
        runTest {
            repository.save(aRecording(id = "r1"), take("t1"))
            val locked =
                RoomRecordingRepository(
                    database.recordingDao(),
                    RecordingFiles { throw IllegalStateException("credential storage locked at /data/user/0/secret") },
                )

            assertEquals(Outcome.Success(aRecording(id = "r1", missing = true)), locked.get("r1"))
            assertEquals(listOf(aRecording(id = "r1", missing = true)), locked.observeAll().first())
            assertEquals(
                Outcome.Failure(DomainError.StorageFailure("recording storage: IllegalStateException")),
                locked.save(aRecording(id = "r2"), RecordedTake("t2", 5_000)),
            )
        }

    @Test
    fun `a take whose file is gone is a storage failure and nothing is stored`() =
        runTest {
            assertEquals(
                Outcome.Failure(DomainError.StorageFailure("recording storage: IOException")),
                repository.save(aRecording(id = "r1"), RecordedTake("gone", 5_000)),
            )
            assertNull((repository.get("r1") as Outcome.Success).value)
        }

    @Test
    fun `a file name outside the folder is refused and the take is deleted`() =
        runTest {
            val recording = aRecording(id = "r1").copy(fileName = "../escape.m4a")

            assertEquals(
                Outcome.Failure(DomainError.StorageFailure("recording storage: IOException")),
                repository.save(recording, take("t1")),
            )
            assertFalse(files.takeFile("t1").exists(), "a failed save deletes the take")
            assertFalse(File(folder.parentFile, "escape.m4a").exists())
        }

    @Test
    fun `a row that cannot be written changes nothing, no file for a new recording and the old audio back for a re-record`() =
        runTest {
            val failing = FailingUpsertDao(database.recordingDao())
            val flaky = RoomRecordingRepository(failing, files)
            flaky.save(aRecording(id = "old"), take("t0"))
            failing.fail = true

            val newOne = flaky.save(aRecording(id = "new"), take("t1"))
            val reRecord = flaky.save(aRecording(id = "old", durationMs = 2_000), take("t2", audio = "replaced"))

            assertEquals(Outcome.Failure(DomainError.StorageFailure("recording storage: IllegalStateException")), newOne)
            assertFalse(File(folder, "new.m4a").exists(), "no file without its row")
            assertFalse(files.takeFile("t1").exists())
            assertEquals(Outcome.Failure(DomainError.StorageFailure("recording storage: IllegalStateException")), reRecord)
            assertEquals("audio of t0", File(folder, "old.m4a").readText(), "the old audio is back")
            assertFalse(files.takeFile("t2").exists(), "the failed take is deleted")
            assertEquals(listOf("old.m4a"), folder.list()?.toList(), "no backup is left")
            assertEquals(Outcome.Success(aRecording(id = "old")), flaky.get("old"))
        }

    @Test
    fun `delete removes the row and the file, and an unknown id is NotFound`() =
        runTest {
            repository.save(aRecording(id = "r1"), take("t1"))

            assertEquals(Outcome.Success(Unit), repository.delete("r1"))

            assertFalse(File(folder, "r1.m4a").exists())
            assertEquals(Outcome.Success(null), repository.get("r1"))
            assertEquals(Outcome.Failure(DomainError.NotFound("r1")), repository.delete("r1"))
        }

    @Test
    fun `a recording whose file is already missing is still deleted`() =
        runTest {
            repository.save(aRecording(id = "r1"), take("t1"))
            File(folder, "r1.m4a").delete()

            assertEquals(Outcome.Success(Unit), repository.delete("r1"))
            assertEquals(0, database.recordingDao().count())
        }

    @Test
    fun `the files helper cleans stale takes, refuses odd names and deletes the whole folder`() {
        files.takeFile("stale").writeText("left by a crash")
        File(folder, "r1.m4a").writeText("kept")

        files.deleteStaleTakes()

        assertEquals(listOf("r1.m4a"), folder.list()?.toList())
        assertFailsWith<IllegalArgumentException> { files.takeFile("../x") }
        assertFailsWith<IllegalArgumentException> { files.takeFile("") }
        assertNull(files.file(".."))
        assertNull(files.file("a/b.m4a"))
        assertFalse(files.exists("a\\b.m4a"))
        assertTrue(files.exists("r1.m4a"))

        files.deleteAll()
        assertFalse(folder.exists())
        files.deleteAll()
        RecordingFiles { throw IllegalStateException("locked") }.deleteStaleTakes()
    }

    @Test
    fun `the production folder is recordings in credential-protected files, not in device-protected storage`() {
        val production = RecordingFiles.of(context).folder()

        assertEquals(File(context.filesDir, "recordings").absolutePath, production.absolutePath)
        assertFalse(production.absolutePath.startsWith(context.createDeviceProtectedStorageContext().dataDir.absolutePath))
        assertEquals("r1.m4a", Recording.fileNameFor("r1"))
    }

    /** [delegate], whose upsert throws while [fail] is set, as a full or broken database would. */
    private class FailingUpsertDao(
        private val delegate: RecordingDao,
    ) : RecordingDao by delegate {
        var fail = false

        override suspend fun upsert(row: RecordingEntity) {
            check(!fail) { "database is full" }
            delegate.upsert(row)
        }
    }
}
