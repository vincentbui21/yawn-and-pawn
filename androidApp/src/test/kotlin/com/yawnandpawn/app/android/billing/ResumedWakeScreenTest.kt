package com.yawnandpawn.app.android.billing

import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Story 4.12 review 1: the resumed wake screen, followed through the application's lifecycle callbacks. A
 * `ComponentActivity` stands in for `WakeActivity` (the predicate), a plain `Activity` for any other screen.
 */
@RunWith(RobolectricTestRunner::class)
class ResumedWakeScreenTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val tracker = ResumedWakeScreen(isWakeScreen = { it is ComponentActivity })

    init {
        app.registerActivityLifecycleCallbacks(tracker)
    }

    @After
    fun tearDown() = app.unregisterActivityLifecycleCallbacks(tracker)

    @Test
    fun `another screen resumed is not the wake screen`() =
        runBlocking<Unit> {
            Robolectric.buildActivity(Activity::class.java).setup()

            assertNull(tracker.current())
            assertNull(tracker.await(50.milliseconds))
        }

    @Test
    fun `a wait ends when the wake screen resumes`() =
        runBlocking<Unit> {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start()
            val waiting = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { tracker.await(1.seconds) }

            controller.resume()

            assertSame<Activity?>(controller.get(), waiting.await())
            assertSame<Activity?>(controller.get(), tracker.current())
        }

    @Test
    fun `a paused wake screen is no longer current`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        assertSame<Activity?>(controller.get(), tracker.current())

        controller.pause()

        assertNull(tracker.current())
    }

    @Test
    fun `a destroyed wake screen is no longer current`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()

        controller.pause().stop().destroy()

        assertNull(tracker.current())
    }
}
