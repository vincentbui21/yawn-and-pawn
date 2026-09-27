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

## Not strings, but worth a look

- The Math problem is drawn as digits with "+" or "×" (for example "47 + 38"); TalkBack reads the key string "47 plus 38".
- The sound names in the preview ("Birdsong", "Marimba", "Rooster", "Argon", "Oxygen", "morning-mix.mp3") and alarm labels ("Stand-up", "Early shift") are fake data, not app copy. The bundled sound names come with Story 1.17.
- The Progress and Settings tabs show only their title until round 2.
- Prices are fake and use the phone's local currency through the normal price formatting, with a realistic base fee per currency (for example "$1", "€1", "25.000 ₫" or "¥150"; feedback item 5).
