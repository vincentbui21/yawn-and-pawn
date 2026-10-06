package com.yawnandpawn.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.theme.PpsTokens
import com.yawnandpawn.app.ui.wake.GraceHeader
import com.yawnandpawn.app.ui.wake.GraceState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.4 review fixes: the countdown's haptic ticks, its live regions and reduced motion. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class GraceHeaderTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /** Records every haptic the header asks for. */
    private class RecordingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    private val haptics = RecordingHaptics()
    private var grace by mutableStateOf<GraceState>(GraceState.Running(20, 20))

    /** Whether the header is in the composition, and the saved state it is restored from when it comes back. */
    private var shown by mutableStateOf(true)
    private var restoredFrom by mutableStateOf<Map<String, List<Any?>>?>(null)
    private var registry: SaveableStateRegistry? = null

    private fun header(block: () -> Unit) =
        withScreen(
            PpsThemeMode.Light,
            content = {
                if (shown) {
                    val saveable = remember(restoredFrom) { SaveableStateRegistry(restoredFrom) { true } }
                    registry = saveable
                    CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalSaveableStateRegistry provides saveable) {
                        GraceHeader(grace = grace)
                    }
                }
            },
            block = block,
        )

    /** Steps the countdown through [seconds], with [vibrate] as the session's quiet-time vibration. */
    private fun count(
        seconds: List<Int>,
        vibrate: Boolean = true,
    ) {
        seconds.forEach {
            grace = GraceState.Running(it, 20, vibrate)
            composeRule.waitForIdle()
        }
    }

    @After
    fun animationsBack() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @Test
    fun `the countdown ticks as it passes 15 and 10, not at the start or between`() =
        header {
            count(listOf(20, 15, 14, 10))

            assertEquals(listOf(HapticFeedbackType.SegmentTick, HapticFeedbackType.SegmentTick), haptics.performed)
        }

    @Test
    fun `without quiet-time vibration the countdown never ticks`() {
        grace = GraceState.Running(20, 20, vibrate = false)
        header {
            count(listOf(20, 15, 14, 10), vibrate = false)

            assertEquals(emptyList(), haptics.performed)
        }
    }

    @Test
    fun `a header recreated at a tick second does not tick again, and the next tick still comes`() =
        header {
            count(listOf(20, 15))
            assertEquals(1, haptics.performed.size, "the tick at 15")

            // The screen is recreated: the header's saved state is kept, the header leaves and comes back.
            val saved = checkNotNull(registry).performSave()
            shown = false
            composeRule.waitForIdle()
            restoredFrom = saved
            shown = true
            composeRule.waitForIdle()
            assertEquals(1, haptics.performed.size, "no second tick for 15 after the screen came back")

            count(listOf(14, 10))
            assertEquals(2, haptics.performed.size, "the tick at 10")
        }

    @Test
    fun `a header opened at a tick second does not tick for it`() {
        grace = GraceState.Running(15, 20)
        header {
            count(listOf(15, 14))

            assertEquals(emptyList(), haptics.performed)
        }
    }

    @Test
    fun `the running line is no live region, the expired line is a polite one`() {
        grace = GraceState.Running(14, 20)
        header {
            val running = composeRule.onAllNodes(hasText("Quiet for 14s. Finish before it rings again.")).fetchSemanticsNodes().single()
            assertNull(running.config.getOrNull(SemanticsProperties.LiveRegion), "not announced every second")

            grace = GraceState.Expired
            composeRule.waitForIdle()
            val expired = composeRule.onAllNodes(hasText("Time's up. Alarm's back on until you finish.")).fetchSemanticsNodes().single()
            assertEquals(LiveRegionMode.Polite, expired.config.getOrNull(SemanticsProperties.LiveRegion))
        }
    }

    @Test
    fun `with the default animator duration scale the ring is drawn in the accent colour`() {
        grace = GraceState.Running(14, 20)
        header { assertTrue(PpsTokens.Light.accent.toArgb() in ringPixels()) }
    }

    @Test
    fun `with the animator duration scale at 0 the plain number replaces the ring`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        grace = GraceState.Running(14, 20)
        header { assertTrue(PpsTokens.Light.accent.toArgb() !in ringPixels(), "no ring with reduced motion") }
    }

    private fun ringPixels(): Set<Int> {
        val map = composeRule.onNode(hasContentDescription("14 seconds left")).captureToImage().toPixelMap()
        return buildSet { for (x in 0 until map.width) for (y in 0 until map.height) add(map[x, y].toArgb()) }
    }
}
