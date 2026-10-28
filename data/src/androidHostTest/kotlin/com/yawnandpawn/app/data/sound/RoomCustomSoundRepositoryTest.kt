package com.yawnandpawn.app.data.sound

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.data.db.AppDatabase
import com.yawnandpawn.app.data.db.AppDatabaseConstructor
import com.yawnandpawn.app.testing.aCustomSound
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 7.4: `custom_sound` rows in `app.db` with their copies in `sounds/`, and the folder helper. */
@RunWith(RobolectricTestRunner::class)
class RoomCustomSoundRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database =
        Room
            .inMemoryDatabaseBuilder<AppDatabase>(context, factory = { AppDatabaseConstructor.initialize() })
            .setDriver(AndroidSQLiteDriver())
            .build()
    private val folder = File(context.filesDir, SoundFiles.FOLDER).apply { mkdirs() }
    private val files = SoundFiles { folder }
    private val repository = RoomCustomSoundRepository(database.customSoundDao(), files)

    @After
    fun tearDown() {
        database.close()
        folder.deleteRecursively()
    }

    @Test
    fun `an added sound is listed oldest first with its copy, and missing once the copy is gone`() =
        runTest {
            val older = aCustomSound(id = "s1", createdAt = Instant.fromEpochMilliseconds(1))
            val newer = aCustomSound(id = "s2", displayName = "Rain", createdAt = Instant.fromEpochMilliseconds(2))
            File(folder, older.fileName).writeText("audio")
            File(folder, newer.fileName).writeText("audio")

            assertEquals(Outcome.Success(Unit), repository.add(newer))
            assertEquals(Outcome.Success(Unit), repository.add(older))

            assertEquals(listOf(older, newer), repository.observeAll().first())
            assertTrue(File(folder, older.fileName).delete())
            assertEquals(Outcome.Success(older.copy(missing = true)), repository.get("s1"))
            assertEquals(listOf(older.copy(missing = true), newer), repository.observeAll().first(), "a restored row")
            assertEquals(Outcome.Success(null), repository.get("unknown"))
        }

    @Test
    fun `the same id twice is a storage failure naming only the exception class`() =
        runTest {
            val sound = aCustomSound(id = "s1")
            assertEquals(Outcome.Success(Unit), repository.add(sound))

            val failure = assertIs<Outcome.Failure<DomainError>>(repository.add(sound.copy(displayName = "Other")))

            val error = assertIs<DomainError.StorageFailure>(failure.error)
            assertTrue(error.cause.startsWith("sound storage: "), error.cause)
            assertFalse("Other" in error.cause)
        }

    @Test
    fun `the ring finds a sound's copy by id in the folder, never a temporary import or another name`() {
        File(folder, "s1.mp3").writeText("audio")
        File(folder, "s10.ogg").writeText("audio")
        files.importFile("t1").writeText("partial")

        assertEquals(File(folder, "s1.mp3"), files.find("s1"))
        assertEquals(File(folder, "s10.ogg"), files.find("s10"))
        assertNull(files.find("s2"))
        assertNull(files.find("import-t1"), "a temporary import is never a sound")
        assertNull(files.find("../s1"), "only plain names in the folder")
        assertNull(files.find(""))
        assertNull(SoundFiles { throw IllegalStateException("locked") }.find("s1"), "storage locked: no sound")
    }

    @Test
    fun `stale imports are deleted, file names must be plain and the whole folder goes only after the unlock`() {
        File(folder, "s1.mp3").writeText("audio")
        files.importFile("t1").writeText("partial")

        files.deleteStaleImports()

        assertFalse(files.importFile("t1").exists())
        assertTrue(files.exists("s1.mp3"))
        assertNull(files.file("../app.db"))
        assertFalse(files.exists("../s1.mp3"))
        assertFailsWith<IllegalArgumentException> { files.importFile("../x") }
        assertFailsWith<IOException> { SoundFiles(isUnlocked = { false }) { folder }.deleteAll() }
        assertTrue(folder.exists(), "kept while locked")
        files.deleteAll()
        assertFalse(folder.exists())
        files.deleteAll()
    }
}
