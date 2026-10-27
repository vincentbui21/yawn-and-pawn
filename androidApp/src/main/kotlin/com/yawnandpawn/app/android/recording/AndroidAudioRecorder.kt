package com.yawnandpawn.app.android.recording

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.recording.AudioRecorder
import com.yawnandpawn.app.core.recording.RecordedTake
import com.yawnandpawn.app.core.recording.Recording
import com.yawnandpawn.app.core.recording.RecordingSession
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.data.recording.RecordingFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [AudioRecorder] over `MediaRecorder` (Story 7.1, through [RecorderEngine]): MIC, MPEG-4/AAC, mono, 44.1 kHz,
 * 64 kbit/s, at most 60 s. A take records into `recordings/take-{takeId}.m4a.tmp` ([RecordingFiles]); the repository
 * renames it on save. Takes left by a crash are deleted at the first [start] of the process.
 *
 * - **Stops by itself, keeping the take** (sent on [autoStopped]): at 60 s (the max-duration listener), when the app has
 *   no started activity left ([AppForeground]), when audio focus is lost (a call, [RecordingFocus]) and when the screen
 *   turns off (`ACTION_SCREEN_OFF`, registered only while recording).
 * - **Failures** delete the take's file: under 1 s is `RecordingTooShort`; no permission, no microphone, the app not
 *   in front or focus refused is `MicrophoneUnavailable`; too little free space or an output file that cannot be opened
 *   is `StorageFailure`; anything else (a recorder error code, a failed start or stop) is `RecorderFailure`. Only these
 *   codes are logged, never a file name or path.
 * - Elapsed time comes from [MonotonicClock]; the level is the recorder's max amplitude, polled about 10 times a second.
 *
 * Thread-safe: the UI calls it on the main thread, the recorder's listeners and [stop] may run elsewhere.
 */
@Suppress("TooManyFunctions")
class AndroidAudioRecorder(
    private val context: Context,
    private val files: RecordingFiles,
    private val ids: IdGenerator,
    private val monotonic: MonotonicClock,
    private val foreground: AppForeground,
    private val focus: RecordingFocus,
    private val logger: Logger,
    private val newEngine: () -> RecorderEngine = { MediaRecorderEngine(context) },
    private val hasPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    },
    private val hasMicrophone: () -> Boolean = { context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) },
    private val freeBytes: (File) -> Long = { allocatableBytes(context, it) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AudioRecorder {
    /** The take being recorded and what it registered. */
    private class Active(
        val takeId: String,
        val file: File,
        val engine: RecorderEngine,
        val startedAt: Long,
        val screenOff: BroadcastReceiver,
        val onForeground: (Boolean) -> Unit,
    )

    /** Why a take ended. */
    private sealed interface Ending {
        data object User : Ending

        data object MaxDuration : Ending

        data class Auto(
            val reason: String,
        ) : Ending

        data class Error(
            val what: Int,
        ) : Ending
    }

    private val lock = Any()
    private var active: Active? = null

    // Unlimited, so no auto-stopped take is dropped unseen (its file would never be discarded).
    private val autoStops = Channel<Outcome<RecordedTake, DomainError>>(Channel.UNLIMITED)

    // Takes left by a crash are cleaned once per process: a later start must keep a finished take waiting for Save.
    private var staleTakesCleaned = false

    @Suppress("TooGenericExceptionCaught")
    override fun start(): Outcome<RecordingSession, DomainError> =
        synchronized(lock) {
            if (active != null) return@synchronized refuse(DomainError.RecorderFailure("already recording"), "already recording")
            unavailableReason()?.let { return@synchronized refuse(DomainError.MicrophoneUnavailable, it) }
            if (!staleTakesCleaned) {
                files.deleteStaleTakes()
                staleTakesCleaned = true
            }
            val takeId = ids.newId()
            val file =
                try {
                    files.takeFile(takeId).also { it.parentFile?.mkdirs() }
                } catch (e: RuntimeException) {
                    // Storage locked (before the first unlock) or a take id that names no file.
                    return@synchronized refuse(DomainError.StorageFailure("recordings folder: ${e::class.simpleName}"), null)
                }
            if (freeBytes(file.parentFile ?: file) < MIN_FREE_BYTES) {
                return@synchronized refuse(DomainError.StorageFailure("storage full"), null)
            }
            if (!focus.request(onLoss = { autoStop(takeId, Ending.Auto("audio focus lost")) })) {
                return@synchronized refuse(DomainError.MicrophoneUnavailable, "audio focus refused")
            }
            begin(takeId, file)
        }

    override suspend fun stop(): Outcome<RecordedTake, DomainError> =
        withContext(io) {
            synchronized(lock) {
                val current = active ?: return@synchronized Outcome.Failure(DomainError.RecorderFailure("not recording"))
                finish(current, Ending.User)
            }
        }

    override fun cancel() {
        synchronized(lock) {
            val current = active ?: return
            active = null
            release(current)
            stopQuietly(current.engine)
            current.engine.release()
            current.file.delete()
        }
    }

    override fun level(): Flow<Float> =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(currentLevel())
                delay(POLL_INTERVAL)
            }
        }

    override fun elapsed(): Flow<Duration> =
        flow {
            while (currentCoroutineContext().isActive) {
                emit(synchronized(lock) { active?.let { elapsedMillis(it).milliseconds } } ?: Duration.ZERO)
                delay(POLL_INTERVAL)
            }
        }

    override fun autoStopped(): Flow<Outcome<RecordedTake, DomainError>> = autoStops.receiveAsFlow()

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override suspend fun discard(take: RecordedTake) {
        withContext(io) {
            try {
                files.takeFile(take.takeId).delete()
            } catch (e: Exception) {
                // An invalid take id names no file; a locked folder is cleaned at the next start.
            }
        }
    }

    /** Why the microphone cannot record now, or null. */
    private fun unavailableReason(): String? =
        when {
            !hasPermission() -> "no permission"
            !hasMicrophone() -> "no microphone"
            !foreground.inForeground -> "app in background"
            else -> null
        }

    // MediaRecorder reports a microphone it cannot open, a file it cannot write and a bad state with several exception
    // types; each means "this take cannot start".
    @Suppress("TooGenericExceptionCaught")
    private fun begin(
        takeId: String,
        file: File,
    ): Outcome<RecordingSession, DomainError> {
        val engine = newEngine()
        try {
            engine.start(
                output = file,
                maxDurationMs = Recording.MAX_DURATION_MS.toInt(),
                onMaxDuration = { autoStop(takeId, Ending.MaxDuration) },
                onError = { what -> autoStop(takeId, Ending.Error(what)) },
            )
        } catch (e: Exception) {
            engine.release()
            file.delete()
            focus.abandon()
            val cause = "start: ${e::class.simpleName}"
            val error =
                when (e) {
                    is SecurityException -> DomainError.MicrophoneUnavailable
                    is IOException -> DomainError.StorageFailure(cause)
                    else -> DomainError.RecorderFailure(cause)
                }
            return refuse(error, cause)
        }
        // Read once the recorder runs: prepare and start can take hundreds of milliseconds, which are not recorded.
        val startedAt = monotonic.elapsedMillis()
        val screenOff =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action == Intent.ACTION_SCREEN_OFF) autoStop(takeId, Ending.Auto("screen off"))
                }
            }
        val onForeground: (Boolean) -> Unit = { inFront -> if (!inFront) autoStop(takeId, Ending.Auto("app in background")) }
        // A system broadcast still arrives with RECEIVER_NOT_EXPORTED; nothing else may send to this receiver.
        ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        foreground.addListener(onForeground)
        active = Active(takeId, file, engine, startedAt, screenOff, onForeground)
        return Outcome.Success(RecordingSession(takeId))
    }

    /** The take [takeId] ends by itself; its take or error goes to [autoStopped]. Nothing when it already ended. */
    private fun autoStop(
        takeId: String,
        ending: Ending,
    ) {
        val result =
            synchronized(lock) {
                val current = active?.takeIf { it.takeId == takeId } ?: return
                finish(current, ending)
            }
        autoStops.trySend(result)
    }

    /** Ends [current]: the take, or its failure with the file deleted. Called with [lock] held. */
    private fun finish(
        current: Active,
        ending: Ending,
    ): Outcome<RecordedTake, DomainError> {
        active = null
        release(current)
        val elapsed = elapsedMillis(current)
        // At the limit the recorder may have stopped itself before its info event arrived, so its stop throws: a written
        // take is still the full one.
        val atLimit = ending == Ending.MaxDuration || elapsed >= Recording.MAX_DURATION_MS - LIMIT_SLACK_MS
        val duration = if (atLimit) Recording.MAX_DURATION_MS else elapsed
        val stopFailure = if (ending is Ending.Error) null else stopQuietly(current.engine)
        current.engine.release()
        val written = current.file.isFile && current.file.length() > 0
        val error =
            when {
                ending is Ending.Error -> DomainError.RecorderFailure("recorder error ${ending.what}")
                duration < Recording.MIN_DURATION_MS -> DomainError.RecordingTooShort
                stopFailure != null && !(atLimit && written) -> DomainError.RecorderFailure("stop: $stopFailure")
                !written -> DomainError.RecorderFailure("empty take")
                else -> null
            }
        if (error == null) return Outcome.Success(RecordedTake(current.takeId, duration))
        current.file.delete()
        if (error != DomainError.RecordingTooShort) logger.log(LogEvent.OperationFailed.of(RECORD, error))
        return Outcome.Failure(error)
    }

    /** Unregisters what [current] registered and gives audio focus back. */
    @Suppress("SwallowedException")
    private fun release(current: Active) {
        foreground.removeListener(current.onForeground)
        try {
            context.unregisterReceiver(current.screenOff)
        } catch (e: IllegalArgumentException) {
            // Already unregistered.
        }
        focus.abandon()
    }

    /** Stops [engine]; the exception class when it throws (a take with no usable audio, or a recorder already stopped). */
    @Suppress("TooGenericExceptionCaught")
    private fun stopQuietly(engine: RecorderEngine): String? =
        try {
            engine.stop()
            null
        } catch (e: Exception) {
            e::class.simpleName ?: "exception"
        }

    private fun elapsedMillis(current: Active): Long =
        (monotonic.elapsedMillis() - current.startedAt).coerceIn(0, Recording.MAX_DURATION_MS)

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun currentLevel(): Float =
        synchronized(lock) {
            try {
                ((active?.engine?.maxAmplitude() ?: 0) / MAX_AMPLITUDE).coerceIn(0f, 1f)
            } catch (e: Exception) {
                0f
            }
        }

    /** Logs why the start was refused (a code only, null: the error's own text) and returns [error]. */
    private fun refuse(
        error: DomainError,
        reason: String?,
    ): Outcome<RecordingSession, DomainError> {
        logger.log(if (reason == null) LogEvent.OperationFailed.of(START, error) else LogEvent.OperationFailed(START, reason))
        return Outcome.Failure(error)
    }

    companion object {
        const val START = "start recording"
        const val RECORD = "record message"

        /** A full 60 s take at 64 kbit/s is about 480 KB; with less free space than this a take does not start. */
        const val MIN_FREE_BYTES = 1_000_000L

        /**
         * The bytes a file in [folder] can still take, cached data the system can clear included; unknown (any
         * failure) reads as plenty, and a recorder that then cannot write fails on its own.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun allocatableBytes(
            context: Context,
            folder: File,
        ): Long =
            try {
                val storage = context.getSystemService(StorageManager::class.java)
                storage.getAllocatableBytes(storage.getUuidForPath(folder))
            } catch (e: Exception) {
                Long.MAX_VALUE
            }

        /** A take this close to 60 s counts as stopped by the limit. */
        const val LIMIT_SLACK_MS = 500L

        private const val MAX_AMPLITUDE = 32_767f
        private val POLL_INTERVAL = 100.milliseconds
    }
}
