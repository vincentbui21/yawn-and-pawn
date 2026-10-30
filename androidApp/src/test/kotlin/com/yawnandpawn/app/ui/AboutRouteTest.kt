package com.yawnandpawn.app.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.BuildConfig
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.ui.links.ExternalLinks
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
 * 0.1.7 polish in the real app (its Koin graph and navigation; the other apps are a fake): Settings' "About" row pushes
 * About over Settings with the app's name and the version the build was given, its rows open the configured website and
 * an email to the support address, and Back returns to Settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AboutRouteTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<String>()

    private val links =
        object : ExternalLinks {
            override fun openUrl(url: String): Boolean = opened.add(url)

            override fun sendEmail(
                to: String,
                subject: String,
                body: String,
            ): Boolean = opened.add("mailto:$to")

            override fun copyText(text: String): Boolean = false
        }

    private fun start() {
        restartKoin(app, module { single<ExternalLinks> { links } })
        runBlocking { GlobalContext.get().get<SessionEngine>().restore() }
    }

    /** Waits for [text], idling the paused main looper on each check (the records DataStore resumes on it). */
    private fun waitForText(text: String) =
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun `Settings About opens About with the version, its rows open the website and the support email, Back returns`() {
        start()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithContentDescription("Settings").performClick()
            waitForText("Make sure alarms ring")
            // The last rows sit under the floating nav capsule until the list scrolls on, as a user would.
            composeRule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, 10_000f) }
            composeRule.onNode(hasText("About") and hasClickAction()).performScrollTo().performClick()

            waitForText("Version ${BuildConfig.VERSION_NAME}")
            composeRule.onNode(hasText("Yawn & Pawn") and isHeading()).assertExists()
            composeRule.onNode(hasText("Website") and hasText("yawnandpawn.com") and hasClickAction()).performClick()
            composeRule.onNode(hasText("Contact support") and hasText(BuildConfig.SUPPORT_EMAIL) and hasClickAction()).performClick()
            composeRule.onNode(hasText("Privacy policy") and hasClickAction()).performScrollTo().performClick()
            composeRule.onNode(hasText("Terms") and hasClickAction()).performScrollTo().performClick()
            assertEquals(
                listOf(BuildConfig.WEBSITE_URL, "mailto:${BuildConfig.SUPPORT_EMAIL}", BuildConfig.PRIVACY_URL, BuildConfig.TERMS_URL),
                opened,
            )

            composeRule.onNodeWithContentDescription("Back").performClick()
            waitForText("Make sure alarms ring")
            composeRule.onNode(hasText("About") and hasClickAction()).assertExists()
        }
    }
}
