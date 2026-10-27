package com.yawnandpawn.app.android.recording

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.YawnAndPawnApp
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.recording.AudioRecorder
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.RecordingSession
import com.yawnandpawn.app.data.recording.RecordingFiles
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMonotonicClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowMediaRecorder
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Story 7.1: the microphone recorder over a scripted [RecorderEngine], on Robolectric's broadcasts and permissions. */
@RunWith(RobolectricTestRunner::class)
class AndroidAudioRecorderTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val folder = File(app.filesDir, RecordingFiles.FOLDER)
    private val files = RecordingFiles { folder }
    private val clock = FakeMonotonicClock(elapsedMillis = 1_000_000)
    private val logger = FakeLogger()
    private val foreground = FakeForeground()
    private val focus = FakeFocus()
    private val engines = mutableListOf<FakeEngine>()
    private var nextEngine: () -> FakeEngine = { FakeEngine() }
    private var free = 10_000_000L
    private var takes = 0
    private val ids = IdGenerator { "take-${++takes}" }
    private val engine: () -> RecorderEngine = { nextEngine().also { engines += it } }

    private fun recorder(
        permission: () -> Boolean = { true },
        microphone: () -> Boolean = { true },
    ): AndroidAudioRecorder =
        AndroidAudioRecorder(
            context = app,
            files = files,
            ids = ids,
            monotonic = clock,
            foreground = foreground,
            focus = focus,
            logger = logger,
            newEngine = engine,
            hasPermission = permission,
            hasMicrophone = microphone,
            freeBytes = { free },
            io = Dispatchers.Unconfined,
        )

    /** The recorder with the real permission and microphone checks. */
    private fun platformChecks(): AndroidAudioRecorder =
        AndroidAudioRecorder(app, files, ids, clock, foreground, focus, logger, newEngine = engine, freeBytes = { free })

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun takeFile(id: String) = files.takeFile(id)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun AudioRecorder.autoStop(): Outcome<RecordedTake, DomainError> =
        runBlocking { withTimeout(5.seconds) { autoStopped().first() } }

    private fun started(recorder: AudioRecorder): String = assertIs<Outcome.Success<RecordingSession>>(recorder.start()).value.takeId

    /** No log event names a file, a folder or a take. */
    private fun assertNoPathsLogged() {
        val text = logger.events.joinToString { it.toString() }
        assertFalse(text.contains(folder.absolutePath) || text.contains(".m4a") || text.contains("take-"), text)
    }

    @Test
    fun `a 9 s take is recorded into its temporary file and kept when stopped`() {
        val recorder = recorder()

        val id = started(recorder)
        clock.advanceBy(9.seconds)
        val take = runBlocking { recorder.stop() }

        assertEquals(Outcome.Success(RecordedTake(id, 9_000)), take)
        assertTrue(takeFile(id).isFile, "the take waits in its temporary file for the save")
        val engine = engines.single()
        assertEquals(takeFile(id), engine.output)
        assertEquals(60_000, engine.maxDurationMs)
        assertTrue(engine.stopped && engine.released)
        assertFalse(focus.held, "focus is given back")
        assertTrue(foreground.listeners.isEmpty(), "the background listener is removed")
        assertEquals(emptyList(), logger.events)
    }

    @Test
    fun `a take under 1 s is too short and its file is deleted, without an error in the log`() {
        val recorder = recorder()
        val id = started(recorder)
        clock.advanceBy(600.milliseconds)

        assertEquals(Outcome.Failure(DomainError.RecordingTooShort), runBlocking { recorder.stop() })
        assertFalse(takeFile(id).exists())
        assertEquals(emptyList(), logger.events)
    }

    @Test
    fun `at 60 s the recorder stops by itself and offers the full take`() {
        val recorder = recorder()
        val id = started(recorder)
        clock.advanceBy(59_900.milliseconds)

        engines.single().reachMaxDuration()

        assertEquals(Outcome.Success(RecordedTake(id, 60_000)), recorder.autoStop())
        assertTrue(takeFile(id).isFile)
        assertEquals(Outcome.Failure(DomainError.RecorderFailure("not recording")), runBlocking { recorder.stop() })
    }

    @Test
    fun `at 60 s the take is kept even when the recorder had already stopped itself`() {
        nextEngine = { FakeEngine(stopFailure = IllegalStateException("already stopped")) }
        val recorder = recorder()
        val id = started(recorder)
        clock.advanceBy(60.seconds)

        engines.single().reachMaxDuration()

        assertEquals(Outcome.Success(RecordedTake(id, 60_000)), recorder.autoStop())
    }

    @Test
    fun `losing audio focus to a call stops the take and keeps it`() {
        val recorder = recorder()
        val id = started(recorder)
        clock.advanceBy(12.seconds)

        focus.lose()

        assertEquals(Outcome.Success(RecordedTake(id, 12_000)), recorder.autoStop())
        assertFalse(focus.held)
    }

    @Test
    fun `the screen turning off stops the take and keeps it`() {
        val recorder = recorder()
        val id = started(recorder)
        clock.advanceBy(5.seconds)

        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle()

        assertEquals(Outcome.Success(RecordedTake(id, 5_000)), recorder.autoStop())
        // The receiver is gone with the take: another screen off changes nothing.
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        idle()
        assertTrue(engines.single().released)
    }

    @Test
    fun `the app going to the background stops the take, and a short one is reported too short`() {
        val recorder = recorder()
        started(recorder)
        clock.advanceBy(400.milliseconds)

        foreground.set(false)

        assertEquals(Outcome.Failure(DomainError.RecordingTooShort), recorder.autoStop())
        assertTrue(folder.listFiles().isNullOrEmpty(), "the short take is deleted")
    }

    @Test
    fun `a recorder error ends the take with its error code, deletes the file and logs no path`() {
        val recorder = recorder()
        val id = started(recorder)
        clock.advanceBy(5.seconds)

        engines.single().fail(what = 100)

        assertEquals(Outcome.Failure(DomainError.RecorderFailure("recorder error 100")), recorder.autoStop())
        assertFalse(takeFile(id).exists())
        assertEquals(
            listOf<LogEvent>(LogEvent.OperationFailed(AndroidAudioRecorder.RECORD, "recorder failure: recorder error 100")),
            logger.events,
        )
        assertNoPathsLogged()
    }

    @Test
    fun `a stop that fails after 1 s is a recorder failure and deletes the file`() {
        nextEngine = { FakeEngine(stopFailure = RuntimeException("stop failed for /data/secret.m4a")) }
        val recorder = recorder()
        val id = started(recorder)
        clock.advanceBy(3.seconds)

        assertEquals(Outcome.Failure(DomainError.RecorderFailure("stop: RuntimeException")), runBlocking { recorder.stop() })
        assertFalse(takeFile(id).exists())
        assertNoPathsLogged()
    }

    @Test
    fun `without the permission the microphone is unavailable, and it records once granted`() {
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_MICROPHONE, true)
        val recorder = platformChecks()

        assertEquals(Outcome.Failure(DomainError.MicrophoneUnavailable), recorder.start())
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed(AndroidAudioRecorder.START, "no permission")), logger.events)
        assertTrue(engines.isEmpty())
        assertFalse(focus.held)

        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        started(recorder)
    }

    @Test
    fun `a phone without a microphone, or an app not in front, cannot record`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_MICROPHONE, false)
        assertEquals(Outcome.Failure(DomainError.MicrophoneUnavailable), platformChecks().start())

        foreground.set(false)
        assertEquals(Outcome.Failure(DomainError.MicrophoneUnavailable), recorder().start())

        assertEquals(
            listOf("no microphone", "app in background"),
            logger.events.map { (it as LogEvent.OperationFailed).cause },
        )
    }

    @Test
    fun `refused audio focus (a call) means the microphone is unavailable`() {
        focus.grant = false

        assertEquals(Outcome.Failure(DomainError.MicrophoneUnavailable), recorder().start())
        assertTrue(engines.isEmpty())
    }

    @Test
    fun `too little free space is a storage failure and nothing starts`() {
        free = 100_000

        assertEquals(Outcome.Failure(DomainError.StorageFailure("storage full")), recorder().start())
        assertTrue(engines.isEmpty())
        assertFalse(focus.held)
    }

    @Test
    fun `a start that fails deletes the temporary file and maps the failure`() {
        nextEngine = { FakeEngine(startFailure = IOException("cannot open /data/secret")) }
        assertEquals(Outcome.Failure(DomainError.StorageFailure("start: IOException")), recorder().start())
        nextEngine = { FakeEngine(startFailure = SecurityException("mic")) }
        assertEquals(Outcome.Failure(DomainError.MicrophoneUnavailable), recorder().start())
        nextEngine = { FakeEngine(startFailure = IllegalStateException("bad state")) }
        assertEquals(Outcome.Failure(DomainError.RecorderFailure("start: IllegalStateException")), recorder().start())

        assertTrue(folder.listFiles().isNullOrEmpty(), "no temporary file is left")
        assertTrue(engines.all { it.released })
        assertFalse(focus.held)
        assertNoPathsLogged()
    }

    @Test
    fun `a second start while recording is refused and the take goes on`() {
        val recorder = recorder()
        val id = started(recorder)

        assertEquals(Outcome.Failure(DomainError.RecorderFailure("already recording")), recorder.start())
        assertTrue(takeFile(id).isFile)
        assertEquals(1, engines.size)
    }

    @Test
    fun `a start deletes takes left by a crash, cancel deletes the take and discard deletes a finished one`() {
        folder.mkdirs()
        val stale = takeFile("stale").apply { writeText("left by a crash") }
        val recorder = recorder()

        val cancelled = started(recorder)
        assertFalse(stale.exists())
        recorder.cancel()
        assertFalse(takeFile(cancelled).exists())
        assertTrue(engines.single().released)
        assertFalse(focus.held)
        recorder.cancel()

        val kept = started(recorder)
        clock.advanceBy(2.seconds)
        val take = assertIs<Outcome.Success<RecordedTake>>(runBlocking { recorder.stop() }).value
        assertEquals(kept, take.takeId)
        runBlocking { recorder.discard(take) }
        assertFalse(takeFile(kept).exists())
        runBlocking { recorder.discard(RecordedTake("../odd", 2_000)) }
    }

    @Test
    fun `a later start keeps a finished take that still waits for Save, and only the first start cleans up`() {
        val recorder = recorder()
        started(recorder)
        clock.advanceBy(3.seconds)
        val waiting = assertIs<Outcome.Success<RecordedTake>>(runBlocking { recorder.stop() }).value
        val leftover = takeFile("crashed").apply { writeText("not cleaned twice") }

        started(recorder)

        assertTrue(takeFile(waiting.takeId).isFile, "the unsaved take is kept")
        assertTrue(leftover.isFile, "stale takes are cleaned once per process")
    }

    @Test
    fun `a stop that lands after the recorder stopped itself at 60 s keeps the full take`() {
        nextEngine = { FakeEngine(stopFailure = IllegalStateException("already stopped")) }
        val recorder = recorder()
        val id = started(recorder)
        clock.advanceBy(59_700.milliseconds)

        assertEquals(Outcome.Success(RecordedTake(id, 60_000)), runBlocking { recorder.stop() })
        assertTrue(takeFile(id).isFile)
    }

    @Test
    fun `the time the recorder takes to start is not counted, so a short take stays too short`() {
        nextEngine = { FakeEngine(onStart = { clock.advanceBy(400.milliseconds) }) }
        val recorder = recorder()
        started(recorder)
        clock.advanceBy(800.milliseconds)

        assertEquals(Outcome.Failure(DomainError.RecordingTooShort), runBlocking { recorder.stop() })
    }

    @Test
    fun `elapsed and level follow the take being recorded and are zero otherwise`() {
        val recorder = recorder()
        assertEquals(Duration.ZERO, runBlocking { recorder.elapsed().first() })
        assertEquals(0f, runBlocking { recorder.level().first() })

        started(recorder)
        clock.advanceBy(9.seconds)
        engines.single().amplitude = 16_384

        assertEquals(9.seconds, runBlocking { recorder.elapsed().first() })
        assertEquals(0.5f, runBlocking { recorder.level().first() }, 0.001f)
        clock.advanceBy(2.seconds.times(60))
        assertEquals(60.seconds, runBlocking { recorder.elapsed().first() }, "never above the limit")
    }

    @Test
    fun `the started-activity count reports the app going to the background and back`() {
        val counter = StartedActivities()
        val changes = mutableListOf<Boolean>()
        val listener: (Boolean) -> Unit = { changes += it }
        counter.addListener(listener)
        val first = Robolectric.buildActivity(android.app.Activity::class.java).get()
        val second = Robolectric.buildActivity(android.app.Activity::class.java).get()

        counter.onActivityStarted(first)
        counter.onActivityStarted(second)
        counter.onActivityStopped(first)
        assertTrue(counter.inForeground)
        counter.onActivityStopped(second)
        counter.onActivityStopped(second)
        counter.removeListener(listener)
        counter.onActivityStarted(first)

        assertEquals(listOf(true, false), changes)
        assertTrue(counter.inForeground)
    }

    @Test
    fun `a configuration change is not the app going to the background`() {
        val counter = StartedActivities()
        val changes = mutableListOf<Boolean>()
        counter.addListener { changes += it }
        val old = Robolectric.buildActivity(ConfigChangingActivity::class.java).get()
        val replacement = Robolectric.buildActivity(android.app.Activity::class.java).get()

        counter.onActivityStarted(old)
        counter.onActivityStopped(old)
        counter.onActivityStarted(replacement)

        assertEquals(listOf(true), changes, "no background between the old activity and its replacement")
        assertTrue(counter.inForeground)
    }

    @Test
    fun `the platform engine records the microphone as AAC in MPEG-4, mono, 44_1 kHz, 64 kbit per s, 60 s at most`() {
        folder.mkdirs()
        var created: MediaRecorder? = null
        val engine = MediaRecorderEngine(app) { MediaRecorder(app).also { created = it } }
        var maxReached = false
        var error: Int? = null

        engine.start(takeFile("t"), 60_000, onMaxDuration = { maxReached = true }, onError = { error = it })

        val shadow = Shadow.extract<ShadowMediaRecorder>(created)
        assertEquals(MediaRecorder.AudioSource.MIC, shadow.audioSource)
        assertEquals(MediaRecorder.OutputFormat.MPEG_4, shadow.outputFormat)
        assertEquals(MediaRecorder.AudioEncoder.AAC, shadow.audioEncoder)
        assertEquals(1, shadow.audioChannels)
        assertEquals(44_100, shadow.audioSamplingRate)
        assertEquals(64_000, shadow.audioEncodingBitRate)
        assertEquals(60_000, shadow.maxDuration)
        assertEquals(takeFile("t").absolutePath, shadow.outputPath)
        assertEquals(ShadowMediaRecorder.STATE_RECORDING, shadow.state)
        shadow.infoListener.onInfo(created, MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0)
        shadow.errorListener.onError(created, 1, 0)
        assertTrue(maxReached)
        assertEquals(1, error)
        engine.maxAmplitude()
        engine.stop()
        engine.release()
        assertEquals(0, engine.maxAmplitude())
        engine.stop()
    }

    @Test
    fun `the real audio focus is asked transiently and exclusively, reports a loss but not a duck, is given back and can be refused`() {
        val audio = app.getSystemService(AudioManager::class.java)
        val real = AndroidRecordingFocus(audio)
        var losses = 0

        assertTrue(real.request { losses++ })
        val request = shadowOf(audio).lastAudioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, request.durationHint)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0, losses, "a notification chime does not stop the take")
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(2, losses)
        real.abandon()
        assertEquals(request.audioFocusRequest, shadowOf(audio).lastAbandonedAudioFocusRequest)
        real.abandon()

        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(real.request {})
    }

    @Test
    fun `the free space check never fails a start by itself`() {
        folder.mkdirs()

        assertTrue(AndroidAudioRecorder.allocatableBytes(app, folder) > 0)
    }

    @Test
    fun `the app's graph binds the recorder and its use cases`() {
        val koin = GlobalContext.get()

        assertIs<AndroidAudioRecorder>(koin.get<AudioRecorder>())
        assertIs<AndroidRecordingFocus>(koin.get<RecordingFocus>())
        assertSame((app as YawnAndPawnApp).startedActivities, koin.get<AppForeground>(), "the app's registered counter")
        koin.get<com.yawnandpawn.app.core.recording.SaveRecording>()
        koin.get<com.yawnandpawn.app.core.recording.ReRecord>()
        koin.get<com.yawnandpawn.app.core.recording.DeleteRecording>()
    }

    /** A take that writes a few bytes when started; [reachMaxDuration] and [fail] call its listeners. */
    private class FakeEngine(
        private val startFailure: Exception? = null,
        private val stopFailure: Exception? = null,
        private val onStart: () -> Unit = {},
    ) : RecorderEngine {
        var output: File? = null
        var maxDurationMs = 0
        var amplitude = 0
        var stopped = false
        var released = false
        private var onMax: () -> Unit = {}
        private var onError: (Int) -> Unit = {}

        override fun start(
            output: File,
            maxDurationMs: Int,
            onMaxDuration: () -> Unit,
            onError: (Int) -> Unit,
        ) {
            this.output = output
            this.maxDurationMs = maxDurationMs
            onMax = onMaxDuration
            this.onError = onError
            output.writeText("aac")
            onStart()
            startFailure?.let { throw it }
        }

        override fun maxAmplitude(): Int = amplitude

        override fun stop() {
            stopped = true
            stopFailure?.let { throw it }
        }

        override fun release() {
            released = true
        }

        fun reachMaxDuration() = onMax()

        fun fail(what: Int) = onError(what)
    }

    private class FakeForeground : AppForeground {
        val listeners = mutableListOf<(Boolean) -> Unit>()
        override var inForeground: Boolean = true
            private set

        fun set(value: Boolean) {
            inForeground = value
            listeners.toList().forEach { it(value) }
        }

        override fun addListener(listener: (Boolean) -> Unit) {
            listeners += listener
        }

        override fun removeListener(listener: (Boolean) -> Unit) {
            listeners -= listener
        }
    }

    private class FakeFocus : RecordingFocus {
        var grant = true
        var held = false
        private var onLoss: () -> Unit = {}

        override fun request(onLoss: () -> Unit): Boolean {
            this.onLoss = onLoss
            held = grant
            return grant
        }

        override fun abandon() {
            held = false
        }

        fun lose() = onLoss()
    }
}

/** An activity being stopped for a configuration change (Robolectric instantiates it). */
class ConfigChangingActivity : android.app.Activity() {
    override fun isChangingConfigurations(): Boolean = true
}
