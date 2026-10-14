package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.config.InMemoryPendingChanges
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.ConfigResolver
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Story 4.6 review fixes 1 to 3: the editor now saves the pending values back, so a kept pending change must still
 * protect a ring the alarm was moved to, a global change waiting for this alarm moves with its ring, and re-saving the
 * same checks (with a re-registered code) keeps the change's date.
 */
class SaveAlarmMovedRingTest {
    /** Monday 2027-03-08, 23:50 UTC; the alarm rings at 07:30, and 07:45 (the moved ring) is inside the window too. */
    private val lateEvening = Instant.parse("2027-03-08T23:50:00Z")
    private val sevenThirty = Instant.parse("2027-03-09T07:30:00Z")
    private val sevenFortyFive = Instant.parse("2027-03-09T07:45:00Z")
    private val clock = TestClock(Instant.parse("2027-03-08T12:00:00Z"))
    private val zone = TestZone(TimeZone.UTC)
    private val repository = InMemoryAlarms()
    private val lock = AlarmWriteLock()
    private val scheduling = AlarmScheduling(repository, RecordingScheduler(), clock, zone, lock, RecordingLogger())
    private val sessionLock =
        SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))
    private val checkConfigs = InMemoryCheckConfigs(repository)
    private val pending = InMemoryPendingChanges()
    private val save =
        SaveAlarm(repository, SequentialIds(), clock, lock, InMemorySequence(), scheduling, sessionLock, checkConfigs, pending, zone)

    private val math = CheckEntry(CheckType.Math, Difficulty.Medium, 3)
    private val draft = AlarmDraft(time = LocalTime(7, 30), graceSeconds = 20, checks = listOf(math))

    /** The alarm stored at noon (no commitment), then the clock at 23:40. */
    private suspend fun stored(draft: AlarmDraft = this.draft): Alarm {
        val alarm = assertIs<Outcome.Success<Alarm>>(save(draft)).value
        clock.now = lateEvening
        return alarm
    }

    private suspend fun edit(
        alarm: Alarm,
        change: (AlarmDraft) -> AlarmDraft,
    ): AlarmSaved {
        val current = draft.copy(id = alarm.id)
        return assertIs<Outcome.Success<AlarmSaved>>(save.save(change(current))).value
    }

    @Test
    fun `a kept quiet time moved to a later ring waits for that ring, and the moved ring resolves the live value`() =
        runTest {
            val alarm = stored()
            edit(alarm) { it.copy(graceSeconds = 30) }
            assertEquals(
                Occurrence(alarm.id, sevenThirty),
                pending.changes.value
                    .single()
                    .effectiveAfter,
            )

            // The editor opens with 30 s selected and the user moves the alarm to 07:45.
            val saved = edit(alarm) { it.copy(graceSeconds = 30, time = LocalTime(7, 45)) }

            assertEquals(Occurrence(alarm.id, sevenFortyFive), saved.pendingUntil)
            assertEquals(
                Occurrence(alarm.id, sevenFortyFive),
                pending.changes.value
                    .single()
                    .effectiveAfter,
            )
            val config =
                ConfigResolver.resolve(
                    saved.alarm,
                    listOf(math),
                    GlobalSettings(),
                    false,
                    sevenFortyFive,
                    pendingChanges = pending.changes.value,
                )
            assertEquals(20, config.graceSeconds)
        }

    @Test
    fun `kept easier checks moved to a later ring wait for that ring`() =
        runTest {
            val alarm = stored()
            val easy = math.copy(difficulty = Difficulty.Easy)
            edit(alarm) { it.copy(checks = listOf(easy)) }

            val saved = edit(alarm) { it.copy(checks = listOf(easy), time = LocalTime(7, 45)) }

            assertEquals(
                Occurrence(alarm.id, sevenFortyFive),
                pending.changes.value
                    .single()
                    .effectiveAfter,
            )
            assertEquals(Occurrence(alarm.id, sevenFortyFive), saved.pendingUntil)
        }

    @Test
    fun `saving the pending checks again with only a label change keeps the change as it is`() =
        runTest {
            val alarm = stored()
            val easy = math.copy(difficulty = Difficulty.Easy)
            edit(alarm) { it.copy(checks = listOf(easy)) }
            val before = pending.changes.value

            edit(alarm) { it.copy(checks = listOf(easy), label = "Gym") }

            assertEquals(before, pending.changes.value)
        }

    @Test
    fun `a QR code re-registered after the pending change keeps its date and takes the new code`() =
        runTest {
            val old = RegisteredCode(CodeFormat.QrCode, "old")
            val new = RegisteredCode(CodeFormat.QrCode, "new")
            val qr = CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, code = old)
            val alarm = stored(draft.copy(checks = listOf(qr, math), checkMode = CheckMode.All))
            // Easier: Random instead of All.
            edit(alarm) { it.copy(checks = listOf(qr, math), checkMode = CheckMode.Random) }
            assertEquals(
                Occurrence(alarm.id, sevenThirty),
                pending.changes.value
                    .single()
                    .effectiveAfter,
            )

            // The sticker is re-registered, and the editor saves the pending plan with the new code.
            val renewed = qr.copy(code = new)
            edit(alarm) { it.copy(checks = listOf(renewed, math), checkMode = CheckMode.Random) }

            val change = pending.changes.value.single()
            assertEquals(Occurrence(alarm.id, sevenThirty), change.effectiveAfter)
            assertEquals(SettingValue.Checks(CheckPlan(CheckMode.Random, listOf(renewed, math))), change.value)
        }

    @Test
    fun `a lower fee waiting for this alarm's ring moves with it when the ring moves later`() =
        runTest {
            val alarm = stored()
            pending.changes.value = listOf(PendingChange(null, SettingValue.BaseFeeTier(1), Occurrence(alarm.id, sevenThirty)))

            edit(alarm) { it.copy(time = LocalTime(7, 45)) }

            assertEquals(
                Occurrence(alarm.id, sevenFortyFive),
                pending.changes.value
                    .single()
                    .effectiveAfter,
            )
            val config =
                ConfigResolver.resolve(
                    alarm.copy(time = LocalTime(7, 45)),
                    listOf(math),
                    GlobalSettings(baseFeeTier = 3),
                    false,
                    sevenFortyFive,
                    pendingChanges = pending.changes.value,
                )
            assertEquals(3, config.baseFeeTier)
        }

    @Test
    fun `a ring moved earlier, a change waiting for another alarm, or an unchanged ring leave a global change as it is`() =
        runTest {
            val alarm = stored()
            val mine = PendingChange(null, SettingValue.BaseFeeTier(1), Occurrence(alarm.id, sevenThirty))
            val other = PendingChange(null, SettingValue.MaxSnoozes(5), Occurrence("other", sevenThirty))
            pending.changes.value = listOf(mine, other)

            edit(alarm) { it.copy(label = "Gym") }
            assertEquals(listOf(mine, other), pending.changes.value)

            edit(alarm) { it.copy(time = LocalTime(7, 0)) }
            assertEquals(listOf(mine, other), pending.changes.value)
        }
}
