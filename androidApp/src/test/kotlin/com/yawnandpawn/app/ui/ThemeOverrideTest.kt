package com.yawnandpawn.app.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.TestYawnAndPawnApp
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.SettingsSnapshot
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.ThemeMode
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.ui.theme.PpsColorSet
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.theme.colors
import com.yawnandpawn.app.ui.wake.WakeSurface
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Story 5.1 in the real app: the theme stored in Settings › Appearance themes every app screen. Dark on a light phone and
 * Light on a dark phone override the system, System follows it (also when it flips to night), the choice survives an
 * activity restart, picking a theme in Settings re-themes the app at once, and a wake surface stays Sunrise whatever is
 * chosen. Which token set the app uses is read from the status bar icons the theme sets (dark icons on Light, light ones
 * on Dark); the Home screenshots show it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi", application = ThemeOverrideTest.PinnedClockApp::class)
class ThemeOverrideTest {
    /** The test app with the wall clock stopped at noon UTC, so Home renders the same whatever the time of the run. */
    class PinnedClockApp : TestYawnAndPawnApp() {
        override val overrideModules: List<Module> =
            super.overrideModules +
                module {
                    single<Clock> { FakeClock(NOON) }
                    single<TimeZoneProvider> { FakeTimeZoneProvider(TimeZone.UTC) }
                }
    }

    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun settings(): GlobalSettingsRepository = GlobalContext.get().get()

    /** Stores [mode] as a previous run of the app left it (the settings store and its last-known snapshot). */
    private fun storeTheme(mode: ThemeMode) = assertEquals(Outcome.Success(Unit), runBlocking { settings().setThemeMode(mode) })

    private fun waitForText(text: String) =
        composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }

    /** True while the app draws the Light set: the theme asks for dark status bar icons on Light, light ones on Dark. */
    private fun ActivityScenario<MainActivity>.drawsLight(): Boolean {
        var light = false
        onActivity { activity: Activity ->
            light = WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars
        }
        return light
    }

    /** The window's own background, painted in `onCreate` before the first Compose frame (review fix). */
    private fun ActivityScenario<MainActivity>.windowColor(): Int? {
        var color: Int? = null
        onActivity { activity: Activity -> color = (activity.window.decorView.background as? ColorDrawable)?.color }
        return color
    }

    private val darkBg =
        PpsColorSet.Dark
            .colors()
            .bg
            .toArgb()

    private fun ActivityScenario<MainActivity>.awaitDraws(light: Boolean) {
        runCatching { composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) { drawsLight() == light } }
        assertEquals(light, drawsLight(), if (light) "the Light set" else "the Dark set")
    }

    private fun home(
        light: Boolean,
        screenshot: String,
    ) = ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        waitForText("No alarms yet.")
        scenario.awaitDraws(light)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$screenshot.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `System on a light phone draws Home in Light`() = home(light = true, screenshot = "home_theme_system_day")

    @Test
    @Config(qualifiers = "+night")
    fun `System on a dark phone draws Home in Dark`() = home(light = false, screenshot = "home_theme_system_night")

    @Test
    fun `Dark on a light phone draws Home in Dark`() {
        storeTheme(ThemeMode.Dark)
        home(light = false, screenshot = "home_theme_dark")
    }

    @Test
    @Config(qualifiers = "+night")
    fun `Light on a dark phone draws Home in Light`() {
        storeTheme(ThemeMode.Light)
        home(light = true, screenshot = "home_theme_light")
    }

    @Test
    fun `the chosen theme survives an activity restart, and its first frame already uses it`() {
        storeTheme(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, settings().lastKnown()?.settings?.themeMode, "the first frame reads the last-known snapshot")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // No wait: the window is Dark before any frame or store read, on this light phone (review fix).
            assertEquals(darkBg, scenario.windowColor(), "the window behind the first frame")
            waitForText("No alarms yet.")
            scenario.awaitDraws(light = false)

            scenario.recreate()

            assertEquals(darkBg, scenario.windowColor(), "the window after the restart, before any frame")
            waitForText("No alarms yet.")
            assertEquals(false, scenario.drawsLight(), "still Dark after the restart")
        }
    }

    @Test
    fun `the first frame uses the last-known theme before the store is read`() {
        // A store that is never read in time: only the last-known snapshot can say Dark (review fix).
        val fake = FakeGlobalSettingsRepository()
        fake.lastKnownSnapshot = SettingsSnapshot(GlobalSettings(themeMode = ThemeMode.Dark), emptyList())
        val silent =
            object : GlobalSettingsRepository by fake {
                override fun observe(): Flow<GlobalSettings> = flow { awaitCancellation() }
            }
        restartKoin(RuntimeEnvironment.getApplication(), module { single<GlobalSettingsRepository> { silent } })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(darkBg, scenario.windowColor(), "the window, at once")
            // The store never emits, so Dark here can only be the first composition's theme from the snapshot.
            scenario.awaitDraws(light = false)
        }
    }

    @Test
    fun `System follows the phone when it switches to night`() =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("No alarms yet.")
            scenario.awaitDraws(light = true)

            RuntimeEnvironment.setQualifiers("+night")
            scenario.recreate()

            waitForText("No alarms yet.")
            scenario.awaitDraws(light = false)
        }

    @Test
    fun `picking Dark in Settings re-themes the app at once, and Home is Dark too`() =
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForText("No alarms yet.")
            scenario.awaitDraws(light = true)

            composeRule.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
            waitForText("Appearance")
            composeRule.onNode(hasText("Dark") and isSelectable()).performClick()

            scenario.awaitDraws(light = false)
            assertEquals(ThemeMode.Dark, (runBlocking { settings().get() } as Outcome.Success).value.themeMode)

            composeRule.onNode(hasContentDescription("Alarms") and hasClickAction()).performClick()
            waitForText("No alarms yet.")
            assertEquals(false, scenario.drawsLight())
        }

    @Test
    fun `a wake surface stays Sunrise under a chosen Light or Dark theme`() {
        listOf(PpsThemeMode.Light, PpsThemeMode.Dark, PpsThemeMode.System).forEach { mode ->
            var drawn: PpsColorSet? = null
            withScreen(mode, content = { WakeSurface { Probe { drawn = it } } }) {
                composeRule.waitForIdle()
            }
            assertEquals(PpsColorSet.Sunrise, drawn, "under $mode")
        }
    }

    private companion object {
        val NOON: Instant = Instant.parse("2027-03-03T12:00:00Z")
    }
}

/** Reports the token set it is drawn with. */
@Composable
private fun Probe(report: (PpsColorSet) -> Unit) {
    report(PpsTheme.colorSet)
}
