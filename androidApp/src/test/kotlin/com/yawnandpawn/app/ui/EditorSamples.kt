package com.yawnandpawn.app.ui

import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.ui.alarms.AlarmRow
import com.yawnandpawn.app.ui.alarms.AlarmsUiState
import com.yawnandpawn.app.ui.editor.EditorForm
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** Screen states shared by the screenshot and semantics tests. */
object EditorSamples {
    /** A new alarm with the defaults, its time still ahead today. */
    val newAlarm = EditorUiState()

    /** An existing repeating alarm with a label and non-default values. */
    val editAlarm =
        EditorUiState(
            isNew = false,
            form =
                EditorForm(
                    time = LocalTime(6, 30),
                    repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                    label = "Gym",
                    snoozeLengthMinutes = 5,
                    volumePercent = 60,
                    vibration = false,
                ),
        )

    /** A one-time 07:00 alarm opened after 07:00. */
    val ringsTomorrow = EditorUiState(ringsTomorrowAt = EditorForm.DEFAULT_TIME)

    /** The edited alarm with a change, after Back. */
    val discardDialog = editAlarm.copy(form = editAlarm.form.copy(label = "Gym day"), showDiscardDialog = true)

    /** Save rejected a 41-character label: the field shows its error. */
    val labelError =
        EditorUiState(form = EditorForm(label = "Morning run with the whole neighbourhood!"), fieldError = AlarmField.Label)

    /** The Sound sub-screen with gradual volume off (owner decision 2026-09-27: never a starting-volume slider). */
    val soundGradualOff = EditorUiState(form = EditorForm(gradualVolume = false), pane = EditorPane.Sound)

    /** The Sound sub-screen of a new alarm: volume and "Gradually increase volume". */
    val soundPane = EditorUiState(pane = EditorPane.Sound)

    /** The Snooze sub-screen of a new alarm: 5 / 9 / 10 / 15 min. */
    val snoozePane = EditorUiState(pane = EditorPane.Snooze)

    val emptyAlarms = AlarmsUiState(isLoading = false)

    val failedAlarms = AlarmsUiState(isLoading = false, loadFailed = true)

    val someAlarms =
        AlarmsUiState(
            isLoading = false,
            alarms =
                listOf(
                    AlarmRow("1", LocalTime(6, 30), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), null),
                    AlarmRow("2", LocalTime(7, 15), emptySet(), "Stand-up"),
                    AlarmRow("3", LocalTime(9, 0), DayOfWeek.entries.toSet(), null),
                ),
        )
}
