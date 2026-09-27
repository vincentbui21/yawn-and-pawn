package com.yawnandpawn.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode

/**
 * Shows [content] in [PpsTheme] ([mode]) inside a real [MainActivity] (so qualifiers such as font scale and
 * night mode apply), replacing the app's own content, and runs [block] while the activity is resumed.
 */
fun withScreen(
    mode: PpsThemeMode,
    content: @Composable () -> Unit,
    block: () -> Unit,
) {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent { PpsTheme(mode = mode) { content() } }
        }
        block()
    }
}
