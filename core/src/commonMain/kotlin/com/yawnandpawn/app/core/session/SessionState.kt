package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.time.Deadline
import com.yawnandpawn.app.core.time.TimeSnapshot
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
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
 * What every active session state holds (AD-2 rule 3), with the display-only `paid` list (Story 4.7).
 *
 * @property ringIndex 1 for the first ring, +1 for each ring after a snooze or a merge.
 * @property paying the purchase in flight, if any; cleared on restore (billing is never relaunched).
 * @property unlocking with [paying]: the keyguard dismiss is requested and billing launches once the user unlocks
 * (Spike S1, Story 4.8). Cleared with [paying].
 * @property noGraceThisRing this ring started from a merged alarm, so "I'm up" goes straight to [SessionState.Loud].
 * @property beforeFirstUnlock this ring rings before the first unlock since boot and has not seen the unlock yet. Set
 * from the lock state when a ring starts, is restored or follows a snooze (Story 2.3), and cleared by `UserUnlocked`.
 * @property directBootRing this ring uses the Direct Boot substitutions, the default sound and the Direct Boot check
 * plan (fallback included; Story 2.4). Set with [beforeFirstUnlock] when a ring starts, is restored or follows a snooze,
 * but kept for the rest of the ring when the user unlocks, so the sound and the check do not change under the user. A
 * session stored before Story 2.4 takes [beforeFirstUnlock].
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
 * @property paid what each paid snooze of the session cost, in order (Story 4.7), appended by `PurchaseGranted` and
 * `ReuseAccepted` when the price is known. For the wake screen only ("{paid} paid this morning"); history totals always
 * come from purchase records. A session stored before Story 4.7 has none; a malformed stored amount is dropped.
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
    val unlocking: Boolean = false,
    val noGraceThisRing: Boolean = false,
    val beforeFirstUnlock: Boolean = false,
    val directBootRing: Boolean = beforeFirstUnlock,
    val paymentPending: Boolean = false,
    val declinedReuseProduct: String? = null,
    val graceEnd: Deadline? = null,
    val interactionDeadline: Deadline? = null,
    val snoozeEnd: Deadline? = null,
    val pausedAt: TimeSnapshot? = null,
    @Serializable(with = PaidAmountsSerializer::class)
    val paid: List<Money> = emptyList(),
) {
    /** A call is in progress (AD-2 `CallStarted` until `CallEnded`). */
    val paused: Boolean
        get() = pausedAt != null

    /** The wall time of [firstRing]; not stored (derived). */
    val firstRingAt: Instant?
        get() = firstRing?.let { Instant.fromEpochMilliseconds(it.wallMillis) }
}

/** One stored amount of [SessionData.paid]: micros and an ISO 4217 code, checked by [Money.parse] when read. */
@Serializable
private data class StoredAmount(
    val micros: Long,
    val currency: String,
)

/**
 * [SessionData.paid] as a list of `{"micros":…,"currency":"…"}`. Display-only data must never make the whole session
 * unreadable (a session that fails to decode must never stop a ring), so reading JSON is lenient element by element:
 * an amount with a missing or malformed micros or currency is dropped, the others are kept, and anything but a list
 * (`null`, a string) reads as nothing paid.
 */
internal object PaidAmountsSerializer : KSerializer<List<Money>> {
    private val stored = ListSerializer(StoredAmount.serializer())

    override val descriptor: SerialDescriptor = stored.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<Money>,
    ) = encoder.encodeSerializableValue(stored, value.map { StoredAmount(it.micros, it.currency) })

    override fun deserialize(decoder: Decoder): List<Money> =
        if (decoder is JsonDecoder) {
            (decoder.decodeJsonElement() as? JsonArray).orEmpty().mapNotNull(::amountOf)
        } else {
            decoder.decodeSerializableValue(stored).mapNotNull { Money.parse(it.micros, it.currency).valueOrNull() }
        }

    private fun amountOf(element: JsonElement): Money? {
        val fields = element as? JsonObject ?: return null
        val micros = (fields["micros"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        val currency = (fields["currency"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (micros == null || currency == null) null else Money.parse(micros, currency).valueOrNull()
    }
}
