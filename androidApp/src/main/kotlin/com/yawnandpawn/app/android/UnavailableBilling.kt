package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.billing.LaunchResult
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.billing.PurchaseSnapshot
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseToken

/**
 * The [Billing] until Story 4.12 binds Play Billing: every launch fails (logged), nothing is listed or consumed, and no
 * update ever comes. Snooze is never offered meanwhile (`LiveSnoozeAvailability` finds no cached price: nothing fills
 * the cache before Story 4.12), so nothing should launch.
 */
class UnavailableBilling(
    private val logger: Logger,
) : Billing {
    override suspend fun launch(
        intent: PurchaseIntent,
        installId: String,
    ): Outcome<LaunchResult, DomainError> {
        logger.log(LogEvent.OperationFailed("launch billing", UNAVAILABLE))
        return Outcome.Failure(DomainError.BillingUnavailable(UNAVAILABLE))
    }

    override suspend fun queryPurchases(): Outcome<List<PurchaseSnapshot>, DomainError> =
        Outcome.Failure(DomainError.BillingUnavailable(UNAVAILABLE))

    /** Nothing can be consumed yet: the grant ledger keeps the row and retries (Story 4.10). */
    override suspend fun consume(token: PurchaseToken): ConsumeResult = ConsumeResult.Failed(UNAVAILABLE)

    private companion object {
        const val UNAVAILABLE = "billing unavailable until Story 4.12"
    }
}
