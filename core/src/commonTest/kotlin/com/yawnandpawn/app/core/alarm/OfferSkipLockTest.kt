package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.config.InMemoryGlobalSettings
import com.yawnandpawn.app.core.config.InMemoryPendingChanges
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PromotePendingChanges
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.onboarding.InMemoryOnboarding
import com.yawnandpawn.app.core.session.GlobalSettings
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 7.11: "Offer to skip 2 h before" under the commitment lock. Turning it on within 8 h of the alarm waits for its
 * next ring (a weakening `PendingChange`, no pre-alarm job until promoted); turning it off applies at once and cancels the
 * job and the notification. Now is Monday 23:40 UTC; the daily alarm rings at 07:30.
 */
class OfferSkipLockTest {
    private val now = Instant.parse("2027-03-08T23:40:00Z")
    private val sevenThirty = Instant.parse("2027-03-09T07:30:00Z")
    private val f = SkipFixture(now)
    private val pending = InMemoryPendingChanges()
    private val save =
        SaveAlarm(
            f.alarms,
            SequentialIds(),
            f.clock,
            f.lock,
            InMemorySequence(),
            f.scheduling,
            f.sessionLock,
            InMemoryCheckConfigs(f.alarms),
            pending,
            f.zones,
            InMemoryOnboarding(),
        )
    private val draft = AlarmDraft(time = LocalTime(7, 30), repeatDays = DayOfWeek.entries.toSet())

    private suspend fun saved(draft: AlarmDraft): AlarmSaved = (save.save(draft) as Outcome.Success).value

    @Test
    fun `a new alarm stores the switch as it is`() =
        runTest {
            val stored = saved(draft.copy(offerSkip = true)).alarm

            assertTrue(stored.offerSkip)
            assertTrue(pending.changes.value.isEmpty())
            assertEquals(sevenThirty - 2.hours - now, f.work.pending.getValue(stored.id))
        }

    @Test
    fun `turning it on within 8 h waits for the next ring, with no job until it is promoted`() =
        runTest {
            val id = saved(draft).alarm.id

            val edited = saved(draft.copy(id = id, offerSkip = true))

            assertFalse(edited.alarm.offerSkip, "the row keeps the live value")
            assertEquals(Occurrence(id, sevenThirty), edited.pendingUntil)
            assertEquals(
                listOf(PendingChange(id, SettingValue.OfferSkip(true), Occurrence(id, sevenThirty))),
                pending.changes.value,
            )
            assertFalse(id in f.work.pending, "no pre-alarm job while it waits")

            // After the ring (and the settle delay) the change is promoted and the job follows at once.
            f.clock.now = sevenThirty + 40.minutes
            val promote =
                PromotePendingChanges(
                    pending,
                    InMemoryGlobalSettings(GlobalSettings()),
                    f.alarms,
                    InMemoryCheckConfigs(f.alarms),
                    f.clock,
                    f.lock,
                    f.logger,
                    preAlarms = f.preAlarms,
                ) { null }
            assertEquals(Outcome.Success(1), promote())
            assertTrue(f.stored(id).offerSkip)
            assertTrue(pending.changes.value.isEmpty())
            assertEquals(Instant.parse("2027-03-10T05:30:00Z") - f.clock.now, f.work.pending.getValue(id))
        }

    @Test
    fun `turning it on outside the window applies at once`() =
        runTest {
            f.clock.now = Instant.parse("2027-03-08T12:00:00Z")
            val id = saved(draft).alarm.id

            val edited = saved(draft.copy(id = id, offerSkip = true))

            assertTrue(edited.alarm.offerSkip)
            assertNull(edited.pendingUntil)
            assertTrue(id in f.work.pending)
        }

    @Test
    fun `turning it off applies at once and cancels the job and the notification, also inside the window`() =
        runTest {
            f.clock.now = Instant.parse("2027-03-08T12:00:00Z")
            val id = saved(draft.copy(offerSkip = true)).alarm.id
            f.clock.now = now
            f.notifier.showing[f.stored(id).requestCode] = 1.hours

            val edited = saved(draft.copy(id = id, offerSkip = false))

            assertFalse(edited.alarm.offerSkip)
            assertNull(edited.pendingUntil)
            assertFalse(id in f.work.pending)
            assertTrue(f.notifier.showing.isEmpty())
        }

    @Test
    fun `turning it back off cancels the pending change`() =
        runTest {
            val id = saved(draft).alarm.id
            saved(draft.copy(id = id, offerSkip = true))

            saved(draft.copy(id = id, offerSkip = false))

            assertTrue(pending.changes.value.none { it.field == LockedField.OfferSkip })
        }

    @Test
    fun `an edit keeps the skip of a repeating alarm and drops it for a one-time one`() =
        runTest {
            f.clock.now = Instant.parse("2027-03-08T12:00:00Z")
            val id = saved(draft).alarm.id
            f.skip(id)
            val skipped = f.stored(id).skippedDate

            assertEquals(skipped, saved(draft.copy(id = id, label = "Gym")).alarm.skippedDate)
            assertNull(saved(draft.copy(id = id, repeatDays = emptySet())).alarm.skippedDate)
        }

    @Test
    fun `a duplicate never copies the skip`() =
        runTest {
            f.clock.now = Instant.parse("2027-03-08T12:00:00Z")
            val id = saved(draft).alarm.id
            f.skip(id)

            val copy =
                DuplicateAlarm(
                    f.alarms,
                    IdGenerator { "copy" },
                    f.clock,
                    f.lock,
                    InMemorySequence(lastUsed = 2_000),
                    f.scheduling,
                    f.sessionLock,
                    InMemoryCheckConfigs(f.alarms),
                )(id)

            assertNull((copy as Outcome.Success).value.skippedDate)
        }
}
