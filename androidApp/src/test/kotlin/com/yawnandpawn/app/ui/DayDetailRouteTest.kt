package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentEvent
import com.yawnandpawn.app.core.config.CommitmentEventRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.history.SessionOutcome
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.DEFAULT_FAKE_INSTANT
import com.yawnandpawn.app.testing.FakeActiveSessionStore
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeCommitmentEventRepository
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.aSessionHistoryRow
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.progress.PROGRESS_CALENDAR_TAG
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Story 6.7 in the real app (Koin, Navigation 3, the app shell, the clock pinned to Wednesday 2027-03-03 in UTC, a
 * snoozed 06:00 morning today paid in EUR and USD with a payment Google refunded, a merged alarm and an alarm turned off
 * today): the calendar chip opens Day detail with no capsule; Back returns to Progress with the chip and the month; a
 * double tap opens one; a second tap on the selected ring dot opens it too; it survives a recreate; a session replaces it
 * with "Alarm in progress".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DayDetailRouteTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val history = FakeSessionHistoryRepository()
    private val records = FakePurchaseRecordRepository()
    private val changes = FakeCommitmentEventRepository()
    private val eur = Money(990_000, "EUR")
    private val usd = Money(1_000_000, "USD")

    private fun start() {
        runBlocking {
            history.upsert(
                aSessionHistoryRow(sessionId = SESSION).copy(
                    endedAt = DEFAULT_FAKE_INSTANT + 18.minutes,
                    snoozeCount = 2,
                    checkTypes = listOf("Math"),
                    timeToCompleteMs = 18.minutes.inWholeMilliseconds,
                    outcome = SessionOutcome.Snoozed,
                ),
            )
            records.putRecord(paid("a", 1, eur, snooze = 1))
            records.putRecord(paid("b", 11, usd, snooze = 2))
            records.putRecord(paid("c", 12, eur, snooze = 3).copy(status = RecordStatus.Stranded))
            history.recordMerge(SessionMergeRow(SESSION, "alarm-2", DEFAULT_FAKE_INSTANT + 5.minutes, DEFAULT_FAKE_INSTANT + 5.minutes))
            changes.insert(CommitmentEvent("e", "alarm-3", DEFAULT_FAKE_INSTANT + 1.hours, CommitmentAction.Disabled, DEFAULT_FAKE_INSTANT))
        }
        restartKoin(
            app,
            module {
                single<SessionHistoryRepository> { history }
                single<PurchaseRecordRepository> { records }
                single<CommitmentEventRepository> { changes }
                single<Clock> { FakeClock() }
                single<TimeZoneProvider> { FakeTimeZoneProvider(TimeZone.UTC) }
                single<ActiveSessionStore> { FakeActiveSessionStore() }
            },
        )
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
    }

    private fun paid(
        token: String,
        minute: Int,
        price: Money,
        snooze: Int,
    ) = aPurchaseRecord(
        token = token,
        status = RecordStatus.Consumed,
        sessionId = SESSION,
        snoozeNumber = snooze,
        price = price,
        purchasedAt = DEFAULT_FAKE_INSTANT + minute.minutes,
    )

    private fun shown(text: String) =
        composeRule.onAllNodes(hasText(text, substring = true) or hasContentDescription(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(
        text: String,
        visible: Boolean = true,
    ) = composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
        shadowOf(Looper.getMainLooper()).idle()
        shown(text) == visible
    }

    private fun tab(label: String) = composeRule.onNode(hasContentDescription(label) and hasClickAction())

    private fun inCalendar() = hasAnyAncestor(hasTestTag(PROGRESS_CALENDAR_TAG))

    private fun back(scenario: ActivityScenario<MainActivity>) = scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun onProgress(block: (ActivityScenario<MainActivity>) -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("Progress")
            tab("Progress").performClick()
            waitForText("snoozes, 2")
            block(scenario)
        }

    /** Today's calendar day, then its chip ([twice]: a quick double tap on it). */
    private fun openFromCalendarChip(twice: Boolean = false) {
        composeRule.onNode(hasTestTag(PROGRESS_CALENDAR_TAG)).performScrollTo()
        composeRule
            .onNode(hasContentDescription("Wednesday 3, Snoozed", substring = true) and inCalendar() and !hasText(CHIP))
            .performClick()
        waitForText(CHIP)
        val chip = composeRule.onNode(hasText(CHIP) and hasClickAction() and inCalendar()).performScrollTo()
        chip.performTouchInput {
            click()
            if (twice) {
                advanceEventTime(DOUBLE_TAP_GAP_MILLIS)
                click()
            }
        }
    }

    @Test
    fun `the calendar chip opens Day detail with the day's facts and no capsule, and Back returns to Progress`() {
        start()
        onProgress { scenario ->
            openFromCalendarChip()
            waitForText(TITLE)
            composeRule.onAllNodes(hasContentDescription("Add alarm")).assertCountEquals(0)
            waitForText("Snoozed · ${formatMoney(eur)}")
            waitForText("Snoozed · ${formatMoney(usd)}")
            waitForText("alarm merged into this session")
            waitForText("Math solved")
            composeRule.onNode(hasText("Not used, refunded automatically by Google", substring = true)).performScrollTo()
            composeRule.onNode(hasText("alarm was turned off. Logged.", substring = true)).performScrollTo()

            back(scenario)
            waitForText(TITLE, visible = false)
            waitForText(CHIP)
            waitForText("March 2027")
        }
    }

    @Test
    fun `a double tap on the chip opens one Day detail, and it survives a recreate`() {
        start()
        onProgress { scenario ->
            openFromCalendarChip(twice = true)
            waitForText(TITLE)

            scenario.recreate()
            waitForText(TITLE)
            waitForText("Math solved")

            // One Back returns to Progress: the second tap pushed nothing.
            back(scenario)
            waitForText(TITLE, visible = false)
            waitForText(CHIP)
            tab("Progress").assertIsSelected()
        }
    }

    @Test
    fun `Day detail closes itself once its day has nothing logged`() {
        start()
        onProgress {
            openFromCalendarChip()
            waitForText(TITLE)

            // The day's turned-off note goes too (its read fails), so nothing is left for the day.
            changes.failure = DomainError.StorageFailure("gone")
            runBlocking { history.deleteAllHistory() }
            waitForText(TITLE, visible = false)
            tab("Progress").assertIsSelected()
        }
    }

    @Test
    fun `a second tap on the selected ring dot opens Day detail, and a session replaces it with Alarm in progress`() {
        start()
        onProgress {
            val dot = composeRule.onNode(hasContentDescription("Wednesday 3, Snoozed", substring = true) and !inCalendar() and !hasText(CHIP))
            dot.performTouchInput { click() }
            waitForText(CHIP)
            dot.performTouchInput { click() }
            waitForText(TITLE)

            val fired = SessionEvent.AlarmFired("session-live", aSessionConfig(), beforeFirstUnlock = false)
            runBlocking { GlobalContext.get().get<SessionEngine>().dispatch(fired) }
            GlobalContext.get().get<ApplicationScope>().awaitChildren()
            waitForText("Alarm in progress")
            waitForText(TITLE, visible = false)
        }
    }

    private companion object {
        const val SESSION = "session-today"
        const val CHIP = "Wed 3 · Snoozed"
        const val TITLE = "Wed, Mar 3"
        const val DOUBLE_TAP_GAP_MILLIS = 60L
    }
}
