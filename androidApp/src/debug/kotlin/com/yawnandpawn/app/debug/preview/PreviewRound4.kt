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
 * Design preview round 4, Story 5.2 "Bright wake screen" (a mock-up for the owner to choose from, not the feature). The
 * wake screens keep the Sunrise tokens in every option; what differs is when the screen is at full brightness:
 * - **A, full at once (the spec as written):** every wake screen at full brightness from the first ring until "Done".
 * - **B, sunrise ramp:** brightness rises from the phone's own level to full over the quiet time (20 s by default),
 *   with the volume ramp, then stays full on the checks.
 * - **C, ringing only:** full brightness while it rings (and on the snooze sheet); a check goes back to the phone's own
 *   level, so solving is easier on the eyes.
 * And the Settings "Wake" card with the "Bright wake screen" switch, on and off.
 *
 * A screenshot cannot show screen brightness, so a state "at the phone's own level" is simulated with a scrim of the
 * Sunrise `inverse-surface` token (documented as a simulation; nothing like it ships). On the phone the full-brightness
 * states really set the preview window to full brightness while they show (window-only, as Story 5.2 specifies).
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
     * token at [dim] (0.55 is a night-time level of about a third, 0.25 halfway through the ramp). Not read by TalkBack.
     */
    @Composable
    private fun PhoneLevel(
        dim: Float,
        content: @Composable () -> Unit,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            PpsTheme(wake = true) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clearAndSetSemantics {}
                            .background(PpsTheme.colors.inverseSurface.copy(alpha = dim)),
                )
            }
        }
    }

    @Composable
    private fun Ringing(is24Hour: Boolean) = RingingScreen(state = s.ringingFirst, is24Hour = is24Hour, onIntent = {})

    @Composable
    private fun MathCheck() = CheckScreen(state = s.checkMath, onIntent = {})

    /** The Settings main screen with the "Bright wake screen" switch [on] or off (its sample read when it shows). */
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
            wake("bright_a_ringing", "A · Full at once: ringing (full brightness)", primary = true) { is24 ->
                FullBrightness { Ringing(is24) }
            },
            wake("bright_a_check", "A · Full at once: Math check (full brightness)") { FullBrightness { MathCheck() } },
            wake("bright_b_ringing_start", "B · Sunrise ramp: ringing at 0 s (phone's level, simulated)", primary = true) { is24 ->
                PhoneLevel(dim = NIGHT_LEVEL) { Ringing(is24) }
            },
            wake("bright_b_ringing_half", "B · Sunrise ramp: ringing at 10 s (halfway, simulated)") { is24 ->
                PhoneLevel(dim = HALFWAY_LEVEL) { Ringing(is24) }
            },
            wake("bright_b_check", "B · Sunrise ramp: Math check (full after the ramp)") { FullBrightness { MathCheck() } },
            wake("bright_c_ringing", "C · Ringing only: ringing (full brightness)") { is24 -> FullBrightness { Ringing(is24) } },
            wake("bright_c_check", "C · Ringing only: Math check (phone's level, simulated)", primary = true) {
                PhoneLevel(dim = NIGHT_LEVEL) { MathCheck() }
            },
            settings("bright_settings_on", "Settings: Bright wake screen on (default)", on = true, primary = true),
            settings("bright_settings_off", "Settings: Bright wake screen off", on = false),
        )

    private const val GROUP = "Bright wake screen · Options"

    /** A night-time phone brightness of about a third, simulated. */
    private const val NIGHT_LEVEL = 0.55f

    /** Halfway through the ramp, simulated. */
    private const val HALFWAY_LEVEL = 0.25f
}

/** The activity hosting this context (the preview activity), or null. */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
