package com.yawnandpawn.app.core.alarm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The motivation message an alarm plays (Story 7.2, Epic 7): none, one of the user's recordings picked at random
 * (resolved once per session by Story 7.3), or one recording by its id. Stored in `alarm.motivation` and frozen into
 * `SessionConfig` as its text [encode]ing: `none`, `random` or `recording:{id}`; any other text reads as [None] ([parse]).
 * Changing it is never a weakening change under the commitment lock.
 */
@Serializable(with = MotivationSerializer::class)
sealed interface Motivation {
    /** The stable text form, stored in `app.db` and in a session's config. */
    fun encode(): String

    data object None : Motivation {
        override fun encode(): String = NONE
    }

    data object Random : Motivation {
        override fun encode(): String = RANDOM
    }

    /** The recording [id] (a `recording` row). */
    data class Recording(
        val id: String,
    ) : Motivation {
        override fun encode(): String = RECORDING_PREFIX + id
    }

    companion object {
        const val NONE = "none"
        const val RANDOM = "random"
        const val RECORDING_PREFIX = "recording:"

        /** [text] as a motivation; unknown text (a newer version's, or a blank recording id) is [None]. */
        fun parse(text: String?): Motivation =
            when {
                text == RANDOM -> {
                    Random
                }

                text != null && text.startsWith(RECORDING_PREFIX) && text.length > RECORDING_PREFIX.length -> {
                    Recording(text.removePrefix(RECORDING_PREFIX))
                }

                else -> {
                    None
                }
            }
    }
}

/** A [Motivation] as its text form, so a stored session reads the same text `app.db` holds. */
internal object MotivationSerializer : KSerializer<Motivation> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.yawnandpawn.app.core.alarm.Motivation", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Motivation,
    ) = encoder.encodeString(value.encode())

    override fun deserialize(decoder: Decoder): Motivation = Motivation.parse(decoder.decodeString())
}
