package com.yawnandpawn.app.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.BuildConfig
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeActiveSessionStore
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
import java.time.Duration
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 5.8 in the real app (Koin, Navigation 3, the app shell): each You link starts its intent on the user's tap, a
 * phone with no browser says so for 4 s, "Email support" opens an email to the support address (or the note when no
 * email app can), "How payments & refunds work" is pushed over You and pushes its links over itself, Back walks back,
 * and a session replaces it all with "Alarm in progress".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class YouRouteTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** The cap in the tier wording: a fresh install has no cached Play price yet (review fix 2). */
    private val capSentence =
        "You can snooze up to your max snoozes per session. A single snooze never costs more than the $50.00 price tier."

    private fun waitForText(text: String) =
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForGone(text: String) =
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
        }

    private fun tab(label: String) = composeRule.onNode(hasContentDescription(label) and hasClickAction())

    private fun row(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    private fun tap(label: String) = row(label).performScrollTo().performClick()

    /** The app on the You tab, inside [block]. */
    private fun onYou(block: (ActivityScenario<MainActivity>) -> Unit) =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("No alarms yet.")
            tab("You").performClick()
            waitForText("Privacy and your data")
            shadowOf(app).clearNextStartedActivities()
            block(scenario)
        }

    private fun ActivityScenario<MainActivity>.pressBack() = onActivity { it.onBackPressedDispatcher.onBackPressed() }

    private fun nextStarted(): Intent = assertNotNull(shadowOf(app).nextStartedActivity, "an activity was started")

    @Test
    fun `You shows Money, Privacy and your data and Help with the built rows only`() =
        onYou {
            listOf("Purchase history", "How payments & refunds work", "Privacy policy", "Support", "Email support", "Terms").forEach {
                row(it).performScrollTo()
            }
            composeRule.onAllNodes(hasText("Delete all data")).assertCountEquals(0)
            composeRule.onAllNodes(hasText("About")).assertCountEquals(0)
        }

    @Test
    fun `Privacy policy, Terms and Support open their pages with ACTION_VIEW, with no snackbar`() =
        onYou {
            listOf(
                "Privacy policy" to BuildConfig.PRIVACY_URL,
                "Terms" to BuildConfig.TERMS_URL,
                "Support" to BuildConfig.SUPPORT_URL,
            ).forEach { (label, url) ->
                tap(label)
                composeRule.waitForIdle()
                val started = nextStarted()
                assertEquals(Intent.ACTION_VIEW, started.action, label)
                assertEquals(url, started.dataString, label)
                assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE), label)
                assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, label)
            }
            composeRule.onAllNodes(hasText("No browser found.")).assertCountEquals(0)
        }

    @Test
    fun `Email support opens an email to the support address with no subject or body`() =
        onYou {
            tap("Email support")
            composeRule.waitForIdle()

            val email = nextStarted()
            assertEquals(Intent.ACTION_SENDTO, email.action)
            assertEquals("mailto", email.data?.scheme)
            assertTrue(email.dataString.orEmpty().startsWith("mailto:${BuildConfig.SUPPORT_EMAIL}"), email.dataString)
            assertContentEquals(arrayOf(BuildConfig.SUPPORT_EMAIL), email.getStringArrayExtra(Intent.EXTRA_EMAIL))
            assertEquals("", email.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals("", email.getStringExtra(Intent.EXTRA_TEXT))
            composeRule.onAllNodes(hasText("No email app found. Write to:")).assertCountEquals(0)
        }

    @Test
    fun `with no browser a link says No browser found, gone some 5 s later`() =
        onYou {
            shadowOf(app).checkActivities(true)

            tap("Privacy policy")
            waitForText("No browser found.")
            // The exact 4 s and the restart on a second tap are YouViewModelTest's, on a virtual clock.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000))
            waitForGone("No browser found.")
            assertNull(shadowOf(app).nextStartedActivity)
        }

    @Test
    fun `with no email app the note shows the support address, and Copy address puts it on the clipboard`() =
        onYou {
            shadowOf(app).checkActivities(true)

            tap("Email support")
            waitForText("No email app found. Write to:")
            tap("Copy address")
            composeRule.waitForIdle()

            val clipboard = app.getSystemService(ClipboardManager::class.java)
            assertEquals(
                BuildConfig.SUPPORT_EMAIL,
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString(),
            )
            // The note stays until the user moves on.
            composeRule.onAllNodes(hasText("No email app found. Write to:")).assertCountEquals(1)
        }

    @Test
    fun `the no-email-app note is gone after a visit to another tab`() =
        onYou {
            shadowOf(app).checkActivities(true)
            tap("Email support")
            waitForText("No email app found. Write to:")

            tab("Settings").performClick()
            waitForGone("Privacy and your data")
            tab("You").performClick()
            waitForText("Privacy and your data")

            composeRule.onAllNodes(hasText("No email app found. Write to:")).assertCountEquals(0)
        }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 2.0f)
    fun `on a small phone at 200 percent the no-email-app note scrolls into view above the nav bar`() =
        onYou {
            shadowOf(app).checkActivities(true)
            tap("Email support")
            waitForText("Copy address")
            composeRule.waitForIdle()

            // No scroll by the test: the screen brought the note into view itself.
            val copy = composeRule.onNode(hasText("Copy address") and hasClickAction()).assertIsDisplayed().getBoundsInRoot()
            val navBarTop = listOf("Alarms", "Progress", "Settings", "You").minOf { tab(it).getBoundsInRoot().top }
            assertTrue(copy.bottom <= navBarTop, "Copy address ends at ${copy.bottom}, under the nav bar at $navBarTop")
        }

    @Test
    fun `How payments and refunds work is pushed over You with the cap sentence, and Back returns to You`() =
        onYou { scenario ->
            tap("How payments & refunds work")
            waitForText(capSentence)
            // Pushed over the shell: no capsule.
            composeRule.onAllNodes(hasContentDescription("Add alarm")).assertCountEquals(0)

            scenario.pressBack()
            waitForGone(capSentence)
            tab("You").assertIsSelected()

            tap("How payments & refunds work")
            waitForText(capSentence)
            composeRule.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
            waitForGone(capSentence)
            tab("You").assertIsSelected()
        }

    @Test
    fun `Payments pushes Problem with a charge and Purchase history over itself, and Back returns to Payments`() =
        onYou { scenario ->
            tap("How payments & refunds work")
            waitForText(capSentence)

            tap("Problem with a charge?")
            waitForText("A pending payment only charges you if it goes through.")
            scenario.pressBack()
            waitForText(capSentence)

            tap("Purchase history")
            waitForText("No snoozes paid. Keep it that way.")
            // Purchase history pushed from Payments still leads to "Problem with a charge?".
            tap("Problem with a charge?")
            waitForText("A pending payment only charges you if it goes through.")
            scenario.pressBack()
            waitForText("No snoozes paid. Keep it that way.")
            scenario.pressBack()
            waitForText(capSentence)

            scenario.pressBack()
            waitForGone(capSentence)
            tab("You").assertIsSelected()
        }

    @Test
    fun `a session that starts while Payments is open replaces it with Alarm in progress only`() {
        restartKoin(app, module { single<ActiveSessionStore> { FakeActiveSessionStore() } })
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
        onYou {
            tap("How payments & refunds work")
            waitForText(capSentence)

            val fired = SessionEvent.AlarmFired("session-1", aSessionConfig(), beforeFirstUnlock = false)
            runBlocking { GlobalContext.get().get<SessionEngine>().dispatch(fired) }
            GlobalContext.get().get<ApplicationScope>().awaitChildren()

            waitForText("Alarm in progress")
            waitForGone(capSentence)
            composeRule.onAllNodes(hasContentDescription("Add alarm")).assertCountEquals(0)
        }
    }
}
