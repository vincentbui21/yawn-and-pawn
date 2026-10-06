package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Id of a persisted `PurchaseIntent` (AD-7), a UUID v4 string chosen outside the reducer. */
@Serializable
@JvmInline
value class PurchaseIntentId(
    val value: String,
)

/**
 * The state of the one wake session (AD-2). Only the reducer ([SessionReducer]) makes a new state; `SessionEngine`
 * (Story 1.12) persists it before any effect runs, so everything here is serializable.
 */
@Serializable
sealed interface SessionState {
    /** No session. */
    @Serializable
    @SerialName("Idle")
    data object Idle : SessionState

    /** A session exists: every state except [Idle]. */
    sealed interface Active : SessionState {
        val session: SessionData

        /** The same state holding [session]. */
        fun with(session: SessionData): Active
    }

    /** The alarm is ringing, quiet or loud: the states where the user acts on the wake screen. */
    sealed interface Ring : Active {
        override fun with(session: SessionData): Ring
    }

    /** Ringing at the set volume, waiting for "I'm up". */
    @Serializable
    @SerialName("Ringing")
    data class Ringing(
        override val session: SessionData,
    ) : Ring {
        override fun with(session: SessionData): Ringing = copy(session = session)
    }

    /** "I'm up" was tapped: muted until [SessionData.graceEnd] while the user starts the check. */
    @Serializable
    @SerialName("Grace")
    data class Grace(
        override val session: SessionData,
    ) : Ring {
        override fun with(session: SessionData): Grace = copy(session = session)
    }

    /** The grace window ended (or this ring has none): loud while the user finishes the check. */
    @Serializable
    @SerialName("Loud")
    data class Loud(
        override val session: SessionData,
    ) : Ring {
        override fun with(session: SessionData): Loud = copy(session = session)
    }

    /** A paid snooze: silent until [SessionData.snoozeEnd]. */
    @Serializable
    @SerialName("Snoozed")
    data class Snoozed(
        override val session: SessionData,
    ) : Active {
        override fun with(session: SessionData): Snoozed = copy(session = session)
    }

    /** The check was passed; waiting for the history row. */
    @Serializable
    @SerialName("Completed")
    data class Completed(
        override val session: SessionData,
    ) : Active {
        override fun with(session: SessionData): Completed = copy(session = session)
    }

    /** Nobody interacted for 30 minutes (FR-ALM-9); waiting for the history row. */
    @Serializable
    @SerialName("Missed")
    data class Missed(
        override val session: SessionData,
    ) : Active {
        override fun with(session: SessionData): Missed = copy(session = session)
    }
}

/**
 * What every active session state holds (AD-2 rule 3). The display-only `paid` list arrives with `Money` in Epic 4.
 *
 * @property ringIndex 1 for the first ring, +1 for each ring after a snooze or a merge.
 * @property paying the purchase in flight, if any; cleared on restore (billing is never relaunched).
 * @property noGraceThisRing this ring started from a merged alarm, so "I'm up" goes straight to [SessionState.Loud].
 * @property beforeFirstUnlock this ring rings before the first unlock since boot and has not seen the unlock yet. Set
 * from the lock state when a ring starts, is restored or follows a snooze (Story 2.3), and cleared by `UserUnlocked`.
 * @property directBootRing this ring uses the Direct Boot substitutions, the default sound and the Direct Boot check
 * plan (Story 2.4). Set with [beforeFirstUnlock] when a ring starts, is restored or follows a snooze,
 * but kept for the rest of the ring when the user unlocks, so the sound and the check do not change under the user.
 * @property paymentPending Play reported a pending payment; cleared by a later grant.
 * @property declinedReuseProduct the product whose stranded payment the user declined to reuse.
 * @property graceEnd when the grace window ends (Grace only).
 * @property interactionDeadline when the ring is stopped as Missed without a user event (FR-ALM-9); null while snoozed.
 * @property snoozeEnd when the snooze rings again (Snoozed only).
 * @property pausedAt when a call paused the ring; null when not paused. Time from here to the call's end is added to
 * [graceEnd] and [interactionDeadline], so paused time never counts.
 * @property firstRing when the first ring started (AD-18 history), set by `AlarmFired` / `TestAlarmFired`. Null in a
 * session stored before Story 1.13; the recorder then falls back to the stored history row, then to the scheduled time.
 * @property startedBeforeUnlock a ring of the session rang before the first unlock after a boot: its first ring, or a
 * ring started or restored while locked (Story 2.3; history `direct_boot`). Unlike [beforeFirstUnlock], which each
 * new ring sets from the lock state, it only ever goes from false to true, so history keeps it after the unlock.
 * @property ended when the session ended (Completed or Missed), set by the reducer on that transition; null before.
 */
@Serializable
data class SessionData(
    val sessionId: String,
    val config: SessionConfig,
    val ringIndex: Int,
    val snoozesGranted: Int,
    val checkRun: CheckRun,
    val firstRing: TimeSnapshot? = null,
    val startedBeforeUnlock: Boolean = false,
    val ended: TimeSnapshot? = null,
    val paying: PurchaseIntentId? = null,
    val noGraceThisRing: Boolean = false,
    val beforeFirstUnlock: Boolean = false,
    val directBootRing: Boolean = false,
    val paymentPending: Boolean = false,
    val declinedReuseProduct: String? = null,
    val graceEnd: Deadline? = null,
    val interactionDeadline: Deadline? = null,
    val snoozeEnd: Deadline? = null,
    val pausedAt: TimeSnapshot? = null,
) {
    /** A call is in progress (AD-2 `CallStarted` until `CallEnded`). */
    val paused: Boolean
        get() = pausedAt != null

    /** The wall time of [firstRing]; not stored (derived). */
    val firstRingAt: Instant?
        get() = firstRing?.let { Instant.fromEpochMilliseconds(it.wallMillis) }
}
