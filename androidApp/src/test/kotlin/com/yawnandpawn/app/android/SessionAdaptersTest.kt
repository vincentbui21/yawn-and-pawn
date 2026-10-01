package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.PurchaseIntent
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Epic 1 session adapters: effects are only logged by type name, and billing always fails. */
class SessionAdaptersTest {
    private val logger = FakeLogger()

    @Test
    fun `the logging effect runner logs only effect type names, one-shot and entry`() {
        val runner = LoggingEffectRunner(logger)

        runBlocking {
            runner.run(SessionEffect.StartWakeRuntime("session-1"))
            runner.apply(EntryEffect.SoundAt("content://my-private-song", 80))
            runner.run(SessionEffect.LogIgnored("ImUpTapped", "session-1"))
        }

        assertEquals(
            listOf(
                LogEvent.SessionEffectLogged("StartWakeRuntime", entry = false),
                LogEvent.SessionEffectLogged("SoundAt", entry = true),
                LogEvent.SessionEventIgnored("ImUpTapped", "session-1"),
            ),
            logger.events,
        )
        assertTrue(logger.events.none { "my-private-song" in it.toString() }, "no effect contents are logged")
    }

    @Test
    fun `unavailable billing fails every launch and logs it`() {
        val billing = UnavailableBilling(logger)
        val intent = PurchaseIntent(PurchaseIntentId("intent-1"), "session-1", "snooze_usd_01", snoozeNumber = 1)

        assertEquals(SessionEvent.PurchaseFailed, runBlocking { billing.launch(intent) })
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("launch billing", "billing unavailable until Epic 4")), logger.events)
    }
}
