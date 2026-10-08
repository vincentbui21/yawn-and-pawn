package com.yawnandpawn.app.android.wake

import android.os.Looper
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmScheduling
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.ReadFireSettings
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.config.SettingsSnapshot
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionConfig
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Story 4.4 review fixes 1, 2 and 11 on the real graph: what the fire reads (the stored settings and the pending changes,
 * one snapshot within the budget, the last-known settings on failure) and where due changes are promoted
 * (`rescheduleAll`, the wake service's shutdown after a session).
 */
@RunWith(RobolectricTestRunner::class)
class CommitmentLockWakeTest {
    @get:Rule(order = 0)
    val stopAppRule = StopAppRule()

    // After the real clock: nothing waiting for it is due, so no promotion interferes.
    private val ringsAt = Instant.parse("2027-03-08T06:00:00Z")

    // Long before the real clock: a change waiting for it is due.
    private val longAgo = Occurrence("gone", Instant.parse("2020-01-01T06:00:00Z"))

    private fun savedAlarm(app: WakeApp): Alarm {
        runBlocking { app.engine.restore() }
        val saved = runBlocking { app.koin.get<SaveAlarm>()(AlarmDraft(time = LocalTime(6, 0))) }
        return assertIs<Outcome.Success<Alarm>>(saved).value
    }

    private fun ringingConfig(app: WakeApp): SessionConfig {
        app.awaitRinging()
        return assertIs<SessionState.Ringing>(app.engine.state.value).session.config
    }

    @Test
    fun `the fire rings the stored fee, and a change waiting for this very occurrence does not apply to it`() {
        val app = WakeApp()
        val alarm = savedAlarm(app)
        val pending = app.koin.get<PendingChangeRepository>()
        runBlocking { app.koin.get<GlobalSettingsRepository>().setBaseFeeTier(7) }
        runBlocking { pending.put(PendingChange(null, SettingValue.BaseFeeTier(1), Occurrence(alarm.id, ringsAt))) }

        app.ring(AlarmFired(alarm.id, ringsAt))

        assertEquals(7, ringingConfig(app).baseFeeTier)
    }

    @Test
    fun `a change that waited for an earlier occurrence applies to this one`() {
        val app = WakeApp()
        val alarm = savedAlarm(app)
        runBlocking { app.koin.get<GlobalSettingsRepository>().setBaseFeeTier(7) }
        val waited = Occurrence(alarm.id, ringsAt - 1.days)
        runBlocking { app.koin.get<PendingChangeRepository>().put(PendingChange(null, SettingValue.BaseFeeTier(1), waited)) }
        runBlocking { app.koin.get<PendingChangeRepository>().put(PendingChange(alarm.id, SettingValue.GraceSeconds(30), waited)) }

        app.ring(AlarmFired(alarm.id, ringsAt))

        val config = ringingConfig(app)
        assertEquals(1, config.baseFeeTier)
        assertEquals(30, config.graceSeconds)
    }

    @Test
    fun `a settings store that fails rings the last-known settings and logs the failure`() {
        val settings =
            FakeGlobalSettingsRepository().apply {
                failure = DomainError.StorageFailure("disk")
                lastKnownSnapshot = SettingsSnapshot(GlobalSettings(baseFeeTier = 7), emptyList())
            }
        val app = WakeApp(globalSettings = settings)
        val alarm = savedAlarm(app)

        app.ring(AlarmFired(alarm.id, ringsAt))

        assertEquals(7, ringingConfig(app).baseFeeTier)
        assertTrue(app.logs().any { "operation=${ReadFireSettings.OPERATION_SETTINGS}" in it }, "${app.logs()}")
    }

    @Test
    fun `a settings store that hangs holds the ring for the budget only, then rings the defaults`() {
        val settings = HangingSettings()
        val app = WakeApp(globalSettings = settings)
        val alarm = savedAlarm(app)

        app.ring(AlarmFired(alarm.id, ringsAt))
        app.awaitUntil("the fire reads the settings") { settings.reads > 0 }
        assertTrue(app.engine.state.value !is SessionState.Ringing, "it waits for the settings first")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ReadFireSettings.BUDGET.inWholeMilliseconds + 1))

        assertEquals(GlobalSettings().baseFeeTier, ringingConfig(app).baseFeeTier)
        assertTrue(app.logs().any { "operation=${ReadFireSettings.OPERATION_SETTINGS} cause=timed out" in it }, "${app.logs()}")
    }

    @Test
    fun `rescheduleAll on the real graph promotes a due change, without deadlocking on the alarm write lock`() {
        val app = WakeApp()
        val pending = app.koin.get<PendingChangeRepository>()
        runBlocking { pending.put(PendingChange(null, SettingValue.BaseFeeTier(1), longAgo)) }

        runBlocking { withTimeout(10.seconds) { app.koin.get<AlarmScheduling>().rescheduleAll() } }

        assertEquals(Outcome.Success(1), runBlocking { app.koin.get<GlobalSettingsRepository>().get() }.map { it.baseFeeTier })
        assertEquals(Outcome.Success(emptyList()), runBlocking { pending.all() })
    }

    @Test
    fun `the wake service promotes a due change once its session is over`() {
        val app = WakeApp()
        val alarm = savedAlarm(app)
        val pending = app.koin.get<PendingChangeRepository>()
        runBlocking { pending.put(PendingChange(null, SettingValue.MaxSnoozes(4), longAgo)) }
        app.ring(AlarmFired(alarm.id, ringsAt))
        app.awaitRinging()
        assertEquals(1, (runBlocking { pending.all() } as Outcome.Success).value.size, "nothing promotes it while the alarm rings")

        app.dispatch(SessionEvent.ImUpTapped)
        app.solveCheck()

        app.awaitUntil("the change is promoted after the session") { (runBlocking { pending.all() } as Outcome.Success).value.isEmpty() }
        assertEquals(Outcome.Success(4), runBlocking { app.koin.get<GlobalSettingsRepository>().get() }.map { it.maxSnoozes })
    }

    /** Settings whose snapshot never answers (a stuck DataStore); counts the reads. */
    private class HangingSettings(
        private val inner: FakeGlobalSettingsRepository = FakeGlobalSettingsRepository(),
    ) : GlobalSettingsRepository by inner {
        @Volatile
        var reads = 0

        override suspend fun snapshot(): Outcome<SettingsSnapshot, DomainError> {
            reads++
            awaitCancellation()
        }
    }
}
