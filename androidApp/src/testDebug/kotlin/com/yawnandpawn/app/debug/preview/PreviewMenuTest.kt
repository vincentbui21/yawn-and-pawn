package com.yawnandpawn.app.debug.preview

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.screenshotOptions
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The preview menu's search and the deep links (`--es state <id>`), and docs/design-preview/states.md. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class PreviewMenuTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @After
    fun tearDown() {
        stopKoin()
    }

    private val allIds: List<String> = PreviewCatalog.items.map { it.stateId } + Flow.entries.map { it.stateId }

    @Test
    fun `every state id is unique and kebab-case`() {
        assertEquals(allIds.size, allIds.toSet().size, "duplicate ids: ${allIds.groupBy { it }.filterValues { it.size > 1 }.keys}")
        allIds.forEach { assertTrue(Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(it), "not kebab-case: $it") }
    }

    @Test
    fun `states md lists exactly the menu's ids`() {
        val file = File("../docs/design-preview/states.md")
        val expected = statesMarkdown()
        if (System.getenv("PREVIEW_WRITE_STATES") == "true") file.writeText(expected)
        assertEquals(expected, file.readText().replace("\r\n", "\n"), "states.md is out of date: rerun with PREVIEW_WRITE_STATES=true")
        val listed = Regex("^\\| `([a-z0-9-]+)` \\|", RegexOption.MULTILINE).findAll(file.readText()).map { it.groupValues[1] }.toList()
        assertEquals(allIds.sorted(), listed.sorted())
    }

    private fun launch(
        intent: Intent,
        block: (ActivityScenario<PreviewActivity>) -> Unit,
    ) = ActivityScenario.launch<PreviewActivity>(intent).use { block(it) }

    private fun intent(vararg extras: Pair<String, Any>): Intent =
        Intent(ApplicationProvider.getApplicationContext(), PreviewActivity::class.java).apply {
            extras.forEach { (key, value) -> if (value is Boolean) putExtra(key, value) else putExtra(key, value.toString()) }
        }

    @Test
    fun `a deep link opens its state and Back returns to the menu`() =
        launch(intent(PreviewLaunch.EXTRA_STATE to "progress-empty", PreviewLaunch.EXTRA_THEME to "dark")) { scenario ->
            composeRule.onNodeWithText("Your first morning shows up here.").assertExists()
            composeRule.onNodeWithText("Yawn & Pawn Preview").assertDoesNotExist()
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNodeWithText("Yawn & Pawn Preview").assertExists()
        }

    @Test
    fun `a deep link opens a tap-through`() =
        launch(intent(PreviewLaunch.EXTRA_STATE to "tap-settings")) {
            composeRule.onNodeWithText("Default snooze length").assertExists()
        }

    @Test
    fun `an unknown id opens the menu`() =
        launch(intent(PreviewLaunch.EXTRA_STATE to "no-such-state")) {
            composeRule.onNodeWithText("Yawn & Pawn Preview").assertExists()
        }

    @Test
    fun `the launch extras parse theme and font`() {
        val launch =
            PreviewLaunch.from(
                intent(
                    PreviewLaunch.EXTRA_STATE to " home-list ",
                    PreviewLaunch.EXTRA_THEME to "Dark",
                    PreviewLaunch.EXTRA_FONT_200 to true,
                ),
            )
        assertEquals(PreviewLaunch(state = "home-list", dark = true, largeFont = true), launch)
        assertEquals(PreviewLaunch(), PreviewLaunch.from(null))
    }

    @Test
    fun `search filters by state, screen and round, and clears`() {
        assertTrue(PreviewCatalog.items.single { it.matches("RINGING-LOCKED") }.id == "ringing_locked")
        assertTrue(PreviewCatalog.items.filter { it.matches("round 2") }.all { it.round == 2 })
        assertTrue(PreviewCatalog.items.filter { it.matches("day detail") }.all { it.group == "Day detail" })
        launch(intent()) {
            composeRule.onNodeWithContentDescription("Search screens and states").performTextInput("empty ring")
            composeRule.onNodeWithText("Empty ring").assertExists()
            composeRule.onNodeWithText("Streak, next alarm, alarm cards").assertDoesNotExist()
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/preview/menu_search_light.png",
                roborazziOptions = screenshotOptions,
            )
            composeRule.onNodeWithContentDescription("Search screens and states").performTextInput("zzz")
            composeRule.onNodeWithText("No matches").assertExists()
            composeRule.onRoot().captureRoboImage(
                "src/test/screenshots/preview/menu_no_matches_light.png",
                roborazziOptions = screenshotOptions,
            )
            composeRule.onNodeWithContentDescription("Clear search").performClick()
            composeRule.onNodeWithText("Streak, next alarm, alarm cards").assertExists()
        }
    }

    @Test
    fun `the menu matches its baselines in Light and Dark`() {
        launch(intent()) {
            composeRule.onRoot().captureRoboImage("src/test/screenshots/preview/menu_light.png", roborazziOptions = screenshotOptions)
        }
        launch(intent(PreviewLaunch.EXTRA_THEME to "dark")) {
            composeRule.onRoot().captureRoboImage("src/test/screenshots/preview/menu_dark.png", roborazziOptions = screenshotOptions)
        }
    }
}
