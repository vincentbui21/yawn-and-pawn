package com.yawnandpawn.app.android.qr

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.qr.QrWake.Companion.LINK
import com.yawnandpawn.app.android.qr.QrWake.Companion.START_MILLIS
import com.yawnandpawn.app.android.wake.awaitScreen
import com.yawnandpawn.app.core.checks.qr.CodeFormat
import com.yawnandpawn.app.core.session.CameraFallbackPolicy
import com.yawnandpawn.app.core.session.FallbackReason
import com.yawnandpawn.app.ui.qr.ScanResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.11 (FR-PWK-11, also the lost-code path): with a working camera, 4 different wrong codes bring no link; the 5th
 * failed attempt brings "Can't do this check?", asked for with reason `FailedAttempts`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class QrFailedAttemptsTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Test
    fun `4 different codes bring no link, the 5th failed attempt brings it with reason FailedAttempts`() {
        val wake = QrWake(composeRule)
        wake.ring()
        wake.launch().use {
            wake.imUp()
            (1..CameraFallbackPolicy.FAILED_ATTEMPTS).forEach { attempt ->
                wake.heartbeatAt(START_MILLIS, attempt * 1_000L)
                wake.scanner.frames(3, ScanResult(CodeFormat.QrCode, "wrong code $attempt"))
                composeRule.awaitScreen(wake.app, "attempt $attempt counted") {
                    wake.run().failedAttempts == attempt
                }
                composeRule
                    .onNodeWithText("That's a different code. Scan your registered one.")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
                if (attempt < CameraFallbackPolicy.FAILED_ATTEMPTS) composeRule.onNodeWithText(LINK).assertDoesNotExist()
            }
            composeRule.onNodeWithText(LINK).assertExists()
            assertTrue(FallbackReason.CameraUnavailable !in wake.fallback.reasons, "the camera worked throughout")

            composeRule.onNodeWithText(LINK).performClick()
            composeRule.onNode(hasText("Math") and hasClickAction()).performClick()
            composeRule.awaitScreen(wake.app, "the fallback replaced the check") {
                wake.run().fallbackUsed
            }
            assertEquals(FallbackReason.FailedAttempts, wake.fallback.reasons.last())
        }
    }
}
