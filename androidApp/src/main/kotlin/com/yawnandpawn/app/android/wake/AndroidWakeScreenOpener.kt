package com.yawnandpawn.app.android.wake

import android.content.Context
import com.yawnandpawn.app.ui.nav.WakeScreenOpener

/**
 * "Back to alarm" on the session lock panel (Story 2.6): opens [WakeActivity] on the current session state. Only ever
 * called from the user's tap on the app screen in front, never from the background (AD-5).
 */
class AndroidWakeScreenOpener(
    private val context: Context,
) : WakeScreenOpener {
    override fun open() {
        context.startActivity(WakeActivity.intent(context))
    }
}
