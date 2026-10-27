package com.yawnandpawn.app.android.recording

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArraySet

/** Whether the app has a screen in front, and when that changes (Story 7.1: a take stops when the app goes away). */
interface AppForeground {
    /** True while at least one of the app's activities is started. */
    val inForeground: Boolean

    /** [listener] runs on each change, with the new [inForeground]. */
    fun addListener(listener: (Boolean) -> Unit)

    fun removeListener(listener: (Boolean) -> Unit)
}

/**
 * [AppForeground] from the started-activity count (Story 7.1), registered on the Application for the life of the
 * process (`YawnAndPawnApp`), so no `lifecycle-process` dependency is needed. Counted on the main thread. An activity
 * stopped for a configuration change does not report the background (its replacement starts right after).
 */
class StartedActivities :
    Application.ActivityLifecycleCallbacks,
    AppForeground {
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    private var started = 0

    // What was last reported: stays true across a configuration change, when no activity is started for a moment.
    @Volatile
    private var reported = false

    override val inForeground: Boolean
        get() = reported

    override fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        if (!reported) report(true)
    }

    override fun onActivityStopped(activity: Activity) {
        if (started == 0) return
        started--
        // A configuration change (theme, font size) stops the old activity before its replacement starts: not background.
        if (started == 0 && !activity.isChangingConfigurations) report(false)
    }

    private fun report(inFront: Boolean) {
        reported = inFront
        listeners.forEach { it(inFront) }
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
