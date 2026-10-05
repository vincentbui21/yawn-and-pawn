package com.yawnandpawn.app.android.wake

import android.content.Context
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.inProgress
import com.yawnandpawn.app.ui.nav.WakeScreenOpener
import kotlinx.coroutines.flow.StateFlow

/**
 * "Back to alarm" on the session lock panel (Story 2.6): opens [WakeActivity] on the current session state. Only ever
 * called from the user's tap on the app screen in front, never from the background (AD-5).
 *
 * Only while a ring or snooze is in progress ([inProgress]) or the emergency ring plays: the wake screen closes once
 * neither is true, so opened in Completed, Missed or Idle (the lock still on screen for a frame, or not restored yet) it
 * would wait for a ring that never comes, and Back would not close it. Then the tap does nothing.
 */
class AndroidWakeScreenOpener(
    private val context: Context,
    private val session: StateFlow<SessionState>,
    private val emergency: StateFlow<Boolean>,
) : WakeScreenOpener {
    override fun open() {
        if (session.value.inProgress || emergency.value) context.startActivity(WakeActivity.intent(context))
    }
}
