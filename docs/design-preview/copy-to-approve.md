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
| `editor_checks` | Checks | Alarm editor, section title | The IA names the section "Checks". |
| `editor_check_chip` | {check} · {difficulty} | Alarm editor, `chip-check` | e.g. "Math · Medium". |
| `difficulty_easy` / `difficulty_medium` / `difficulty_hard` | Easy · Medium · Hard | Alarm editor `chip-check` (and Check setup in round 3) | EXPERIENCE.md mentions difficulty but names no levels. |
| `editor_grace_window` | Grace window | Alarm editor, slider title | The glossary term, used as the label; the value reads "{seconds} seconds" (key string). |

## Not strings, but worth a look

- The Math problem is drawn as digits with "+" or "×" (for example "47 + 38"); TalkBack reads the key string "47 plus 38".
- The sound names in the preview ("Birdsong", "Marimba", "Rooster", "Argon", "Oxygen", "morning-mix.mp3") and alarm labels ("Stand-up", "Early shift") are fake data, not app copy. The bundled sound names come with Story 1.17.
- The Progress and Settings tabs show only their title until round 2.
- Prices are fake and use the phone's local currency through the normal price formatting (for example "€1" or "$1").
