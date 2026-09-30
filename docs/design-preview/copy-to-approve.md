# Design preview: copy to approve

Strings the design preview needed that EXPERIENCE.md does not define. Each is drafted in voice (short, supportive, no em dash, no hype words), lives in `composeApp/src/commonMain/composeResources/values/strings.xml` like any other string, and passes `CopyRulesTest`. Approve, reword or reject each one; approved strings move into EXPERIENCE.md > Key strings.

Every other string on the preview screens is an EXPERIENCE.md key string, used verbatim.

## Round 1 (the daily loop)

| Key | Draft | Screen | Note |
|---|---|---|---|
| `time_wheel_hour` | Hour | Alarm editor, time wheel (TalkBack) | From the spec ("Hour, 6"); TalkBack says the label, then the value. |
| `time_wheel_minute` | Minute | Alarm editor, time wheel (TalkBack) | From the spec ("Minute, 45"). |
| `time_wheel_period` | AM or PM | Alarm editor, AM/PM wheel (TalkBack) | Read as "AM or PM, PM". The AM/PM markers themselves come from the phone's locale. |
| `alarm_card_switch` | {time} alarm | Home, `card-alarm` switch (TalkBack) | e.g. "7:30 AM alarm, switch, on". |
| `alarm_card_summary` | {repeat} · {label} | Home, `card-alarm` caption | e.g. "Mon, Tue, Wed, Thu, Fri · Stand-up". Without a label only the repeat summary shows. |
| `editor_checks` | Checks | Wake-up check sub-screen, section title | The IA names the section "Checks". |
| `editor_check_chip` | {a} · {b} | Alarm editor row values | Now joins a row value and its detail: "Math, QR/Barcode · Random" (Wake-up check), "Message 1 · After I'm up" (Motivation). |
| `difficulty_easy` / `difficulty_medium` / `difficulty_hard` | Easy · Medium · Hard | Wake-up check sub-screen (and Check setup in round 3) | EXPERIENCE.md mentions difficulty but names no levels. |
| ~~`editor_grace_window`~~ | ~~Grace window~~ | | Replaced by the owner's "Quiet time" (feedback item 2). |

## Round 1 rework (owner design direction, 2026-09-27)

Owner-given in `docs/design-preview/feedback.md` (items 2, 3, 7, 10, 11) and now in EXPERIENCE.md > Key strings, so nothing to approve: "Quiet time" · "{seconds} seconds" · "Vibrate during quiet time" · "Default quiet time" (Settings, round 2) · "Weekdays" · "Weekends" · "Once" / "Weekdays" / "Custom" · "Alarm name" · "Wake-up check" · "Snooze" · "Motivation" · "Cancel" / "Save" · "h" / "min" · "Record a message" · "When it plays". The label field is now "Alarm name" (was "Label"); the "Starting volume" string is gone with its slider.

Drafted for the new sub-screens:

| Key | Draft | Screen | Note |
|---|---|---|---|
| `editor_quiet_time_note` | After I'm up, the alarm stays quiet this long while you do your check. | Quiet time sub-screen, note under the card | Explains the one setting on the screen; 14 words. |
| `editor_check_mode` | Mode | Wake-up check sub-screen, title above Random / All | The IA calls it "mode". Shown with two or more checks. |
| `editor_difficulty` | Difficulty | Wake-up check sub-screen, title above Easy / Medium / Hard | Applies to every selected check in the preview. |
| (reused `editor_message_none`) | None | Wake-up check row value with no check selected | Same word as the Motivation "None"; the row also shows "Pick at least one check." |
| `editor_message` (existing) | Message | Motivation sub-screen, title above None / Random / Message 1 | EXPERIENCE.md "Recordings, editor section" row. |

## Success screen (owner feedback items 19 to 20, 2026-09-28)

| Key | Draft | Screen | Note |
|---|---|---|---|
| `success_days_in_a_row` | days in a row | Success, under the streak number | Owner-given (item 19). |
| `success_day_in_a_row` | day in a row | Success, under "1" | Draft singular of the owner's label. |

The old `success_zero_snooze` ("Up on time. {n} days in a row.") and `success_zero_snooze_one` strings are no longer used on this screen.

## Round 2 (progress and settings, 2026-09-30)

Every other round 2 string is an EXPERIENCE.md key string or long-form paragraph, used verbatim (outcome labels from the glossary: "On time" · "Snoozed" · "Missed" · "Skipped" · "Test").

| Key | Draft | Screen | Note |
|---|---|---|---|
| `day_detail_rings` | Rings | Day detail, session card row | How many times the alarm rang (first ring plus re-rings). |
| `day_detail_snoozes` | Snoozes | Day detail, session card row | |
| `day_detail_paid` | Paid | Day detail, session card row | Shown only when something was charged. |
| `day_detail_time_to_up` | Time to up | Day detail, session card row | Value uses the Progress strings "{minutes} min" / "Under 1 min". |
| (reused `editor_checks`) | Checks | Day detail, session card row | Same word as the Wake-up check sub-screen. |
| `purchase_snooze_number` | Snooze {n} | Purchase history, row caption | From DESIGN.md `purchase-row` ("Snooze 2"). |
| `purchase_row_title` | {date} · {time} | Purchase history, row title | e.g. "Thu, Sep 10 · 7:30 AM" (date and alarm). Rows are grouped in one card per month ("September 2026", from the phone's locale). |
| `stepper_lower` / `stepper_raise` | Lower {setting} / Raise {setting} | Base fee and Max snoozes steppers (TalkBack) | e.g. "Raise Base fee". |
| `stepper_value` | {setting}, {value} | Stepper value (TalkBack, announced on change) | e.g. "Base fee, $2". |
| `progress_day_sessions` | {n} sessions | Calendar day (TalkBack) | The key string "+ ', {n} sessions'" as its own piece; the pieces are joined with ", ". |

Layout choices to look at (no new copy):

- Settings has no "Notifications" or "Usage stats" headings: "Weekly summary" and "Share anonymous usage stats" share one untitled card. Cards with a key-string section name ("Snooze", "Wake", "Appearance") have it as the title above them.
- Appearance is the System / Light / Dark `segmented-control` inline (EXPERIENCE.md lists theme under `segmented-control`), not a sub-screen.
- The alarm behaviour disclosure on Payments & refunds is split into its three sentences (one string each) so each paragraph stays under 25 words; the words are verbatim.
- Reliability checklist: the "Test alarm" row has no "Fix"; it shows a hollow ring until a test has rung, and "Ring a test alarm" below the card rings it.
- The calendar card is 12 dp from the screen edges (the other cards 20 dp) so seven 48 dp days fit the 360 dp phone.
- Links (Privacy policy, Terms, Support) open nothing in the preview; tapping one shows the "No browser found." snackbar, tap again to hide it.

## Progress redesign (owner feedback item 21, 2026-09-30)

Owner-given in item 21, so nothing to approve: "day streak" · "Snoozes this week" · "Keep it going." · "Insight" · "You get up fastest on weekdays.". The Round 2 draft "Snoozes per week" is gone with the 8-week chart, and so are "On time · 7 days", "This week" and "All time" on this screen.

| Key | Draft | Screen | Note |
|---|---|---|---|
| `progress_ring_of` | / {n} | Progress ring, after the streak number | "5 / 30": the streak out of the 30 mornings shown. |
| `progress_day_today` | today | Ring dot (TalkBack), appended to today's dot | "Monday 28, On time, today". |
| `progress_chart_average` | Average {n} snoozes a morning. | "Snoozes this week", summary line | {n} with one decimal in the phone's locale ("0.4"), followed by the key string "Lower is better.". |
| `progress_bar_day` / `progress_bar_day_one` | {weekday}, {n} snoozes / {weekday}, 1 snooze | Chart bar (TalkBack) | Replaces "Week of {date}, ..." now that bars are days. |
| `progress_insight_weekends` | You get up fastest on weekends. | Insight card | The other half of the owner's template. |

Owner notes 2026-10-01 (item 23), owner-given so nothing to approve: tile labels "on time" · "to get up" · "snoozes"; the legend, its "Today" label and "Export CSV" / "Nothing to export yet." are gone.

| Key | Draft | Screen | Note |
|---|---|---|---|
| `progress_chip` | {weekday} {day} · {outcome} | Label chip of a tapped ring dot or calendar day | e.g. "Tue 23 · Snoozed", from the owner's example; weekday from the phone's locale. TalkBack reads the full "Tuesday 23, Snoozed". |
| `progress_bar_selected` | {weekday} · {n snoozes} | "Snoozes this week" header when a bar is tapped | e.g. "Wed · 2 snoozes"; with no bar tapped the header shows the week total ("3 snoozes"). |

Layout choices to look at:

- A tile without data (only before the first morning, which shows the empty ring instead) shows a dash, not "No mornings yet".
- The fallback `alt_route` badge is no longer drawn on ring and calendar dots (no legend to explain it); Day detail and TalkBack still say "Fallback check used".
- The streak card reuses the Home label "days on time" next to the number; the ring centre uses the owner's "day streak".
- Ring dots can be tapped but are not separate TalkBack buttons (30 × 48 dp does not fit a ring); TalkBack reads each dot and opens a day from the calendar instead.
- The accent tint is a new token, `glass-accent` (12% accent over glass), for the streak card only; plain accent fails on it, so the number and icon use `accent-text`.

## Not strings, but worth a look

- The Math problem is drawn as digits with "+" or "×" (for example "47 + 38"); TalkBack reads the key string "47 plus 38".
- The sound names in the preview ("Birdsong", "Marimba", "Rooster", "Argon", "Oxygen", "morning-mix.mp3") and alarm labels ("Stand-up", "Early shift") are fake data, not app copy. The bundled sound names come with Story 1.17.
- Prices are fake and use the phone's local currency through the normal price formatting, with a realistic base fee per currency (for example "$1", "€1", "25.000 ₫" or "¥150"; feedback item 5).
