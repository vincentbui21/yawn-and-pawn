package com.yawnandpawn.app.android.sound

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.R
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.AlarmSound
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.sound.SoundCatalog
import com.yawnandpawn.app.core.sound.SoundRef
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 1.17: the bundled files, the sound resolver and the phone's alarm ringtones. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SoundLibraryTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()
    private val argon = SoundRef.System("content://media/internal/audio/media/42?title=Argon", "Argon")

    @Test
    fun `every catalog sound has its raw file and every bundled alarm file is in the catalog`() {
        val catalog = SoundCatalog.sounds.map { it.resourceName }.toSet()
        // Robolectric runs in the module directory.
        val bundled = File("src/main/res/raw").listFiles().orEmpty().filter { it.name.startsWith("alarm_") }

        assertEquals(catalog, BUILT_IN_SOUND_FILES.keys)
        assertEquals(catalog, bundled.map { it.nameWithoutExtension }.toSet())
        assertTrue(bundled.all { it.extension == "ogg" }, "OGG only: ${bundled.map { it.name }}")
        BUILT_IN_SOUND_FILES.values.forEach { raw -> context.resources.openRawResourceFd(raw).use { assertTrue(it.length > 0) } }
    }

    @Test
    fun `the resolver maps the default, the other built-ins and ringtones, and nothing else`() {
        val resolver = LibrarySoundResolver()

        assertEquals(AlarmSound.Default, resolver.resolve(Alarm.DEFAULT_SOUND_REF))
        assertEquals(AlarmSound.BuiltIn(R.raw.alarm_sonar), resolver.resolve("builtin:sonar"))
        assertEquals(AlarmSound.File(argon.uri), resolver.resolve(argon.encode()))
        listOf("builtin:birds", "file:x", "", "system:No uri|").forEach { assertNull(resolver.resolve(it), it) }
        assertNull(LibrarySoundResolver(files = emptyMap()).resolve("builtin:sonar"), "a catalog sound without its file")
    }

    private class FakeRingtones(
        var list: () -> List<SoundRef.System> = { emptyList() },
        var opens: (String) -> Boolean = { false },
    ) : RingtoneSource {
        override fun alarmRingtones(): List<SoundRef.System> = list()

        override fun opens(uri: String): Boolean = opens.invoke(uri)
    }

    @Test
    fun `the library lists the phone's alarm ringtones and an unreadable list is empty and logged without names`() =
        runTest {
            val ringtones = FakeRingtones(list = { listOf(argon) })
            val library = AndroidSoundLibrary(ringtones, logger, UnconfinedTestDispatcher(testScheduler))

            assertEquals(listOf(argon), library.systemSounds())
            ringtones.list = { listOf(SoundRef.System("content://media/internal/audio/media/43", " "), argon) }
            assertEquals(listOf(argon), library.systemSounds(), "a ringtone without a name is left out")
            ringtones.list = { throw SecurityException("content://media/internal/audio/media/42") }
            assertEquals(emptyList(), library.systemSounds())
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("list alarm ringtones", "SecurityException")), logger.events)
        }

    @Test
    fun `a built-in is available when the catalog has it, a ringtone while its file opens`() =
        runTest {
            val ringtones = FakeRingtones(opens = { it == argon.uri })
            val library = AndroidSoundLibrary(ringtones, logger, UnconfinedTestDispatcher(testScheduler))

            assertTrue(library.isAvailable(SoundRef.BuiltIn("bell")))
            assertFalse(library.isAvailable(SoundRef.BuiltIn("birds")))
            assertTrue(library.isAvailable(argon))
            assertFalse(library.isAvailable(SoundRef.System("content://gone", "Gone")))
            ringtones.opens = { throw FileNotFoundException(it) }
            assertFalse(library.isAvailable(argon))
            assertTrue(logger.events.none { "content://" in it.toString() }, "no uri in the log")
        }

    @Test
    fun `the platform source reads RingtoneManager and reports a uri that does not open as unavailable`() =
        runTest {
            val library = AndroidSoundLibrary(PlatformRingtoneSource(context), logger, UnconfinedTestDispatcher(testScheduler))

            // Robolectric has no media provider: no ringtones, never a crash.
            assertTrue(library.systemSounds().all { it.uri.isNotBlank() })
            assertFalse(library.isAvailable(SoundRef.System("content://media/internal/audio/media/404", "Gone")))
        }
}
