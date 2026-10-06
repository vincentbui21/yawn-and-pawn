package com.yawnandpawn.app.android.wake

import android.content.Intent
import android.media.MediaPlayer
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.android.AndroidLogger
import com.yawnandpawn.app.android.ApplicationScope
import com.yawnandpawn.app.android.call.CallState
import com.yawnandpawn.app.core.alarm.AlarmFired
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.history.SessionHistoryRepository
import com.yawnandpawn.app.core.history.SessionMergeRow
import com.yawnandpawn.app.core.session.ActiveSessionStore
import com.yawnandpawn.app.core.session.Billing
import com.yawnandpawn.app.core.session.FallbackPolicy
import com.yawnandpawn.app.core.session.SessionEngine
import com.yawnandpawn.app.core.session.SessionEvent
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.SnoozeAvailabilityPolicy
import com.yawnandpawn.app.core.session.TestAlarmStore
import com.yawnandpawn.app.core.session.UserLockState
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.restartKoin
import com.yawnandpawn.app.testing.FakeCrashReporter
import com.yawnandpawn.app.testing.rightAnswer
import com.yawnandpawn.app.ui.qr.CodeScanner
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import kotlin.time.Duration

/**
 * The real app's Koin graph, restarted with a [FakeCrashReporter] and optionally a replaced session [store], alarm
 * [repository], service [starter], session [history] repository, wall [clock], [monotonic] clock, pending [testAlarms],
 * [billing] or the alarms' [checkConfigs]. The alarm receiver waits [serviceStartWait] for the wake service (none by
 * default: the tests start it themselves, as the system would). The real `MediaPlayer` adapter plays over Robolectric's
 * media shadow (every source opens). The service's coroutines run on the main looper: [awaitUntil] idles it (and the Room threads) until
 * a condition holds. Tear down with `StopAppRule`.
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
    userLock: UserLockState? = null,
    serviceStartWait: Duration = Duration.ZERO,
    calls: CallState? = null,
    policy: SnoozeAvailabilityPolicy? = null,
    checkConfigs: CheckConfigRepository? = null,
    fallback: FallbackPolicy? = null,
    scanner: CodeScanner? = null,
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
                userLock?.let { replaced -> single<UserLockState> { replaced } }
                calls?.let { replaced -> single<CallState> { replaced } }
                policy?.let { replaced -> single<SnoozeAvailabilityPolicy> { replaced } }
                checkConfigs?.let { replaced -> single<CheckConfigRepository> { replaced } }
                fallback?.let { replaced -> single<FallbackPolicy> { replaced } }
                scanner?.let { replaced -> single<CodeScanner> { replaced } }
                single { WakeServiceStarts(serviceStartWait) }
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

    /** The `session_merge` rows of [sessionId] (Story 2.9) in the app's history repository. */
    fun merges(sessionId: String): List<SessionMergeRow> =
        when (val read = runBlocking { koin.get<SessionHistoryRepository>().merges(sessionId) }) {
            is Outcome.Success -> read.value
            is Outcome.Failure -> fail("merges not readable: ${read.error}")
        }

    /** Waits until [sessionId] has [count] merge rows (the engine writes them after the merge's other effects). */
    fun awaitMerges(
        sessionId: String,
        count: Int = 1,
    ): List<SessionMergeRow> {
        awaitUntil("$count merge row(s) of $sessionId") { merges(sessionId).size >= count }
        return merges(sessionId)
    }

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

    /**
     * Answers every item of the session's check right, one `CheckAnswerSubmitted` each, as a user does on the Check
     * screen (Story 3.2: Math problems; the placeholder of a placeholder plan), until the session leaves Grace or Loud.
     */
    fun solveCheck() {
        repeat(MAX_ANSWERS) {
            val ring = engine.state.value as? SessionState.Ring ?: return
            val answer = rightAnswer(ring.session.checkRun) ?: return
            dispatch(SessionEvent.CheckAnswerSubmitted(answer))
        }
    }

    private companion object {
        const val MAX_ANSWERS = 100
        const val MEDIA_MILLIS = 3_600_000

        // About 30 s of real sleep (System.nanoTime is shadowed, so rounds count the time). A loaded gate run (many
        // workers, a first Room open on another SDK) once needed more than 5 s for one dispatch.
        const val MAX_ROUNDS = 3_000
        const val ROUND_MILLIS = 10L
    }
}
