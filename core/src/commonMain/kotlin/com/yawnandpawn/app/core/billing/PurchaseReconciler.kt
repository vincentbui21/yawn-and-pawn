package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.session.PurchaseToken
import com.yawnandpawn.app.core.session.PurchaseVerdict
import com.yawnandpawn.app.core.session.SessionState
import kotlin.time.Instant

/**
 * The state of a Play purchase. Only the Play Billing adapter's mapping creates it from `Purchase.purchaseState`, and
 * only [PurchaseReconciler] reads it (AD-7; a scan test in `:data` enforces both). Play's `UNSPECIFIED_STATE` is never
 * mapped: the adapter drops such a purchase.
 */
enum class PlayPurchaseState {
    Purchased,
    Pending,
}

/**
 * One purchase as Play reported it, from a purchase update or a `queryPurchasesAsync` result. [token] hides itself in
 * [toString], so a snapshot can be logged.
 *
 * Deliberately not a data class: with no `componentN`, nothing can destructure [purchaseState] past the reader scan.
 *
 * @property profileId the `obfuscatedProfileId` the purchase was launched with: the session id (AD-7). Null when Play
 * gives none, for example a promo-code purchase (OQ-1).
 * @property accountId the `obfuscatedAccountId` the purchase was launched with: the install id of the install that
 * launched it (AD-7). Null when Play gives none (a promo code). Another install's payments are never touched here.
 * @property orderId Play's order id; null for a pending purchase or a test purchase without one.
 */
class PurchaseSnapshot(
    val token: PurchaseToken,
    val productId: String,
    val purchaseState: PlayPurchaseState,
    val profileId: String?,
    val accountId: String?,
    val orderId: String?,
    val purchaseTime: Instant,
) {
    private fun fields(): List<Any?> = listOf(token, productId, purchaseState, profileId, accountId, orderId, purchaseTime)

    override fun equals(other: Any?): Boolean = other is PurchaseSnapshot && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        "PurchaseSnapshot(token=$token, productId=$productId, purchaseState=$purchaseState, profileId=$profileId, " +
            "accountId=$accountId, orderId=$orderId, purchaseTime=$purchaseTime)"
}

/** Which [SessionState] the active session is in, as far as a purchase is concerned. */
enum class ActiveSessionKind {
    Ringing,
    Grace,
    Loud,
    Snoozed,
    Completed,
    Missed,
    ;

    /** True in Ringing, Grace and Loud: the states where a paid snooze can start (AD-2). */
    val canSnooze: Boolean get() = this == Ringing || this == Grace || this == Loud
}

/**
 * The active session as the reconciler sees it.
 *
 * @property expectedNextProductId the product the next snooze of this session costs (`FeeLadder`), or null when no
 * snooze can be bought (max snoozes or the price cap reached).
 */
data class ActiveSessionSummary(
    val sessionId: String,
    val kind: ActiveSessionKind,
    val expectedNextProductId: String?,
    val testMode: Boolean,
) {
    companion object {
        /** The summary of [state], or null when no session is active ([SessionState.Idle]). */
        fun of(
            state: SessionState,
            expectedNextProductId: String?,
        ): ActiveSessionSummary? =
            (state as? SessionState.Active)?.let { active ->
                ActiveSessionSummary(active.session.sessionId, kindOf(active), expectedNextProductId, active.session.config.testMode)
            }

        private fun kindOf(state: SessionState.Active): ActiveSessionKind =
            when (state) {
                is SessionState.Ringing -> ActiveSessionKind.Ringing
                is SessionState.Grace -> ActiveSessionKind.Grace
                is SessionState.Loud -> ActiveSessionKind.Loud
                is SessionState.Snoozed -> ActiveSessionKind.Snoozed
                is SessionState.Completed -> ActiveSessionKind.Completed
                is SessionState.Missed -> ActiveSessionKind.Missed
            }
    }
}

/**
 * The token's row in the grant ledger (`runtime.db`, Story 4.10): [Granted] means a snooze was granted for it and it is
 * not consumed yet.
 */
enum class LedgerStatus {
    Absent,
    Granted,
    Consumed,
}

/** The token's purchase record (`app.db`, by token hash, Story 4.10). */
enum class RecordStatus {
    Absent,
    Granted,
    Consumed,
    Stranded,
    Reused,
}

/** Why the reconciler was asked. */
sealed interface ReconcileContext {
    /** A purchase update (`onPurchasesUpdated`), solicited or not. */
    data object Update : ReconcileContext

    /** A `queryPurchasesAsync` result on app start, resume or wake screen open. */
    data object Recovery : ReconcileContext

    /** The owned purchases queried before launching the purchase of [productId]. */
    data class PreLaunch(
        val productId: String,
    ) : ReconcileContext

    /** The owned purchases queried after the launch of [productId] returned `ITEM_ALREADY_OWNED`. */
    data class AlreadyOwned(
        val productId: String,
    ) : ReconcileContext
}

/**
 * Everything [PurchaseReconciler.decide] needs; the caller reads the ledger and the record before asking. [installId] is
 * this install's id, the `obfuscatedAccountId` its own launches carry (Story 4.8's `InstallIdProvider`).
 */
data class ReconcileInput(
    val purchase: PurchaseSnapshot,
    val session: ActiveSessionSummary?,
    val ledger: LedgerStatus,
    val record: RecordStatus,
    val context: ReconcileContext,
    val installId: String,
)

/** Why a purchase is left alone. */
enum class IgnoreReason {
    /** Another install of the app (same Google account, another device) launched it; that install decides it. */
    OtherInstall,

    /** The payment has not cleared. When it becomes PURCHASED it is decided again. */
    Pending,

    /** The token was already consumed or reused: a duplicate or stale delivery. */
    AlreadyHandled,
}

/** What to do with one purchase (AD-7, PRD §6.3). */
sealed interface PurchaseDecision {
    /** The [PurchaseVerdict] a session event carries for this decision. */
    val verdict: PurchaseVerdict

    /**
     * Grant one snooze to the active session, then consume the token. [abortLaunch]: the token was found while
     * launching a purchase (before it, or after `ITEM_ALREADY_OWNED`); the snooze is already paid by this token, so the
     * caller must cancel that launch and clear `paying`. Launching anyway would charge a second time.
     */
    data class Grant(
        val abortLaunch: Boolean,
    ) : PurchaseDecision {
        override val verdict: PurchaseVerdict get() = PurchaseVerdict.Grant
    }

    /**
     * The token already granted a snooze: consume it only, never grant again. [retryLaunch]: the launch that returned
     * `ITEM_ALREADY_OWNED` is retried once after the consume.
     */
    data class ConsumeOnly(
        val retryLaunch: Boolean,
    ) : PurchaseDecision {
        override val verdict: PurchaseVerdict get() = PurchaseVerdict.ConsumeOnly
    }

    /** A stranded payment: record it, never consume it. Google refunds it automatically. */
    data object LeaveForAutoRefund : PurchaseDecision {
        override val verdict: PurchaseVerdict get() = PurchaseVerdict.LeaveForAutoRefund
    }

    /**
     * A payment that gave nothing can pay for this snooze, if the user agrees (FR-RNG-10). [recordStrandedFirst]: the
     * token has no purchase record yet, so the caller records it stranded before offering; accepting then turns that
     * record into reused (`PurchaseLedger.markReused` starts only from stranded), and declining leaves it stranded.
     */
    data class OfferReuse(
        val recordStrandedFirst: Boolean,
    ) : PurchaseDecision {
        override val verdict: PurchaseVerdict get() = PurchaseVerdict.OfferReuse
    }

    /** Nothing to do now. */
    data class Ignore(
        val reason: IgnoreReason,
    ) : PurchaseDecision {
        override val verdict: PurchaseVerdict get() = PurchaseVerdict.Ignore
    }
}

/**
 * The one place the purchase rules live (AD-7, PRD §6.3 recovery table). Pure, synchronous and total: it never
 * suspends, throws or touches the session, so reconciling can never stand between the user and "I'm up". First match
 * wins:
 * 1. Launched by another install (its account id is set and is not [ReconcileInput.installId]) and not granted here:
 *    [IgnoreReason.OtherInstall]. No record, no consume, no reuse: that install grants it or leaves it to be refunded.
 * 2. PENDING: [IgnoreReason.Pending]. A pending payment never grants.
 * 3. A snooze was already granted for the token (a granted ledger row, or a granted record without a ledger row):
 *    [PurchaseDecision.ConsumeOnly], retrying the launch when Play said `ITEM_ALREADY_OWNED` for this product. Nothing
 *    else consumes, because consuming keeps the money.
 * 4. Already consumed or reused: [IgnoreReason.AlreadyHandled].
 * 5. Never seen, PURCHASED for the active session while it rings (not test mode, not snoozed, not ended) and for the
 *    product of its next snooze: [PurchaseDecision.Grant], which cancels a launch in progress.
 * 6. Before or after launching that product, an owned token for it (never seen, or stranded) while the session can buy
 *    it: [PurchaseDecision.OfferReuse].
 * 7. Anything else: [PurchaseDecision.LeaveForAutoRefund]. A stranded token grants only through reuse, with consent.
 */
object PurchaseReconciler {
    fun decide(input: ReconcileInput): PurchaseDecision {
        val purchase = input.purchase
        val launched = input.context.launchedProduct()
        val launching = launched == purchase.productId
        return when {
            fromOtherInstall(input) -> {
                PurchaseDecision.Ignore(IgnoreReason.OtherInstall)
            }

            purchase.purchaseState == PlayPurchaseState.Pending -> {
                PurchaseDecision.Ignore(IgnoreReason.Pending)
            }

            alreadyGranted(input.ledger, input.record) -> {
                PurchaseDecision.ConsumeOnly(retryLaunch = launching && input.context is ReconcileContext.AlreadyOwned)
            }

            alreadyHandled(input.ledger, input.record) -> {
                PurchaseDecision.Ignore(IgnoreReason.AlreadyHandled)
            }

            input.record == RecordStatus.Absent && grantable(purchase, input.session) -> {
                PurchaseDecision.Grant(abortLaunch = launched != null)
            }

            launching && input.session?.canBuy(purchase.productId) == true -> {
                PurchaseDecision.OfferReuse(recordStrandedFirst = input.record == RecordStatus.Absent)
            }

            else -> {
                PurchaseDecision.LeaveForAutoRefund
            }
        }
    }

    /**
     * Another install launched it. A granted ledger row (`runtime.db`, never backed up) proves this install granted it,
     * so that token is still consumed here.
     */
    private fun fromOtherInstall(input: ReconcileInput): Boolean {
        val accountId = input.purchase.accountId
        return accountId != null && accountId != input.installId && input.ledger != LedgerStatus.Granted
    }

    /** A snooze was granted for the token and it is not consumed: a granted ledger row, or a granted record without one. */
    private fun alreadyGranted(
        ledger: LedgerStatus,
        record: RecordStatus,
    ): Boolean = ledger == LedgerStatus.Granted || (ledger == LedgerStatus.Absent && record == RecordStatus.Granted)

    /** Consumed (ledger or record) or reused. Asked after [alreadyGranted], so only never-seen and stranded tokens pass. */
    private fun alreadyHandled(
        ledger: LedgerStatus,
        record: RecordStatus,
    ): Boolean = ledger != LedgerStatus.Absent || (record != RecordStatus.Absent && record != RecordStatus.Stranded)

    /** PURCHASED for the active session, which can buy that product now. */
    private fun grantable(
        purchase: PurchaseSnapshot,
        session: ActiveSessionSummary?,
    ): Boolean = session != null && purchase.profileId == session.sessionId && session.canBuy(purchase.productId)

    /** The session rings, can charge, and its next snooze costs [productId]. */
    private fun ActiveSessionSummary.canBuy(productId: String): Boolean = kind.canSnooze && !testMode && expectedNextProductId == productId

    /** The product being launched, or null outside a launch. */
    private fun ReconcileContext.launchedProduct(): String? =
        when (this) {
            is ReconcileContext.PreLaunch -> productId
            is ReconcileContext.AlreadyOwned -> productId
            ReconcileContext.Update, ReconcileContext.Recovery -> null
        }
}
