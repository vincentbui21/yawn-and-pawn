package com.yawnandpawn.app.android

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import com.yawnandpawn.app.APP_WORK_TIMEOUT_MILLIS
import com.yawnandpawn.app.MainActivity
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.onboarding.OnboardingProgress
import com.yawnandpawn.app.core.onboarding.OnboardingRepository
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.data.settings.SettingsDataStore
import com.yawnandpawn.app.testing.anAlarm
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Story 5.10 review: a phone updated from a build before onboarding keeps its alarms. With the real (empty) onboarding
 * store and an alarm stored before the update, a reboot and the update broadcast still arm it (the disclosure rule only
 * guards saving), and the next app open shows onboarding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class OnboardingUpgradeTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private val berlin = TimeZone.of("Europe/Berlin")

    // Wednesday 2027-03-03, 06:00 in Berlin.
    private val app = SchedulingApp(LocalDateTime.parse("2027-03-03T06:00").toInstant(berlin), "Europe/Berlin")

    @After
    fun tearDown() {
        app.close()
    }

    private fun broadcast(action: String) {
        app.app.sendBroadcast(Intent(action).setPackage(app.app.packageName))
        app.awaitWork()
    }

    @Test
    fun `an alarm stored before the update still rings after a reboot and the update, and the app then opens onboarding`() {
        loadKoinModules(module { single<OnboardingRepository> { get<SettingsDataStore>().onboarding(get()) } })
        val onboarding = app.koin.get<OnboardingRepository>()
        assertEquals(OnboardingProgress(), runBlocking { onboarding.get().valueOrNull() }, "no consent stored yet")
        val old = anAlarm(id = "old", time = LocalTime(7, 0), requestCode = 1000)
        runBlocking { assertEquals(Outcome.Success(Unit), app.repository.upsert(old)) }
        val sevenOClock = LocalDateTime.parse("2027-03-03T07:00").toInstant(berlin).toEpochMilliseconds()

        broadcast(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(mapOf(1000 to sevenOClock), app.armed())

        app.clearArmed()
        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(mapOf(1000 to sevenOClock), app.armed())

        runBlocking { app.koin.get<SessionEngine>().restore() }
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = APP_WORK_TIMEOUT_MILLIS) {
                shadowOf(Looper.getMainLooper()).idle()
                composeRule.onAllNodes(hasText(MISSION)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    private companion object {
        const val MISSION = "This app makes money only when you snooze. We hope you never pay us."
    }
}
