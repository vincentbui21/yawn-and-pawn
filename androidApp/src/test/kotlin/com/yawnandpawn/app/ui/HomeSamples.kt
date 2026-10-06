package com.yawnandpawn.app.ui

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.format.Weekends
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.DeleteAlarmDialog
import com.yawnandpawn.app.ui.home.HomeUiState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** Home states (Story 1.9) shared by the screenshot and semantics tests. Epic 1 cards have no check icons. */
object HomeSamples {
    private fun card(
        id: String,
        time: LocalTime,
        repeatDays: Set<DayOfWeek> = emptySet(),
        label: String? = null,
        enabled: Boolean = true,
    ) = AlarmCard(id = id, time = time, repeatDays = repeatDays, label = label, checks = emptyList(), enabled = enabled)

    val loading = HomeUiState(isLoading = true)

    val empty = HomeUiState()

    val loadFailed = HomeUiState(loadFailed = true)

    val one =
        HomeUiState(
            nextAlarm = Countdown.HoursMinutes(7, 12),
            alarms = listOf(card("1", LocalTime(6, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))),
        )

    /** Enough cards to fill a phone, in time order, one of them off. */
    val many =
        HomeUiState(
            nextAlarm = Countdown.Minutes(45),
            alarms =
                listOf(
                    card("1", LocalTime(5, 45), Weekdays, label = "Early shift"),
                    card("2", LocalTime(6, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)),
                    card("3", LocalTime(7, 15), label = "Stand-up"),
                    card("4", LocalTime(8, 0), Weekends, enabled = false),
                    card("5", LocalTime(9, 0), DayOfWeek.entries.toSet()),
                    card("6", LocalTime(21, 30), label = "Pills", enabled = false),
                ),
        )

    val allDisabled =
        HomeUiState(
            alarms =
                listOf(
                    card("1", LocalTime(6, 30), Weekdays, enabled = false),
                    card("2", LocalTime(9, 0), Weekends, label = "Run", enabled = false),
                ),
        )

    val deleteDialog = one.copy(deleteDialog = DeleteAlarmDialog("1", LocalTime(6, 30)))

    val openFailed = one.copy(openFailed = true)

    /** A switch, Duplicate or Delete could not be stored (owner decision 2026-10-02). */
    val saveFailed = one.copy(saveFailed = true)

    /** Story 1.16: the 6:00 alarm stopped after 30 minutes and the user has not dismissed the note yet. */
    val missedNote = one.copy(missedAlarmAt = LocalTime(6, 0))

    /** A reliability setting is off (Story 1.19): "Alarms may not ring. Fix settings" with "Fix". */
    val reliability = one.copy(reliabilityProblem = true)

    /** Story 3.13: the fallback replaced the QR/Barcode check 3 times this week: the info banner with "Re-register". */
    val reRegister = one.copy(reregisterCheck = CheckType.QrBarcode)

    /** Story 3.13 with a reliability setting off too: the reliability banner stays above the re-register one. */
    val reRegisterUnderReliability = reliability.copy(reregisterCheck = CheckType.QrBarcode)
}
