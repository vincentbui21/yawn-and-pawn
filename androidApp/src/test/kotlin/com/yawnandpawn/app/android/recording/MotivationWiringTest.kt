package com.yawnandpawn.app.android.recording

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.wake.AndroidAlarmPlayer
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.recording.MotivationPlayer
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.data.recording.RecordingFiles
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.aRecording
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaPlayer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Story 7.3: the app's own Koin graph plays the Success message on the alarm stream and keeps it out of a ring. */
@RunWith(RobolectricTestRunner::class)
class MotivationWiringTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app: YawnAndPawnApp = ApplicationProvider.getApplicationContext()
    private val created = mutableListOf<MediaPlayer>()

    init {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(2_000, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> created += player }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Idles the main looper until [condition] holds (the row is read on the app scope, off the main thread). */
    private fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        repeat(MAX_ROUNDS) {
            idle()
            if (condition()) return
            Thread.sleep(ROUND_MILLIS)
        }
        fail("timed out waiting until $what")
    }

    @Test
    fun `the message plays on the alarm stream, a ring stops it, and a message during the ring opens nothing`() {
        restartKoin(app)
        val koin = GlobalContext.get()
        val ring = koin.get<AndroidAlarmPlayer>()
        val motivation = koin.get<MotivationPlayer>()
        val files = koin.get<RecordingFiles>()
        files.folder().mkdirs()
        files.takeFile("t1").writeText("audio")
        val saved = runBlocking { koin.get<RecordingRepository>().save(aRecording(id = "r1"), RecordedTake("t1", 9_000)) }
        assertEquals(Outcome.Success(Unit), saved)

        motivation.play("s", "r1")
        awaitUntil("the message plays") { motivation.state.value?.playing == true }
        val message = created.single()
        val shadow = Shadow.extract<ShadowMediaPlayer>(message)
        assertTrue(shadow.isReallyPlaying)
        assertEquals(AudioAttributes.USAGE_ALARM, shadow.audioAttributes.usage)

        ring.play(Alarm.DEFAULT_SOUND_REF, volumePercent = 100, gradual = false, rampStartPercent = 20)
        idle()

        assertNull(motivation.state.value, "the ring stopped the message")
        assertEquals(ShadowMediaPlayer.State.END, shadow.state)

        motivation.play("s", "r1")
        repeat(SETTLE_ROUNDS) {
            idle()
            Thread.sleep(ROUND_MILLIS)
        }

        assertNull(motivation.state.value, "no message during a ring")
        assertEquals(2, created.size, "only the message and the ring opened a player")
        ring.stop(restoreVolume = true)
    }

    private companion object {
        const val MAX_ROUNDS = 3_000
        const val SETTLE_ROUNDS = 50
        const val ROUND_MILLIS = 10L
    }
}
