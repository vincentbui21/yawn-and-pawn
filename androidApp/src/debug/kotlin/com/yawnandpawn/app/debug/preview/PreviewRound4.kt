package com.yawnandpawn.app.debug.preview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.yawnandpawn.app.ui.settings.SettingsScreen
import com.yawnandpawn.app.ui.settings.SettingsUiState
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.RingingScreen

/**
 * Design preview round 4, Story 5.2 "Bright wake screen". The owner chose option C, "Ringing only" (2026-10-22; the
 * other options, A "full at once" and B "sunrise ramp", are dropped): the screen is at full brightness while it rings
 * (and on the snooze sheet over it), and a check goes back to the phone's own level. The wake screens keep the Sunrise
 * tokens. Also the Settings "Wake" card with the "Bright wake screen" switch, on and off.
 *
 * A screenshot cannot show screen brightness, so a state "at the phone's own level" is simulated with a scrim of the
 * Sunrise `inverse-surface` token (documented as a simulation; nothing like it ships). On the phone the full-brightness
 * state really sets the preview window to full brightness while it shows (window-only, as the app's `WakeBrightness`).
 */
object PreviewRound4 {
    private val s = PreviewSamples

    private fun wake(
        id: String,
        title: String,
        primary: Boolean = false,
        render: @Composable (is24Hour: Boolean) -> Unit,
    ) = PreviewItem(id, BRIGHT_WAKE_ROUND, GROUP, title, wake = true, primary = primary, render = render)

    /** [content] at full brightness: on the phone the preview window really goes to full brightness while it shows. */
    @Composable
    private fun FullBrightness(content: @Composable () -> Unit) {
        val activity = LocalContext.current.findActivity()
        DisposableEffect(activity) {
            val window = activity?.window
            val before = window?.attributes?.screenBrightness
            window?.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            onDispose {
                if (window != null && before != null) window.attributes = window.attributes.apply { screenBrightness = before }
            }
        }
        content()
    }

    /**
     * [content] at the phone's own brightness, simulated for the screenshot: a scrim of the Sunrise `inverse-surface`
     * token at [NIGHT_LEVEL] alpha (a dim night-time look, not a measured level). Not read by TalkBack.
     */
    @Composable
    private fun PhoneLevel(content: @Composable () -> Unit) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            PpsTheme(wake = true) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clearAndSetSemantics {}
                            .background(PpsTheme.colors.inverseSurface.copy(alpha = NIGHT_LEVEL)),
                )
            }
        }
    }

    /** The Settings main screen with the "Bright wake screen" switch [on] or off. */
    private fun settings(
        id: String,
        title: String,
        on: Boolean,
        primary: Boolean = false,
    ) = PreviewItem(id, BRIGHT_WAKE_ROUND, "Bright wake screen · Settings", title, primary = primary, tall = true) { is24 ->
        val state: SettingsUiState = PreviewProgressSamples.settings.copy(brightWakeScreen = on)
        AppShell(selected = AppTab.Settings, onSelect = {}) { SettingsScreen(state = state, is24Hour = is24, onIntent = {}) }
    }

    val items: List<PreviewItem> =
        listOf(
            wake("bright_c_ringing", "Ringing (full brightness)", primary = true) { is24 ->
                FullBrightness { RingingScreen(state = s.ringingFirst, is24Hour = is24, onIntent = {}) }
            },
            wake("bright_c_check", "Math check (phone's level, simulated)", primary = true) {
                PhoneLevel { CheckScreen(state = s.checkMath, onIntent = {}) }
            },
            settings("bright_settings_on", "Settings: Bright wake screen on (default)", on = true, primary = true),
            settings("bright_settings_off", "Settings: Bright wake screen off", on = false),
        )

    private const val GROUP = "Bright wake screen · Ringing only"

    /** The scrim's alpha for a dim night-time phone, simulated. */
    private const val NIGHT_LEVEL = 0.55f
}

/** The activity hosting this context (the preview activity), or null. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
