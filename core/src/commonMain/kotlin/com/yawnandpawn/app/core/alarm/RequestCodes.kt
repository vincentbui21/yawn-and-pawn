package com.yawnandpawn.app.core.alarm

/**
 * PendingIntent request codes (AD-4). The session slot and the test alarm have fixed codes; every alarm gets its own
 * code from [FIRST_ALARM] upward, kept for its whole life (edits keep it) and never shared with another alarm.
 * New codes come from the persisted high-water mark ([RequestCodeSequence]), so a deleted alarm's code is never
 * handed out again.
 */
object RequestCodes {
    /** The snooze/session slot alarm. */
    const val SESSION_SLOT = 1

    /** The test alarm (Story 1.18). */
    const val TEST_ALARM = 2

    /** The lowest code an alarm can get; everything below is reserved. */
    const val FIRST_ALARM = 1000

    /** The high-water mark before any alarm code was handed out: the first [RequestCodeSequence.next] is [FIRST_ALARM]. */
    const val INITIAL_HIGH_WATER_MARK = FIRST_ALARM - 1

    /** The high-water mark for existing [codesInUse]: the highest of them, at least [INITIAL_HIGH_WATER_MARK]. */
    fun highWaterMark(codesInUse: Collection<Int>): Int = maxOf(INITIAL_HIGH_WATER_MARK, codesInUse.maxOrNull() ?: 0)
}
