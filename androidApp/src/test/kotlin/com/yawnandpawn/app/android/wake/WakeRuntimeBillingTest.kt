package com.yawnandpawn.app.android.wake

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.sound.LibrarySoundResolver
import com.yawnandpawn.app.awaitChildren
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.session.PurchaseIntentId
import com.yawnandpawn.app.core.session.PurchaseOutcome
import com.yawnandpawn.app.core.session.SessionEffect
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.entryEffects
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeCrashReporter
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.datetime.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.11: the wake runtime hands the billing effects to the orchestration and returns at once, and every payment
 * outcome that hands the screen back to the ring re-asserts the ring volume once (the Story 2.8 hand-off).
 */
@RunWith(RobolectricTestRunner::class)
class WakeRuntimeBillingTest {
    @get:Rule(order = 0)
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val logger = FakeLogger()
    private val playbacks = FakePlaybackFactory()
    private val dispatcher = StandardTestDispatcher()
    private val volume = AlarmVolume(context, logger)
    private val player =
        AndroidAlarmPlayer(playbacks, LibrarySoundResolver(), volume, FakeMonotonicClock(), CoroutineScope(dispatcher), logger)
    private val now = TimeSnapshot(wallMillis = 1_000_000, elapsedMillis = 5_000, bootCount = 1)
    private var state: SessionState = SessionState.Idle
    private val launches = mutableListOf<SessionEffect.LaunchBilling>()
    private var unlocks = 0
    private val runtime =
        WakeRuntime(
            WakeOutputs(
                player,
                AlarmVibrator(context),
                WakeNotifier(context, FakeTimeZoneProvider(TimeZone.UTC)),
                volume,
                FakeAlarmScheduler(),
                FakeCrashReporter(),
            ),
            WakeServiceStarter(context, logger) { },
            CoroutineScope(dispatcher),
            logger,
            { now },
            { state },
            onLaunchBilling = { launches += it },
            onKeyguardDismiss = { unlocks++ },
        )
    private val session = aSession()

    /** The test's volume shares the app's "wake_runtime" preferences: let the app's start finish first (WakeRuntimeTest). */
    @Before
    fun awaitAppStart() = GlobalContext.get().get<ApplicationScope>().awaitChildren()

    private fun enter(next: SessionState) {
        state = next
        runBlocking { entryEffects(next).forEach { runtime.apply(it) } }
    }

    private fun run(effect: SessionEffect) = runBlocking { runtime.run(effect) }

    private fun alarmStream() = audio.getStreamVolume(AudioManager.STREAM_ALARM)

    @Test
    fun `every payment outcome that hands the screen back re-asserts the ring volume once`() {
        val setVolume = (audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * 0.8).roundToInt()
        enter(SessionState.Ringing(session))
        listOf(
            SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Cancelled),
            SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed),
            SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Offline),
            SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.UnlockFailed),
            SessionEffect.ShowPaymentPending,
            SessionEffect.ShowReuseSheet("snooze_usd_01"),
            SessionEffect.HideReuseSheet,
        ).forEach { effect ->
            // The volume keys turned the alarm down under Play's sheet.
            audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)
            run(effect)
            assertEquals(setVolume, alarmStream(), "$effect")
        }
        assertEquals(1, playbacks.opened.size, "the ring was never restarted")
        runtime.endSession()
    }

    @Test
    fun `a payment outcome during the grace mute never raises the alarm stream (review 9)`() {
        enter(SessionState.Ringing(session))
        run(SessionEffect.Mute)
        enter(SessionState.Grace(session))
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 1, 0)

        run(SessionEffect.ShowPurchaseOutcome(PurchaseOutcome.Failed))
        run(SessionEffect.ShowPaymentPending)

        assertEquals(1, alarmStream(), "muted for the grace window: the volume is left alone")
        assertTrue(player.isMuted)
        runtime.endSession()
    }

    @Test
    fun `the billing effects go to the orchestration and return at once`() {
        val launch = SessionEffect.LaunchBilling(PurchaseIntentId("intent-1"), session.sessionId)

        run(launch)
        run(SessionEffect.RequestKeyguardDismiss(PurchaseIntentId("intent-1")))

        assertEquals(listOf(launch), launches)
        assertEquals(1, unlocks)
        assertTrue(logger.events.none { it is LogEvent.SessionEffectLogged }, "handled, not only logged")
    }
}
