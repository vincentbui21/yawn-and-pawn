---
title: 'Story 4.6: Fee ladder, lock notes and turn-off confirmation in the editor and on Home'
type: 'feature'
created: '2026-10-09'
status: 'review'
baseline_revision: '0c3665c1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-4-commitment-lock-and-pending-changes-in-core.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-5-snooze-settings-base-fee-and-max-snoozes.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 4.4 decides the commitment lock in core and 4.5 shows it in Settings, but the alarm editor and Home
still behave as before Epic 4:
- The editor's Snooze sub-screen has no fee ladder.
- The editor opens with the live values. Re-saving an alarm cancels its pending weakening (4.4 decision 3).
- The editor closes silently after a weakening save.
- Home's switch turns an alarm off at once, even at 23:40 before a 07:30 ring.
- A turn-off or a delete inside the window writes no commitment event.

**Approach:**
- **Editor fee ladder.** The Snooze sub-screen shows "Snooze 1: {price1} · 2: {price2} · 3: {price3}" under the
  snooze lengths, with 1 or 2 entries when max snoozes is 1 or 2 (Story 4.5 strings).
  - The prices are those of the base fee and max snoozes that the alarm's **next ring** will charge. These are the live
    global settings plus the global pending changes that apply to that ring (`applyingTo`), so a change that waits for
    this ring or a later one is not shown.
  - Pricing reuses 4.5's `SnoozePrices`: Play's cached prices, or all USD approximations with "Approximate. Your local
    price shows when you're online.".
  - The ladder follows the form's time and repeat days, and it re-reads the settings, pending changes and prices on
    every change.
- **Pending values and notes.** A stored alarm opens with its pending `GraceSeconds` and `Checks` values selected
  (the QR code taken from the live plan, as when the change is promoted). The opened form is the baseline, so opening it
  is not an unsaved change.
  - While a field holds its pending value, its sub-screen shows "Saved. Takes effect after tomorrow's {time} alarm." (or
    "today's") under it, as a polite live region: Quiet time under the slider, Wake-up check under the picker.
  - The main screen shows the same note under the rows card (the approved `editor-weakening` preview).
  - Saving the pending value again keeps the pending change and its date. This is 4.4's rule for the same value, and it
    fixes 4.4 decision 3.
  - Setting a field back to its live value hides the note, and saving then cancels the pending change.
  - If the pending changes cannot be read, the editor closes with "Couldn't open this alarm.", so a save can never cancel
    them unseen.
- **Saved-under-lock snackbar.** The editor saves through `SaveAlarm.save`. When `pendingUntil` is set it closes with
  `EditorEffect.CloseSavedUnderLock(note)`. The nav host keeps the note in saved state, and Home shows it as a
  `snackbar` for 4 s.
- **Turn-off dialog on Home.** Switching off an alarm whose next ring is inside the window (LockWindow, 0 < t ≤ 8 h)
  opens the existing `dialog-confirm`: "Turn off your {time} alarm? It rings in {hours} h. This is logged.", or
  "…{minutes} min…" under 1 h.
  - Hours and minutes are whole units rounded down (`lockCountdownOf`), at least 1 min.
  - "Keep it on", Back or a tap outside writes nothing.
  - "Turn off" calls `SetAlarmEnabled(false)`.
  - Outside the window the switch turns off at once (Story 1.9).
- **Commitment events in core.**
  - `SetAlarmEnabled` writes `CommitmentEvent(Disabled)` when it turns an enabled alarm off inside its window.
  - `DeleteAlarm` writes `CommitmentEvent(Deleted)` inside the window and always logs `AlarmDeleted`. This moves that log
    out of `AlarmActions`, which closes the Epic 4 deferred item.
  - Both write inside their own `whenIdle`, right after the alarm change, through a small `CommitmentLog` (repository,
    ids, zone) that shares `commitmentEventOf` with `RecordCommitmentEvent`.

## Boundaries & Constraints

**Always:**
- **Alarm safety:** the alarm change comes first. A failed turn-off or delete changes nothing (the alarm stays stored,
  on and armed) and writes no event. A failed event write is logged, and the alarm change stands (the user asked for it,
  and it is stored). The dialog never leaves a half-saved alarm: nothing is written until "Turn off".
- Every write still goes through the 4.4 use cases (`SaveAlarm.save`, `SetAlarmEnabled`, `DeleteAlarm`), inside the
  session lock.
- The ladder never waits for a price refresh. The editor works offline.
- All strings are resources and match EXPERIENCE.md verbatim. No new strings, tokens or colour pairs.

**Never:**
- No change to the lock rules themselves (4.4) or to Settings (4.5).
- No Day detail rendering of the events (Story 6.x).
- No guard against deleting and recreating an alarm (owner decision 4).

## AC deviations and decisions (fast mode: default taken, owner can change)

1. **The events are written by the use cases, not by a separate `RecordCommitmentEvent` call.**
   - The AC says "Turn off … calls `RecordCommitmentEvent(Disabled)`". `SetAlarmEnabled` and `DeleteAlarm` write the
     same event (same rule, same occurrence) inside their own guarded write, so no path can skip it and a session cannot
     start between the two writes.
   - `RecordCommitmentEvent` stays as the stand-alone use case and shares `commitmentEventOf`.
2. **The order is alarm write first, then the event.** A failed event write leaves the alarm off (or deleted), with the
   failure logged. It does not roll back.
3. **Rounding:** the dialog's hours and minutes are rounded down (AC). This differs from F6's "It rings in 8 h" at
   23:40 for a 07:30 alarm, which reads "7 h" here.
   - At 59 min 59 s the dialog says "59 min".
   - Under a minute it says "1 min" (never "0 min").
4. **Main-screen note plus field notes.** The approved preview shows one note under the rows card. The AC asks for the
   note under each pending field, so both are shown: the main note, and the field note in each sub-screen. A field note
   shows only while that field holds its pending value.
5. **A duplicate opens with the live values** and no notes: a new alarm has no commitment.
6. **Unreadable pending changes close the editor** ("Couldn't open this alarm."), rather than opening with live values
   that a save would silently commit as "no change".
7. **The switch is treated as on while turning it on is still being stored**, so turning it off again at once asks too.
8. **`FullEditorSections.feeLadder` is now `List<LadderPrice>`.**
   - `Text` holds Play's string or an already formatted approximation.
   - `Amount` holds a `Money` that the screen formats; the design-preview samples use it.
   - `weakeningAppliesAfter: LocalTime` became `weakeningNote: WeakeningNote`, so the "today's" variant can show.
   - The preview baselines are unchanged.
9. **`AlarmActions` lost its `Clock`**, because `DeleteAlarm` logs the deletion now.

## I/O & Edge-Case Matrix

| Case | Expected |
|---|---|
| Switch off at 7 h 59 min | dialog "…It rings in 7 h…", nothing written yet |
| Switch off at 8 h 01 min | off at once, no dialog, no event |
| "Keep it on" / Back / outside | alarm stays on and armed, no event |
| "Turn off" (tapped twice) | alarm off, schedule cancelled once, one `Disabled` event for the given-up ring |
| "Turn off" with storage failing | alarm stays on and armed, no event, "Couldn't save the alarm. Try again." |
| Switch off at 42 min 30 s | "…It rings in 42 min…" |
| Delete inside / outside the window | `AlarmDeleted` logged; `Deleted` event only inside |
| Editor opens with pending quiet time 30 s (live 20 s) | slider at 30 s, notes shown, Back closes without "Discard changes?" |
| Save again unchanged | pending kept with its date, row keeps 20 s, Home snackbar |
| Quiet time back to 20 s, save | note hidden, pending cancelled, plain close |
| Weakening save at 01:00 before 07:30 | Home: "Saved. Takes effect after today's 7:30 AM alarm." |
| Pending read fails | editor closes, "Couldn't open this alarm." |
| Base fee 2, cache loaded | "Snooze 1: EUR 2.00 · 2: EUR 4.00 · 3: EUR 6.00" |
| Cache empty | USD approximations and the approximate note; updates when prices arrive |
| Max snoozes 2 | two entries |
| Global lower fee waiting for this alarm's 07:30 | ladder still at the live fee |
| Global lower fee that waited for another alarm's 06:00 | applies to the 07:30 ladder; not to a 05:30 one |

</intent-contract>

## Code Map

- `core/.../config/RecordCommitmentEvent.kt`: `CommitmentLog` and `commitmentEventOf`.
- `core/.../alarm/AlarmUseCases.kt`: `SetAlarmEnabled` and `DeleteAlarm` write the events (and the `AlarmDeleted` log)
  through the private `recordCommitment`.
- `composeApp/.../ui/editor/`:
  - `EditorLock.kt` (new): `EditorFeeSources`, `feeLadderOf`, `PendingFields`;
  - `AlarmEditorViewModel.kt`: the pending values, `withLock()`, the fee watcher, `save` with the note;
  - `AlarmEditorContract.kt`: `LadderPrice`, the three notes, `CloseSavedUnderLock`;
  - `EditorSubScreens.kt`, `AlarmEditorScreen.kt`: the ladder, the approximate note, the field notes and the route's
    `onSavedUnderLock`.
- `composeApp/.../ui/home/`: `HomeViewModel.kt` (`requestTurnOff`, `lockCountdownOf`, the saved snackbar),
  `HomeContract.kt`, `HomeSnackbar.kt`, `HomeScreen.kt`, `HomeRoute.kt`, `AlarmActions.kt`.
- `composeApp/.../ui/settings/`: `weakeningNoteOf(Instant?)`; `feeLadderText`, `WeakeningNoteInline` and
  `weakeningNoteText` are now internal and shared.
- `composeApp/.../ui/nav/AppNavHost.kt`: the editor's saved-under-lock result for Home, kept in saved state.
- `composeApp/.../ui/UiModule.kt`, `androidApp/.../YawnAndPawnApp.kt`: the Koin bindings.
- `testing/.../SchedulerFakes.kt`: the fixture gets `commitmentEvents`.
- `androidApp/src/debug/.../PreviewSamples.kt`: `LadderPrice.Amount`, `WeakeningNote`.
- Tests:
  - `core/.../alarm/CommitmentEventsOnAlarmChangeTest.kt`;
  - `composeApp/.../editor/AlarmEditorLockTest.kt`, `home/HomeTurnOffUnderLockTest.kt`;
  - `androidApp/src/test/.../ui/EditorLockScreenshotTest.kt`, `EditorLockSemanticsTest.kt`;
  - the existing Home toggle tests move to a 15:00 alarm, outside the window.

## Tasks & Acceptance

- [x] Editor Snooze sub-screen: the fee ladder for the next ring (loaded, approximate, max snoozes 1 and 2).
- [x] Pending fields open selected, with the note under each one and on the main screen. Re-saving keeps them.
- [x] A weakening save closes with Home's "Saved. Takes effect after …" snackbar (tomorrow or today). A strengthening
  save closes plainly.
- [x] The turn-off dialog inside the window (hours / minutes). "Keep it on" is the default dismiss. "Turn off" disables
  the alarm and writes `Disabled`. Outside the window there is no dialog.
- [x] A delete inside the window writes `Deleted` besides `AlarmDeleted`. Outside the window there is no event.
- [x] Screenshots: the fee ladder (loaded, approximate, max snoozes 2), the pending notes (main, quiet time, checks),
  both dialog variants and the snackbar, in Light, Dark and 200 %.
- [x] ViewModel tests with `FakeClock`: dialog at 7 h 59 min, none at 8 h 01 min, "Keep it on", events only inside the
  window.
- [x] `CopyRulesTest` passes; no new strings.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the approved `NoteInline`,
  `ConfirmDialog`, `AppSnackbar`, `RadioRow` and `GroupCard`; nothing new is drawn.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: the editor and Home are app screens
  (Light and Dark); Roborazzi covers Light, Dark and Light at 200 %.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: no new pairs (`text-secondary` notes on the gradient
  and glass, the dialog and `inverse-surface` snackbar as approved).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: the snooze-length rows and both dialog buttons are ≥ 48 dp at 200 %
  (semantics test). No wake action here.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present:
  - 200 % screenshots;
  - the pending notes and the snackbar are polite live regions;
  - the dialog body is read as its title, and "Keep it on" is the safe action;
  - no outcomes here.
- [x] Reduced-motion path works: no new motion. The sub-screen slide and the dialog are the shared components.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources): the existing
  resources `disable_under_lock_hours/minutes`, `disable_turn_off`, `disable_keep_on`, `editor_weakening_under_lock`,
  `settings_weakening_today`, `editor_fee_ladder`, `settings_fee_ladder_1/2` and `settings_prices_approximate` are
  verbatim. `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled:
  - "Weakening under lock" (editor notes and Home snackbar);
  - "Prices never loaded" (approximate ladder);
  - the `card-alarm` switch row "turning off within 8 h opens `dialog-confirm`";
  - the session lock (the use cases refuse; Home is replaced).
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: not a wake screen. Prices are in
  `text-secondary` notes, never green or red.
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi
  or Roborazzi): the design-preview items `editor_snooze`, `editor_weakening` and `home_disable_dialog` render as before
  (baselines unchanged), plus 26 new production screenshots.

## Strings needing owner sign-off

None are new. Two existing strings are reused in new places: "Saved. Takes effect after today's {time} alarm." (until
now Settings only) is used in the editor and in Home's snackbar, and "Approximate. Your local price shows when you're
online." is used in the editor's Snooze sub-screen.

## Rebase notes

- **`SetAlarmEnabled` / `DeleteAlarm` constructors** gained `CommitmentLog` and `Logger` (`DeleteAlarm` also gained
  `Clock`). `AlarmActions` lost `Clock`. A story that builds these by hand must follow.
- **`AlarmUseCasesFixture`** has `commitmentEvents`. Its `delete` logs `AlarmDeleted` to the fixture's `logger`, so a
  test that expects that log must pass its own logger to the fixture.
- **Epic 6 Day detail** reads the `commitment_event` rows written here (Disabled and Deleted, `occurrence_at` = the
  given-up ring).

## Verification

- Gate policy changed mid-story (coordinator, 2026-10-14): the PC runs out of memory with five lanes building, so CI runs
  the full `qualityGate` on the PR. Locally, with `--no-daemon --max-workers=2`:
  - `spotlessApply spotlessCheck detekt` (root and every module);
  - the new and changed test classes in `:core`, `:composeApp`, `:data` (`SessionLockGuardScanTest`) and `:androidApp`
    (the new screenshot and semantics tests, the Home and editor route tests, `AlarmWiringTest`, `HomeScreenshotTest` and
    the design-preview `PreviewScreenshotTest`, whose baselines are unchanged).

## Auto Run Result

See the commit `feat(4.6)` and the coordinator's report.
