package com.yawnandpawn.app.android.wake

import android.content.Intent
import android.media.MediaPlayer
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.TestAlarmStore
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeCrashReporter
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaPlayer
import kotlin.test.fail

/**
 * The real app's Koin graph, restarted with a [FakeCrashReporter] and optionally a replaced session [store], alarm
 * [repository], service [starter], session [history] repository, wall [clock], [monotonic] clock, pending [testAlarms]
 * or [billing]; the real `MediaPlayer` adapter plays over Robolectric's media shadow (every source opens). The
 * service's coroutines run on the main looper: [awaitUntil] idles it (and the Room threads) until a condition holds.
 * Tear down with `StopAppRule`.
 */
internal class WakeApp(
    store: ActiveSessionStore? = null,
    repository: AlarmRepository? = null,
    starter: ((YawnAndPawnApp) -> WakeServiceStarter)? = null,
    history: SessionHistoryRepository? = null,
    clock: Clock? = null,
    monotonic: MonotonicClock? = null,
    testAlarms: TestAlarmStore? = null,
    billing: Billing? = null,
) {
    val app: YawnAndPawnApp = ApplicationProvider.getApplicationContext()
    val crashReporter = FakeCrashReporter()

    /** Every `MediaPlayer` the app created, oldest first. */
    val mediaPlayers = mutableListOf<MediaPlayer>()

    init {
        // A real boot count, so deadlines compare monotonic time (which the main looper's clock moves) in these tests.
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 1)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(MEDIA_MILLIS, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> mediaPlayers += player }
        restartKoin(
            app,
            module {
                single<CrashReporter> { crashReporter }
                store?.let { replaced -> single<ActiveSessionStore> { replaced } }
                repository?.let { replaced -> single<AlarmRepository> { replaced } }
                starter?.let { build -> single { build(app) } }
                history?.let { replaced -> single<SessionHistoryRepository> { replaced } }
                clock?.let { replaced -> single<Clock> { replaced } }
                monotonic?.let { replaced -> single<MonotonicClock> { replaced } }
                testAlarms?.let { replaced -> single<TestAlarmStore> { replaced } }
                billing?.let { replaced -> single<Billing> { replaced } }
            },
        )
        ShadowLog.clear()
    }

    val koin: Koin
        get() = GlobalContext.get()

    val engine: SessionEngine
        get() = koin.get()

    val runtime: WakeRuntime
        get() = koin.get()

    val player: AndroidAlarmPlayer
        get() = koin.get()

    val vibrator: AlarmVibrator
        get() = koin.get()

    /** The media shadow of the newest player. */
    fun lastMediaPlayer(): ShadowMediaPlayer = Shadow.extract(mediaPlayers.last())

    /** Starts the service as the system would for [intent] (created, then `onStartCommand`). */
    fun startService(intent: Intent): ServiceController<WakeService> =
        Robolectric.buildService(WakeService::class.java, intent).create().startCommand(0, 1)

    /** Starts the service for the alarm [fired]. */
    fun ring(fired: AlarmFired): ServiceController<WakeService> = startService(WakeService.alarmIntent(app, fired))

    /** The log lines of the app's logger. */
    fun logs(): List<String> = ShadowLog.getLogsForTag(AndroidLogger.TAG).map { it.msg }

    /**
     * Runs the main looper until [condition] holds, letting the background threads (Room, `ApplicationScope`) work in
     * between; fails with [what] after a while. It never blocks the main thread: the service's coroutines need it.
     */
    fun awaitUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        repeat(MAX_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(ROUND_MILLIS)
        }
        fail("timed out waiting until $what")
    }

    /** "Now" on the app's time ports. */
    fun now(): TimeSnapshot = TimeSnapshot.of(koin.get(), koin.get(), koin.get())

    /** Waits until the engine rings and the runtime has applied its entry effects (the sound is open). */
    fun awaitRinging() = awaitUntil("the alarm rings") { engine.state.value is SessionState.Ringing && player.sound != null }

    /** Dispatches [events] in order from `ApplicationScope`, as the wake screen does, and waits until they are done. */
    fun dispatch(vararg events: SessionEvent) {
        val job = koin.get<ApplicationScope>().launch { events.forEach { engine.dispatch(it) } }
        awaitUntil("${events.map { it::class.simpleName }} are dispatched") { job.isCompleted }
    }

    private companion object {
        const val MEDIA_MILLIS = 3_600_000
        const val MAX_ROUNDS = 500
        const val ROUND_MILLIS = 10L
    }
}
