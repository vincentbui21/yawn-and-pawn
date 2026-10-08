package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.session.PurchaseToken
import kotlin.time.Instant

// Shared builders for the reconciler tests. Core cannot depend on :testing (AD-1), so they live here.

internal const val ACTIVE = "session-active"
internal const val OTHER_SESSION = "session-other"

/** The product of the active session's next snooze. */
internal const val NEXT = "snooze_usd_02"

/** Any other snooze product. */
internal const val OTHER_PRODUCT = "snooze_usd_01"

internal val PURCHASED_AT: Instant = Instant.parse("2027-03-03T06:05:00Z")

internal val RING_KINDS = listOf(ActiveSessionKind.Ringing, ActiveSessionKind.Grace, ActiveSessionKind.Loud)
internal val ENDED_KINDS = listOf(ActiveSessionKind.Completed, ActiveSessionKind.Missed)

/** Update and Recovery: the contexts that do not launch anything. */
internal val PLAIN_CONTEXTS = listOf(ReconcileContext.Update, ReconcileContext.Recovery)

/** Every context, launching [NEXT] or [OTHER_PRODUCT]. */
internal val ALL_CONTEXTS: List<ReconcileContext> =
    PLAIN_CONTEXTS +
        listOf(NEXT, OTHER_PRODUCT).flatMap { listOf(ReconcileContext.PreLaunch(it), ReconcileContext.AlreadyOwned(it)) }

internal fun snapshot(
    state: PlayPurchaseState = PlayPurchaseState.Purchased,
    productId: String = NEXT,
    profileId: String? = ACTIVE,
    token: String = "token-1",
): PurchaseSnapshot =
    PurchaseSnapshot(
        token = PurchaseToken(token),
        productId = productId,
        purchaseState = state,
        profileId = profileId,
        orderId = "GPA.0000-0000-0000-00000",
        purchaseTime = PURCHASED_AT,
    )

internal fun active(
    kind: ActiveSessionKind = ActiveSessionKind.Ringing,
    expectedNext: String? = NEXT,
    testMode: Boolean = false,
    sessionId: String = ACTIVE,
): ActiveSessionSummary = ActiveSessionSummary(sessionId, kind, expectedNext, testMode)

internal fun decide(
    purchase: PurchaseSnapshot = snapshot(),
    session: ActiveSessionSummary? = active(),
    ledger: LedgerStatus = LedgerStatus.Absent,
    record: RecordStatus = RecordStatus.Absent,
    context: ReconcileContext = ReconcileContext.Update,
): PurchaseDecision = PurchaseReconciler.decide(ReconcileInput(purchase, session, ledger, record, context))
