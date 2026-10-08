package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.Difficulty
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.session.ConfigResolver
import com.yawnandpawn.app.core.session.GlobalSettings
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class CommitmentRulesTest {
    private val now = Instant.parse("2027-03-08T23:40:00Z")
    private val window = Occurrence("a", Instant.parse("2027-03-09T07:30:00Z"))

    private fun fee(tier: Int) = SettingValue.BaseFeeTier(tier)

    private fun pending(
        value: SettingValue,
        after: Occurrence = window,
        alarmId: String? = null,
    ) = PendingChange(alarmId, value, after)

    private data class Case(
        val name: String,
        val live: Int,
        val pending: PendingChange?,
        val new: Int,
        val window: Occurrence?,
        val expected: LockDecision,
    )

    private val passed = Occurrence("a", now - 1.hours)

    private val decisionCases =
        listOf(
            Case("raising applies at once", 3, null, 5, window, LockDecision.ApplyNow(fee(5))),
            Case("lowering inside the window waits", 3, null, 1, window, LockDecision.Defer(fee(3), pending(fee(1)))),
            Case("lowering outside the window applies", 3, null, 1, null, LockDecision.ApplyNow(fee(1))),
            Case(
                "effective 3, pending 1, new 2: still weakening",
                3,
                pending(fee(1)),
                2,
                window,
                LockDecision.Defer(fee(3), pending(fee(2))),
            ),
            Case(
                "effective 3, pending 1, new 5: applied, pending cleared",
                3,
                pending(fee(1)),
                5,
                window,
                LockDecision.ApplyNow(fee(5)),
            ),
            Case("the same value applies (and clears the pending one)", 3, pending(fee(1)), 3, window, LockDecision.ApplyNow(fee(3))),
            Case(
                "the value already pending keeps its date",
                3,
                pending(fee(1), after = Occurrence("b", window.scheduledAt - 1.hours)),
                1,
                window,
                LockDecision.Defer(fee(3), pending(fee(1), after = Occurrence("b", window.scheduledAt - 1.hours))),
            ),
            Case(
                "a due pending change is the effective value",
                3,
                pending(fee(1), after = passed),
                1,
                window,
                LockDecision.ApplyNow(fee(1)),
            ),
            Case(
                "weakening a due change promotes it and waits",
                3,
                pending(fee(2), after = passed),
                1,
                window,
                LockDecision.Defer(fee(2), pending(fee(1))),
            ),
        )

    @Test
    fun `the lock decision table`() {
        decisionCases.forEach { case ->
            assertEquals(
                case.expected,
                CommitmentRules.decide(null, fee(case.live), case.pending, fee(case.new), case.window, now),
                case.name,
            )
        }
    }

    @Test
    fun `a pending change applies only to an occurrence strictly after the one it waits for`() {
        val change = pending(fee(1))

        assertEquals(false, change.appliesTo(window.scheduledAt))
        assertEquals(false, change.appliesTo(window.scheduledAt - 1.minutes))
        assertEquals(true, change.appliesTo(window.scheduledAt + 1.days))
        assertEquals(true, change.appliesTo(window.scheduledAt + 1.milliseconds), "a millisecond later (review 7)")
        assertEquals(
            false,
            change.isDue(window.scheduledAt + PendingChange.SETTLE),
            "not while the fire may still be on its way (review 9)",
        )
        assertEquals(true, change.isDue(window.scheduledAt + PendingChange.SETTLE + 1.milliseconds))
        assertEquals(31.minutes, PendingChange.SETTLE)
        assertEquals(LockedField.BaseFee, change.field)
    }

    private val alarm =
        Alarm(
            id = "a",
            time = LocalTime(7, 30),
            graceSeconds = 20,
            requestCode = 1_000,
            createdAt = Instant.parse("2027-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2027-01-01T00:00:00Z"),
            checkMode = CheckMode.All,
        )
    private val hardMath = CheckEntry(CheckType.Math, Difficulty.Hard, 5)
    private val easyMath = CheckEntry(CheckType.Math, Difficulty.Easy, 1)

    private val changes =
        listOf(
            pending(fee(1)),
            pending(SettingValue.MaxSnoozes(5)),
            pending(SettingValue.GraceSeconds(30), alarmId = "a"),
            pending(SettingValue.Checks(CheckPlan(CheckMode.Random, listOf(easyMath))), alarmId = "a"),
            // Another alarm's change never applies to this one.
            pending(SettingValue.GraceSeconds(15), alarmId = "b"),
        )

    private fun resolve(at: Instant) =
        ConfigResolver.resolve(
            alarm,
            listOf(hardMath),
            GlobalSettings(baseFeeTier = 3, maxSnoozes = 2),
            testMode = false,
            scheduledAt = at,
            wordsAvailable = true,
            pendingChanges = changes,
        )

    @Test
    fun `the session at the waited-for occurrence and earlier ones ring the live settings`() {
        listOf(window.scheduledAt, window.scheduledAt - 30.minutes).forEach { at ->
            val config = resolve(at)
            assertEquals(3, config.baseFeeTier, "$at")
            assertEquals(2, config.maxSnoozes)
            assertEquals(20, config.graceSeconds)
            assertEquals(CheckPlan(CheckMode.All, listOf(hardMath)), config.checkPlan)
        }
    }

    @Test
    fun `every later occurrence rings the pending values, and only this alarm's and the global ones`() {
        val config = resolve(window.scheduledAt + 1.days)

        assertEquals(1, config.baseFeeTier)
        assertEquals(5, config.maxSnoozes)
        assertEquals(30, config.graceSeconds)
        assertEquals(CheckPlan(CheckMode.Random, listOf(easyMath)), config.checkPlan)
    }

    @Test
    fun `a global change waiting for one alarm applies to another alarm's later ring only (review 5)`() {
        val other = alarm.copy(id = "b")
        val change = listOf(pending(fee(1)))

        fun tierAt(at: Instant) =
            ConfigResolver
                .resolve(
                    other,
                    listOf(hardMath),
                    GlobalSettings(baseFeeTier = 3),
                    false,
                    at,
                    wordsAvailable = true,
                    pendingChanges = change,
                ).baseFeeTier

        assertEquals(1, tierAt(Instant.parse("2027-03-09T08:00:00Z")), "b rings after a's 07:30")
        assertEquals(3, tierAt(Instant.parse("2027-03-09T07:00:00Z")), "b rings before a's 07:30")
    }

    @Test
    fun `a pending plan rings with the code registered since`() {
        val old = RegisteredCode.of(CodeFormat.QrCode, "old sticker")!!
        val new = RegisteredCode.of(CodeFormat.QrCode, "new sticker")!!
        val pendingPlan = CheckPlan(CheckMode.Random, listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, old), easyMath))
        val live = listOf(CheckEntry(CheckType.QrBarcode, Difficulty.Medium, 1, new))

        val config =
            ConfigResolver.resolve(
                alarm,
                live,
                GlobalSettings(),
                testMode = false,
                scheduledAt = window.scheduledAt + 1.days,
                wordsAvailable = true,
                pendingChanges = listOf(pending(SettingValue.Checks(pendingPlan), alarmId = "a")),
            )

        assertEquals(
            new,
            config.checkPlan.entries
                .first()
                .code,
        )
        // Without a live QR entry the pending plan keeps its own code.
        assertEquals(pendingPlan, pendingPlan.withCodesFrom(listOf(easyMath)))
    }

    @Test
    fun `the global settings take only global changes`() {
        val settings = GlobalSettings().withPending(listOf(pending(fee(4)), pending(SettingValue.GraceSeconds(30), alarmId = "a")))
        assertEquals(GlobalSettings(baseFeeTier = 4), settings)
        assertEquals(changes.take(4), changes.applyingTo("a", window.scheduledAt + 1.days))
        assertNull(changes.applyingTo("a", window.scheduledAt).firstOrNull())
    }
}
