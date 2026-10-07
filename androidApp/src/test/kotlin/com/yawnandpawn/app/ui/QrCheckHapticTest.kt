package com.yawnandpawn.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeUnavailableReason
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** Story 3.10 review: the wake QR check's error haptic, one per failed attempt with a different code. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class QrCheckHapticTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** Records every haptic the screen asks for. */
    private class RecordingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    private val haptics = RecordingHaptics()
    private val snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded)
    private var content by mutableStateOf(CheckContent.QrBarcode())

    private fun screen(block: () -> Unit) =
        withScreen(
            PpsThemeMode.Light,
            content = {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    CheckScreen(state = CheckUiState(grace = GraceState.Expired, content = content, snooze = snooze), onIntent = {})
                }
            },
            block = block,
        )

    private fun show(next: CheckContent.QrBarcode) {
        content = next
        composeRule.waitForIdle()
    }

    @Test
    fun `each failed attempt with a different code gives one error haptic`() =
        screen {
            composeRule.waitForIdle()
            show(CheckContent.QrBarcode(wrongCode = true, wrongAttempts = 1))
            show(CheckContent.QrBarcode(wrongCode = true, wrongAttempts = 2))

            assertEquals(listOf(HapticFeedbackType.Reject, HapticFeedbackType.Reject), haptics.performed)
        }

    @Test
    fun `scanning without a wrong code gives no haptic`() =
        screen {
            composeRule.waitForIdle()
            show(CheckContent.QrBarcode(wrongCode = false, wrongAttempts = 0))
            show(CheckContent.QrBarcode(torchOn = true))

            assertEquals(emptyList(), haptics.performed)
        }
}
