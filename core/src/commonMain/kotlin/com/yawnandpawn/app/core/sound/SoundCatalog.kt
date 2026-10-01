package com.yawnandpawn.app.core.sound

import com.yawnandpawn.app.core.alarm.Alarm

/**
 * One bundled alarm sound: [id] in its [SoundRef.BuiltIn], [resourceName] the `res/raw` file name without its
 * extension (`alarm_<id>`). Its display name is a UI string resource (`sound_name_<id>`).
 */
data class BuiltInSound(
    val id: String,
    val resourceName: String,
) {
    val ref: SoundRef.BuiltIn
        get() = SoundRef.BuiltIn(id)
}

/**
 * The built-in sound library (FR-SND-1, Story 1.17): every bundled alarm sound, in the order the Sound picker lists
 * them, with exactly one [default] (the never-silent fallback, `Alarm.DEFAULT_SOUND_REF`). Each sound is generated in
 * the repository, licensed CC0 (docs/sounds/LICENSES.md) and passes the loudness gate (`checkSoundLoudness`).
 */
object SoundCatalog {
    const val DEFAULT_ID = "default"

    val sounds: List<BuiltInSound> =
        listOf(
            DEFAULT_ID,
            "classic",
            "digital",
            "rising",
            "chimes",
            "siren",
            "pulse",
            "buzzer",
            "marimba",
            "bell",
            "morning",
            "sonar",
        ).map { BuiltInSound(id = it, resourceName = "alarm_$it") }

    /** The default sound: "Sunrise", `res/raw/alarm_default`. */
    val default: BuiltInSound = sounds.single { it.id == DEFAULT_ID }

    /** The bundled sound with [id], or null when this version has none. */
    fun find(id: String): BuiltInSound? = sounds.firstOrNull { it.id == id }

    /** The bundled sound [ref] names, or null when [ref] is not a known built-in. */
    fun find(ref: SoundRef?): BuiltInSound? = (ref as? SoundRef.BuiltIn)?.let { find(it.id) }

    init {
        check(default.ref.encode() == Alarm.DEFAULT_SOUND_REF) { "the default sound must be Alarm.DEFAULT_SOUND_REF" }
    }
}
