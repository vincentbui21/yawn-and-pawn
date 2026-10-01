package com.yawnandpawn.app.android.wake

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationAttributes
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 1.14: the alarm vibration repeats with alarm usage on every API level. */
@RunWith(RobolectricTestRunner::class)
class AlarmVibratorTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Suppress("DEPRECATION") // The shadow's static state is shared by every vibrator instance.
    private val shadow = shadowOf(context.getSystemService(Vibrator::class.java))

    @Test
    fun `on API 34 it repeats with the alarm vibration usage and stops`() {
        val vibrator = AlarmVibrator(context)

        vibrator.start()

        assertTrue(vibrator.isVibrating)
        assertEquals(0, shadow.repeat, "repeats from the start")
        assertEquals(VibrationAttributes.USAGE_ALARM, (shadow.vibrationAttributesFromLastVibration as VibrationAttributes).usage)
        vibrator.stop()
        assertFalse(vibrator.isVibrating)
        assertTrue(shadow.isCancelled)
    }

    @Test
    @Config(sdk = [30])
    fun `below API 33 it vibrates with the alarm audio attributes`() {
        val vibrator = AlarmVibrator(context)

        vibrator.start()
        vibrator.start()

        assertTrue(vibrator.isVibrating)
        assertEquals(AudioAttributes.USAGE_ALARM, checkNotNull(shadow.audioAttributesFromLastVibration).usage)
    }

    @Test
    fun `the strong pulse replaces the pattern until the next start`() {
        val vibrator = AlarmVibrator(context)
        vibrator.start()

        vibrator.strongHaptic()

        assertFalse(vibrator.isVibrating)
        vibrator.start()
        assertTrue(vibrator.isVibrating)
    }
}
