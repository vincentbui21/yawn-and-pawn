package com.yawnandpawn.app.core.session

import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmScheduler
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Port for the one pending test ring (Story 1.18): the config the next test-alarm fire rings with, kept in
 * device-protected storage by `:data` so a fire before the first unlock can read it.
 */
interface TestAlarmStore {
    /** Replaces the pending config with [config]. */
    suspend fun put(config: SessionConfig): Outcome<Unit, DomainError>

    /** The pending config, cleared in the same step; null when none is pending. */
    suspend fun take(): Outcome<SessionConfig?, DomainError>
}

/**
 * "Test alarm" (FR-ALM-12): rings the editor's current, possibly unsaved, values as a test [delay] from now, through
 * the test request code of [scheduler]. The config ([ConfigResolver.resolveTest], `testMode`) is stored first, so the
 * fire always finds it; if arming fails it is taken back and the failure returned. A second test replaces the first.
 * Returns when it will ring.
 */
class ScheduleTestAlarm(
    private val scheduler: AlarmScheduler,
    private val store: TestAlarmStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        draft: AlarmDraft,
        delay: Duration = TEST_DELAY,
        globalSettings: GlobalSettings = GlobalSettings(),
    ): Outcome<Instant, DomainError> {
        val ringsAt = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds() + delay.inWholeMilliseconds)
        val stored = store.put(ConfigResolver.resolveTest(draft, globalSettings, ringsAt))
        if (stored is Outcome.Failure) return stored
        return when (val armed = scheduler.scheduleTest(ringsAt.toEpochMilliseconds())) {
            is Outcome.Success -> {
                Outcome.Success(ringsAt)
            }

            is Outcome.Failure -> {
                store.take()
                armed
            }
        }
    }

    companion object {
        /** "We'll ring in 10 seconds." */
        val TEST_DELAY: Duration = 10.seconds
    }
}
