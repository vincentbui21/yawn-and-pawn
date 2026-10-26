# Design preview round 5: Epic 6 progress (owner look before the build)

The Epic 6 screens are approved in rounds 1 and 2. Three things changed since, or were never shown, and need the owner's look before stories 6.3, 6.5 and 6.7 are built (epic-6-context.md, "Need an owner look before the build", and the open questions Q1 and Q2). Each state below shows the **proposed default**. Approve it, or pick the alternative.

**Owner decision 2026-10-26: all three defaults approved** ("OK with all"): two currencies joined with " + ", one line per currency on Progress; "/ 30" hidden once the streak passes 30; Day detail from stored facts only (option A, no `session_event` table).

Open a state on the phone: debug build, design preview, "Round 5 · Epic 6 progress", or deep-link its id from [states.md](states.md), for example:

```sh
adb shell am start -S -n com.yawnandpawn.app/.debug.preview.PreviewActivity --es state day-facts-snoozed --es theme dark --ez font200 true
```

Screenshots (Light, Dark and 200 % font, Light) are in [round-5/](round-5/). They are the Roborazzi baselines of `PreviewScreenshotTest`, named `{id}_light.png`, `{id}_dark.png` and `{id}_light_font200.png`.

## 1. Money in two currencies

**Question:** how does money look when the user paid in two currencies (for example after moving country, which changes the Play price currency)? The approved designs show one currency only.

**Default shown:** one total per currency, never added together (AD-8).
- **Home hero:** one line, the amounts joined with " + " (EXPERIENCE.md "Money, mixed currencies"): "€3.27 + $2.18 paid this week".
- **Progress "Money paid":** one line per currency (FR-PRG-2), "€8.98" then "$2.18", above "This month".

**Alternative:** the same " + " line on Progress too (fewer lines, but it may wrap in the half-width card), or only the currency of the latest payment with a "Purchase history" hint (not recommended: it hides money).

| Id | State | Screenshots |
|---|---|---|
| `home-paid-two-currencies` | Home hero, paid this week in EUR and USD | `home_paid_two_currencies_light.png`, `_dark.png`, `_light_font200.png` |
| `progress-money-two-currencies` | Progress, "Money paid" with one line per currency | `progress_money_two_currencies_light.png`, `_dark.png`, `_light_font200.png` |

The amounts are fixed EUR and USD samples, whatever the phone's locale; on the phone they are formatted in the phone's locale.

## 2. Day detail from the facts history really holds (Q1)

**Question:** the approved timeline (round 2, `day-*`) shows "Rang again", "I'm up", "Quiet time ran out", "Check switched", the snooze length and the number of tries. Session history does not store those. What should Day detail show?

**Default shown:** only stored facts, so nothing is invented:
- the first ring, as "Rang before your first unlock" when the session started in Direct Boot;
- each snooze at its purchase time with its price: "Snoozed · {price}" (no snooze length);
- merged alarms at their merge time;
- the fallback check, with **no time** (history keeps none), just before the end;
- the check solved at the end time with **no try count**: "{check} solved";
- or "Stopped after 30 minutes" at the end time for a missed morning.

The hero and the three tiles stay. Rings = 1 + snoozes; the time to get up runs from the first ring to the end. Test and skipped days show the hero only, as in round 2, so they have no new state. The round 2 `day-*` states stay in the menu for comparison.

**Alternative (option B):** a `session_event` table written by `SessionRecorder`, which fills the full approved timeline for new sessions only. It needs an `app.db` migration, backup and "Delete all data" updates, and about one extra story.

| Id | Compare with (round 2) | Screenshots |
|---|---|---|
| `day-facts-snoozed` | `day-snoozed` | `day_facts_snoozed_light.png`, `_dark.png`, `_light_font200.png` |
| `day-facts-fallback` | `day-fallback` | `day_facts_fallback_light.png`, `_dark.png`, `_light_font200.png` |
| `day-facts-before-unlock` | `day-before-unlock` | `day_facts_before_unlock_light.png`, `_dark.png`, `_light_font200.png` |
| `day-facts-two-sessions` | `day-two-sessions` | `day_facts_two_sessions_light.png`, `_dark.png`, `_light_font200.png` |
| `day-facts-missed` | `day-missed` (snoozed once, then missed) | `day_facts_missed_light.png`, `_dark.png`, `_light_font200.png` |

New strings (in EXPERIENCE.md; owner approved the reduced timeline 2026-10-26): `day_event_snoozed_paid` "Snoozed · {price}" and `day_event_solved` "{check} solved".

## 3. The ring centre with a streak over 30 (Q2)

**Question:** the ring centre shows "{streak} / 30 day streak". What does it show once the streak passes 30?

**Default shown:** the streak number and "day streak", with "/ 30" hidden once the streak is over 30 (up to 30 it stays). The sample is a 42-day streak: all 30 dots on time, nothing paid. Its "Money paid" card shows no amount, as the approved round 2 design does today; the "Nothing paid" line for a month with no payments (FR-PRG-2) comes with story 6.5.

**Alternative:** keep "/ 30" and cap the number at 30 (hides the real streak), or show "30+" (loses the number; the streak card below still shows it).

| Id | State | Screenshots |
|---|---|---|
| `progress-streak-over-30` | A 42-day streak: "42", "day streak", no "/ 30" | `progress_streak_over_30_light.png`, `_dark.png`, `_light_font200.png` |

## Model changes made for the build

So 6.3, 6.5 and 6.7 build on them:
- `HomeHero.paidThisWeek` is now `List<Money>` (empty for nothing paid).
- `ProgressUiState.paidThisMonth` is now `List<Money>` (empty for nothing paid).
- `MorningEvent.time` is nullable (only `FallbackUsed` uses `null`), `MorningEvent.Snoozed.minutes` and `MorningEvent.CheckSolved.tries` are nullable for facts history does not hold, and `SessionDetail.rings` is 1 + snoozes.
- The ring centre hides "/ 30" when the streak is over 30.

The existing preview states look the same; no existing baseline changed.
