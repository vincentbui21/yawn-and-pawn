package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.aPurchaseIntent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The billing adapter until Story 4.12: every launch fails, nothing is listed, consumed or updated. */
class SessionAdaptersTest {
    private val logger = FakeLogger()

    @Test
    fun `unavailable billing fails every call, logs the launch and never sends an update`() {
        val billing = UnavailableBilling(logger)
        val unavailable = DomainError.BillingUnavailable("billing unavailable until Story 4.12")

        assertEquals(Outcome.Failure(unavailable), runBlocking { billing.launch(aPurchaseIntent(), "install-1") })
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("launch billing", unavailable.cause)), logger.events)
        assertEquals(Outcome.Failure(unavailable), runBlocking { billing.queryPurchases() })
        assertIs<ConsumeResult.Failed>(runBlocking { billing.consume(PurchaseToken("t")) })
        assertEquals(emptyList(), runBlocking { billing.purchaseUpdates.toList() })
    }
}
