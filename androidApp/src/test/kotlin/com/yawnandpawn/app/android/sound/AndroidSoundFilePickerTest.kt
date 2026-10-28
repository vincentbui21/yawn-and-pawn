package com.yawnandpawn.app.android.sound

import android.content.ActivityNotFoundException
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 7.4: the document picker is opened by the screen in front, and its answer survives the screen stopping. */
@RunWith(RobolectricTestRunner::class)
class AndroidSoundFilePickerTest {
    private val logger = FakeLogger()
    private val picker = AndroidSoundFilePicker(logger)

    @Test
    fun `a pick opens the picker once and returns the picked uri, also after the screen stopped while it was open`() =
        runTest(UnconfinedTestDispatcher()) {
            var launches = 0
            val launch: () -> Unit = { launches++ }
            picker.attach(launch)

            val first = async { picker.pick() }
            val second = async { picker.pick() }
            // The picker covers the app: the activity stops, and the answer arrives when it starts again.
            picker.detach(launch)
            picker.onResult("content://picked/1")

            assertEquals(1, launches, "a second pick waits on the open picker")
            assertEquals("content://picked/1", first.await())
            assertEquals("content://picked/1", second.await())
        }

    @Test
    fun `a cancelled picker answers null, and with no screen in front nothing is opened`() =
        runTest(UnconfinedTestDispatcher()) {
            assertNull(picker.pick(), "no screen")
            assertTrue(logger.events.isNotEmpty())

            picker.attach {}
            val pick = async { picker.pick() }
            picker.onResult(null)
            assertNull(pick.await())
        }

    @Test
    fun `a pick abandoned by its caller opens the picker again for the next one`() =
        runTest(UnconfinedTestDispatcher()) {
            var launches = 0
            picker.attach { launches += 1 }

            val abandoned = async { picker.pick() }
            abandoned.cancel()
            // The answer of the abandoned picker never arrives; the next pick opens it again.
            val next = async { picker.pick() }
            picker.onResult("content://picked/3")

            assertEquals(2, launches)
            assertEquals("content://picked/3", next.await())
        }

    @Test
    fun `a phone with no document picker answers null at once, and the next pick tries again`() =
        runTest(UnconfinedTestDispatcher()) {
            picker.attach { throw ActivityNotFoundException("no picker") }

            assertNull(picker.pick())

            var launches = 0
            picker.attach { launches += 1 }
            val pick = async { picker.pick() }
            picker.onResult("content://picked/2")
            assertEquals("content://picked/2", pick.await())
            assertEquals(1, launches)
            assertTrue(logger.events.none { "content://" in it.toString() })
        }
}
