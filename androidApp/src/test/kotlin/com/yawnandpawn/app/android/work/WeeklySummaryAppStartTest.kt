package com.yawnandpawn.app.android.work

import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.TestYawnAndPawnApp
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.core.work.BackgroundWork
import com.yawnandpawn.app.core.work.SchedulePolicy
import com.yawnandpawn.app.testing.FakeBackgroundWork
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours

/**
 * Story 6.8 review: the real app start (`YawnAndPawnApp.onCreate`) on an unlocked phone hands the weekly summary over
 * once, with KEEP, at the next Sunday 19:00 local; price refresh is enqueued apart, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = WeeklySummaryAppStartTest.SaturdayEveningApp::class)
class WeeklySummaryAppStartTest {
    /** The test app with the wall clock at Saturday 2027-03-06 19:00 in Berlin: the next target is 24 h away. */
    class SaturdayEveningApp : TestYawnAndPawnApp() {
        override val overrideModules: List<Module> =
            super.overrideModules +
                module {
                    single<Clock> { FakeClock(LocalDateTime.parse("2027-03-06T19:00").toInstant(BERLIN)) }
                    single<TimeZoneProvider> { FakeTimeZoneProvider(BERLIN) }
                }
    }

    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @Test
    fun `the app start keeps or enqueues the weekly summary at the next Sunday 19 00, once`() {
        val koin = GlobalContext.get()
        koin.get<ApplicationScope>().awaitChildren()

        val work = assertIs<FakeBackgroundWork>(koin.get<BackgroundWork>())
        assertEquals(listOf(24.hours to SchedulePolicy.KeepPending), work.weeklySchedules)
        assertEquals(0, work.weeklyCancels)
    }

    private companion object {
        val BERLIN: TimeZone = TimeZone.of("Europe/Berlin")
    }
}
