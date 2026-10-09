package com.yawnandpawn.app.android.billing

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.yawnandpawn.app.android.wake.WakeActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.time.Duration

/**
 * The wake screen while it is resumed (Story 4.12): Play's sheet and the keyguard's PIN prompt open from it, and only
 * from it (FR-RNG-3). Registered on the application as lifecycle callbacks, so `WakeActivity` itself needs no change.
 * Holds the activity weakly and only between `onResume` and `onPause`.
 */
class ResumedWakeScreen(
    private val isWakeScreen: (Activity) -> Boolean = { it is WakeActivity },
) : Application.ActivityLifecycleCallbacks,
    WakeScreens {
    private val resumed = MutableStateFlow<WeakReference<Activity>?>(null)

    /** The resumed wake screen now, or null. */
    fun current(): Activity? = resumed.value?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    /**
     * The resumed wake screen, waiting up to [timeout] for it: after the PIN prompt the screen resumes a moment after
     * the unlock callback. Null when it does not resume in time.
     */
    override suspend fun await(timeout: Duration): Activity? =
        current() ?: withTimeoutOrNull(timeout) {
            resumed.first { ref -> ref?.get()?.takeUnless { it.isFinishing || it.isDestroyed } != null }
            current()
        }

    override fun onActivityResumed(activity: Activity) {
        if (isWakeScreen(activity)) resumed.value = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed.value?.get() === activity) resumed.value = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed.value?.get() === activity) resumed.value = null
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit
}
