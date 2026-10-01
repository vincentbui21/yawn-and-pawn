package com.yawnandpawn.app.ui.sound

import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.sound_name_bell
import com.yawnandpawn.app.ui.resources.sound_name_buzzer
import com.yawnandpawn.app.ui.resources.sound_name_chimes
import com.yawnandpawn.app.ui.resources.sound_name_classic
import com.yawnandpawn.app.ui.resources.sound_name_default
import com.yawnandpawn.app.ui.resources.sound_name_digital
import com.yawnandpawn.app.ui.resources.sound_name_marimba
import com.yawnandpawn.app.ui.resources.sound_name_morning
import com.yawnandpawn.app.ui.resources.sound_name_pulse
import com.yawnandpawn.app.ui.resources.sound_name_rising
import com.yawnandpawn.app.ui.resources.sound_name_siren
import com.yawnandpawn.app.ui.resources.sound_name_sonar
import org.jetbrains.compose.resources.StringResource

/** The display name of each `SoundCatalog` sound by id; a test keeps it and the catalog the same. */
val BUILT_IN_SOUND_NAMES: Map<String, StringResource> =
    mapOf(
        "default" to Res.string.sound_name_default,
        "classic" to Res.string.sound_name_classic,
        "digital" to Res.string.sound_name_digital,
        "rising" to Res.string.sound_name_rising,
        "chimes" to Res.string.sound_name_chimes,
        "siren" to Res.string.sound_name_siren,
        "pulse" to Res.string.sound_name_pulse,
        "buzzer" to Res.string.sound_name_buzzer,
        "marimba" to Res.string.sound_name_marimba,
        "bell" to Res.string.sound_name_bell,
        "morning" to Res.string.sound_name_morning,
        "sonar" to Res.string.sound_name_sonar,
    )
