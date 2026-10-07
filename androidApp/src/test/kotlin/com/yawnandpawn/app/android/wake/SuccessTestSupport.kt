package com.yawnandpawn.app.android.wake

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.session.SessionState

/** The wake screen shows the Success screen with [headline] (Story 3.3). */
internal fun ComposeTestRule.successShown(headline: String): Boolean =
    onAllNodesWithText(headline).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

/**
 * Waits until [condition] holds, then until the wake screen has caught up with the engine. The screen's sends and the
 * engine's dispatches run on [ApplicationScope], off the main thread, and a new engine state is readable a moment
 * before the screen's collector is resumed with it: a condition on the engine can hold while the screen still shows
 * the step before. So the work on [ApplicationScope] is awaited and the composition idled again before the screen is
 * read.
 */
internal fun ComposeTestRule.awaitScreen(
    app: WakeApp,
    what: String,
    condition: () -> Boolean,
) {
    app.awaitUntil(what) {
        waitForIdle()
        condition()
    }
    app.koin.get<ApplicationScope>().awaitChildren()
    waitForIdle()
}

/** Waits until the session of [app] is over (Idle) and the wake screen shows Success with [headline] (Story 3.3). */
internal fun ComposeTestRule.awaitSuccess(
    app: WakeApp,
    headline: String,
) = awaitScreen(app, "Success with $headline") { app.engine.state.value == SessionState.Idle && successShown(headline) }
