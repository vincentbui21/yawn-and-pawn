package com.yawnandpawn.app.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 2.3: the lock state adapter on `UserManager` and `ACTION_USER_UNLOCKED` (Robolectric runs API 34). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AndroidUserLockStateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val userManager = shadowOf(app.getSystemService(UserManager::class.java))
    private val logger = FakeLogger()

    private fun unlockedReceivers() = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(Intent.ACTION_USER_UNLOCKED) }

    @Test
    fun `it reads UserManager`() {
        val lock = AndroidUserLockState(app, logger)

        userManager.setUserUnlocked(false)
        assertEquals(false, lock.isUserUnlocked())
        userManager.setUserUnlocked(true)
        assertEquals(true, lock.isUserUnlocked())
    }

    @Test
    fun `a failing system service never throws, says unlocked and is logged once`() {
        val broken =
            object : ContextWrapper(app) {
                override fun getSystemService(name: String): Any = throw IllegalStateException("system server died")
            }
        val lock = AndroidUserLockState(broken, logger)

        assertEquals(true, lock.isUserUnlocked())
        assertEquals(true, lock.isUserUnlocked())

        assertEquals(
            listOf<LogEvent>(LogEvent.OperationFailed("read user lock state", "java.lang.IllegalStateException: system server died")),
            logger.events,
        )
    }

    @Test
    fun `observe registers a not exported receiver while collected and emits unlocked when ACTION_USER_UNLOCKED arrives`() =
        runTest(UnconfinedTestDispatcher()) {
            userManager.setUserUnlocked(false)
            val values = mutableListOf<Boolean>()
            val collecting = launch { AndroidUserLockState(app, logger).observe().toList(values) }
            runCurrent()

            val receiver = unlockedReceivers().single()
            assertEquals(Context.RECEIVER_NOT_EXPORTED, receiver.flags and Context.RECEIVER_NOT_EXPORTED, "API 33+: not exported")
            assertEquals(listOf(false), values)

            userManager.setUserUnlocked(true)
            app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
            shadowOf(Looper.getMainLooper()).idle()
            runCurrent()
            assertEquals(listOf(false, true), values)

            collecting.cancel()
            runCurrent()
            assertTrue(unlockedReceivers().isEmpty(), "unregistered when the collector stops")
        }

    @Test
    fun `an unlock just as the receiver registers is not missed, the value is read after registering`() =
        runTest(UnconfinedTestDispatcher()) {
            userManager.setUserUnlocked(false)
            // The unlock lands after the receiver is registered but its broadcast is never delivered here.
            val unlocksOnRegister =
                object : ContextWrapper(app) {
                    override fun registerReceiver(
                        receiver: BroadcastReceiver?,
                        filter: IntentFilter,
                        flags: Int,
                    ): Intent? = super.registerReceiver(receiver, filter, flags).also { userManager.setUserUnlocked(true) }
                }
            val values = mutableListOf<Boolean>()
            val collecting = launch { AndroidUserLockState(unlocksOnRegister, logger).observe().toList(values) }
            runCurrent()

            assertEquals(listOf(true), values)
            collecting.cancel()
        }
}
