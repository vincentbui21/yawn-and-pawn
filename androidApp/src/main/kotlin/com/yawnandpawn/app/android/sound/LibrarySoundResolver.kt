package com.yawnandpawn.app.android.sound

import com.yawnandpawn.app.R
import com.yawnandpawn.app.android.wake.AlarmSound
import com.yawnandpawn.app.android.wake.SoundResolver
import com.yawnandpawn.app.core.sound.SoundCatalog
import com.yawnandpawn.app.core.sound.SoundRef

/**
 * The bundled alarm sounds by `SoundCatalog` resource name (`res/raw/alarm_<id>.ogg`). A test keeps it, the catalog and
 * the files in `res/raw` the same.
 */
val BUILT_IN_SOUND_FILES: Map<String, Int> =
    mapOf(
        "alarm_default" to R.raw.alarm_default,
        "alarm_classic" to R.raw.alarm_classic,
        "alarm_digital" to R.raw.alarm_digital,
        "alarm_rising" to R.raw.alarm_rising,
        "alarm_chimes" to R.raw.alarm_chimes,
        "alarm_siren" to R.raw.alarm_siren,
        "alarm_pulse" to R.raw.alarm_pulse,
        "alarm_buzzer" to R.raw.alarm_buzzer,
        "alarm_marimba" to R.raw.alarm_marimba,
        "alarm_bell" to R.raw.alarm_bell,
        "alarm_morning" to R.raw.alarm_morning,
        "alarm_sonar" to R.raw.alarm_sonar,
    )

/**
 * The sound library's [SoundResolver] (Story 1.17): `builtin:default` is [AlarmSound.Default], another catalog sound its
 * raw resource, a phone ringtone its content URI. Anything else (an unknown reference, a built-in this version does not
 * have) resolves to null, and the player plays the default.
 */
class LibrarySoundResolver(
    private val files: Map<String, Int> = BUILT_IN_SOUND_FILES,
) : SoundResolver {
    override fun resolve(soundRef: String): AlarmSound? =
        when (val ref = SoundRef.parse(soundRef)) {
            is SoundRef.BuiltIn -> {
                SoundCatalog.find(ref)?.let { sound ->
                    if (sound == SoundCatalog.default) AlarmSound.Default else files[sound.resourceName]?.let(AlarmSound::BuiltIn)
                }
            }

            is SoundRef.System -> {
                AlarmSound.File(ref.uri)
            }

            null -> {
                null
            }
        }
}
