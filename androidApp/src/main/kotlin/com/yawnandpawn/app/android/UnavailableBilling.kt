package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.SessionEvent

/**
 * The Epic 1 [Billing]: there is no Play Billing yet (Epic 4), so every launch fails and is logged. Snooze is never
 * offered in Epic 1 (`NoBillingSnoozeAvailability`), so nothing should launch at all.
 */
class UnavailableBilling(
    private val logger: Logger,
) : Billing {
    override suspend fun launch(intent: PurchaseIntent): SessionEvent.PurchaseEvent {
        logger.log(LogEvent.OperationFailed("launch billing", "billing unavailable until Epic 4"))
        return SessionEvent.PurchaseFailed
    }

    /** Nothing can be consumed yet: the grant ledger keeps the row and retries (Story 4.10). */
    override suspend fun consume(token: PurchaseToken): ConsumeResult = ConsumeResult.Failed("billing unavailable until Story 4.12")
}
