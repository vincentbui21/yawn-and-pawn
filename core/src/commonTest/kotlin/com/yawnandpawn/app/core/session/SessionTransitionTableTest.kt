package com.yawnandpawn.app.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** One test per AD-2 row (examples in [ROW_CASES]), plus the table-coverage test. */
class SessionTransitionTableTest {
    @Test
    fun `the table has 31 rows with unique ids and every row has examples and no others exist`() {
        assertEquals(31, AD2_ROWS.size)
        assertEquals(AD2_ROWS.size, AD2_ROWS.toSet().size, "row ids are unique")
        assertEquals(AD2_ROWS.toSet(), ROW_CASES.keys, "every AD-2 row has a case and every case is a row")
        AD2_ROWS.forEach { row -> assertTrue(ROW_CASES.getValue(row).isNotEmpty(), "$row has no examples") }
    }

    @Test
    fun `every row example passes, so none is skipped`() = AD2_ROWS.forEach(::assertRow)

    @Test
    fun `R01 Idle and AlarmFired with the alarm enabled starts Ringing(1)`() = assertRow("R01 Idle+AlarmFired")

    @Test
    fun `R02 Idle and TestAlarmFired starts Ringing(1) in test mode`() = assertRow("R02 Idle+TestAlarmFired")

    @Test
    fun `R03 SlotFired while ringing re-arms the heartbeat`() = assertRow("R03 Ringing|Grace|Loud+SlotFired")

    @Test
    fun `R04 SlotFired after the snooze end rings again`() = assertRow("R04 Snoozed+SlotFired")

    @Test
    fun `R05 an overdue snooze after a reboot or restore rings immediately`() = assertRow("R05 Snoozed+SlotFired|ProcessRestored overdue")

    @Test
    fun `R06 I'm up starts the muted grace window`() = assertRow("R06 Ringing+ImUpTapped with grace")

    @Test
    fun `R07 I'm up on a ring without grace goes Loud`() = assertRow("R07 Ringing+ImUpTapped without grace")

    @Test
    fun `R08 the grace window ending goes Loud with a strong haptic`() = assertRow("R08 Grace+GraceElapsed")

    @Test
    fun `R09 a valid answer that is not the last advances the check`() = assertRow("R09 Grace|Loud+CheckAnswerSubmitted valid, not last")

    @Test
    fun `R10 an invalid answer counts a failed attempt`() = assertRow("R10 Grace|Loud+CheckAnswerSubmitted invalid")

    @Test
    fun `R11 a valid last answer completes the session`() = assertRow("R11 Grace|Loud+CheckAnswerSubmitted valid, last")

    @Test
    fun `R12 an allowed fallback replaces the plan once`() = assertRow("R12 Grace|Loud+FallbackRequested")

    @Test
    fun `R13 Snooze while available shows the confirm sheet`() = assertRow("R13 Ringing|Grace|Loud+SnoozeTapped")

    @Test
    fun `R14 paying persists the intent and launches billing`() = assertRow("R14 Ringing|Grace|Loud+PayConfirmed")

    @Test
    fun `R15 a reuse offer clears paying and shows the reuse sheet`() = assertRow("R15 Ringing|Grace|Loud+ReuseOffered")

    @Test
    fun `R16 accepting the reuse snoozes like a grant`() = assertRow("R16 Ringing|Grace|Loud+ReuseAccepted")

    @Test
    fun `R17 declining the reuse remembers the product and hides the sheet`() = assertRow("R17 Ringing|Grace|Loud+ReuseDeclined")

    @Test
    fun `R18 a granted purchase snoozes whether or not paying is set`() = assertRow("R18 Ringing|Grace|Loud+PurchaseGranted")

    @Test
    fun `R19 a failed or cancelled purchase shows the outcome and keeps ringing`() =
        assertRow("R19 Ringing|Grace|Loud+PurchaseFailed|PurchaseCancelled")

    @Test
    fun `R20 a pending purchase sets paymentPending and keeps ringing`() = assertRow("R20 Ringing|Grace|Loud+PurchasePending")

    @Test
    fun `R21 a matched image counts as a valid answer`() = assertRow("R21 Grace|Loud+ImageMatchCompleted matched")

    @Test
    fun `R22 no match or a matcher error counts a failed attempt and asks again`() =
        assertRow("R22 Grace|Loud+ImageMatchCompleted|ImageMatchFailed not matched")

    @Test
    fun `R23 30 minutes without interaction ends the session as Missed`() = assertRow("R23 Ringing|Loud+NoInteractionTimeout")

    @Test
    fun `R24 any user event resets the interaction deadline`() = assertRow("R24 Ringing|Grace|Loud+any user event")

    @Test
    fun `R25 a call pauses the sound and the deadlines`() = assertRow("R25 Ringing|Grace|Loud+CallStarted")

    @Test
    fun `R26 the call ending resumes and leaves the call time out of the deadlines`() =
        assertRow("R26 Ringing|Grace|Loud (paused)+CallEnded")

    @Test
    fun `R27 calls do not touch a snooze`() = assertRow("R27 Snoozed+CallStarted|CallEnded")

    @Test
    fun `R28 an overlapping alarm while ringing is recorded as merged and rescheduled`() =
        assertRow("R28 Ringing|Grace|Loud+OverlapAlarmFired")

    @Test
    fun `R29 an overlapping alarm while snoozed rings again with no grace`() = assertRow("R29 Snoozed+OverlapAlarmFired")

    @Test
    fun `R30 the first unlock lifts Direct Boot substitutions and starts billing`() =
        assertRow("R30 Ringing (before first unlock)+UserUnlocked")

    @Test
    fun `R31 the history row written returns to Idle and clears the runtime session`() = assertRow("R31 Completed|Missed+Recorded")

    private fun assertRow(row: String) {
        val examples = ROW_CASES[row] ?: fail("no examples for $row")
        assertTrue(row in AD2_ROWS, "$row is not an AD-2 row")
        assertTrue(examples.isNotEmpty(), "$row has no examples")
        examples.forEach { example ->
            val transition = example.reducer.reduce(example.from, example.event, example.now)
            assertEquals(example.expected.state, transition.state, "$row / ${example.name}: state")
            assertEquals(example.expected.effects, transition.effects, "$row / ${example.name}: effects")
            example.also(example.reducer, transition)
        }
    }
}
