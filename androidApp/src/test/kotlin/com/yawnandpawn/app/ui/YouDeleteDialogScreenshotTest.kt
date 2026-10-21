package com.yawnandpawn.app.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.ui.shell.AppShell
import com.yawnandpawn.app.ui.shell.AppTab
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.you.YouRow
import com.yawnandpawn.app.ui.you.YouScreen
import com.yawnandpawn.app.ui.you.YouUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Story 5.9: the You tab as production shows it (Purchase history, and "Delete all data" in `error`) with the approved
 * `dialog-confirm` open: "Delete all data?", the key-string body, "Delete" in `error` and "Keep it" as the default
 * dismiss. Light, Dark and Light at 200 % font scale; the dialog is its own window, so the whole screen is captured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class YouDeleteDialogScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    private fun dialog(
        name: String,
        mode: PpsThemeMode,
    ) = withScreen(
        mode,
        content = {
            AppShell(selected = AppTab.You, onSelect = {}) {
                YouScreen(
                    state = YouUiState(appVersion = "", showDeleteDialog = true),
                    onIntent = {},
                    rows = setOf(YouRow.PurchaseHistory, YouRow.DeleteAll),
                )
            }
        },
    ) {
        composeRule.onNode(isDialog()).assertExists()
        composeRule.onNodeWithText(TITLE).assertExists()
        composeRule.onNodeWithText(BODY).assertExists()
        composeRule.onNodeWithText("Delete").assertHasClickAction()
        composeRule.onNodeWithText("Keep it").assertHasClickAction()
        captureScreenRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)
    }

    @Test
    fun `delete dialog in Light`() = dialog("you_delete_dialog_light", PpsThemeMode.Light)

    @Test
    fun `delete dialog in Dark`() = dialog("you_delete_dialog_dark", PpsThemeMode.Dark)

    @Test
    @Config(fontScale = 2.0f)
    fun `delete dialog in Light at 200 percent`() = dialog("you_delete_dialog_light_font200", PpsThemeMode.Light)

    private companion object {
        const val TITLE = "Delete all data?"
        const val BODY = "Alarms, history, purchase records and settings are removed from this phone. This can't be undone."
    }
}
