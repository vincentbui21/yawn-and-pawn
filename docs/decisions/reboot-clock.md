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
