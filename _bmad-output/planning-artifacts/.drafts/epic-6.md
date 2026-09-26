## Epic 6: Wake-up progress

The user sees how their mornings are going: the current and best zero-snooze streak and this week's money on Home above everything else, a Progress screen with on-time rate (7 and 30 days), average time from first ring to up, snoozes per week and money paid per currency, a calendar that marks each day's outcome with glyph, colour and label, a Day detail for each morning, a Sunday-evening weekly summary, a one-screen zero-snooze celebration, and a CSV export. Every number comes from pure functions in `core.stats` over the Story 1.13 `session_history` table (written only by `SessionRecorder`) and the Epic 4 purchase records (written only by `PurchaseLedger`, `Money` micros plus currency); nothing stores derived stats (AD-18, AD-8). Test and Skipped sessions, and sessions still in progress (null outcome), never count toward rates, streaks or averages. This epic is built while the closed test from Epic 5 runs.

Every UI story in this epic carries the two standing acceptance criteria from Epic 1, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass `CopyRulesTest` (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `[ASSUMPTION: add to EXPERIENCE.md Key strings]` and listed for the owner.

Stats definitions shared by every story in this epic (owner to confirm; these resolve PRD Q15 and the open definitions in FR-PRG-2) [ASSUMPTION: confirm with owner, then add to EXPERIENCE.md > State Patterns]:
- **Day of a session** = local date of `scheduled_at` in the zone from `TimeZoneProvider` at the time of computation (a 23:50 alarm finished at 00:10 belongs to the earlier day).
- **Counted session** = outcome OnTime, Snoozed or Missed. Test, Skipped and null outcome are never counted.
- **Good day** = a day with at least one counted session where every counted session is OnTime. A day with any Snoozed or Missed session breaks the streak. Days with no counted session (no alarm, or only Test/Skipped) neither extend nor break a streak.
- **Week** = ISO week, Monday 00:00 to Sunday 24:00 local, so the Sunday 19:00 summary covers the whole week so far. **Month** = local calendar month.
- **Money paid** = purchase records with status granted, consumed or reused, summed in micros per currency; stranded records are never counted (they are refunded by Google). Self-requested refunds are not detectable (PRD §6.3), so totals show what was charged.
- **Calendar day outcome** when a day has several sessions = the worst counted outcome (Missed, then Snoozed, then On time); a day with only Test or Skipped sessions shows the hollow ring labelled Skipped if any session was skipped, otherwise Test.

### Story 6.1: Streaks, on-time rate and time to up in core.stats

As a user,
I want my streak, on-time rate and average time to get up calculated the same way everywhere,
So that Home, Progress, the celebration and the weekly summary never disagree.
**Refs:** FR-PRG-1, FR-PRG-2, NFR-11, NFR-12, AD-1, AD-3, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.stats`
**When** the day model is added
**Then** a pure `sessionDay(entry, zone): LocalDate` returns the local date of `scheduled_at`, and `isCounted(entry)` is true only for outcome OnTime, Snoozed or Missed (false for Test, Skipped and a null outcome)
**And** every function in this story takes the history as `List<SessionHistoryEntry>` plus `today: LocalDate` and `zone: TimeZone`, reads no clock itself (time comes from the Story 1.6 ports at the call site), and lives in commonMain with no platform import (AD-1)

**Given** a history
**When** `currentStreak(history, today, zone)` and `bestStreak(history, zone)` are computed
**Then** days with a counted session are ordered by date; a good day extends the run, a day with any Snoozed or Missed session ends it, and days without a counted session are skipped without breaking it
**And** the current streak walks back from the latest day ≤ `today` that has a counted session; days after `today` (clock set back) are ignored
**And** tests cover: empty history → 0 and 0; five on-time weekdays with no weekend alarms → 5; on time Monday, Missed Tuesday, on time Wednesday → current 1, best 1; two sessions on one day, one OnTime and one Snoozed → that day breaks the streak; a day with only a Test session between two good days → streak 2; a Skipped day between two good days → streak 2; today Snoozed after a 9-day run → current 0, best 9; a session still in progress (null outcome) today → ignored, current streak unchanged
**And** a seeded property test over 1,000 random histories asserts `bestStreak ≥ currentStreak ≥ 0` and that adding a Test or Skipped row never changes either value

**Given** a history
**When** `onTimeRate(history, today, zone, days)` is computed for `days` = 7 and 30
**Then** the window is `today − (days − 1)` through `today`, the result is OnTime count ÷ counted count in the window rounded half-up to a whole percent, and it is `null` (not 0) when the window has no counted session
**And** tests cover 6 of 7 → 86%, 1 of 3 → 33%, 2 of 3 → 67%, a Missed-only window → 0%, only Test/Skipped in the window → null, and a session exactly on the window's first day is included while the day before is not

**Given** a history
**When** `averageTimeToUp(history, today, zone)` is computed
**Then** it is the mean of `time_to_complete_ms` over OnTime and Snoozed sessions in the last 30 days (for Snoozed sessions this includes snooze time, as defined in FR-PRG-2 "from first ring to up"), returned as a `Duration`, and `null` when there are none
**And** rows with a null `time_to_complete_ms` are skipped, Missed sessions never contribute, and a helper `wholeMinutes(duration)` rounds half-up (2 min 29 s → 2, 2 min 30 s → 3) with durations under 30 s marked as "under a minute" for the UI
**And** tests use builders from `:testing` (`aSession(...)`) and cover Europe/Berlin across the DST change, where 2027-03-28 still counts as one day, and a time-zone change from Asia/Ho_Chi_Minh to Europe/London that moves a 06:30 session to the previous local date
**And** Kover shows `core.stats` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 6.2: Snoozes per week, money per currency and day outcomes in core.stats

As a user,
I want my snoozes and payments added up honestly, per currency, from what was really charged,
So that the numbers I see match my Google Play receipts.
**Refs:** FR-PRG-1, FR-PRG-2, FR-PRG-3, FR-MSG-2, NFR-10, NFR-11, AD-8, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a history
**When** `snoozesPerWeek(history, today, zone, weeks = 8)` is computed
**Then** it returns exactly 8 entries, oldest first, one per ISO week ending with the week that contains `today`, each holding the week's Monday and the sum of `snooze_count` over sessions whose day falls in that week, including Snoozed and Missed sessions (paid snoozes before a Missed timeout still happened) and excluding Test, Skipped and null-outcome rows
**And** weeks without sessions are present with 0, and tests cover a year boundary (ISO week 53 of 2026 into week 1 of 2027) and a Sunday 23:30 session that belongs to the week that started on the previous Monday

**Given** purchase records from the Epic 4 read port (each with `Money(micros, currency)`, status, `session_id` and purchase time)
**When** `moneyPaid(records, period, today, zone)` is computed for `ThisWeek`, `ThisMonth` and `AllTime`
**Then** it returns a list of `Money`, one per currency, summing micros as `Long` only over statuses granted, consumed and reused, sorted by ISO currency code, and an empty list when nothing was paid
**And** stranded records are never included, a record that moved from stranded to reused is counted once, and amounts in different currencies are never added together (test with EUR 2.99 + USD 1.00 + EUR 5.99 → EUR 8.98 and USD 1.00 as two entries)
**And** a purchase at 23:59 on the last day of a month counts in that month and a purchase at 00:00 on Monday counts in the new week (tests)

**Given** a history and purchase records
**When** `dayOutcome(history, records, date, zone)` is computed
**Then** it returns `DaySummary(date, outcome, sessionCount, fallbackUsed)` where `outcome` is `None` when the day has no session, otherwise the worst counted outcome (Missed, then Snoozed, then OnTime), or `Skipped` / `Test` for days with only those sessions (Skipped wins over Test), and `fallbackUsed` is true if any session that day used the fallback check
**And** `monthSummary(history, records, yearMonth, zone)` returns one `DaySummary` per day of that month, and `sessionsOn(history, records, date, zone)` returns that day's sessions ordered by `scheduled_at`, each with its paid `Money` list (granted, consumed, reused) and its stranded records listed separately

**Given** `SessionHistoryRepository` from Story 1.13 and the Epic 4 purchase-record port
**When** the `ObserveProgress` use case is added
**Then** it exposes `Flow<ProgressSnapshot>` holding current and best streak, on-time rate 7 d and 30 d, average time to up, snoozes per week, money this week / month / all time and a `hasHistory` flag, recomputed whenever either table changes and whenever the local date or time zone changes (a `DayTicker` input driven from `Clock` and `TimeZoneProvider`)
**And** a read-only `observeAll(): Flow<List<SessionHistoryEntry>>` is added to `SessionHistoryRepository` (Room implementation plus the `:testing` fake) if Story 1.13 did not provide one; `SessionRecorder` stays the only writer
**And** Turbine tests with `FakeSessionHistoryRepository`, the Epic 4 purchase-record fake and `FakeClock` assert a new emission after a session row is upserted, after a purchase record is upserted, and when the fake clock crosses local midnight
**And** a performance test builds 3 years of daily sessions (about 1,100 rows) and asserts a full `ProgressSnapshot` computes in under 50 ms on the JVM
**And** Kover shows `core.stats` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 6.3: Streak and money hero card on Home

As a user,
I want Home to show my streak and what I paid this week before anything else,
So that the thing I'm working toward is the first thing I see.
**Refs:** FR-MSG-2, FR-PRG-2, FR-MSG-4, NFR-9, NFR-10, AD-8, AD-11, UX-DR30, UX-DR61, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** Home with at least one alarm or at least one history row
**When** it renders
**Then** `card-hero` is the first element above the reliability `banner-warning`, the next-alarm countdown, notes and the alarm list, and shows the current streak from `ObserveProgress` in `display` with tabular figures (`accent-text` in Light, `accent-dark` in Dark), the label "days on time" in `body` [ASSUMPTION: add to EXPERIENCE.md Key strings; plural form "day on time" for 1], and the money line in `text-secondary`
**And** the card is `surface`, `rounded.md`, 16 dp padding, not tappable, and the streak number never animates on Home

**Given** nothing was paid this ISO week
**When** the money line renders
**Then** it reads exactly "Nothing paid this week. Keep it that way." (EXPERIENCE.md Key strings), never a zero amount

**Given** purchases this week in one or more currencies
**When** the money line renders
**Then** it reads "{paid} paid this week" [ASSUMPTION: add to EXPERIENCE.md Key strings] where `{paid}` is each currency's total formatted by the Epic 4 `MoneyFormatter` port, joined with " · " in currency-code order (for example "€8.98 · $1.00 paid this week"), with no currency symbol in any string resource
**And** the price text uses `text` colour, never accent, green or red (UX-DR7)

**Given** the Home empty state (no alarms and no history) or an active session
**When** Home renders
**Then** the hero card is hidden: the empty state shows "No alarms yet." with "Add your first alarm", and during a session `panel-session-in-progress` replaces all Home content (UX-DR61)

**Given** the hero card
**When** a session is recorded, a purchase record changes, the local date rolls over, or the app returns to the foreground
**Then** the streak and money line update without a restart (ViewModel test with fakes and Turbine)
**And** TalkBack reads the card as one element, "{streak} days on time. {money line}", with no button role

**Given** Roborazzi and semantic tests
**When** they run
**Then** screenshots cover streak 0 with nothing paid, streak 1, streak 12 with one currency paid, two currencies paid, and the hero above the reliability banner, in Light and Dark and at 200% font scale with nothing clipped
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.4: Zero-snooze celebration on the Success screen

As a user who got up without snoozing,
I want one short, warm celebration that shows my streak growing,
So that waking on time feels like a win, and a paid morning never feels like a failure.
**Refs:** FR-MSG-3, FR-MSG-4, FR-PRG-2, NFR-7, NFR-9, AD-2, AD-11, AD-18, UX-DR2, UX-DR64, UX-DR66, UX-DR70, UX-DR71, UX-DR72, UX-DR77, UX-DR78, UX-DR84, UX-DR87 · **Priority:** Must · **Verify:** auto, plus (human-verify) haptic and brightness in Story 6.10

**Acceptance Criteria:**

**Given** a session that starts (`AlarmFired`, `TestAlarmFired` or `ProcessRestored`)
**When** `WakeService` handles it
**Then** it loads a history snapshot for the celebration off the wake critical path (after the ringing screen is shown) and keeps it in memory for the session; `streakBefore = currentStreak(snapshot, today, zone)`
**And** the ringing screen's first frame never waits for this load (test asserts no repository call before the first frame, as in Story 1.15)

**Given** the Success wake screen shown after `Completed` (extended here; if the wake flow still closes `WakeActivity` on `Completed`, this story adds the Success screen in `WakeActivity` with the "after snooze" variant from Epic 4 and a "Done" `button-wake-primary`-style 64 dp action (EXPERIENCE.md Component Patterns))
**When** the session completed with 0 snoozes and is not a test
**Then** `streakAfter = currentStreak(snapshot + this session as OnTime, today, zone)` and the headline reads "Up on time. {streak} days in a row." (EXPERIENCE.md Key strings) with `{streak}` = `streakAfter` in tabular figures [ASSUMPTION: add to EXPERIENCE.md Key strings a singular form for streak 1, for example "Up on time. 1 day in a row."]
**And** when `streakAfter > streakBefore` the streak number plays exactly one 600 ms scale + fade and one success haptic, and nothing else moves (UX-DR71); when the streak did not grow (a second on-time session on the same day) the same text shows with no animation and no extra haptic
**And** the whole animation finishes within 3 s of the screen appearing (FR-MSG-3), is not repeated on recomposition, rotation or return from Recents, and never plays on Home

**Given** the animator duration scale is 0
**When** the celebration would play
**Then** the final state appears instantly with no scale or fade, and the success haptic still fires (UX-DR72)

**Given** a session completed after one or more paid snoozes
**When** Success renders
**Then** it shows "You're up. That's what counts." and "{paid} paid this morning" in `text-secondary-sunrise` (EXPERIENCE.md Key strings), with no streak number, no animation and no celebration haptic beyond the standard success pattern (paid mornings are never shamed or celebrated)

**Given** a test session that completes
**When** Success renders
**Then** it shows "Test finished. Your alarm works." [ASSUMPTION: add to EXPERIENCE.md Key strings] with no streak, no animation, and history outcome Test unchanged

**Given** the in-memory snapshot could not be loaded (read error or process restored without it)
**When** a zero-snooze session completes
**Then** Success shows "You're up. That's what counts." without a number or animation and the failure is logged through `Logger` (never a loading state, never a wrong streak)

**Given** the Success screen
**When** the user taps "Done" or the activity stops
**Then** the screen closes, the screen brightness set by the Epic 5 "Bright wake screen" setting is restored, and `FLAG_KEEP_SCREEN_ON` is cleared so the system screen timeout applies while Success is visible
**And** Back still does nothing on the wake screen (UX-DR74), and TalkBack initial focus is the headline, then "Done"

**Given** Roborazzi, semantic and ViewModel tests with `FakeClock` and fake history
**When** they run
**Then** they cover streak growing 11 → 12 (animation state start and end), no growth on a second session the same day, first-ever on-time morning, after snooze with one currency, test session, and snapshot missing, in Sunrise at 100% and 200% font scale
**And** a unit test asserts the success-screen string keys contain no emoji other than the one allowed key and no word from the banned list (`CopyRulesTest`)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.5: Progress screen with stat tiles, snoozes chart and money

As a user,
I want one screen that shows my streaks, on-time rate, time to get up, snoozes per week and money paid,
So that I can see whether I'm snoozing less over time.
**Refs:** FR-PRG-2, FR-PRG-4, FR-MSG-4, NFR-3, NFR-9, NFR-10, AD-8, AD-11, AD-18, UX-DR44, UX-DR46, UX-DR47, UX-DR58, UX-DR59, UX-DR64, UX-DR66, UX-DR67, UX-DR68, UX-DR80, UX-DR84, UX-DR94 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the `nav-bar`
**When** the user taps "Progress" (created here if earlier epics left it as a placeholder)
**Then** the Progress route shows, top to bottom: two rows of `stat-tile`s (current streak and best streak; on-time rate 7 d and 30 d), a full-width `stat-tile` for average time to up, the snoozes `bar-chart`, the money section, and links to Purchase history (the Epic 4 screen, whose entry point moves here) and Export CSV (disabled until Story 6.9)
**And** the tile labels are "Current streak", "Best streak", "On time · 7 days", "On time · 30 days" and "Average time to up" [ASSUMPTION: add all five to EXPERIENCE.md Key strings], numbers are in `display` with tabular figures in `text` colour, percentages are locale-formatted (86% in en-US), average time reads "{minutes} min" or "Under 1 min" [ASSUMPTION: add both to EXPERIENCE.md Key strings]
**And** a tile whose value is `null` shows "No mornings yet" [ASSUMPTION: add to EXPERIENCE.md Key strings] in `body` instead of a number, never 0% or 0 min

**Given** the snoozes chart
**When** it renders `snoozesPerWeek` for the last 8 weeks
**Then** bars use `snoozed` colour with `rounded.sm` top corners on an `outline` baseline, week labels (Monday's date, locale short format) are `text-secondary`, the caption reads "Lower is better." (DESIGN.md `bar-chart`) [ASSUMPTION: add to EXPERIENCE.md Key strings], and a week with 0 snoozes shows only the baseline
**And** each bar's tap area is at least 48 dp wide and the full chart height; when 8 × 48 dp does not fit the screen width, the chart scrolls horizontally with the current week visible when the screen opens
**And** tapping a bar shows that week's number above it as "{n} snoozes" [ASSUMPTION: add to EXPERIENCE.md Key strings, with singular "1 snooze"], and TalkBack reads each bar as "Week of {date}, {n} snoozes" [ASSUMPTION: add to EXPERIENCE.md Key strings]

**Given** the money section titled "Money paid" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**When** it renders `moneyPaid` for this week, this month and all time
**Then** each period row is labelled "This week", "This month" or "All time" [ASSUMPTION: add all three to EXPERIENCE.md Key strings] and shows one line per currency formatted by `MoneyFormatter`, in `text` colour, never accent, green or red
**And** a period with nothing paid reads "Nothing paid" (FR-PRG-2), never a zero amount or a currency symbol

**Given** no history at all
**When** Progress opens
**Then** it shows only "Your first morning shows up here." (EXPERIENCE.md Key strings) and the Purchase history link, with no empty tiles or chart
**And** while the first snapshot loads, `skeleton` blocks appear only after 300 ms, without shimmer when animations are off, and text replaces them after 3 s (UX-DR58)

**Given** a history with only Test and Skipped sessions
**When** Progress opens
**Then** streak tiles show 0, rate and average tiles show "No mornings yet", and the chart shows 8 empty weeks (Test and Skipped never count)

**Given** an active session
**When** the user opens the app
**Then** Progress is unreachable because `panel-session-in-progress` replaces the app and the `nav-bar` is hidden (UX-DR61)

**Given** Roborazzi, semantic and ViewModel tests with fake history and purchase records
**When** they run
**Then** screenshots cover empty, Test-only, a typical month (F9: streak 12, best 12, 86% 30 d, 3 min, a chart with varied weeks), two currencies, and a tapped bar, in Light and Dark and at 200% font scale with nothing clipped, all targets ≥ 48 dp and every tile read by TalkBack as "{label}, {value}"
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.6: Calendar of morning outcomes

As a user,
I want a month calendar that marks each morning as on time, snoozed, missed, skipped or test,
So that I can spot patterns, like which weekdays I struggle on.
**Refs:** FR-PRG-3, FR-PRG-1, FR-MSG-4, NFR-9, AD-18, UX-DR11, UX-DR48, UX-DR49, UX-DR64, UX-DR66, UX-DR67, UX-DR68, UX-DR85, UX-DR94 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** the Progress screen with at least one history row
**When** it renders
**Then** a calendar section below the chart shows the current month as a 7-column grid of 48 dp `calendar-day` cells starting on Monday (matching the ISO week used by the stats), with the month and year as a locale-formatted title and weekday initials above the columns
**And** each cell shows the date in `caption` and, below it, the `outcome-marker` for `dayOutcome`: filled `check_circle` in `success` (On time), filled `schedule` in `snoozed` (Snoozed), filled `cancel` in `missed` (Missed), hollow `radio_button_unchecked` in `outline` (Skipped or Test), plus a small `alt_route` badge in `text-secondary` when a fallback check was used; days with no session show only the date
**And** today's cell has a 1 dp accent ring, and future days show only the date and are not tappable

**Given** the calendar legend
**When** it renders below the grid
**Then** it pairs each glyph with its glossary label "On time", "Snoozed", "Missed", "Skipped", "Test" and "Fallback check used" (EXPERIENCE.md Glossary and Component Patterns) so colour is never the only signal (UX-DR68)

**Given** the calendar
**When** the user taps the 48 dp "Previous month" or "Next month" icon buttons [ASSUMPTION: add both content descriptions to EXPERIENCE.md Key strings]
**Then** the grid moves one month, "Next month" is disabled on the current month, and "Previous month" is disabled on the month of the oldest history row
**And** the grid recomputes from `monthSummary` when a session is recorded or the local date changes

**Given** TalkBack
**When** focus moves over a day cell
**Then** it reads "{weekday} {day}, {outcome}" in the EXPERIENCE.md pattern (for example "Tuesday 14, on time"), adding ", fallback check used" when relevant, ", {n} sessions" when the day has more than one session [ASSUMPTION: add to EXPERIENCE.md Key strings], or "{weekday} {day}, no alarm" for an empty day [ASSUMPTION: add to EXPERIENCE.md Key strings], with a double-tap hint to open Day detail on days with sessions

**Given** a day with several sessions (for example a Snoozed 06:00 and an On time 06:30)
**When** its cell renders
**Then** it shows the worst counted outcome (Snoozed here) per the shared stats definitions, and Day detail lists both sessions (PRD Q15 resolution, owner to confirm)

**Given** an alarm turned off or deleted inside the commitment-lock window
**When** the calendar renders that day
**Then** no marker is shown for the occurrence that did not ring (no session row exists, so it is neither Missed nor counted), per the PRD Q15 resolution (owner to confirm)

**Given** Roborazzi and semantic tests
**When** they run
**Then** screenshots cover a month with every outcome, a fallback badge, a multi-session day, today, an empty previous month, and the legend, in Light and Dark and at 200% font scale with no clipped cell, and a test asserts every glyph/colour pair used is in the DESIGN.md verified contrast table
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.7: Day detail for each morning

As a user,
I want to tap a day and see what happened: when it rang, when I got up, snoozes, what I paid, which check I did,
So that I understand each morning, not just the totals.
**Refs:** FR-PRG-1, FR-PRG-3, FR-PRG-4, FR-SES-7, FR-PWK-11, FR-MSG-4, NFR-9, AD-8, AD-16, AD-18, UX-DR48, UX-DR52, UX-DR64, UX-DR66, UX-DR78, UX-DR80, UX-DR91, UX-DR94 · **Priority:** Should · **Verify:** auto

**Acceptance Criteria:**

**Given** a calendar day with sessions
**When** the user taps it
**Then** the Day detail route opens with a `top-app-bar` titled with the locale-formatted full date and one `surface` `rounded.md` card per session from `sessionsOn`, ordered by scheduled time

**Given** a counted session (On time, Snoozed or Missed)
**When** its card renders
**Then** it shows the `outcome-marker` with its label, the alarm time and label, first ring time, time up (`ended_at`; not shown for Missed), time to up as "{minutes} min" or "Under 1 min" (Story 6.5 strings), snooze count as "{n} snoozes" (Story 6.5 string), the amount paid per currency via `MoneyFormatter` or "Nothing paid", and the check types by their glossary names
**And** when `fallback_used` is true it adds the `alt_route` badge and "Fallback check used"; when `direct_boot` is true it adds "Rang before your first unlock" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** each occurrence merged into this session (recorded by the Epic 2 merge handling) adds a `note-inline` "{time} alarm merged into this session" (EXPERIENCE.md State Patterns)
**And** each stranded purchase linked to the session is listed as a `purchase-row`-style line reading "Not used, refunded automatically by Google" (EXPERIENCE.md Key strings), not included in the paid amount

**Given** a Test or Skipped session
**When** its card renders
**Then** it shows only the outcome marker, the label "Test" or "Skipped" and the alarm time (EXPERIENCE.md State Patterns: outcome label only)

**Given** an alarm the user turned off or deleted inside the commitment-lock window on that day (F6)
**When** Day detail renders
**Then** it lists "Your {time} alarm was turned off. Logged." or "Your {time} alarm was deleted. Logged." [ASSUMPTION: add both to EXPERIENCE.md Key strings] from the lock-window change records kept by Epic 4
**And** if Epic 4 only wrote those actions through the `Logger` port, this story adds an `alarm_change_log` table (`id`, `alarm_id`, `alarm_time`, `action` TurnedOff / Deleted, `at`) to `app.db` with a migration, exported schema and migration test, written by the Epic 4 turn-off and delete use cases when they confirm inside the lock window, and included in backup like the rest of `app.db`

**Given** Day detail with TalkBack
**When** focus moves through a card
**Then** the outcome is read as a word, never as a colour or icon name, and each fact is read as label and value

**Given** a session whose `session_history` row has a null `ended_at` or `time_to_complete_ms` (for example an older row)
**When** its card renders
**Then** the missing facts are omitted, never shown as 0 or "null" (test)

**Given** Roborazzi, semantic and ViewModel tests
**When** they run
**Then** screenshots cover an On time session, a Snoozed session with two currencies and a stranded purchase, a Missed session, a fallback plus Direct Boot session, a merged occurrence, a Test session, a Skipped session and a turned-off note, in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.8: Weekly summary notification on Sunday evening

As a user,
I want a short Sunday-evening note with my on-time mornings and what I paid,
So that I notice my progress without opening the app.
**Refs:** FR-PRG-5, FR-MSG-4, NFR-8, NFR-10, AD-3, AD-11, AD-17, AD-18, UX-DR51, UX-DR57, UX-DR84, UX-DR94 · **Priority:** Should · **Verify:** auto, plus (human-verify) real delivery in Story 6.10

**Acceptance Criteria:**

**Given** `core.stats`
**When** `nextWeeklySummaryAt(now, zone)` is computed
**Then** it returns the first Sunday 19:00 local strictly after `now` [A6], tested for Saturday 23:00 → next day, Sunday 18:59 → same day, Sunday 19:00 exactly → a week later, the Europe/Berlin DST change weekend, and a zone change from Asia/Ho_Chi_Minh to America/New_York
**And** `weeklySummary(history, records, weekOf, zone)` returns the on-time count and `moneyPaid` for that ISO week, plus `shouldSend = false` when the week has no counted session

**Given** the `BackgroundWork` port (AD-17) and its WorkManager adapter (from Epic 4 consume retries; the port gains a `scheduleWeeklySummary(at)` / `cancelWeeklySummary()` pair here) with `FakeBackgroundWork` updated
**When** the weekly summary is enabled
**Then** one unique one-time work request named `weekly-summary` is enqueued (policy REPLACE) with its initial delay computed from `nextWeeklySummaryAt`, and it is re-enqueued at the end of every run, on app start, on toggle-on, and from the Story 1.10 `SystemEventsReceiver` on boot, `TIME_SET` and `TIMEZONE_CHANGED`
**And** nothing on the wake path depends on it, no periodic or foreground work is added, and no alarm is scheduled through `AlarmScheduler` for it (NFR-8, AD-17)

**Given** the worker runs
**When** it evaluates the summary
**Then** if the run is within 24 h after the target Sunday 19:00 it summarises that ISO week; if later (for example the phone stayed locked since boot until Tuesday) it skips that week without posting; if `shouldSend` is false it posts nothing
**And** the ISO week id of the last posted summary is stored in device-protected DataStore, so a duplicate run for the same week never posts twice (test)
**And** if `POST_NOTIFICATIONS` is not granted it posts nothing and never requests the permission from the background

**Given** a week to report
**When** the notification is posted
**Then** it uses channel "Weekly summary" [ASSUMPTION: add to EXPERIENCE.md Key strings] with default importance, a monochrome small icon with no accent tint, the app name as title, and text "{onTime} on-time mornings, nothing paid. Nice." when nothing was paid and at least one morning was on time, or "{onTime} on-time mornings, {paid} paid this week." when something was paid (EXPERIENCE.md Key strings), with `{paid}` formatted per currency by `MoneyFormatter` and joined with " · "
**And** when nothing was paid and `{onTime}` is 0 (only Missed mornings) it reads "0 on-time mornings this week. Next week's a fresh start." [ASSUMPTION: add to EXPERIENCE.md Key strings], never "Nice."
**And** `{onTime}` = 1 uses a singular form ("1 on-time morning, nothing paid. Nice.") [ASSUMPTION: add singular forms to EXPERIENCE.md Key strings]
**And** tapping it opens the app on the Progress route (or `panel-session-in-progress` if a session is active), and it auto-cancels on tap

**Given** Settings
**When** the Notifications section renders (created here if Epic 5 did not add it)
**Then** it has a `settings-row` `switch` "Weekly summary" [ASSUMPTION: add to EXPERIENCE.md Key strings], default on, stored in device-protected DataStore through a core use case; turning it off cancels the unique work immediately, turning it on enqueues it
**And** the row is unreachable during an active session (session lock)

**Given** Robolectric tests with WorkManager's test driver, `FakeClock` and fake repositories
**When** they run
**Then** they cover: enqueue on first launch with default on; delay equals the computed instant; run on Sunday 19:00 posts the expected text for nothing-paid, paid in two currencies, zero on-time, and singular cases; a week with only Test sessions posts nothing; a late run on Tuesday skips; a second run for the same week does nothing; toggle off cancels; notification denied posts nothing; tap intent targets Progress
**And** `WorkManager` stays in `config/dependency-allowlist.txt` and the permission allowlist still passes
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.9: Export history as CSV

As a user,
I want to export my wake-up history as a CSV file,
So that I can keep it or analyse it in a spreadsheet.
**Refs:** FR-PRG-6, FR-PRG-1, FR-MSG-4, NFR-4, AD-6, AD-8, AD-18, UX-DR80, UX-DR94 · **Priority:** Could · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.stats`
**When** `historyCsv(history, records, zone)` is generated
**Then** it returns RFC 4180 text (CRLF line endings, UTF-8 without BOM) with a header row `date,scheduled_time,first_ring,up_at,outcome,snoozes,paid_micros,currency,check_types,minutes_to_up,fallback_used,rang_before_unlock,alarm_label` and one row per session with a non-null outcome, including Test and Skipped rows, oldest first
**And** times are ISO 8601 local date-times with offset in `zone`, `outcome` uses the glossary words (On time, Snoozed, Missed, Skipped, Test), `check_types` are glossary names joined by ";", and money columns hold the summed micros and ISO currency code of granted, consumed and reused records (a session paid in two currencies gets one row per currency with the other columns repeated; nothing paid leaves both columns empty)
**And** fields containing a comma, quote, CR or LF are quoted with quotes doubled, and any field starting with `=`, `+`, `-` or `@` is prefixed with a single quote so spreadsheets do not run it as a formula (tests with a label `=HYPERLINK("x")`, a label with a comma and a newline, and a non-ASCII label)
**And** no purchase token, order id, install id or file path is ever written (test)

**Given** Progress
**When** the "Export CSV" link [ASSUMPTION: add to EXPERIENCE.md Key strings] is tapped with at least one exportable session
**Then** the file `pay-per-snooze-history-{yyyy-MM-dd}.csv` is written to the app cache `exports/` folder (credential-protected, excluded from backup, old exports deleted first), shared through a `FileProvider` with `ACTION_SEND` type `text/csv` and read permission granted only to the chosen target, and the Android share sheet opens
**And** generation runs off the main thread; with 3 years of daily sessions it completes in under 1 s on the JVM (test)

**Given** no exportable session
**When** Progress renders
**Then** the "Export CSV" link is disabled and "Nothing to export yet." (EXPERIENCE.md Key strings) is shown under it

**Given** an active session
**When** the user opens the app
**Then** export is unreachable (session lock)

**Given** Robolectric and Roborazzi tests
**When** they run
**Then** they assert the share intent's type, URI authority and grant flag, that the file content equals `historyCsv` output, and screenshots cover the enabled and disabled export link in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 6.10: Epic 6 device verification checklist

As the owner,
I want to confirm on real phones the progress features that tests can't prove,
So that closed testers and launch users see numbers and notifications they can trust.
**Refs:** FR-PRG-2, FR-PRG-3, FR-PRG-5, FR-PRG-6, FR-MSG-2, FR-MSG-3, FR-MSG-4, NFR-9, NFR-10, NFR-11 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build installed on each device of the matrix (Pixel, Samsung, Xiaomi, budget device), using the debug fire-now hook and manual date changes to build a week of history
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. After three on-time test-free mornings (real, non-test alarms via the debug hook), Home's hero shows "3 days on time" and "Nothing paid this week. Keep it that way." above the reliability banner and the countdown.
2. A zero-snooze morning shows "Up on time. {streak} days in a row." with one scale of the number and one success haptic, finishing within 3 s; a second on-time alarm the same day shows the text with no animation.
3. With "Remove animations" on, the celebration appears instantly and the haptic still fires.
4. A morning with one paid snooze (license tester) shows "You're up. That's what counts." with the amount paid, no animation; Home then shows "{paid} paid this week" with the Play-localized price, and Progress shows it under This week, This month and All time.
5. "Done" on Success restores the screen brightness set before the alarm.
6. Progress matches a hand count from Day detail for current streak, best streak, on-time 7 d and 30 d, average time to up and the 8-week chart; a Test alarm and a skipped occurrence (if Epic 7 skip ships) change none of them.
7. The calendar shows the correct glyph, colour and label for On time, Snoozed, Missed and Test days, the fallback badge after a fallback check, and TalkBack reads "{weekday} {day}, {outcome}".
8. Day detail for a snoozed morning lists rings, snoozes, paid amount, check and time to up; a Missed morning shows no time up.
9. With the weekly summary on and the device clock set to Sunday 18:58, the notification arrives by 19:15 with the EXPERIENCE.md text, and tapping it opens Progress; with the toggle off, nothing arrives.
10. After changing the time zone, Home, Progress and the calendar recompute without restarting the app.
11. "Export CSV" opens the share sheet, and the file saved to Drive or Files opens in Google Sheets with one row per session, readable dates and no formula execution from a label starting with "=".
12. At 200% font size and with TalkBack on, Home hero, Progress tiles, chart bars and calendar cells are readable and reachable.
13. All copy seen matches EXPERIENCE.md and the owner-approved assumption strings (no em dashes, no currency symbols in resources, "Nothing paid" for zero) (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done
