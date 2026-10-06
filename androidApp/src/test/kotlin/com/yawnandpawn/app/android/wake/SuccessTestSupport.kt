package com.yawnandpawn.app.android.wake

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import com.yawnandpawn.app.core.session.SessionState

/** The wake screen shows the Success screen with [headline] (Story 3.3). */
internal fun ComposeTestRule.successShown(headline: String): Boolean =
    onAllNodesWithText(headline).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

/** Waits until the session of [app] is over (Idle) and the wake screen shows Success with [headline] (Story 3.3). */
internal fun ComposeTestRule.awaitSuccess(
    app: WakeApp,
    headline: String,
) = waitUntil(timeoutMillis = 10_000) { app.engine.state.value == SessionState.Idle && successShown(headline) }
