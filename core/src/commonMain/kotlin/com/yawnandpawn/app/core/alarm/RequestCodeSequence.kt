package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome

/**
 * Port for new alarm request codes (AD-4): a persisted high-water mark, so a code is never handed out twice, not even
 * after its alarm was deleted or the app restarted. `RoomRequestCodeSequence` in production, `FakeRequestCodeSequence`
 * in tests.
 */
fun interface RequestCodeSequence {
    /**
     * The stored mark + 1, persisted as the new mark in the same atomic step. The first code is
     * [RequestCodes.FIRST_ALARM]. A code is burnt even if the caller then fails to store its alarm; that is fine,
     * because codes are never reused.
     */
    suspend fun next(): Outcome<Int, DomainError>
}
