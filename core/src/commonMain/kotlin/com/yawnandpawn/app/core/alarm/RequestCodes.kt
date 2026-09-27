package com.yawnandpawn.app.core.alarm

/**
 * PendingIntent request codes (AD-4). The session slot and the test alarm have fixed codes; every alarm gets its own
 * code from [FIRST_ALARM] upward, kept for its whole life (edits keep it) and never shared with another alarm.
 */
object RequestCodes {
    /** The snooze/session slot alarm. */
    const val SESSION_SLOT = 1

    /** The test alarm (Story 1.18). */
    const val TEST_ALARM = 2

    /** The lowest code an alarm can get; everything below is reserved. */
    const val FIRST_ALARM = 1000

    /** The code for a new alarm: one above the highest code in use, at least [FIRST_ALARM]. */
    fun nextAlarmCode(codesInUse: Collection<Int>): Int = maxOf(FIRST_ALARM - 1, codesInUse.maxOrNull() ?: 0) + 1
}
