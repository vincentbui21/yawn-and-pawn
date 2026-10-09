package com.yawnandpawn.app.android.billing

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.session.UnlockResult
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowKeyguardManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Story 4.12 (Spike S1 option B): the PIN before Play, through `requestDismissKeyguard` from the wake screen. */
@RunWith(RobolectricTestRunner::class)
class AndroidDeviceUnlockerTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val keyguard = ApplicationProvider.getApplicationContext<Context>().getSystemService(KeyguardManager::class.java)
    private val shadow = shadowOf(keyguard)
    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).create().get()
    private var screen: Activity? = activity
    private val logger = FakeLogger()

    private fun unlocker() =
        AndroidDeviceUnlocker(
            keyguard = keyguard,
            wakeScreens = { screen },
            main = Dispatchers.Unconfined,
            logger = logger,
            screenWait = 50.milliseconds,
            promptLimit = 300.milliseconds,
        )

    @After
    fun tearDown() = ShadowKeyguardManager.reset()

    @Test
    fun `an unlocked phone needs no prompt`() =
        runBlocking<Unit> {
            shadow.setKeyguardLocked(false)

            assertFalse(unlocker().isKeyguardLocked())
            assertEquals(UnlockResult.Succeeded, unlocker().requestUnlock())
        }

    @Test
    fun `the PIN entered gives Succeeded, so Play opens only after onDismissSucceeded`() =
        runBlocking<Unit> {
            shadow.setKeyguardLocked(true)
            val unlocker = unlocker()
            assertTrue(unlocker.isKeyguardLocked())

            val result = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { unlocker.requestUnlock() }
            assertFalse(result.isCompleted, "waits for the prompt")
            shadow.setKeyguardLocked(false)

            assertEquals(UnlockResult.Succeeded, result.await())
        }

    @Test
    fun `the prompt cancelled gives Failed`() =
        runBlocking<Unit> {
            shadow.setKeyguardLocked(true)
            val unlocker = unlocker()

            val started = TimeSource.Monotonic.markNow()
            val result = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { unlocker.requestUnlock() }
            shadow.setKeyguardLocked(true) // Robolectric: re-locking answers onDismissCancelled

            assertEquals(UnlockResult.Failed, result.await())
            assertTrue(started.elapsedNow() < 100.milliseconds, "answered by the callback, not the limit")
        }

    @Test
    fun `onDismissError gives Failed at once`() =
        runBlocking<Unit> {
            shadow.setKeyguardLocked(true)
            val unlocker = unlocker()
            val first = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { unlocker.requestUnlock() }

            // Robolectric answers onDismissError to a second request while one is pending.
            val started = TimeSource.Monotonic.markNow()
            assertEquals(UnlockResult.Failed, unlocker.requestUnlock())
            assertTrue(started.elapsedNow() < 100.milliseconds, "answered by onDismissError, not the limit")

            shadow.setKeyguardLocked(false)
            first.await() // Robolectric answers the pending request too; only the second one's answer is checked
        }

    @Test
    fun `no resumed wake screen gives Failed without a prompt`() =
        runBlocking<Unit> {
            shadow.setKeyguardLocked(true)
            screen = null

            assertEquals(UnlockResult.Failed, unlocker().requestUnlock())
        }

    @Test
    fun `a prompt that never answers (a wrong PIN) fails after the limit, so nothing waits for ever`() =
        runBlocking<Unit> {
            shadow.setKeyguardLocked(true)

            assertEquals(UnlockResult.Failed, unlocker().requestUnlock())
            assertTrue(logger.events.isNotEmpty())
        }
}
