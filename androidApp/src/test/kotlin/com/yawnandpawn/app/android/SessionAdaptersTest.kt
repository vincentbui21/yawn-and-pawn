package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.PurchaseIntent
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.testing.FakeLogger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/** The Epic 1 billing adapter: every launch fails. */
class SessionAdaptersTest {
    private val logger = FakeLogger()

    @Test
    fun `unavailable billing fails every launch and logs it`() {
        val billing = UnavailableBilling(logger)
        val intent = PurchaseIntent(PurchaseIntentId("intent-1"), "session-1", "snooze_usd_01", snoozeNumber = 1)

        assertEquals(SessionEvent.PurchaseFailed, runBlocking { billing.launch(intent) })
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("launch billing", "billing unavailable until Epic 4")), logger.events)
    }
}
