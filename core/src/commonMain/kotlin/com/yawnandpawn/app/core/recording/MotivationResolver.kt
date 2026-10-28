package com.yawnandpawn.app.core.recording

import com.yawnandpawn.app.core.alarm.Motivation
import com.yawnandpawn.app.core.alarm.MotivationMode
import com.yawnandpawn.app.core.checks.SeedDeriver
import com.yawnandpawn.app.core.checks.SeededRandom
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.SessionConfig
import com.yawnandpawn.app.core.session.SessionData
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one place for the motivation rules of a session (Story 7.3). The alarm's motivation is resolved once, where the
 * session starts (a real or a test fire), and the result is frozen into its [SessionConfig], which the session stores:
 * a restored session therefore plays the same message, and the reducer stays pure.
 *
 * - A session that starts before the first unlock after a boot has no message ([Motivation.None]) for its whole length,
 *   also after the unlock: the audio lives in credential-protected storage.
 * - [Motivation.Random] becomes one of the recordings whose file is there, ordered oldest first (`createdAt`, then
 *   `id`), picked with the session seed ([SeedDeriver.MOTIVATION]); with none usable it is [Motivation.None].
 * - [Motivation.Recording] and [Motivation.None] stay as they are (a recording whose row or file is gone by the end of
 *   the session plays nothing then).
 */
object MotivationResolver {
    /** How long a fire may wait for the recordings list; a slower or failing read freezes [Motivation.None]. */
    val READ_TIMEOUT: Duration = 500.milliseconds

    /** The `OperationFailed` operation of a recordings read that failed or timed out. */
    const val OPERATION = "resolve motivation"

    /** [motivation] as the session [sessionId] freezes it, picking from [recordings] (pure). */
    fun resolve(
        motivation: Motivation,
        sessionId: String,
        recordings: List<Recording>,
        beforeFirstUnlock: Boolean,
    ): Motivation =
        when {
            beforeFirstUnlock -> Motivation.None
            motivation is Motivation.Random -> pick(sessionId, recordings)
            else -> motivation
        }

    /**
     * [config] with its motivation resolved for the session [sessionId] ([resolve]). The [repository] is read only for
     * [Motivation.Random] and not before the first unlock, for at most [READ_TIMEOUT]: a failure or a timeout freezes
     * [Motivation.None] and is logged (never a name or a path). Never throws but cancellation.
     */
    suspend fun freeze(
        config: SessionConfig,
        sessionId: String,
        beforeFirstUnlock: Boolean,
        repository: RecordingRepository,
        logger: Logger,
    ): SessionConfig {
        val resolved = freeze(config.motivation, sessionId, beforeFirstUnlock, repository, logger)
        return if (resolved == config.motivation) config else config.copy(motivation = resolved)
    }

    /**
     * [motivation] resolved for the session [sessionId], as [freeze] of a config does: a fire can read it beside its
     * settings, so the two reads never add up before the alarm sounds.
     */
    suspend fun freeze(
        motivation: Motivation,
        sessionId: String,
        beforeFirstUnlock: Boolean,
        repository: RecordingRepository,
        logger: Logger,
    ): Motivation {
        val recordings =
            if (!beforeFirstUnlock && motivation is Motivation.Random) {
                read(repository, logger) ?: return Motivation.None
            } else {
                emptyList()
            }
        return resolve(motivation, sessionId, recordings, beforeFirstUnlock)
    }

    /**
     * The recording id the completed [session] plays on Success: its frozen [Motivation.Recording], never for a session
     * that started before the first unlock, never for a `Random` stored unresolved (before Story 7.3), and never when
     * the message is mixed into the ring instead ([MotivationMode.MixIntoAlarm], Story 7.5).
     */
    fun playableRecordingId(session: SessionData): String? =
        recordingId(session).takeIf { session.config.motivationMode == MotivationMode.AfterImUp }

    /**
     * The recording id the rings of [session] mix into the alarm sound (Story 7.5, `mixSchedule`): its frozen
     * [Motivation.Recording] when the mode is [MotivationMode.MixIntoAlarm], never for a session that started before the
     * first unlock, nor for `None` or an unresolved `Random`.
     */
    fun mixRecordingId(session: SessionData): String? =
        recordingId(session).takeIf { session.config.motivationMode == MotivationMode.MixIntoAlarm }

    private fun recordingId(session: SessionData): String? =
        (session.config.motivation as? Motivation.Recording)?.id?.takeUnless { session.startedBeforeUnlock }

    private fun pick(
        sessionId: String,
        recordings: List<Recording>,
    ): Motivation {
        val usable = recordings.filterNot { it.missing }.sortedWith(compareBy<Recording>({ it.createdAt }, { it.id }))
        if (usable.isEmpty()) return Motivation.None
        val seed = SeedDeriver.seed(sessionId, ringIndex = 0, entryIndex = SeedDeriver.MOTIVATION, attempt = 0)
        return Motivation.Recording(usable[SeededRandom(seed).nextInt(usable.indices)].id)
    }

    // Whatever the repository throws means "no list": the ring must start anyway.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun read(
        repository: RecordingRepository,
        logger: Logger,
    ): List<Recording>? =
        try {
            withTimeoutOrNull(READ_TIMEOUT) { repository.observeAll().first() }
                ?: null.also { logger.log(LogEvent.OperationFailed(OPERATION, "timed out")) }
        } catch (e: CancellationException) {
            // Only the caller's own cancellation ends the fire; one the repository throws while it runs is a failed read.
            currentCoroutineContext().ensureActive()
            null.also { logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName ?: "CancellationException")) }
        } catch (e: Exception) {
            null.also { logger.log(LogEvent.OperationFailed(OPERATION, e::class.simpleName ?: "Exception")) }
        }
}
