package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.checks.CheckAnswer
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.SeedDeriver
import com.yawnandpawn.app.core.session.SessionEffect.ArmSlot
import com.yawnandpawn.app.core.session.SessionEvent.AlarmFired
import com.yawnandpawn.app.core.session.SessionEvent.CallEnded
import com.yawnandpawn.app.core.session.SessionEvent.CallStarted
import com.yawnandpawn.app.core.session.SessionEvent.CheckAnswerSubmitted
import com.yawnandpawn.app.core.session.SessionEvent.ImUpTapped
import com.yawnandpawn.app.core.session.SessionEvent.PayConfirmed
import com.yawnandpawn.app.core.session.SessionEvent.SnoozeTapped
import com.yawnandpawn.app.core.session.SessionEvent.UserInteracted
import com.yawnandpawn.app.core.session.SessionState.Completed
import com.yawnandpawn.app.core.session.SessionState.Grace
import com.yawnandpawn.app.core.session.SessionState.Idle
import com.yawnandpawn.app.core.session.SessionState.Loud
import com.yawnandpawn.app.core.session.SessionState.Missed
import com.yawnandpawn.app.core.session.SessionState.Ringing
import com.yawnandpawn.app.core.session.SessionState.Snoozed
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The AD-2 transition table of Architecture Spine v0.3 (31 rows) plus the Spike S1 unlock rows (R32–R34, Story 4.8),
 * one stable id per row.
 * [ROW_CASES] holds the examples of each row; the coverage test checks both lists match, so a new row without a test
 * fails.
 */
internal val AD2_ROWS: List<String> =
    listOf(
        "R01 Idle+AlarmFired",
        "R02 Idle+TestAlarmFired",
        "R03 Ringing|Grace|Loud+SlotFired",
        "R04 Snoozed+SlotFired",
        "R05 Snoozed+SlotFired|ProcessRestored overdue",
        "R06 Ringing+ImUpTapped with grace",
        "R07 Ringing+ImUpTapped without grace",
        "R08 Grace+GraceElapsed",
        "R09 Grace|Loud+CheckAnswerSubmitted valid, not last",
        "R10 Grace|Loud+CheckAnswerSubmitted invalid",
        "R11 Grace|Loud+CheckAnswerSubmitted valid, last",
        "R12 Grace|Loud+FallbackRequested",
        "R13 Ringing|Grace|Loud+SnoozeTapped",
        "R14 Ringing|Grace|Loud+PayConfirmed keyguard not locked",
        "R15 Ringing|Grace|Loud+ReuseOffered",
        "R16 Ringing|Grace|Loud+ReuseAccepted",
        "R17 Ringing|Grace|Loud+ReuseDeclined",
        "R18 Ringing|Grace|Loud+PurchaseGranted",
        "R19 Ringing|Grace|Loud+PurchaseFailed|PurchaseCancelled",
        "R20 Ringing|Grace|Loud+PurchasePending",
        "R21 Grace|Loud+ImageMatchCompleted matched",
        "R22 Grace|Loud+ImageMatchCompleted|ImageMatchFailed not matched",
        "R23 Ringing|Loud+NoInteractionTimeout",
        "R24 Ringing|Grace|Loud+any user event",
        "R25 Ringing|Grace|Loud+CallStarted",
        "R26 Ringing|Grace|Loud (paused)+CallEnded",
        "R27 Snoozed+CallStarted|CallEnded",
        "R28 Ringing|Grace|Loud+OverlapAlarmFired",
        "R29 Snoozed+OverlapAlarmFired",
        "R30 Ringing (before first unlock)+UserUnlocked",
        "R31 Completed|Missed+Recorded",
        "R32 Ringing|Grace|Loud+PayConfirmed keyguard locked",
        "R33 Ringing|Grace|Loud (unlocking)+UnlockSucceeded",
        "R34 Ringing|Grace|Loud (unlocking)+UnlockFailed",
    )

/** One example of a row: [event] in [from] at [now] gives exactly [expected]; [also] checks anything beyond it. */
internal data class RowExample(
    val name: String,
    val from: SessionState,
    val event: SessionEvent,
    val now: TimeSnapshot,
    val expected: Transition,
    val reducer: SessionReducer = reducer(),
    val also: (SessionReducer, Transition) -> Unit = { _, _ -> },
    /** The environment input "the keyguard is showing" (Story 4.8). */
    val keyguardLocked: Boolean = false,
)

/** "Now" for most examples: 5 minutes into the first ring. */
private val N = at(5.minutes)

/** A snapshot after a reboot: boot 4, 40 s after boot, wall time 2 h after [T0]. */
private val AFTER_REBOOT = TimeSnapshot(wallMillis = T0.wallMillis + 2.hours.inWholeMilliseconds, elapsedMillis = 40_000, bootCount = 4)

private val MERGED_AT = SCHEDULED_AT + 1.minutes

private fun heartbeatAt(now: TimeSnapshot) = ArmSlot(Deadline.after(now, 60.seconds))

/** A Pay that fails its guards is logged as ignored (Story 4.8); other user events only reset the deadline. */
private fun payIgnored(event: SessionEvent): List<SessionEffect> =
    if (event is PayConfirmed) listOf(SessionEffect.LogIgnored("PayConfirmed", SESSION_ID)) else emptyList()

private fun SessionState.Ring.touchedAt(now: TimeSnapshot): SessionState.Ring = with(session.touched(now))

/**
 * Snoozed after a paid snooze at [now]: the check progress is dropped (the next ring resolves new seeds), the slot is
 * armed at the snooze end.
 */
private fun grantedFrom(
    session: SessionData,
    now: TimeSnapshot,
): Transition {
    val snoozeEnd = Deadline.after(now, 9.minutes)
    val snoozed =
        session.noTimers().copy(
            snoozesGranted = session.snoozesGranted + 1,
            checkRun = session.checkRun.copy(step = StepPointer(0, 0), failedAttempts = 0),
            paying = null,
            paymentPending = false,
            snoozeEnd = snoozeEnd,
        )
    return Transition(Snoozed(snoozed), listOf(SessionEffect.StopSound, ArmSlot(snoozeEnd), SessionEffect.Consume(TOKEN)))
}

/** The next ring after the snooze of [snoozedSession], at [now]: the plan resolved again with ring 2's seeds. */
private fun nextRing(
    now: TimeSnapshot,
    noGrace: Boolean,
): Ringing =
    Ringing(
        snoozedSession().noTimers().copy(
            ringIndex = 2,
            checkRun = CheckRun(snoozedSession().config.checkPlan, ringSeeds(2, 1)),
            noGraceThisRing = noGrace,
            interactionDeadline = Deadline.after(now, 30.minutes),
        ),
    )

private fun completedFrom(
    session: SessionData,
    now: TimeSnapshot,
): Transition =
    Transition(
        Completed(
            session.noTimers().copy(checkRun = session.checkRun.copy(step = StepPointer(session.checkRun.step.entry + 1, 0)), ended = now),
        ),
        listOf(
            SessionEffect.StopSound,
            SessionEffect.CancelSlot,
            SessionEffect.PlayMotivation,
        ),
    )

private fun failedAttemptIn(
    from: SessionState.Ring,
    now: TimeSnapshot,
    touched: Boolean,
    feedback: SessionEffect,
): Transition {
    val session = if (touched) from.session.touched(now) else from.session
    val run = session.checkRun
    // Story 3.1 review: the session-wide count goes up with the entry's.
    val counted = run.copy(failedAttempts = run.failedAttempts + 1, totalFailedAttempts = run.totalFailedAttempts + 1)
    return Transition(from.with(session.copy(checkRun = counted)), listOf(feedback))
}

private val twoStepSession = ringSession(testConfig(checkPlan = TWO_STEPS))

/** A two-entry session on its second entry after 5 failed attempts on it and 7 in the session (the fallback row). */
private val onSecondEntry =
    twoStepSession.copy(checkRun = twoStepSession.checkRun.copy(step = StepPointer(1, 0), failedAttempts = 5, totalFailedAttempts = 7))

/**
 * A two-entry session on item 2 of entry 1 with 2 failed attempts on it and 4 in the session, so pointer moves and
 * resets show.
 */
private val progressed =
    twoStepSession.copy(checkRun = twoStepSession.checkRun.copy(step = StepPointer(0, 1), failedAttempts = 2, totalFailedAttempts = 4))

/** [progressed] after the fallback: item 2 of fallback entry 2, with the fallback seeds (Story 3.1 review). */
private val progressedFallback =
    twoStepSession.copy(
        checkRun =
            CheckRun(
                plan = FALLBACK_PLAN,
                seeds = ringSeeds(1, 3, fallback = true),
                step = StepPointer(1, 1),
                failedAttempts = 2,
                fallbackUsed = true,
                fallbackSource = FALLBACK_PLAN,
                totalFailedAttempts = 4,
            ),
    )

internal val ROW_CASES: Map<String, List<RowExample>> =
    mapOf(
        "R01 Idle+AlarmFired" to
            listOf(false, true).map { locked ->
                val config = testConfig()
                val session =
                    SessionData(
                        sessionId = SESSION_ID,
                        config = config,
                        ringIndex = 1,
                        snoozesGranted = 0,
                        checkRun = CheckRun(config.checkPlan, ringSeeds(1, 1)),
                        firstRing = N,
                        startedBeforeUnlock = locked,
                        beforeFirstUnlock = locked,
                        directBootRing = locked,
                        interactionDeadline = Deadline.after(N, 30.minutes),
                    )
                RowExample(
                    name = "before first unlock = $locked",
                    from = Idle,
                    event = AlarmFired(SESSION_ID, config, beforeFirstUnlock = locked),
                    now = N,
                    expected =
                        Transition(
                            Ringing(session),
                            listOf(
                                SessionEffect.StartWakeRuntime(SESSION_ID),
                                heartbeatAt(N),
                                SessionEffect.RecordSessionStart(SESSION_ID, config),
                            ),
                        ),
                )
            },
        "R02 Idle+TestAlarmFired" to
            listOf(
                testConfig().let { editorValues ->
                    val config = editorValues.copy(testMode = true)
                    RowExample(
                        name = "test mode is forced and snooze is unavailable",
                        from = Idle,
                        event = SessionEvent.TestAlarmFired(SESSION_ID, editorValues, beforeFirstUnlock = false),
                        now = N,
                        expected =
                            Transition(
                                Ringing(
                                    SessionData(
                                        sessionId = SESSION_ID,
                                        config = config,
                                        ringIndex = 1,
                                        snoozesGranted = 0,
                                        checkRun = CheckRun(config.checkPlan, ringSeeds(1, 1)),
                                        firstRing = N,
                                        interactionDeadline = Deadline.after(N, 30.minutes),
                                    ),
                                ),
                                listOf(
                                    SessionEffect.StartWakeRuntime(SESSION_ID),
                                    heartbeatAt(N),
                                    SessionEffect.RecordSessionStart(SESSION_ID, config),
                                ),
                            ),
                        also = { reducer, transition ->
                            val session = (transition.state as SessionState.Active).session
                            kotlin.test.assertEquals(
                                SnoozeAvailability.Unavailable(UnavailableReason.TestMode),
                                reducer.snoozeAvailability(session),
                            )
                        },
                    )
                },
            ),
        "R03 Ringing|Grace|Loud+SlotFired" to
            ringStates().map { from ->
                RowExample(from.kind, from, SessionEvent.SlotFired, N, Transition(from, listOf(heartbeatAt(N))))
            },
        "R04 Snoozed+SlotFired" to
            listOf(9.minutes, 10.minutes).map { elapsed ->
                val now = at(elapsed)
                RowExample(
                    name = "slot fires $elapsed after the grant, same boot",
                    from = Snoozed(snoozedSession()),
                    event = SessionEvent.SlotFired,
                    now = now,
                    expected = Transition(nextRing(now, noGrace = false), listOf(heartbeatAt(now))),
                )
            },
        "R05 Snoozed+SlotFired|ProcessRestored overdue" to
            listOf(
                "slot fires after a reboot" to (SessionEvent.SlotFired to AFTER_REBOOT),
                "restored on the same boot after the snooze end" to (SessionEvent.ProcessRestored to at(12.minutes)),
                "restored after a reboot" to (SessionEvent.ProcessRestored to AFTER_REBOOT),
            ).map { (name, eventAndNow) ->
                val (event, now) = eventAndNow
                RowExample(
                    name = name,
                    from = Snoozed(snoozedSession()),
                    event = event,
                    now = now,
                    expected =
                        Transition(
                            nextRing(now, noGrace = false),
                            listOf(SessionEffect.StartWakeRuntime(SESSION_ID), heartbeatAt(now)),
                        ),
                )
            },
        "R06 Ringing+ImUpTapped with grace" to
            listOf(
                RowExample(
                    name = "grace window starts muted",
                    from = Ringing(ringSession()),
                    event = ImUpTapped,
                    now = N,
                    expected =
                        Transition(
                            Grace(ringSession().touched(N).copy(graceEnd = Deadline.after(N, 20.seconds))),
                            listOf(SessionEffect.Mute, SessionEffect.StartCheckStep(0)),
                        ),
                ),
            ),
        "R07 Ringing+ImUpTapped without grace" to
            listOf(
                ringSession().copy(noGraceThisRing = true).let { session ->
                    RowExample(
                        name = "merged ring goes straight to Loud",
                        from = Ringing(session),
                        event = ImUpTapped,
                        now = N,
                        expected = Transition(Loud(session.touched(N)), listOf(SessionEffect.StartCheckStep(0))),
                    )
                },
            ),
        "R08 Grace+GraceElapsed" to
            listOf(
                RowExample(
                    name = "grace ends",
                    from = Grace(ringSession().copy(graceEnd = Deadline.after(T0, 20.seconds))),
                    event = SessionEvent.GraceElapsed,
                    now = at(20.seconds),
                    expected = Transition(Loud(ringSession()), listOf(SessionEffect.UnmuteToVolume(80), SessionEffect.StrongHaptic)),
                ),
            ),
        // Story 3.1: the plugin results ItemCorrect (ValidNextItem) and Correct on an entry that is not the last (ValidNext)
        // both map onto this row; the entry's failed attempts reset only when the entry advances.
        "R09 Grace|Loud+CheckAnswerSubmitted valid, not last" to
            checkStates(progressed).flatMap { from ->
                val touched = from.session.touched(N)
                listOf(
                    RowExample(
                        name = "${from.kind} next entry",
                        from = from,
                        event = CheckAnswerSubmitted(CheckAnswer.Placeholder),
                        now = N,
                        expected =
                            Transition(
                                from.with(touched.copy(checkRun = touched.checkRun.copy(step = StepPointer(1, 0), failedAttempts = 0))),
                                listOf(SessionEffect.StartCheckStep(1)),
                            ),
                        reducer = reducer(check = StepResult.ValidNext),
                    ),
                    RowExample(
                        name = "${from.kind} next item",
                        from = from,
                        event = CheckAnswerSubmitted(CheckAnswer.Placeholder),
                        now = N,
                        expected =
                            Transition(from.with(touched.copy(checkRun = touched.checkRun.copy(step = StepPointer(0, 2)))), emptyList()),
                        reducer = reducer(check = StepResult.ValidNextItem),
                    ),
                )
            },
        // Story 3.1: Wrong (Invalid) and WrongRestart (InvalidRestart) both map onto this row; a restart also starts the
        // entry over at its first item with a new seed from SeedDeriver, keyed by the new failed-attempt count.
        "R10 Grace|Loud+CheckAnswerSubmitted invalid" to
            checkStates(progressed).flatMap { from ->
                val touched = from.session.touched(N)
                val restartSeed = SeedDeriver.seed(SESSION_ID, 1, 0, 3)
                listOf(
                    RowExample(
                        name = from.kind,
                        from = from,
                        event = CheckAnswerSubmitted(CheckAnswer.Placeholder),
                        now = N,
                        expected = failedAttemptIn(from, N, touched = true, feedback = SessionEffect.WrongAnswerFeedback),
                        reducer = reducer(check = StepResult.Invalid),
                    ),
                    RowExample(
                        name = "${from.kind} restart",
                        from = from,
                        event = CheckAnswerSubmitted(CheckAnswer.Placeholder),
                        now = N,
                        expected =
                            Transition(
                                from.with(
                                    touched.copy(
                                        checkRun =
                                            touched.checkRun.copy(
                                                seeds = listOf(restartSeed, touched.checkRun.seeds[1]),
                                                step = StepPointer(0, 0),
                                                failedAttempts = 3,
                                                totalFailedAttempts = 5,
                                            ),
                                    ),
                                ),
                                listOf(SessionEffect.WrongAnswerFeedback),
                            ),
                        reducer = reducer(check = StepResult.InvalidRestart),
                    ),
                )
            } +
            // Story 3.1 review: a restart on entry 1 of the fallback run re-derives only that entry's seed, with the fallback flag.
            checkStates(progressedFallback).map { from ->
                val touched = from.session.touched(N)
                val seeds = touched.checkRun.seeds
                RowExample(
                    name = "${from.kind} fallback restart on entry 1",
                    from = from,
                    event = CheckAnswerSubmitted(CheckAnswer.Placeholder),
                    now = N,
                    expected =
                        Transition(
                            from.with(
                                touched.copy(
                                    checkRun =
                                        touched.checkRun.copy(
                                            seeds = listOf(seeds[0], SeedDeriver.seed(SESSION_ID, 1, 1, 3, fallback = true), seeds[2]),
                                            step = StepPointer(1, 0),
                                            failedAttempts = 3,
                                            totalFailedAttempts = 5,
                                        ),
                                ),
                            ),
                            listOf(SessionEffect.WrongAnswerFeedback),
                        ),
                    reducer = reducer(check = StepResult.InvalidRestart),
                )
            },
        "R11 Grace|Loud+CheckAnswerSubmitted valid, last" to
            checkStates().map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = CheckAnswerSubmitted(CheckAnswer.Placeholder),
                    now = N,
                    expected = completedFrom(from.session, N),
                    reducer = reducer(check = StepResult.ValidLast),
                )
            },
        "R12 Grace|Loud+FallbackRequested" to
            checkStates(onSecondEntry).map { from ->
                val touched = from.session.touched(N)
                // Story 3.9: the fallback plan's own seeds, no failed attempts on its first entry, the plan kept for later
                // rings, the replaced check kept for history, and the first fallback entry shown. Timers are unchanged.
                val fallbackRun =
                    CheckRun(
                        FALLBACK_PLAN,
                        ringSeeds(1, 3, fallback = true),
                        failedAttempts = 0,
                        fallbackUsed = true,
                        fallbackSource = FALLBACK_PLAN,
                        totalFailedAttempts = 7,
                        fallbackFrom = "Placeholder",
                    )
                val policy = StubFallback(FallbackDecision.Allowed(FALLBACK_PLAN))
                RowExample(
                    name = from.kind,
                    from = from,
                    event = FALLBACK_REQUEST,
                    now = N,
                    expected = Transition(from.with(touched.copy(checkRun = fallbackRun)), listOf(SessionEffect.StartCheckStep(0))),
                    reducer =
                        SessionReducer(
                            StubAvailability(SnoozeAvailability.Available(OFFER)),
                            StubCheck(StepResult.ValidLast),
                            policy,
                        ),
                    also = { _, _ ->
                        val asked = listOf(FallbackRequest(CheckType.Math, FallbackReason.CameraUnavailable))
                        kotlin.test.assertEquals(
                            asked.toSet(),
                            policy.requests.toSet(),
                            "the policy decides on the picked type and the reason",
                        )
                    },
                )
            },
        "R13 Ringing|Grace|Loud+SnoozeTapped" to
            ringStates().map { from ->
                RowExample(from.kind, from, SnoozeTapped, N, Transition(from.touchedAt(N), listOf(SessionEffect.ShowSnoozeConfirm(OFFER))))
            },
        "R14 Ringing|Grace|Loud+PayConfirmed keyguard not locked" to
            listOf(QUOTE, QUOTE.copy(price = Money(1_000_000, "USD"), formattedPrice = "$1.00"))
                .flatMap { quote ->
                    ringStates().map { from ->
                        RowExample(
                            name = "${from.kind} ${quote.price.currency}",
                            from = from,
                            event = PayConfirmed(INTENT, quote),
                            now = N,
                            expected =
                                Transition(
                                    from.with(from.session.touched(N).copy(paying = INTENT)),
                                    listOf(
                                        SessionEffect.PersistPurchaseIntent(intentAt(N, quote)),
                                        SessionEffect.LaunchBilling(INTENT, SESSION_ID),
                                    ),
                                ),
                        )
                    }
                },
        "R15 Ringing|Grace|Loud+ReuseOffered" to
            ringStates(ringSession().copy(paying = INTENT)).map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.ReuseOffered(PRODUCT, PurchaseVerdict.OfferReuse),
                    now = N,
                    expected = Transition(from.with(from.session.copy(paying = null)), listOf(SessionEffect.ShowReuseSheet(PRODUCT))),
                )
            },
        "R16 Ringing|Grace|Loud+ReuseAccepted" to
            ringStates(progressed).map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.ReuseAccepted(PRODUCT, TOKEN),
                    now = N,
                    expected = grantedFrom(from.session, N),
                )
            },
        "R17 Ringing|Grace|Loud+ReuseDeclined" to
            ringStates().map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.ReuseDeclined(PRODUCT),
                    now = N,
                    expected =
                        Transition(
                            from.with(from.session.touched(N).copy(declinedReuseProduct = PRODUCT)),
                            listOf(SessionEffect.HideReuseSheet),
                        ),
                )
            },
        "R18 Ringing|Grace|Loud+PurchaseGranted" to
            listOf(
                twoStepSession.copy(
                    paying = INTENT,
                    paymentPending = true,
                    checkRun = twoStepSession.checkRun.copy(step = StepPointer(1), failedAttempts = 2),
                ),
                twoStepSession,
            ).flatMap { session ->
                ringStates(session).map { from ->
                    RowExample(
                        name = "${from.kind} paying = ${session.paying}",
                        from = from,
                        event = SessionEvent.PurchaseGranted(PRODUCT, TOKEN, PurchaseVerdict.Grant),
                        now = N,
                        expected = grantedFrom(from.session, N),
                    )
                }
            },
        "R19 Ringing|Grace|Loud+PurchaseFailed|PurchaseCancelled" to
            listOf(
                SessionEvent.PurchaseFailed to PurchaseOutcome.Failed,
                SessionEvent.PurchaseCancelled to PurchaseOutcome.Cancelled,
            ).flatMap { (event, outcome) ->
                ringStates(ringSession().copy(paying = INTENT)).map { from ->
                    RowExample(
                        name = "${from.kind} $event",
                        from = from,
                        event = event,
                        now = N,
                        expected =
                            Transition(
                                from.with(from.session.copy(paying = null)),
                                listOf(SessionEffect.ShowPurchaseOutcome(outcome)),
                            ),
                    )
                }
            },
        "R20 Ringing|Grace|Loud+PurchasePending" to
            ringStates(ringSession().copy(paying = INTENT)).map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.PurchasePending,
                    now = N,
                    expected =
                        Transition(
                            from.with(from.session.copy(paying = null, paymentPending = true)),
                            listOf(SessionEffect.ShowPaymentPending),
                        ),
                )
            },
        "R21 Grace|Loud+ImageMatchCompleted matched" to
            checkStates(twoStepSession).flatMap { from ->
                val next = StepPointer(1, 0)
                listOf(
                    RowExample(
                        name = "${from.kind} last step",
                        from = from,
                        event = SessionEvent.ImageMatchCompleted(matched = true),
                        now = N,
                        expected = completedFrom(from.session, N),
                        reducer = reducer(check = StepResult.ValidLast),
                    ),
                    RowExample(
                        name = "${from.kind} more steps",
                        from = from,
                        event = SessionEvent.ImageMatchCompleted(matched = true),
                        now = N,
                        expected =
                            Transition(
                                from.with(from.session.copy(checkRun = from.session.checkRun.copy(step = next))),
                                listOf(SessionEffect.StartCheckStep(1)),
                            ),
                        reducer = reducer(check = StepResult.ValidNext),
                    ),
                )
            },
        "R22 Grace|Loud+ImageMatchCompleted|ImageMatchFailed not matched" to
            listOf(SessionEvent.ImageMatchCompleted(matched = false), SessionEvent.ImageMatchFailed).flatMap { event ->
                checkStates().map { from ->
                    RowExample(
                        name = "${from.kind} $event",
                        from = from,
                        event = event,
                        now = N,
                        expected = failedAttemptIn(from, N, touched = false, feedback = SessionEffect.ShowRetryPrompt),
                    )
                }
            },
        "R23 Ringing|Loud+NoInteractionTimeout" to
            listOf(Ringing(ringSession()), Loud(ringSession())).map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.NoInteractionTimeout,
                    now = at(30.minutes),
                    expected =
                        Transition(
                            Missed(ringSession().noTimers().copy(ended = at(30.minutes))),
                            listOf(
                                SessionEffect.StopSound,
                                SessionEffect.CancelSlot,
                            ),
                        ),
                )
            },
        "R24 Ringing|Grace|Loud+any user event" to
            ringStates().map { from ->
                RowExample("${from.kind} UserInteracted", from, UserInteracted, N, Transition(from.touchedAt(N), emptyList()))
            } +
            checkStates().map { from ->
                RowExample("${from.kind} ImUpTapped again", from, ImUpTapped, N, Transition(from.touchedAt(N), emptyList()))
            } +
            listOf(CheckAnswerSubmitted(CheckAnswer.Placeholder), FALLBACK_REQUEST).map { event ->
                val from = Ringing(ringSession())
                RowExample("Ringing $event before the check", from, event, N, Transition(from.touchedAt(N), emptyList()))
            } +
            listOf(SnoozeTapped, PAY).flatMap { event ->
                ringStates().map { from ->
                    RowExample(
                        name = "${from.kind} $event while snooze is unavailable",
                        from = from,
                        event = event,
                        now = N,
                        expected = Transition(from.touchedAt(N), payIgnored(event)),
                        reducer = reducer(availability = SnoozeAvailability.Unavailable(UnavailableReason.MaxSnoozesReached)),
                    )
                }
            } +
            listOf(false, true).flatMap { locked ->
                // Story 4.8 guards: a second Pay while one is in flight (unlocking or not), or a price for another
                // product, persists no intent and launches nothing; it only counts as a user event.
                listOf(
                    ringSession().copy(paying = INTENT) to PayConfirmed(PurchaseIntentId("intent-2"), QUOTE),
                    ringSession().copy(paying = INTENT, unlocking = true) to PayConfirmed(PurchaseIntentId("intent-2"), QUOTE),
                    ringSession() to PayConfirmed(INTENT, QUOTE.copy(productId = "snooze_usd_02")),
                ).flatMap { (session, event) ->
                    ringStates(session).map { from ->
                        RowExample(
                            name = "${from.kind} locked=$locked paying=${session.paying} price for ${event.livePrice.productId}",
                            from = from,
                            event = event,
                            now = N,
                            expected = Transition(from.touchedAt(N), payIgnored(event)),
                            keyguardLocked = locked,
                        )
                    }
                }
            } +
            checkStates().flatMap { from ->
                val used = from.with(from.session.copy(checkRun = from.session.checkRun.copy(fallbackUsed = true)))
                listOf(
                    RowExample(
                        "${from.kind} fallback not allowed",
                        from,
                        FALLBACK_REQUEST,
                        N,
                        Transition(from.touchedAt(N), emptyList()),
                        reducer = reducer(fallback = FallbackDecision.NotAllowed),
                    ),
                    RowExample(
                        "${from.kind} fallback already used",
                        used,
                        FALLBACK_REQUEST,
                        N,
                        Transition(used.touchedAt(N), emptyList()),
                    ),
                )
            },
        "R25 Ringing|Grace|Loud+CallStarted" to
            ringStates().map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = CallStarted,
                    now = N,
                    expected = Transition(from.with(from.session.copy(pausedAt = N)), listOf(SessionEffect.PauseSound)),
                )
            },
        "R26 Ringing|Grace|Loud (paused)+CallEnded" to
            ringStates(ringSession().copy(pausedAt = at(5.minutes))).map { from ->
                val session = from.session
                RowExample(
                    name = from.kind,
                    from = from,
                    event = CallEnded,
                    now = at(15.minutes),
                    expected =
                        Transition(
                            from.with(
                                session.copy(
                                    interactionDeadline = Deadline.after(T0, 40.minutes),
                                    graceEnd = session.graceEnd?.let { Deadline.after(T0, 20.seconds + 10.minutes) },
                                    pausedAt = null,
                                ),
                            ),
                            listOf(SessionEffect.ResumeSound),
                        ),
                )
            },
        "R27 Snoozed+CallStarted|CallEnded" to
            listOf(CallStarted, CallEnded).map { event ->
                RowExample(event.toString(), Snoozed(snoozedSession()), event, N, Transition(Snoozed(snoozedSession()), emptyList()))
            },
        "R28 Ringing|Grace|Loud+OverlapAlarmFired" to
            ringStates().map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.OverlapAlarmFired("alarm-2", MERGED_AT),
                    now = N,
                    expected =
                        Transition(
                            from,
                            listOf(
                                SessionEffect.RecordMergedOccurrence(SESSION_ID, "alarm-2", MERGED_AT),
                                SessionEffect.RescheduleAlarm("alarm-2"),
                            ),
                        ),
                )
            },
        "R29 Snoozed+OverlapAlarmFired" to
            listOf(
                at(3.minutes).let { now ->
                    RowExample(
                        name = "snooze ends early, no grace",
                        from = Snoozed(snoozedSession()),
                        event = SessionEvent.OverlapAlarmFired("alarm-2", MERGED_AT),
                        now = now,
                        expected =
                            Transition(
                                nextRing(now, noGrace = true),
                                listOf(
                                    heartbeatAt(now),
                                    SessionEffect.RecordMergedOccurrence(SESSION_ID, "alarm-2", MERGED_AT),
                                    SessionEffect.RescheduleAlarm("alarm-2"),
                                ),
                            ),
                    )
                },
            ),
        "R30 Ringing (before first unlock)+UserUnlocked" to
            listOf(
                RowExample(
                    name = "first unlock",
                    from = Ringing(ringSession().copy(beforeFirstUnlock = true)),
                    event = SessionEvent.UserUnlocked,
                    now = N,
                    expected =
                        Transition(
                            Ringing(ringSession()),
                            listOf(SessionEffect.LiftDirectBootSubstitutions, SessionEffect.InitBilling),
                        ),
                ),
            ),
        "R31 Completed|Missed+Recorded" to
            listOf(Completed(ringSession().noTimers()), Missed(ringSession().noTimers())).map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.Recorded(SESSION_ID),
                    now = N,
                    expected = Transition(Idle, listOf(SessionEffect.ClearRuntimeSession(SESSION_ID))),
                )
            },
        "R32 Ringing|Grace|Loud+PayConfirmed keyguard locked" to
            ringStates().map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = PAY,
                    now = N,
                    expected =
                        Transition(
                            from.with(from.session.touched(N).copy(paying = INTENT, unlocking = true)),
                            listOf(SessionEffect.PersistPurchaseIntent(intentAt(N)), SessionEffect.RequestKeyguardDismiss(INTENT)),
                        ),
                    keyguardLocked = true,
                )
            },
        "R33 Ringing|Grace|Loud (unlocking)+UnlockSucceeded" to
            ringStates(ringSession().copy(paying = INTENT, unlocking = true)).map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.UnlockSucceeded,
                    now = N,
                    expected =
                        Transition(
                            from.with(from.session.copy(unlocking = false)),
                            listOf(SessionEffect.LaunchBilling(INTENT, SESSION_ID)),
                        ),
                )
            },
        "R34 Ringing|Grace|Loud (unlocking)+UnlockFailed" to
            ringStates(ringSession().copy(paying = INTENT, unlocking = true)).map { from ->
                RowExample(
                    name = from.kind,
                    from = from,
                    event = SessionEvent.UnlockFailed,
                    now = N,
                    expected =
                        Transition(
                            from.with(from.session.copy(paying = null, unlocking = false)),
                            listOf(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed)),
                        ),
                )
            },
    )
