package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.session.EntryEffect.HeartbeatSlotArmed
import com.yawnandpawn.app.core.session.EntryEffect.Muted
import com.yawnandpawn.app.core.session.EntryEffect.SoundAt
import com.yawnandpawn.app.core.session.EntryEffect.Vibrating
import com.yawnandpawn.app.core.session.EntryEffect.WakeUiShown
import com.yawnandpawn.app.core.time.Deadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionEntryEffectsTest {
    private val sound = SoundAt("builtin:default", 80)

    @Test
    fun `Idle wants nothing`() {
        assertEquals(emptyList(), entryEffects(SessionState.Idle))
    }

    @Test
    fun `Ringing and Loud want the sound at the set volume, vibration, the slot armed and the wake UI`() {
        listOf(SessionState.Ringing(ringSession()), SessionState.Loud(ringSession())).forEach { state ->
            assertEquals(listOf(sound, Vibrating, HeartbeatSlotArmed, WakeUiShown), entryEffects(state), state.kind)
        }
    }

    @Test
    fun `Ringing without vibration does not vibrate`() {
        val state = SessionState.Ringing(ringSession(testConfig(vibration = false)))
        assertEquals(listOf(sound, HeartbeatSlotArmed, WakeUiShown), entryEffects(state))
    }

    @Test
    fun `a call pauses the sound and the vibration while the slot and the wake UI stay`() {
        val state = SessionState.Loud(ringSession().copy(pausedAt = T0))
        assertEquals(listOf(EntryEffect.SoundPaused, HeartbeatSlotArmed, WakeUiShown), entryEffects(state))
    }

    @Test
    fun `Grace is muted and vibrates only with vibrate in grace`() {
        val graceEnd = Deadline.after(T0, 20.seconds)
        val quiet = SessionState.Grace(ringSession().copy(graceEnd = graceEnd))
        assertEquals(listOf(Muted, HeartbeatSlotArmed, WakeUiShown), entryEffects(quiet))
        val vibrating = SessionState.Grace(ringSession(testConfig(vibrateInGrace = true, vibration = false)).copy(graceEnd = graceEnd))
        assertEquals(listOf(Muted, Vibrating, HeartbeatSlotArmed, WakeUiShown), entryEffects(vibrating))
    }

    @Test
    fun `Snoozed wants the sound off and the slot armed at the snooze end`() {
        assertEquals(
            listOf(EntryEffect.SoundOff, EntryEffect.SlotArmedAt(Deadline.after(T0, 9.minutes))),
            entryEffects(SessionState.Snoozed(snoozedSession())),
        )
    }

    @Test
    fun `Completed and Missed request the history write`() {
        listOf(SessionState.Completed(ringSession()), SessionState.Missed(ringSession())).forEach { state ->
            assertEquals(listOf(EntryEffect.HistoryWriteRequested(SESSION_ID)), entryEffects(state), state.kind)
        }
    }

    @Test
    fun `entry effects are idempotent`() {
        val state = SessionState.Ringing(ringSession())
        assertEquals(entryEffects(state), entryEffects(state))
    }
}
