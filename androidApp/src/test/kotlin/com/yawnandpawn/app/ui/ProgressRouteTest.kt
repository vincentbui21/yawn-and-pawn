package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseRecordRepository
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionHistoryRow
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
import com.yawnandpawn.app.testing.FakePurchaseRecordRepository
import com.yawnandpawn.app.testing.FakeSessionHistoryRepository
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPurchaseRecord
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.testing.aSessionHistoryRow
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
import kotlin.time.Duration.Companion.days

/**
 * Story 6.5 in the real app (Koin, Navigation 3, the app shell, the real Story 6.2 snapshot over fake history and
 * purchase records, the clock pinned to Wednesday 2027-03-03 in UTC): a fresh install shows the empty ring and the
 * Purchase history link; the F9 month shows its tiles and money; Purchase history is pushed over Progress with no
 * capsule and Back returns to it; a session replaces it all with "Alarm in progress".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ProgressRouteTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val history = FakeSessionHistoryRepository()
    private val records = FakePurchaseRecordRepository()

    private fun start() {
        restartKoin(
            app,
            module {
                single<SessionHistoryRepository> { history }
                single<PurchaseRecordRepository> { records }
                single<Clock> { FakeClock() }
                single<TimeZoneProvider> { FakeTimeZoneProvider(TimeZone.UTC) }
                single<ActiveSessionStore> { FakeActiveSessionStore() }
            },
        )
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
    }

    private fun waitForText(
        text: String,
        shown: Boolean = true,
    ) = composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.onAllNodes(hasText(text) or hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() == shown
    }

    private fun tab(label: String) = composeRule.onNode(hasContentDescription(label) and hasClickAction())

    /** The app on the Progress tab, inside [block]. */
    private fun onProgress(
        ready: String,
        block: (ActivityScenario<MainActivity>) -> Unit,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        waitForText("Progress")
        tab("Progress").performClick()
        waitForText(ready)
        block(scenario)
    }

    /** A finished 06:00 UTC morning [back] days before the pinned today. */
    private fun morning(
        back: Int,
        outcome: SessionOutcome = SessionOutcome.OnTime,
        snoozes: Int = 0,
    ): SessionHistoryRow {
        val at = DEFAULT_FAKE_INSTANT - back.days
        return aSessionHistoryRow(sessionId = "session-$back").copy(
            scheduledAt = at,
            firstRingAt = at,
            endedAt = at,
            snoozeCount = snoozes,
            outcome = outcome,
        )
    }

    /** EXPERIENCE.md F9: 12 on-time mornings in a row, two snoozed mornings (4 snoozes) and a test alarm; $1.00 paid. */
    private fun seedTypicalMonth() =
        runBlocking {
            (0..11).forEach { history.upsert(morning(it)) }
            history.upsert(morning(12, SessionOutcome.Snoozed, snoozes = 1))
            history.upsert(morning(13, SessionOutcome.Test))
            history.upsert(morning(14, SessionOutcome.Snoozed, snoozes = 3))
            records.putRecord(aPurchaseRecord(price = Money(1_000_000, "USD")))
        }

    @Test
    fun `a fresh install shows the empty ring and the Purchase history link`() {
        start()
        onProgress("Your first morning shows up here.") {
            composeRule.onNode(hasText("Purchase history") and hasClickAction()).performScrollTo()
            composeRule.onAllNodes(hasContentDescription("on time", substring = true)).assertCountEquals(0)
        }
    }

    @Test
    fun `the F9 month shows its tiles, its streak and this month's money`() {
        seedTypicalMonth()
        start()
        onProgress("on time, 86%") {
            waitForText("to get up, 3 min")
            waitForText("snoozes, 4")
            composeRule.onNode(hasText("$1.00")).performScrollTo()
            composeRule.onNode(hasText("Keep it going.")).performScrollTo()
        }
    }

    @Test
    fun `Purchase history is pushed over Progress with no capsule, and Back returns to Progress`() {
        seedTypicalMonth()
        start()
        onProgress("on time, 86%") { scenario ->
            composeRule.onNode(hasText("Purchase history") and hasClickAction()).performScrollTo().performClick()
            waitForText("Problem with a charge?")
            composeRule.onAllNodes(hasContentDescription("Add alarm")).assertCountEquals(0)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitForText("on time, 86%")
            tab("Progress").assertIsSelected()
        }
    }

    @Test
    fun `a session that starts on Progress replaces it with Alarm in progress only`() {
        seedTypicalMonth()
        start()
        onProgress("on time, 86%") {
            val fired = SessionEvent.AlarmFired("session-live", aSessionConfig(), beforeFirstUnlock = false)
            runBlocking { GlobalContext.get().get<SessionEngine>().dispatch(fired) }
            GlobalContext.get().get<ApplicationScope>().awaitChildren()

            waitForText("Alarm in progress")
            waitForText("on time, 86%", shown = false)
            composeRule.onAllNodes(hasContentDescription("Progress") and hasClickAction()).assertCountEquals(0)
        }
    }
}
