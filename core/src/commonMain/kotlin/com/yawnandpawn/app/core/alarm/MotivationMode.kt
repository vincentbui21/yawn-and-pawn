package com.yawnandpawn.app.core.alarm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * When an alarm's motivation message plays ("When it plays", Story 7.5, FR-SND-7): [AfterImUp] on Success after the
 * check (Story 7.3), or [MixIntoAlarm], alternating with the alarm sound during the ring (`mixSchedule`). Stored in
 * `alarm.motivation_mode` and frozen into `SessionConfig` as its [text]; any other text reads as [AfterImUp] ([parse]).
 * It only matters when the alarm has a message, and changing it is never a weakening change under the commitment lock.
 */
@Serializable(with = MotivationModeSerializer::class)
enum class MotivationMode(
    /** The stable text form, stored in `app.db` and in a session's config. */
    val text: String,
) {
    AfterImUp("after_up"),
    MixIntoAlarm("mix"),
    ;

    companion object {
        /** [text] as a mode; unknown text (a newer version's) or none is [AfterImUp]. */
        fun parse(text: String?): MotivationMode = entries.firstOrNull { it.text == text } ?: AfterImUp
    }
}

/** A [MotivationMode] as its text form, so a stored session reads the same text `app.db` holds. */
internal object MotivationModeSerializer : KSerializer<MotivationMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.yawnandpawn.app.core.alarm.MotivationMode", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: MotivationMode,
    ) = encoder.encodeString(value.text)

    override fun deserialize(decoder: Decoder): MotivationMode = MotivationMode.parse(decoder.decodeString())
}
