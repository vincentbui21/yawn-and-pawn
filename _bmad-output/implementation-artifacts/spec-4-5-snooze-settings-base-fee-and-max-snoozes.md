---
title: 'Story 4.5: Snooze settings: base fee and max snoozes'
type: 'feature'
created: '2026-10-09'
status: 'review'
baseline_revision: '8a38a112'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-4-commitment-lock-and-pending-changes-in-core.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 4.4 stores the base fee tier and max snoozes and decides the commitment lock, but the Settings tab
still shows only its title (`AppNavHost.SettingsTab`, `rows = emptySet()`), so the user cannot set what a snooze costs
(FR-SET-1). The approved design preview already draws the Snooze card and its Base fee and Max snoozes sub-screens.

**Approach:** wire the approved `SettingsScreen` to a new `SettingsViewModel`.

- **`SettingsViewModel`** (`:composeApp`, `ui/settings`) combines:
  - `GlobalSettingsRepository.observe()` (live tier and max snoozes);
  - `PendingChangeRepository.observe()` (the global BaseFee / MaxSnoozes pending changes);
  - `PriceCatalog.observe()` (Story 4.3's cached Play prices);
  - `TimeChangeSignal` ticks (a note whose alarm has rung disappears).
- **The value shown** is the one the user chose: the pending value when one is stored, else the live one ("the new
  value is shown as saved").
- **The note** "Saved. Takes effect after tomorrow's {time} alarm." (or "today's") shows while a pending change waits
  for an occurrence still in the future. {time} is the occurrence's local time, formatted per the system 12/24 h
  setting; "today" when the occurrence's local date is today.
- **Prices** (`SnoozePrices`, pure, unit-tested): the stepper shows Play's `formattedPrice` of `snooze_usd_0B`, and the
  ladder shows snoozes 1 to min(3, max snoozes) at tiers B × N, from `PriceCatalogSnapshot.displayablePriceFor`. When
  any of these tiers has no displayable price, all of them show `MoneyFormatter` USD amounts
  (`Money(B × N × 1_000_000, "USD")`) with "Approximate. Your local price shows when you're online.".
- **Saving:** − / + and the max snoozes stepper call `SetBaseFee` / `SetMaxSnoozes` (4.4) through one sequential
  queue. The stepper moves at once to the requested value. The request is cleared once the store shows it, or reverted
  (logged) when the save fails.
- **Nav:** `AppNavHost`'s Settings tab shows `SettingsRoute` with only the Base fee and Max snoozes rows. Other rows
  belong to Epic 5 and stay hidden, so no row leads nowhere. System Back on a sub-screen returns to the Settings main
  screen. The session lock (Story 2.6) still replaces the whole app during a session, which a Robolectric test
  re-asserts with the Settings tab open.
- **Stepper:** long-press repeats (400 ms delay, then every 100 ms) until release or the end of the range. The release
  after a repeat does not step once more.

## Boundaries & Constraints

**Always:**
- Every write goes through `SetBaseFee` / `SetMaxSnoozes`, so the lock, the write order and the session guard are 4.4's.
- Prices before a purchase are Play's own strings. USD approximations go only through `MoneyFormatter`.
- Settings works offline: the cached or approximate prices render at once, and nothing awaits a price refresh (the
  refresh jobs from 4.3 already run at app start).
- The screen is stateless and the approved layout is kept. All strings are resources.

**Never:**
- No editor ladder, editor pending-field notes, turn-off dialog or commitment events (Story 4.6).
- No Epic 5 rows (default snooze length, quiet time, appearance, weekly summary, usage stats, reliability).
- No new tokens and no new colour pairs.

## AC deviations and decisions (fast mode: default taken, owner can change)

1. **The lock note is always shown** (AC: "always shown under the stepper"), and the "Saved. Takes effect…" note sits
   under it. The approved preview showed the saved note *instead of* the lock note, so the two
   `settings_base_fee_weakening` preview baselines change (intended). The other preview baselines are unchanged.
2. **Approximate is all or nothing per screen.** If any tier the screen shows lacks a cached price, the stepper and
   the ladder all show USD with the note, so one line never mixes currencies.
3. **Shorter ladder strings** for max snoozes 1 and 2: "Snooze 1: {price1}" and "Snooze 1: {price1} · 2: {price2}",
   added to EXPERIENCE.md next to the 3-entry string.
4. **The ladder follows the chosen max snoozes** (pending value if any), like the stepper.
5. **Max snoozes note:** raising max snoozes under the lock shows the same "Saved. Takes effect…" note on its
   sub-screen. Max snoozes has no lock-note copy of its own, so none is added.
6. **A failed save** reverts the stepper to the stored value and is logged. No snackbar: there is no approved copy,
   and the store is a local DataStore.
7. **`SettingsUiState`** now carries display strings (`baseFee`, `feeLadder`) and `baseFeeTier`, not `Money`, because
   Play's `formattedPrice` is a string. The preview samples format their fake prices with `formatMoney` as before, so
   they render identically.
8. **A stale note** (its occurrence has passed, but the change is not promoted yet) is hidden. The value shown is
   still the chosen one, which is now effective.

## I/O & Edge-Case Matrix

| Case | Expected |
|---|---|
| Tier 1, cache has tiers 1, 2, 3 | "$1.00" (Play string), ladder "Snooze 1: $1.00 · 2: $2.00 · 3: $3.00", − disabled |
| Tier 10 | + disabled; ladder tiers 10, 20, 30 |
| Max snoozes 1 / 2 | ladder with 1 / 2 entries |
| Empty cache (never online) | USD via `MoneyFormatter`, the approximate note, steppers usable |
| Cache missing only tier 3 | all USD, approximate note |
| Expired cache entry (> 30 days) | treated as missing |
| 23:40, alarm 07:30, lower fee 3 → 1 | stepper shows tier 1, "Saved. Takes effect after tomorrow's 7:30 AM alarm." |
| 01:00, alarm 07:30, lower fee | "…after today's 7:30 AM alarm." |
| Raise fee / lower max snoozes inside the window | applies at once, no note |
| Raise max snoozes 2 → 3 at 23:40 | pending, note on the Max snoozes sub-screen |
| No alarm inside 8 h | every change applies at once, no note |
| Save fails | stepper returns to the stored value, failure logged |
| Session active | Settings is replaced by "Alarm in progress" |

</intent-contract>

## Code Map

- `composeApp/.../ui/settings/`:
  - `SettingsContract.kt`: the state now holds display strings, the tier and the two notes;
  - `SettingsScreen.kt`, `SnoozePanes.kt` (new; the Base fee and Max snoozes panes moved out of `SettingsScreen.kt`):
    the ladder variants, both notes and the max snoozes note;
  - `SnoozePrices.kt` (new): the pure price and note mapping;
  - `SettingsViewModel.kt` (new);
  - `SettingsRoute.kt` (new): the ViewModel, Back on sub-screens.
- `composeApp/.../ui/components/Stepper.kt`: long-press repeat.
- `composeApp/.../ui/nav/AppNavHost.kt`: the Settings tab uses `SettingsRoute`.
- `composeApp/.../ui/UiModule.kt`: the `SettingsViewModel` binding.
- `composeApp/.../composeResources/values/strings.xml`: two ladder strings.
- `androidApp/src/debug/.../preview/`: the samples and the reducer use the new state. The Settings samples are getters
  and the catalogue takes them as lambdas, because they format prices with the phone's `MoneyFormatter` (ICU), which
  is not available when the parameterized tests build their case list.
- Tests:
  - `composeApp/src/commonTest/.../ui/settings/` (ViewModel and `SnoozePrices`);
  - `androidApp/src/test/.../ui/SnoozeSettingsScreenshotTest.kt`, `SnoozeSettingsSemanticsTest.kt`,
    `SnoozeSettingsRouteTest.kt`.
- `EXPERIENCE.md`: the shorter ladder strings.

## Tasks & Acceptance

- [x] The Snooze card shows "Base fee" and "Max snoozes per session" (56 dp `settings-row`s), which open their
  sub-screens.
- [x] Base fee: the Play price, − disabled at 1 and + at 10, long-press repeat, the ladder, and the lock note always.
- [x] Never online: USD, the approximate note, usable.
- [x] Lowering the fee or raising max snoozes at 23:40 before a 07:30 alarm shows the saved value and the note
  (tomorrow / today). Strengthening, or no alarm within 8 h, applies with no note.
- [x] Screenshots: loaded, approximate, lock note after lowering, max snoozes 1 and 5, in Light, Dark and 200 %.
- [x] Semantics: targets ≥ 48 dp, the stepper value is a polite live region with the localized price.
- [x] ViewModel tests (`FakePriceCatalog`, `FakePendingChangeRepository`, `FakeClock`): immediate, pending, offline and
  bounds.
- [x] `CopyRulesTest` passes; the key strings are verbatim.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the approved `SettingsScreen`,
  `PpsStepper`, `NoteInline` and `GroupCard`; the stepper repeat adds no visuals.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: Settings is an app screen (Light
  and Dark); Roborazzi in Light, Dark and Light at 200 %.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: no new pairs (`text` and `text-secondary` on
  `glass`, `outline` / `disabled-content` stepper rings, as in the preview).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: the stepper buttons are 48 dp, rows 56 dp, Back 48 dp (semantics
  test). No wake action here.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: 200 % screenshots. The stepper buttons read
  "Lower Base fee" / "Raise Base fee". The value is a polite live region ("Base fee, $2.00"). The notes merge their
  icon and text. No outcomes here.
- [x] Reduced-motion path works: the sub-screen slide is the shared `subScreenTransition` (instant with animator
  duration scale 0). The long-press repeat is input, not motion.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources): the key strings
  are verbatim. The two shorter ladder strings are added to EXPERIENCE.md. `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: "Prices never loaded" (USD tiers
  and the note), "Weakening under lock" (the saved note), and the session lock.
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: not a wake screen. Prices are in
  `text`, never green or red.
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi
  or Roborazzi): the design-preview catalogue items `settings_base_fee*` and `settings_max_snoozes` (the weakening
  baseline is re-recorded for decision 1), plus new production screenshots.

## Rebase notes

- **4.6** stacks on this. It reuses `SnoozePrices` (the ladder and approximation) for the editor's Snooze sub-screen,
  and `WeakeningNote` / `weakeningNoteOf` for the pending-field notes.
- **Epic 5** adds its rows to `SettingsRoute`'s row set and its fields to the ViewModel.
- `editor_fee_ladder` stays the 3-entry string; the 1- and 2-entry strings are `settings_fee_ladder_1/2`.

## Verification

- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`.

## Review (fast mode)

No HIGH findings. Every item below is fixed in `fix(4.5): review fixes`, each with its test.

1. **The stepper could flicker back and step from the old value.** The echo check and the screen read the store
   through separate subscriptions.
   - Fix: a request now carries `saved`, and the render pass that sees the store hold it lets it go
     (`forgetEchoed`). The save loop no longer reads the store itself.
   - Test: with the screen's copy of the store late, the shown value never goes back, and a tap after the echo saves
     the next step (`SettingsViewModelSequenceTest`).
2. **TalkBack never heard that a weakening waits.**
   - Fix: the "Saved. Takes effect after…" notes are polite live regions.
   - Test: a semantics test on both sub-screens.
3. **The quick-steps test could not fail.**
   - Fix: it now holds the first save at a gate. Four taps show 7 before any save finishes, and the writes are
     [4, 5, 6, 7].
4. **Failed-save sequences were not covered.** New tests:
   - a failed save(4) with 5 queued keeps 5, which is then saved;
   - a failed save moves the stepper at once, then back;
   - the stored note is hidden while another value is being saved.
5. **Today and tomorrow were tested in UTC only.** The 23:40 and 01:00 cases now also run in UTC+7 and on the
   Europe/Helsinki spring-forward night of 2027-03-28.
6. **The hold-repeat test used a fixed state.** A host now applies the intents:
   - hold − from 3 down to 1, then release;
   - tap +, then tap − once;
   - exactly one `LowerBaseFee` follows, which checks that `repeated` resets on Cancel.
- **Minor:** saves still queued or running when the ViewModel is cleared are logged as dropped (tested through a
  `ViewModelStore`).

## Auto Run Result

See the commit `feat(4.5)` and the coordinator's report.
