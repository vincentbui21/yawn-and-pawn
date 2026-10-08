package com.yawnandpawn.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.PpsThemeMode

/**
 * Shows [content] in [PpsTheme] ([mode]) inside a real [MainActivity] (so qualifiers such as font scale and
 * night mode apply), replacing the app's own content, and runs [block] while the activity is resumed.
 *
 * Replacing the content is a snapshot state write in the activity's running composition, and the test recomposer only
 * sees it once the write is applied. It is applied here, at once, so the next frame of the test clock composes
 * [content], also with that clock paused. Left alone, the write was applied by chance: Compose's `GlobalSnapshotManager`
 * applies writes from the main looper, which `mainClock.advanceTimeBy` does not run (and in full Robolectric runs its
 * once-per-JVM dispatch was found never to run at all), so only a recomposition of the app's own screen, timed by its
 * background work, applied it in time. A test that paused the clock and advanced it before its first `waitForIdle` (the
 * "I'm up" pulse, the still Success screen) then pictured the app's own screen in about half of the full runs.
 */
fun withScreen(
    mode: PpsThemeMode,
    content: @Composable () -> Unit,
    block: () -> Unit,
) {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            activity.setContent { PpsTheme(mode = mode) { content() } }
            Snapshot.sendApplyNotifications()
        }
        block()
    }
}
