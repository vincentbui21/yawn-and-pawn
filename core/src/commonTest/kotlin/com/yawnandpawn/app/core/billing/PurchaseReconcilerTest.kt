package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.billing.PurchaseDecision.ConsumeOnly
import com.yawnandpawn.app.core.billing.PurchaseDecision.Grant
import com.yawnandpawn.app.core.billing.PurchaseDecision.Ignore
import com.yawnandpawn.app.core.billing.PurchaseDecision.LeaveForAutoRefund
import com.yawnandpawn.app.core.billing.PurchaseDecision.OfferReuse
import com.yawnandpawn.app.core.session.PurchaseVerdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The PRD §6.3 recovery table and the Story 4.9 rows, one test per row (rows 1–14 are the epic's numbering), then the
 * cases the table leaves open (rows A–I, defaults taken in the spec).
 */
class PurchaseReconcilerTest {
    @Test
    fun `row 1 - PURCHASED for the ringing active session, unseen, the expected product - Grant`() {
        RING_KINDS.forEach { kind ->
            PLAIN_CONTEXTS.forEach { context ->
                assertEquals(Grant(abortLaunch = false), decide(session = active(kind), context = context), "$kind $context")
            }
        }
        // A promo-code style purchase with no account id still grants when its profile id matches.
        assertEquals(Grant(abortLaunch = false), decide(snapshot(accountId = null)))
    }

    @Test
    fun `row 2 - PURCHASED for the active session but not the expected product - LeaveForAutoRefund`() {
        PLAIN_CONTEXTS.forEach { context ->
            assertEquals(LeaveForAutoRefund, decide(purchase = snapshot(productId = OTHER_PRODUCT), context = context))
        }
    }

    @Test
    fun `row 3 - PURCHASED for an ended session - LeaveForAutoRefund`() {
        ENDED_KINDS.forEach { kind ->
            ALL_CONTEXTS.forEach { context ->
                assertEquals(LeaveForAutoRefund, decide(session = active(kind), context = context), "$kind $context")
            }
        }
        // The session the purchase was for ended and a new one rings.
        assertEquals(LeaveForAutoRefund, decide(purchase = snapshot(profileId = "session-yesterday")))
    }

    @Test
    fun `row 4 - PURCHASED for another session id - LeaveForAutoRefund`() {
        PLAIN_CONTEXTS.forEach { context ->
            assertEquals(LeaveForAutoRefund, decide(purchase = snapshot(profileId = OTHER_SESSION), context = context))
        }
    }

    @Test
    fun `row 5 - PURCHASED for the active session while it is Snoozed - LeaveForAutoRefund`() {
        ALL_CONTEXTS.forEach { context ->
            assertEquals(LeaveForAutoRefund, decide(session = active(ActiveSessionKind.Snoozed), context = context), "$context")
        }
    }

    @Test
    fun `row 6 - PURCHASED with no profileId (promo code) - LeaveForAutoRefund`() {
        PLAIN_CONTEXTS.forEach { context ->
            assertEquals(LeaveForAutoRefund, decide(purchase = snapshot(profileId = null), context = context))
            assertEquals(LeaveForAutoRefund, decide(purchase = snapshot(profileId = null, accountId = null), context = context))
        }
    }

    @Test
    fun `row 7 - PURCHASED with a granted ledger row, any profileId - ConsumeOnly without a relaunch`() {
        listOf(ACTIVE, OTHER_SESSION, null).forEach { profileId ->
            listOf(active(), active(ActiveSessionKind.Snoozed), null).forEach { session ->
                PLAIN_CONTEXTS.forEach { context ->
                    assertEquals(
                        ConsumeOnly(retryLaunch = false),
                        decide(snapshot(profileId = profileId), session, LedgerStatus.Granted, RecordStatus.Granted, context),
                        "$profileId $session $context",
                    )
                }
            }
        }
    }

    @Test
    fun `row 8 - PENDING, whatever else is known - Ignore(Pending)`() {
        val pending = snapshot(state = PlayPurchaseState.Pending)
        LedgerStatus.entries.forEach { ledger ->
            RecordStatus.entries.forEach { record ->
                ALL_CONTEXTS.forEach { context ->
                    listOf(active(), active(testMode = true), null).forEach { session ->
                        assertEquals(Ignore(IgnoreReason.Pending), decide(pending, session, ledger, record, context))
                    }
                }
            }
        }
    }

    @Test
    fun `row 9 - duplicate delivery of a granted token for the active session - ConsumeOnly, never a second Grant`() {
        RING_KINDS.forEach { kind ->
            assertEquals(ConsumeOnly(retryLaunch = false), decide(session = active(kind), ledger = LedgerStatus.Granted))
        }
    }

    @Test
    fun `row 10 - duplicate delivery after consume or reuse - Ignore(AlreadyHandled)`() {
        listOf(RecordStatus.Consumed, RecordStatus.Reused).forEach { record ->
            ALL_CONTEXTS.forEach { context ->
                assertEquals(Ignore(IgnoreReason.AlreadyHandled), decide(record = record, context = context), "$record $context")
            }
        }
    }

    @Test
    fun `row 11 - an owned unseen or stranded token for the product being bought - OfferReuse`() {
        val launches = listOf(ReconcileContext.PreLaunch(NEXT), ReconcileContext.AlreadyOwned(NEXT))
        launches.forEach { context ->
            RING_KINDS.forEach { kind ->
                // Paid in an earlier session (unseen, or already recorded stranded), or with no profileId. An unseen
                // token is recorded stranded first, so accepting can mark that record reused.
                listOf(OTHER_SESSION, null).forEach { profileId ->
                    assertEquals(
                        OfferReuse(recordStrandedFirst = true),
                        decide(snapshot(profileId = profileId), active(kind), record = RecordStatus.Absent, context = context),
                    )
                    assertEquals(
                        OfferReuse(recordStrandedFirst = false),
                        decide(snapshot(profileId = profileId), active(kind), record = RecordStatus.Stranded, context = context),
                    )
                }
                // Paid for this session while it was snoozed: stranded, so it needs consent too.
                assertEquals(
                    OfferReuse(recordStrandedFirst = false),
                    decide(session = active(kind), record = RecordStatus.Stranded, context = context),
                )
            }
        }
    }

    @Test
    fun `row 12 - ITEM_ALREADY_OWNED for a granted token - ConsumeOnly, then retry the launch once`() {
        val owned = ReconcileContext.AlreadyOwned(NEXT)
        assertEquals(ConsumeOnly(retryLaunch = true), decide(ledger = LedgerStatus.Granted, context = owned))
        // Before the launch the coordinator consumes it and launches anyway: no retry needed.
        assertEquals(ConsumeOnly(retryLaunch = false), decide(ledger = LedgerStatus.Granted, context = ReconcileContext.PreLaunch(NEXT)))
    }

    @Test
    fun `row 13 - test mode session, any PURCHASED token for it - LeaveForAutoRefund, never a grant`() {
        (RING_KINDS + ActiveSessionKind.Snoozed + ENDED_KINDS).forEach { kind ->
            ALL_CONTEXTS.forEach { context ->
                listOf(RecordStatus.Absent, RecordStatus.Stranded).forEach { record ->
                    assertEquals(
                        LeaveForAutoRefund,
                        decide(session = active(kind, testMode = true), record = record, context = context),
                        "$kind $context $record",
                    )
                }
            }
        }
    }

    @Test
    fun `row 14 - no active session, PURCHASED and unseen - LeaveForAutoRefund`() {
        ALL_CONTEXTS.forEach { context ->
            assertEquals(LeaveForAutoRefund, decide(session = null, context = context))
            assertEquals(LeaveForAutoRefund, decide(snapshot(profileId = null), session = null, context = context))
        }
    }

    @Test
    fun `row A - a lost callback for this session found during a launch - Grant that cancels the launch`() {
        // Launching anyway would open a second Play sheet and charge again for the snooze this token already paid.
        listOf(NEXT, OTHER_PRODUCT).forEach { product ->
            assertEquals(Grant(abortLaunch = true), decide(context = ReconcileContext.PreLaunch(product)))
            assertEquals(Grant(abortLaunch = true), decide(context = ReconcileContext.AlreadyOwned(product)))
        }
    }

    @Test
    fun `row B - a granted record without a ledger row - ConsumeOnly, never a second Grant`() {
        PLAIN_CONTEXTS.forEach { context ->
            assertEquals(ConsumeOnly(retryLaunch = false), decide(record = RecordStatus.Granted, context = context))
        }
        assertEquals(
            ConsumeOnly(retryLaunch = true),
            decide(record = RecordStatus.Granted, context = ReconcileContext.AlreadyOwned(NEXT)),
        )
        // The ledger row wins over whatever the record says (a crash between the record write and the ledger delete).
        RecordStatus.entries.forEach { record ->
            assertEquals(ConsumeOnly(retryLaunch = false), decide(ledger = LedgerStatus.Granted, record = record))
        }
    }

    @Test
    fun `row C - a stranded token stays stranded on updates and recovery, even for the ringing session`() {
        PLAIN_CONTEXTS.forEach { context ->
            assertEquals(LeaveForAutoRefund, decide(record = RecordStatus.Stranded, context = context))
        }
    }

    @Test
    fun `row D - no next snooze to buy (max snoozes or the price cap) - LeaveForAutoRefund, no reuse offer`() {
        ALL_CONTEXTS.forEach { context ->
            assertEquals(LeaveForAutoRefund, decide(session = active(expectedNext = null), context = context), "$context")
        }
    }

    @Test
    fun `row E - a launch for another product decides this token as recovery would, but a grant still cancels it`() {
        val launches = listOf(ReconcileContext.PreLaunch(OTHER_PRODUCT), ReconcileContext.AlreadyOwned(OTHER_PRODUCT))
        launches.forEach { context ->
            assertEquals(Grant(abortLaunch = true), decide(context = context))
            assertEquals(LeaveForAutoRefund, decide(snapshot(profileId = OTHER_SESSION), context = context))
            assertEquals(ConsumeOnly(retryLaunch = false), decide(ledger = LedgerStatus.Granted, context = context))
        }
    }

    @Test
    fun `row F - a consumed ledger row - Ignore(AlreadyHandled)`() {
        RecordStatus.entries.forEach { record ->
            ALL_CONTEXTS.forEach { context ->
                val decision = decide(ledger = LedgerStatus.Consumed, record = record, context = context)
                assertEquals(Ignore(IgnoreReason.AlreadyHandled), decision, "$record $context")
            }
        }
    }

    @Test
    fun `row G - no reuse offer while snoozed, ended or for a product that is not next`() {
        val context = ReconcileContext.PreLaunch(NEXT)
        val stranded = snapshot(profileId = OTHER_SESSION)
        (ENDED_KINDS + ActiveSessionKind.Snoozed).forEach { kind ->
            assertEquals(LeaveForAutoRefund, decide(stranded, active(kind), context = context), "$kind")
        }
        assertEquals(LeaveForAutoRefund, decide(stranded, active(expectedNext = OTHER_PRODUCT), context = context))
    }

    @Test
    fun `row H - pending stays pending even for a launch`() {
        val pending = snapshot(state = PlayPurchaseState.Pending)
        assertEquals(Ignore(IgnoreReason.Pending), decide(pending, context = ReconcileContext.AlreadyOwned(NEXT)))
    }

    @Test
    fun `row I - another install's payment is left alone - no record, no consume, no reuse, no grant`() {
        val foreign = snapshot(accountId = OTHER_INSTALL)
        val foreignPending = snapshot(state = PlayPurchaseState.Pending, accountId = OTHER_INSTALL)
        ALL_CONTEXTS.forEach { context ->
            listOf(LedgerStatus.Absent, LedgerStatus.Consumed).forEach { ledger ->
                RecordStatus.entries.forEach { record ->
                    listOf(foreign, foreignPending).forEach { purchase ->
                        assertEquals(
                            Ignore(IgnoreReason.OtherInstall),
                            decide(purchase, active(), ledger, record, context),
                            "$context $ledger $record",
                        )
                    }
                }
            }
            // Even with no session, or for a stranded-looking token from yesterday's session.
            assertEquals(Ignore(IgnoreReason.OtherInstall), decide(foreign, session = null, context = context))
            val yesterday = snapshot(profileId = OTHER_SESSION, accountId = OTHER_INSTALL)
            assertEquals(Ignore(IgnoreReason.OtherInstall), decide(yesterday, context = context))
        }
        // A granted ledger row (runtime.db, never restored) proves this install granted it: still consumed here.
        assertEquals(ConsumeOnly(retryLaunch = false), decide(foreign, ledger = LedgerStatus.Granted))
    }

    @Test
    fun `each decision carries the matching session verdict`() {
        val verdicts =
            mapOf(
                Grant(abortLaunch = false) to PurchaseVerdict.Grant,
                Grant(abortLaunch = true) to PurchaseVerdict.Grant,
                ConsumeOnly(retryLaunch = true) to PurchaseVerdict.ConsumeOnly,
                ConsumeOnly(retryLaunch = false) to PurchaseVerdict.ConsumeOnly,
                LeaveForAutoRefund to PurchaseVerdict.LeaveForAutoRefund,
                OfferReuse(recordStrandedFirst = true) to PurchaseVerdict.OfferReuse,
                OfferReuse(recordStrandedFirst = false) to PurchaseVerdict.OfferReuse,
                Ignore(IgnoreReason.OtherInstall) to PurchaseVerdict.Ignore,
                Ignore(IgnoreReason.Pending) to PurchaseVerdict.Ignore,
                Ignore(IgnoreReason.AlreadyHandled) to PurchaseVerdict.Ignore,
            )
        verdicts.forEach { (decision, verdict) -> assertEquals(verdict, decision.verdict, "$decision") }
        assertEquals(PurchaseVerdict.entries.toSet(), verdicts.values.toSet())
    }
}
