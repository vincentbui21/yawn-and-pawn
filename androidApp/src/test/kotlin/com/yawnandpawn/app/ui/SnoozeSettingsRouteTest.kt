package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.testing.aSessionConfig
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Story 4.5 in the real app: the Settings tab shows the Snooze card with Play's cached price, the Base fee sub-screen
 * saves through `SetBaseFee` into the settings DataStore, Back returns to the card, and a session start replaces
 * Settings with "Alarm in progress" (the Epic 2 session lock, re-asserted).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SnoozeSettingsRouteTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private val log = FakeLogger()

    private fun start() {
        restartKoin(
            app,
            module {
                // Play's prices as cached now ("USD 1.00": the fake's locale-free form, so it is never the formatter's).
                single<PriceCatalog> { FakePriceCatalog(aPriceSnapshot(fetchedAt = get<Clock>().now())) }
                single<Logger> { log }
            },
        )
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
    }

    private fun waitForText(text: String) =
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun storedTier(): Int =
        runBlocking { (GlobalContext.get().get<GlobalSettingsRepository>().get() as Outcome.Success).value.baseFeeTier }

    /**
     * Waits until the settings DataStore holds [tier]. The save resumes on the main looper between its reads and writes,
     * which this test's paused looper only runs when idled, so each check idles it first.
     */
    private fun awaitStoredTier(tier: Int) {
        runCatching {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                storedTier() == tier
            }
        }
        assertEquals(tier, storedTier(), "stored by SetBaseFee; logged: ${log.events}")
    }

    @Test
    fun `the Snooze card shows the cached price, the Base fee sub-screen saves, and Back returns to the card`() {
        start()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            waitForText("USD 1.00")
            composeRule.onNode(hasText("Max snoozes per session", substring = true) and hasClickAction()).assertExists()
            composeRule.onNodeWithText("Default snooze length").assertDoesNotExist()

            composeRule.onNode(hasText("Base fee", substring = true) and hasClickAction()).performClick()
            composeRule.onNodeWithText("Snooze 1: USD 1.00 · 2: USD 2.00 · 3: USD 3.00").assertExists()
            composeRule.onNodeWithText("You can raise it anytime. Lowering it waits until after your next alarm.").assertExists()

            composeRule.onNodeWithContentDescription("Raise Base fee").performClick()
            waitForText("Snooze 1: USD 2.00")
            awaitStoredTier(2)

            composeRule.onNodeWithContentDescription("Back").performClick()
            waitForText("Snooze")
            composeRule.onNode(hasText("USD 2.00", substring = true) and hasClickAction()).assertExists()
        }
    }

    @Test
    fun `a session start replaces Settings with Alarm in progress`() {
        start()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            waitForText("USD 1.00")

            val fired = SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false)
            runBlocking { GlobalContext.get().get<SessionEngine>().dispatch(fired) }
            GlobalContext.get().get<ApplicationScope>().awaitChildren()

            waitForText("Alarm in progress")
            composeRule.onNodeWithText("Base fee").assertDoesNotExist()
            assertEquals(1, storedTier())
        }
    }
}
