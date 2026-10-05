# Which clock re-arms alarms after a reboot

- **Date:** 2026-10-01 (Story 1.10)
- **Question:** after a reboot the monotonic clock restarts at 0, and the wall clock may still be wrong until network time arrives. Which time does `rescheduleAll()` use to re-arm the alarms?
- **Decision:** the wall clock, as AD-3 says. `rescheduleAll()` (on `BOOT_COMPLETED`, and also on app start) computes each alarm's next occurrence from `Clock` (wall time) and `TimeZoneProvider`, and `AndroidAlarmScheduler` arms it with `AlarmManager.setAlarmClock()`, which is wall-clock based (`RTC_WAKEUP`). Story 1.10 adds no extra logic for a wrong clock.

## Why

- An alarm is a local wall-clock time ("07:00 on weekdays"). Only wall time and the time zone can say when that is; monotonic time has no meaning across a reboot.
- `setAlarmClock()` takes wall time, and the system moves the armed alarm when the wall clock changes. When network time corrects the clock, Android sends `TIME_SET`, and `SystemEventsReceiver` runs `rescheduleAll()` again, so the alarm is recomputed from the corrected clock.
- Session deadlines are different: a `Deadline` compares monotonic time within its boot and falls back to its stored wall time after a reboot (`Deadline.remaining`). `armSessionSlot` converts a deadline to wall time only when arming (AD-3).

## Known gap (carried to Story 2.2)

If the wall clock is wrong right after a reboot (for example a phone with a flat RTC battery boots at a default date, or boots offline), the alarm is armed against the wrong "now":

- **Early or late ring:** the occurrence is computed for the wrong day or the wrong offset, so the alarm can ring early or late, or the next occurrence can be skipped, until the clock is corrected.
- **Recovery:** when network time arrives, `TIME_SET` re-runs `rescheduleAll()`, and the alarm is armed correctly from then on. A ring that already happened at the wrong time is not undone.
- **No network at all:** the clock stays wrong and so does the alarm, the same as every other alarm clock app on that phone.

Story 2.2 (session deadlines survive clock changes and reboots) decides whether this needs more, for example checking whether the clock is trustworthy before arming, or a warning. Story 1.10 adds nothing beyond the re-run on `TIME_SET`.

## Story 2.2 decision (2026-10-05)

- **Decision:** no extra trust check on the wall clock after a reboot. The existing re-run on `TIME_SET` is enough, and since Story 2.2 it also re-arms the session slot (`SessionSlotRearm.afterSystemEvent`).
- **Session after a reboot:** a restored Ringing, Grace or Loud session rings at once (slot at wall now + 1 s, then a fresh 30-minute deadline), whatever the clock says, so a wrong clock can never silence it. A snooze compares its stored end on wall time (AD-3):
  - A clock that is ahead ends the snooze early. The user is woken sooner, which is never a free escape.
  - A clock that is behind delays the snooze end until network time arrives. `TIME_SET` then re-arms the slot from the corrected clock, and a snooze end that has passed fires at once.
- **Same boot:** a clock or zone change never moves a session deadline, because deadlines count monotonic time and the slot is re-armed as new wall time plus the monotonic time left.
- **Devices without `BOOT_COUNT`:** the boot counter reports the same value every boot. `Deadline.sameBoot` treats elapsed time lower than at the deadline's creation as a reboot, so clock changes stay harmless and reboots still fall back to wall time.
- **Why no clock-trust check:** Android exposes no reliable "clock is trustworthy" signal to apps without network access. Delaying a ring until network time would make an offline phone silent, which breaks never-silent (NFR-2). Every other alarm clock behaves the same way on a phone whose clock is wrong.
