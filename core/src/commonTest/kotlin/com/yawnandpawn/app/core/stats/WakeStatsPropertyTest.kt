package com.yawnandpawn.app.core.stats

import com.yawnandpawn.app.core.history.SessionHistoryRow
import com.yawnandpawn.app.core.history.SessionOutcome
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Story 6.1: over 1,000 seeded random histories, `bestStreak ≥ currentStreak ≥ 0`, and a row that is never counted
 * (Test, Skipped or still running) changes no number.
 */
class WakeStatsPropertyTest {
    private val start = LocalDate(2027, 1, 1)
    private val outcomes = listOf(*SessionOutcome.entries.toTypedArray(), null)
    private val neutral = listOf(SessionOutcome.Test, SessionOutcome.Skipped, null)

    @Test
    fun `random histories keep the streak invariants and ignore rows that never count`() {
        val random = Random(SEED)
        repeat(HISTORIES) { n ->
            val history = List(random.nextInt(MAX_ROWS + 1)) { randomSession(random, random.nextInt(DAYS)) }
            val today = start.day(random.nextInt(-5, DAYS + 5))
            val current = currentStreak(history, today, StatsZone)
            val best = bestStreak(history, StatsZone)

            assertTrue(current >= 0, "history $n: current $current")
            assertTrue(best >= current, "history $n: best $best < current $current")

            val extra = randomSession(random, random.nextInt(-2, DAYS + 2), neutral[random.nextInt(neutral.size)])
            val withExtra = (history + extra).shuffled(random)
            assertEquals(current, currentStreak(withExtra, today, StatsZone), "history $n: current with $extra")
            assertEquals(best, bestStreak(withExtra, StatsZone), "history $n: best with $extra")
            assertEquals(numbers(history, today), numbers(withExtra, today), "history $n: rates with $extra")
        }
    }

    private fun numbers(
        history: List<SessionHistoryRow>,
        today: LocalDate,
    ) = listOf(
        onTimeRate(history, today, StatsZone, WakeStats.RATE_DAYS_SHORT),
        onTimeRate(history, today, StatsZone, WakeStats.RATE_DAYS_LONG),
        averageTimeToUp(history, today, StatsZone),
    )

    private fun randomSession(
        random: Random,
        dayOffset: Int,
        outcome: SessionOutcome? = outcomes[random.nextInt(outcomes.size)],
    ): SessionHistoryRow =
        aSession(
            day = start.day(dayOffset),
            outcome = outcome,
            at = LocalTime(random.nextInt(24), random.nextInt(60)),
            timeToUp = if (random.nextInt(10) == 0) null else random.nextInt(MAX_TIME_TO_UP_S).seconds,
        )

    private companion object {
        const val SEED = 61
        const val HISTORIES = 1_000
        const val MAX_ROWS = 40
        const val DAYS = 60
        const val MAX_TIME_TO_UP_S = 1_800
    }
}
