package com.yawnandpawn.app.core.alarm

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime

/** When an alarm rings: a local wall-clock [time] on [repeatDays]; an empty set means one-time (next possible day). */
data class AlarmRule(
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek> = emptySet(),
) {
    val isOneTime: Boolean
        get() = repeatDays.isEmpty()

    fun ringsOn(day: DayOfWeek): Boolean = isOneTime || day in repeatDays
}
