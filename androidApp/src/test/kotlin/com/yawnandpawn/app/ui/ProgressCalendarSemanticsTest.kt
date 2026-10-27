package com.yawnandpawn.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.ui.TalkBackRules.headings
import com.yawnandpawn.app.ui.progress.DaySelection
import com.yawnandpawn.app.ui.progress.Outcome
import com.yawnandpawn.app.ui.progress.PROGRESS_CALENDAR_TAG
import com.yawnandpawn.app.ui.progress.ProgressIntent
import com.yawnandpawn.app.ui.progress.ProgressScreen
import com.yawnandpawn.app.ui.progress.ProgressUiState
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 6.6: what TalkBack reads on the Progress calendar and how its days and month buttons behave. A day reads
 * "{weekday} {day}, {outcome}" with the fallback check and the session count when they apply, or "no alarm", and today
 * adds "today" (with no "no alarm" before its session); a day with a session is a button whose double-tap opens Day
 * detail once it is selected, as the chip's does; a day after today reads its date only and is never a button; the month
 * is a heading; the month buttons are 48 dp and disabled at the limits; at 200% on a 360 dp phone the seven days keep
 * 48 dp and no text is cut off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class ProgressCalendarSemanticsTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val intents = mutableListOf<ProgressIntent>()

    private fun show(
        state: ProgressUiState,
        block: () -> Unit,
    ) = withScreen(PpsThemeMode.Light, content = { ProgressScreen(state = state, onIntent = { intents += it }) }) {
        composeRule.waitForIdle()
        composeRule.onNode(hasTestTag(PROGRESS_CALENDAR_TAG)).performScrollTo()
        block()
    }

    /** The calendar day TalkBack reads as [label] (the ring has days with the same words). */
    private fun day(label: String) = composeRule.onNode(hasContentDescription(label) and inCalendar())

    private fun inCalendar() = hasAnyAncestor(hasTestTag(PROGRESS_CALENDAR_TAG))

    private fun dayNode(label: String): SemanticsNode = day(label).fetchSemanticsNode()

    private fun february(dayOfMonth: Int) = LocalDate(2027, 2, dayOfMonth)

    @Test
    fun `each day reads its weekday, date and outcome, with the fallback check and the session count`() =
        show(ProgressSamples.calendarEveryOutcome) {
            listOf(
                "Monday 1, On time",
                "Tuesday 2, Snoozed, 2 sessions",
                "Wednesday 3, Missed",
                "Thursday 4, Skipped",
                "Friday 5, Test",
                "Monday 8, On time, fallback check used",
                "Saturday 6, no alarm",
            ).forEach { label -> day(label).assertExists() }
        }

    @Test
    fun `a day with a session is a button, and once selected its double-tap opens day detail`() {
        show(ProgressSamples.calendarEveryOutcome) {
            val monday = dayNode("Monday 1, On time")
            assertEquals(Role.Button, monday.config.getOrNull(SemanticsProperties.Role))
            assertNull(monday.config[SemanticsActions.OnClick].label)
            // An empty day is no button.
            assertTrue(SemanticsActions.OnClick !in dayNode("Saturday 6, no alarm").config)

            day("Monday 1, On time").performClick()
            val selected = DaySelection(february(1), Outcome.OnTime, inCalendar = true)
            assertEquals(listOf<ProgressIntent>(ProgressIntent.DaySelected(selected)), intents)
        }
        intents.clear()
        show(ProgressSamples.calendarChip) {
            val selected = dayNode("Tuesday 2, Snoozed, 2 sessions")
            assertEquals("open day detail", selected.config[SemanticsActions.OnClick].label)
            assertNull(dayNode("Monday 1, On time").config[SemanticsActions.OnClick].label)

            day("Tuesday 2, Snoozed, 2 sessions").performClick()
            val chip = composeRule.onNode(hasText("Tue 2 · Snoozed") and hasClickAction())
            assertEquals("open day detail", chip.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            chip.performClick()
            assertEquals(listOf<ProgressIntent>(ProgressIntent.DayTapped(february(2)), ProgressIntent.DayTapped(february(2))), intents)
        }
    }

    @Test
    fun `a day after today shows no marker and is no button, even with a session`() =
        show(ProgressSamples.calendarFutureSession) {
            // Only the date: no outcome, and no "no alarm" either (an alarm may still ring that day).
            assertTrue(SemanticsActions.OnClick !in dayNode("Wednesday 10").config)
            day("Wednesday 10, Missed").assertDoesNotExist()
            day("Thursday 4").assertExists()
            day("Thursday 4, no alarm").assertDoesNotExist()
            assertTrue(SemanticsActions.OnClick in dayNode("Monday 1, On time").config)
            // Today says so, as on the ring, and claims no "no alarm" before its session either.
            day("Wednesday 3, today").assertExists()
            day("Wednesday 3, no alarm, today").assertDoesNotExist()
        }

    @Test
    fun `the month is a heading, and the month buttons are 48 dp and disabled at the limits`() {
        show(ProgressSamples.typical) {
            assertTrue("March 2027" in composeRule.headings(), "${composeRule.headings()}")
            monthButton("Previous month").assertIsEnabled()
            monthButton("Next month").assertIsNotEnabled()
            listOf("Previous month", "Next month").forEach { label ->
                val node = monthButton(label).fetchSemanticsNode()
                with(composeRule.density) {
                    assertTrue(node.size.width.toDp() >= 48.dp && node.size.height.toDp() >= 48.dp, "$label is ${node.size}")
                }
            }
        }
        show(ProgressSamples.calendarEmptyOldestMonth) {
            assertTrue("January 2027" in composeRule.headings(), "${composeRule.headings()}")
            monthButton("Previous month").assertIsNotEnabled()
            monthButton("Next month").assertIsEnabled().performClick()
            assertEquals(listOf<ProgressIntent>(ProgressIntent.NextMonth), intents)
        }
    }

    private fun monthButton(label: String) =
        composeRule.onNode(hasContentDescription(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi", fontScale = 2.0f)
    fun `at 200 percent on a 360 dp phone the days keep 48 dp, fit the screen, and no text is cut off`() =
        listOf(ProgressSamples.calendarEveryOutcome, ProgressSamples.calendarChip, ProgressSamples.typical).forEach { state ->
            show(state) {
                val screen = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
                val days =
                    composeRule
                        .onAllNodes(dayLabel() and inCalendar())
                        .fetchSemanticsNodes()
                assertTrue(days.size >= 28, "${days.size} days")
                with(composeRule.density) {
                    days.forEach { node ->
                        val label = TalkBackRules.label(node)
                        assertTrue(node.size.width.toDp() >= 47.5.dp && node.size.height.toDp() >= 48.dp, "$label is ${node.size}")
                        assertTrue(node.boundsInRoot.left >= screen.left && node.boundsInRoot.right <= screen.right, "$label off screen")
                    }
                }
                assertEquals(emptyList(), composeRule.cutOffTexts(), "cut off in ${state.calendar?.firstDay}")
            }
        }

    @Test
    fun `the cut-off check catches a clipped line, an ellipsis and a line wrapped past the last, but not a centred fit`() =
        withScreen(
            PpsThemeMode.Light,
            content = {
                Column {
                    Text(LONG_LINE, modifier = Modifier.width(40.dp), softWrap = false, overflow = TextOverflow.Clip)
                    Text(LONG_LINE, modifier = Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(LONG_LINE, modifier = Modifier.width(40.dp), maxLines = 1, overflow = TextOverflow.Clip)
                    Text(LONG_LINE, modifier = Modifier.widthIn(max = 40.dp), softWrap = false, textAlign = TextAlign.Center)
                    Text("12", modifier = Modifier.widthIn(min = 28.dp, max = 44.dp), maxLines = 1, textAlign = TextAlign.Center)
                    Text("day streak", modifier = Modifier.widthIn(max = 200.dp), textAlign = TextAlign.Center)
                }
            },
        ) {
            composeRule.waitForIdle()
            assertEquals(4, composeRule.cutOffTexts().size, "${composeRule.cutOffTexts()}")
        }

    /** A calendar day's TalkBack text: "{weekday} {day}", then ", …" up to today. */
    private fun dayLabel() =
        SemanticsMatcher("a calendar day") { node ->
            TalkBackRules.label(node).matches(Regex("""^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday) \d+(, .*)?$"""))
        }

    private companion object {
        const val LONG_LINE = "A line far too long for its room"
    }
}
