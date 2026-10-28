package com.yawnandpawn.app.android.sound

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.sound.CustomSound
import com.yawnandpawn.app.core.sound.CustomSoundRepository
import com.yawnandpawn.app.core.sound.ImportResult
import com.yawnandpawn.app.data.sound.SoundFiles
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeCustomSoundRepository
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Story 7.4: a picked file is checked, copied into `sounds/` and stored, or refused leaving nothing behind. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidSoundImporterTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder = File(context.filesDir, SoundFiles.FOLDER)
    private val files = SoundFiles { folder }
    private val repository = FakeCustomSoundRepository()
    private val logger = FakeLogger()
    private val documents = FakeDocuments()
    private val durations = mutableMapOf<String, Long?>()
    private val probe =
        AudioProbe { file ->
            val head = ByteArray(PROBE_KEY)
            val read = file.inputStream().use { it.read(head) }
            durations[head.copyOf(maxOf(read, 0)).decodeToString()]
        }
    private val now = Instant.parse("2027-03-03T06:00:00.123Z")

    private fun importer(dispatcher: TestDispatcher) =
        AndroidSoundImporter(documents, probe, files, repository, IdGenerator { ID }, FakeClock(now), logger, dispatcher)

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun folderContents(): List<String> =
        folder
            .listFiles()
            .orEmpty()
            .map { it.name }
            .sorted()

    private fun assertNothingLogsTheFile() =
        assertTrue(logger.events.none { "Morning" in it.toString() || "content://" in it.toString() || "sounds" in it.toString() })

    @Test
    fun `a 30 s MP3 of 2 MB is copied to sounds by id, stored with its name and length, and the permission is given back`() =
        runTest {
            val audio = "song" + "x".repeat(2 * 1024 * 1024)
            documents.content = audio.encodeToByteArray()
            durations["song"] = 30_000

            val result = importer(UnconfinedTestDispatcher(testScheduler)).import(URI)

            val sound = CustomSound(ID, "Morning song", "$ID.mp3", 30_000, Instant.parse("2027-03-03T06:00:00.123Z"))
            assertEquals(Outcome.Success(ImportResult.Imported(sound)), result)
            assertEquals(listOf("$ID.mp3"), folderContents(), "the copy only, no temporary file left")
            assertContentEquals(audio.encodeToByteArray(), File(folder, "$ID.mp3").readBytes())
            assertEquals(listOf(sound), repository.stored)
            assertEquals(listOf("take $URI", "release $URI"), documents.permissions)
            assertEquals(emptyList(), logger.events)
        }

    @Test
    fun `a file that is not audio or is shorter than 1 s can't be played, and nothing is copied or saved`() =
        runTest {
            documents.content = "pdf".encodeToByteArray()
            durations["pdf"] = null

            assertEquals(Outcome.Success(ImportResult.NotPlayable), importer(UnconfinedTestDispatcher(testScheduler)).import(URI))

            documents.content = "blip".encodeToByteArray()
            durations["blip"] = 500
            assertEquals(Outcome.Success(ImportResult.NotPlayable), importer(UnconfinedTestDispatcher(testScheduler)).import(URI))
            assertEquals(emptyList(), folderContents())
            assertEquals(emptyList(), repository.stored)
            assertEquals(List(2) { listOf("take $URI", "release $URI") }.flatten(), documents.permissions)
            assertNothingLogsTheFile()
        }

    @Test
    fun `a file that cannot be opened or read can't be played`() =
        runTest {
            documents.openFails = true

            assertEquals(Outcome.Success(ImportResult.NotPlayable), importer(UnconfinedTestDispatcher(testScheduler)).import(URI))

            documents.openFails = false
            documents.readFails = true
            documents.content = "song".encodeToByteArray()
            durations["song"] = 30_000
            assertEquals(Outcome.Success(ImportResult.NotPlayable), importer(UnconfinedTestDispatcher(testScheduler)).import(URI))
            assertEquals(emptyList(), folderContents(), "the partial copy is deleted")
            assertEquals(emptyList(), repository.stored)
        }

    @Test
    fun `a file its provider says is 25 MB is too big before anything is read`() =
        runTest {
            documents.size = 25L * 1024 * 1024

            assertEquals(Outcome.Success(ImportResult.TooBig), importer(UnconfinedTestDispatcher(testScheduler)).import(URI))

            assertEquals(0, documents.opens)
            assertTrue(!folder.exists() || folderContents().isEmpty())
            assertEquals(emptyList(), repository.stored)
        }

    @Test
    fun `a file of unknown size is counted while copied, and over 20 MB the partial copy is deleted`() =
        runTest {
            documents.size = null
            documents.stream = { ZeroStream(CustomSound.MAX_SIZE_BYTES + 1) }

            assertEquals(Outcome.Success(ImportResult.TooBig), importer(UnconfinedTestDispatcher(testScheduler)).import(URI))

            assertEquals(emptyList(), folderContents(), "a partial temp file is deleted")
            assertEquals(emptyList(), repository.stored)
            assertEquals(listOf("take $URI", "release $URI"), documents.permissions)
        }

    @Test
    fun `exactly 20 MB is not too big`() =
        runTest {
            documents.size = CustomSound.MAX_SIZE_BYTES
            documents.stream = { ZeroStream(CustomSound.MAX_SIZE_BYTES) }
            durations["\u0000\u0000\u0000\u0000"] = 2_000

            val result = importer(UnconfinedTestDispatcher(testScheduler)).import(URI)

            assertIs<ImportResult.Imported>(assertIs<Outcome.Success<ImportResult>>(result).value)
            assertEquals(CustomSound.MAX_SIZE_BYTES, File(folder, "$ID.mp3").length())
        }

    @Test
    fun `a name without a usable extension is stored as audio, and a provider without names gives an empty one`() =
        runTest {
            documents.content = "song".encodeToByteArray()
            durations["song"] = 4_000
            documents.name = "Morning song"

            val named = importer(UnconfinedTestDispatcher(testScheduler)).import(URI)

            val sound = assertIs<ImportResult.Imported>(assertIs<Outcome.Success<ImportResult>>(named).value).sound
            assertEquals("$ID.audio", sound.fileName)
            assertEquals("Morning song", sound.displayName)
            assertTrue(File(folder, "$ID.audio").isFile)
        }

    @Test
    fun `a row that cannot be stored is a storage failure and the copy is deleted again`() =
        runTest {
            documents.content = "song".encodeToByteArray()
            durations["song"] = 30_000
            repository.failure = DomainError.StorageFailure("sound storage: SQLiteException")

            val result = importer(UnconfinedTestDispatcher(testScheduler)).import(URI)

            assertEquals(Outcome.Failure(DomainError.StorageFailure("sound storage: SQLiteException")), result)
            assertEquals(emptyList(), folderContents())
            assertEquals(listOf("take $URI", "release $URI"), documents.permissions)
            assertNothingLogsTheFile()
        }

    @Test
    fun `an import cancelled while its row is stored leaves no copy behind`() =
        runTest {
            documents.content = "song".encodeToByteArray()
            durations["song"] = 30_000
            val cancelling =
                object : CustomSoundRepository by repository {
                    override suspend fun add(sound: CustomSound): Outcome<Unit, DomainError> = throw CancellationException("editor closed")
                }
            val importer =
                AndroidSoundImporter(
                    documents,
                    probe,
                    files,
                    cancelling,
                    IdGenerator { ID },
                    FakeClock(now),
                    logger,
                    UnconfinedTestDispatcher(testScheduler),
                )

            assertFailsWith<CancellationException> { importer.import(URI) }

            assertEquals(emptyList(), folderContents(), "neither the temporary file nor the renamed copy")
            assertEquals(listOf("take $URI", "release $URI"), documents.permissions)
        }

    @Test
    fun `a provider without a persistable permission still imports, and nothing is released`() =
        runTest {
            documents.content = "song".encodeToByteArray()
            durations["song"] = 30_000
            documents.persistable = false

            val result = importer(UnconfinedTestDispatcher(testScheduler)).import(URI)

            assertIs<ImportResult.Imported>(assertIs<Outcome.Success<ImportResult>>(result).value)
            assertEquals(emptyList(), documents.permissions)
        }

    @Test
    fun `the metadata probe accepts audio with a duration and refuses a file without audio`() {
        folder.mkdirs()
        val song = File(folder, "song.mp3").apply { writeText("audio") }
        val pdf = File(folder, "doc.pdf").apply { writeText("pdf") }
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(song.path), MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes")
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(song.path), MediaMetadataRetriever.METADATA_KEY_DURATION, "30000")
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(pdf.path), MediaMetadataRetriever.METADATA_KEY_DURATION, "30000")

        assertEquals(30_000, MetadataAudioProbe().durationMs(song))
        assertNull(MetadataAudioProbe().durationMs(pdf), "no audio track")
        assertNull(MetadataAudioProbe().durationMs(File(folder, "gone.mp3")), "unreadable")
    }

    /** [PickedDocuments] over [content] (or [stream]), reporting [size] (unknown by default) and [name]; records permissions. */
    private class FakeDocuments : PickedDocuments {
        var content: ByteArray = ByteArray(0)
        var stream: (() -> InputStream)? = null
        var size: Long? = null
        var name: String? = "Morning song.MP3"
        var openFails = false
        var readFails = false
        var persistable = true
        var opens = 0
            private set
        val permissions = mutableListOf<String>()

        override fun size(uri: String): Long? = size

        override fun name(uri: String): String? = name

        override fun open(uri: String): InputStream {
            opens++
            if (openFails) throw SecurityException("no grant for $uri")
            if (readFails) {
                return object : InputStream() {
                    override fun read(): Int = throw IOException("provider died")
                }
            }
            return stream?.invoke() ?: ByteArrayInputStream(content)
        }

        override fun takeRead(uri: String): Boolean {
            if (!persistable) return false
            permissions += "take $uri"
            return true
        }

        override fun releaseRead(uri: String) {
            permissions += "release $uri"
        }
    }

    /** [length] zero bytes, without holding them. */
    private class ZeroStream(
        private val length: Long,
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int = if (position++ < length) 0 else -1

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (position >= length) return -1
            val count = minOf(len.toLong(), length - position).toInt()
            b.fill(0, off, off + count)
            position += count
            return count
        }
    }

    private companion object {
        const val ID = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val URI = "content://com.android.providers.downloads.documents/document/42"

        /** The probe tells files apart by their first bytes. */
        const val PROBE_KEY = 4
    }
}
