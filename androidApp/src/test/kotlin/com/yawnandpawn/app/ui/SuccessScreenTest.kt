package com.yawnandpawn.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.recording.MotivationPlayback
import com.yawnandpawn.app.core.stats.SuccessStreak
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.TalkBackRules.talkBackOrder
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.SuccessUiState
import com.yawnandpawn.app.ui.wake.WakeIntent
import com.yawnandpawn.app.ui.wake.successUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Success states, built the way `WakeActivity` builds them (Stories 3.3 and 6.4). */
object SuccessSamples {
    val onTime: SuccessUiState = successUiState(aSession())
    val afterSnooze: SuccessUiState = successUiState(aSession().copy(snoozesGranted = 2))
    val test: SuccessUiState = successUiState(aSession(config = aSessionConfig(testMode = true)))

    /** Story 6.4: the streak grew from 11 to 12, first showing: the count-up and the confetti. */
    val streakGrew: SuccessUiState = successUiState(aSession(), streak = SuccessStreak.Known(before = 11, after = 12))

    /** Story 6.4: a second on-time session on a day already good: the number, still. */
    val streakSameDay: SuccessUiState = successUiState(aSession(), streak = SuccessStreak.Known(before = 12, after = 12))

    /** Story 6.4: the first on-time morning ever: "1", "day in a row". */
    val streakFirst: SuccessUiState = successUiState(aSession(), streak = SuccessStreak.Known(before = 0, after = 1))

    /** Story 6.4: no snapshot: "You're up. That's what counts." with no number. */
    val snapshotMissing: SuccessUiState = successUiState(aSession(), streak = SuccessStreak.Unknown)

    /** Story 7.3: a zero-snooze streak with its message playing, a third in. */
    val streakWithMessage: SuccessUiState =
        successUiState(
            aSession(),
            streak = SuccessStreak.Known(before = 12, after = 12),
            motivation = MotivationPlayback(aSession().sessionId, playing = true, positionMs = 3_000, durationMs = 9_000),
        )

    /** Story 7.3: after a snooze, the message played to its end (the card stays, full, with "Play message"). */
    val afterSnoozeWithMessage: SuccessUiState =
        successUiState(
            aSession().copy(snoozesGranted = 2),
            motivation = MotivationPlayback(aSession().sessionId, playing = false, positionMs = 9_000, durationMs = 9_000),
        )

    /** Each sample with its headline (no streak number among them). */
    val all: List<Pair<SuccessUiState, String>> =
        listOf(
            onTime to "Up on time.",
            afterSnooze to "You're up. That's what counts.",
            test to "Test finished. Your alarm works.",
            snapshotMissing to "You're up. That's what counts.",
        )
}

/**
 * Story 3.3 layout, accessibility and behaviour of the basic Success screen on a phone-sized window: the 72 dp "Done"
 * in the thumb zone at 100% and 200%, TalkBack order, no paid line, no motion, and one success haptic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-mdpi")
class SuccessScreenTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val haptics = RecordingHaptics()
    private val intents = mutableListOf<WakeIntent>()

    private fun success(
        state: SuccessUiState,
        block: () -> Unit,
    ) = withScreen(
        PpsThemeMode.Light,
        content = {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                SuccessScreen(state = state, onIntent = { intents += it }, basic = true)
            }
        },
        block = block,
    )

    private fun done() = composeRule.onNodeWithText("Done")

    private fun assertDoneInThumbZone() {
        val root = composeRule.onRoot().getBoundsInRoot()
        val done = done().getBoundsInRoot()
        assertTrue(done.bottom - done.top >= 72.dp, "Done is ${done.bottom - done.top}")
        assertTrue(done.right - done.left >= root.right - root.left - 24.dp * 2, "Done is full width: $done in $root")
        assertTrue(done.top >= root.top + (root.bottom - root.top) * 0.6f, "Done (top ${done.top}) is in the bottom 40% of ${root.bottom}")
        assertTrue(done.bottom <= root.bottom, "Done is on screen without scrolling")
    }

    @Test
    fun `each variant shows its headline and a 72 dp Done in the thumb zone`() =
        SuccessSamples.all.forEach { (state, headline) ->
            success(state) {
                composeRule.onNodeWithText(headline).assertExists()
                assertDoneInThumbZone()
            }
        }

    @Test
    @Config(fontScale = 2.0f)
    fun `at 200 percent on a 360 by 640 dp phone Done stays on screen in the thumb zone`() =
        SuccessSamples.all.forEach { (state, headline) ->
            success(state) {
                composeRule.onNodeWithText(headline).assertExists()
                assertDoneInThumbZone()
            }
        }

    @Test
    fun `TalkBack reads the headline first, as a heading, then Done`() =
        SuccessSamples.all.forEach { (state, headline) ->
            success(state) {
                val readable =
                    SemanticsMatcher("read by TalkBack") {
                        SemanticsProperties.Text in it.config || SemanticsProperties.ContentDescription in it.config
                    }
                val order =
                    composeRule
                        .onAllNodes(readable)
                        .fetchSemanticsNodes()
                        .map { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }
                assertEquals(listOf(headline, "Done"), order, "reading order")
                composeRule.onNodeWithText(headline).assert(isHeading())
            }
        }

    @Test
    fun `after a snooze there is no paid line before Epic 4`() =
        success(SuccessSamples.afterSnooze) {
            composeRule.onNodeWithText("paid this morning", substring = true).assertDoesNotExist()
        }

    @Test
    fun `nothing moves, the screen at 32 ms and at 1 s is the same picture`() =
        success(SuccessSamples.onTime) {
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(FRAME)
            val start = composeRule.onRoot().captureToImage().toPixelMap()
            composeRule.mainClock.advanceTimeBy(ONE_SECOND)
            val later = composeRule.onRoot().captureToImage().toPixelMap()

            assertEquals(start.width * start.height, later.width * later.height)
            val changed = (0 until start.height).sumOf { y -> (0 until start.width).count { x -> start[x, y] != later[x, y] } }
            assertEquals(0, changed, "pixels changed between the two moments")
        }

    @Test
    fun `the success haptic plays once for each variant`() =
        SuccessSamples.all.forEach { (state, _) ->
            haptics.played.clear()
            success(state) {
                composeRule.waitForIdle()
                assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played, "for $state")
            }
        }

    @Test
    fun `leaving and re-entering composition asks the caller again, which plays the haptic only once`() {
        var shown by mutableStateOf(true)
        var claims = 0
        withScreen(
            PpsThemeMode.Light,
            content = {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    if (shown) SuccessScreen(state = SuccessSamples.onTime, onIntent = {}, basic = true, claimHaptic = { ++claims == 1 })
                }
            },
        ) {
            composeRule.waitForIdle()
            shown = false
            composeRule.waitForIdle()
            shown = true
            composeRule.waitForIdle()

            assertEquals(2, claims, "asked on each entry")
            assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played, "played once")
        }
    }

    // --- Story 6.4: the streak ---

    @Test
    fun `TalkBack reads the headline first, then the streak, then Done`() =
        listOf(SuccessSamples.streakGrew, SuccessSamples.streakSameDay).forEach { state ->
            success(state) {
                composeRule.waitForIdle()
                assertEquals(listOf("Up on time.", "12", "days in a row", "Done"), composeRule.talkBackOrder(), "$state")
            }
        }

    @Test
    fun `the first on-time morning reads 1 day in a row`() =
        success(SuccessSamples.streakFirst) {
            composeRule.waitForIdle()
            assertEquals(listOf("Up on time.", "1", "day in a row", "Done"), composeRule.talkBackOrder())
        }

    @Test
    fun `a streak that did not grow is the same picture at 32 ms and at 1 s, with the haptic`() =
        success(SuccessSamples.streakSameDay) {
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(FRAME)
            val start = composeRule.onRoot().captureToImage().toPixelMap()
            composeRule.mainClock.advanceTimeBy(ONE_SECOND)
            val later = composeRule.onRoot().captureToImage().toPixelMap()

            val changed = (0 until start.height).sumOf { y -> (0 until start.width).count { x -> start[x, y] != later[x, y] } }
            assertEquals(0, changed, "pixels changed between the two moments")
            assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
        }

    @Test
    fun `a streak that grew plays one haptic for the whole celebration`() =
        success(SuccessSamples.streakGrew) {
            composeRule.mainClock.autoAdvance = false
            composeRule.mainClock.advanceTimeBy(FRAME)
            val start = composeRule.onRoot().captureToImage().toPixelMap()
            composeRule.mainClock.advanceTimeBy(CELEBRATION_END)
            val later = composeRule.onRoot().captureToImage().toPixelMap()

            val changed = (0 until start.height).sumOf { y -> (0 until start.width).count { x -> start[x, y] != later[x, y] } }
            assertTrue(changed > 0, "the count-up and the confetti move")
            assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)

            // All of it ends within 3 s: nothing moves between 2 s and 3 s.
            composeRule.mainClock.advanceTimeBy(ONE_SECOND)
            val atThree = composeRule.onRoot().captureToImage().toPixelMap()
            val still = (0 until later.height).sumOf { y -> (0 until later.width).count { x -> later[x, y] != atThree[x, y] } }
            assertEquals(0, still, "pixels changed between 2 s and 3 s")
            assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played, "still one haptic")
        }

    @Test
    fun `with the animator duration scale at 0 the final number shows at once and the haptic still plays`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            success(SuccessSamples.streakGrew) {
                composeRule.mainClock.autoAdvance = false
                composeRule.mainClock.advanceTimeBy(FRAME)
                val start = composeRule.onRoot().captureToImage().toPixelMap()
                composeRule.mainClock.advanceTimeBy(CELEBRATION_END)
                val later = composeRule.onRoot().captureToImage().toPixelMap()

                val changed = (0 until start.height).sumOf { y -> (0 until start.width).count { x -> start[x, y] != later[x, y] } }
                assertEquals(0, changed, "no count-up, bounce or confetti with reduced motion")
                composeRule.onNodeWithContentDescription("12").assertExists()
                assertEquals(listOf(HapticFeedbackType.Confirm), haptics.played)
            }
        } finally {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    }

    @Test
    fun `with no snapshot there is no number and no paid line`() =
        success(SuccessSamples.snapshotMissing) {
            composeRule.onNodeWithText("You're up. That's what counts.").assertExists()
            composeRule.onNodeWithText("in a row", substring = true).assertDoesNotExist()
            composeRule.onNodeWithText("paid this morning", substring = true).assertDoesNotExist()
            composeRule.onNodeWithText("Up on time.").assertDoesNotExist()
        }

    // --- Story 7.3: the message card ---

    @Test
    fun `the message card reads Pause message, the progress, then Replay message, between the streak and Done`() =
        success(SuccessSamples.streakWithMessage) {
            composeRule.waitForIdle()
            assertEquals(
                listOf("Up on time.", "12", "days in a row", "Pause message", "Replay message", "Done"),
                composeRule.talkBackOrder(),
            )
            val bar = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNode()
            assertEquals(1f / 3, bar.config[SemanticsProperties.ProgressBarRangeInfo].current, 0.001f)
            val card = composeRule.onNodeWithContentDescription("Replay message").getBoundsInRoot()
            assertTrue(card.top > composeRule.onNodeWithText("Up on time.").getBoundsInRoot().bottom, "under the headline card")
            assertTrue(card.bottom < done().getBoundsInRoot().top, "above Done")
        }

    @Test
    fun `played to its end the card offers Play message, and each button sends its intent`() =
        success(SuccessSamples.afterSnoozeWithMessage) {
            composeRule.onNodeWithContentDescription("Pause message").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Play message").performClick()
            composeRule.onNodeWithContentDescription("Replay message").performClick()
            assertEquals(listOf(WakeIntent.MotivationPlayPauseClicked, WakeIntent.MotivationReplayClicked), intents)
        }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2.0f)
    fun `with the card every target is at least 48 dp and Done stays in the thumb zone, at 200 percent on a small phone`() =
        listOf(SuccessSamples.streakWithMessage, SuccessSamples.afterSnoozeWithMessage).forEach { state ->
            success(state) {
                val density = composeRule.density
                val targets = composeRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
                assertEquals(3, targets.size, "play / pause, replay and Done")
                targets.forEach { node ->
                    val size = with(density) { node.size.width.toDp() to node.size.height.toDp() }
                    assertTrue(size.first >= 48.dp && size.second >= 48.dp, "$size: ${node.config}")
                }
                assertDoneInThumbZone()
            }
        }

    @Test
    fun `Done sends DoneClicked`() =
        success(SuccessSamples.onTime) {
            done().performClick()
            assertEquals(listOf<WakeIntent>(WakeIntent.DoneClicked), intents)
        }

    private companion object {
        const val FRAME = 32L
        const val ONE_SECOND = 1_000L

        /** After the 1.5 s celebration. */
        const val CELEBRATION_END = 2_000L
    }
}

/** A [HapticFeedback] that records what was played. */
private class RecordingHaptics : HapticFeedback {
    val played = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        played += hapticFeedbackType
    }
}
