---
title: 'Story 1.8: Create and edit an alarm'
type: 'feature'
created: '2026-09-27'
status: 'done'
baseline_commit: '56c085b35131b1a52ce485a2c41992887b676b8f'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-7-store-alarms-in-app-db.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Alarms can be stored (Story 1.7) but the app has no way to create or edit one: `App()` shows only the app name.

**Approach:** Implement Story 1.8 in `epics.md`: Navigation 3 with an Alarms route (empty state + FAB) and the Alarm editor screen (ViewModel with `StateFlow<UiState>`, `onIntent`, `Channel<UiEffect>`) using `SaveAlarm`, the one-time "Rings tomorrow" note, validation, and the discard dialog, fully themed with `PpsTheme`, with screenshot and semantics tests.

## Boundaries & Constraints

**Always:**
- Navigation 3 (pinned 1.1.2, JetBrains CMP): sealed `@Serializable Route : NavKey` registered with `subclassesOfSealed`; routes `Alarms` and `AlarmEditor(alarmId: String?)`.
- Alarms route: with no alarms, empty state "No alarms yet." + `button-filled` "Add your first alarm"; `fab` "+" with content description "Add alarm"; both open the editor with defaults. With alarms, a minimal interim list (time in `title` with tabular figures, label or repeat summary, tap to edit) until Story 1.9 builds `card-alarm`, countdown and toggles.
- Editor order: `time-picker` (keyboard input first, 12/24 h per system setting, digits in `display` tabular), repeat `chip-day`s M T W T F S S (TalkBack reads full day names, selected = accent fill), label `text-field`, snooze `segmented-control` 5 / 9 / 10 / 15 min (9 selected), Sound row showing the default sound's name (not tappable yet; picker is Story 1.17), volume `slider`, "Gradually increase volume" `switch` (default on), starting-volume `slider` only while on (5% steps, value announced), vibration `switch`, bottom `button-filled` "Save". Not shown: fee ladder, checks, grace window, motivation, "Test alarm".
- One-time alarm whose time has passed today: `note-inline` "Rings tomorrow at {time}." with the time per system 12/24 h, computed with Story 1.6 `nextOccurrence` and the `Clock`/`TimeZoneProvider` ports.
- Save calls `SaveAlarm` (enabled) and returns to Alarms; label over 40 characters blocks Save with supporting text "Keep the label under 40 characters." in `error` colour; other `InvalidAlarm` fields map to field errors.
- Editing an existing alarm with changes: Back or top-app-bar back shows `dialog-confirm` "Discard changes?" with "Discard" / "Keep editing" ("Keep editing" is the default dismiss); with no changes Back closes immediately. New alarms with changes prompt too.
- Only `PpsTheme` tokens; strings in resources; EXPERIENCE.md key strings verbatim; `CopyRulesTest` passes; `:composeApp` depends only on `:core` (use cases via Koin `uiModule`).
- Tests: ViewModel tests with `FakeAlarmRepository` and fake time cover save, edit, validation and discard; Roborazzi screenshots for new alarm, edit alarm, one-time-tomorrow note and discard dialog in Light, Dark and Light at 200% font scale with nothing clipped; semantics tests that every touch target is ≥ 48 dp and every control has a TalkBack label with role and state.
- pps-design Done checklist copied into Implementation Notes and ticked (N/A with reason where it doesn't apply).
- New runtime artifacts (Navigation 3, lifecycle-viewmodel, koin-compose) reviewed into `config/dependency-allowlist.txt`.

- Owner-approved strings (2026-09-27) for copy EXPERIENCE.md doesn't define: editor titles "New alarm" · "Edit alarm"; section labels "Repeat" · "Label" · "Snooze length" · "Sound" · "Volume" · "Starting volume" · "Vibration"; snooze options "5 min" · "9 min" · "10 min" · "15 min"; slider values "{percent}%"; default sound name "Sunrise" (placeholder until Story 1.17); save failure snackbar "Couldn't save the alarm. Try again."; top-app-bar back (TalkBack) "Back".

**Never:**
- No alarm cards, next-alarm countdown, enable toggles or delete UI (Story 1.9); no scheduling (1.10); no sound picker (1.17); no test alarm (1.18).
- No strings that are not in EXPERIENCE.md or in the owner-approved list above.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First open | no alarms | empty state + FAB | N/A |
| New alarm defaults | FAB | 9 min snooze, volume 80, gradual on (start 20), vibration on, no days | N/A |
| Passed one-time | now 08:00, time 07:00, no days | note "Rings tomorrow at 07:00." (or "7:00 AM") | N/A |
| Future one-time / repeat | time later today, or any day chips | no note | N/A |
| Gradual off | switch off | starting-volume slider hidden | N/A |
| Save valid | Save | stored enabled, back to Alarms, row visible | N/A |
| Label 41 chars | Save | not saved, field error text | `InvalidAlarm(label)` shown on field |
| Storage failure | repository fails | stays on editor with a generic error | `StorageFailure` never shown raw |
| Edit, no change | Back | closes immediately | N/A |
| Edit, changed | Back / top-bar back | "Discard changes?" dialog; Keep editing default | N/A |
| Discard | "Discard" | closes without saving | N/A |

</frozen-after-approval>

## Code Map

- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/App.kt` -- becomes the Navigation 3 host inside `PpsTheme`.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/theme/` -- `PpsTheme`, tokens, `PpsTheme.colors/typography/spacing` accessors (Story 1.3); detekt bans raw colours/radii/sp outside this package.
- `composeApp/src/commonMain/composeResources/values/strings.xml` -- add strings; `CopyRulesTest` scans it.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/UiModule.kt` -- register ViewModels (Koin compose).
- `core/.../alarm/AlarmUseCases.kt` (`AlarmDraft`, `SaveAlarm`, `AlarmWriteLock`), `Alarm.kt`, `AlarmOccurrence.kt` (`nextOccurrence`), `core/.../time/TimePorts.kt` -- use as-is.
- `testing/.../AlarmFakes.kt`, `TimeFakes.kt` -- fakes for ViewModel tests.
- `androidApp/src/test/.../ScreenshotOptions.kt`, `androidApp/src/testDebug/...ThemeShowcaseScreenshotTest.kt` -- screenshot pattern and shared 0.1% threshold; baselines under `androidApp/src/test/screenshots/`; `MainActivityTest` / `empty_screen.png` will change because `App()` now shows the Alarms route.
- `gradle/libs.versions.toml` -- `navigation3-ui`, `androidx-lifecycle-viewmodel-compose`, `koin-compose` aliases exist.
- `config/dependency-allowlist.txt` -- add reviewed runtime artifacts.

## Tasks & Acceptance

**Execution:**
- [x] `composeApp/.../ui/nav/` -- `Route`, nav host in `App()`.
- [x] `composeApp/.../ui/alarms/` -- Alarms screen (empty state, FAB, interim rows) + ViewModel.
- [x] `composeApp/.../ui/editor/` -- editor screen, `EditorUiState`, intents, effects, ViewModel + ViewModel tests.
- [x] `composeApp/.../ui/components/` -- themed components used here (time picker, day chips, segmented control, slider, switch row, note-inline, dialog-confirm, fab, button-filled, top-app-bar) with previews.
- [x] `composeApp/src/commonMain/composeResources/values/strings.xml` -- key strings + approved strings.
- [x] `androidApp/src/test*/...` -- editor/alarms screenshot tests (Light, Dark, 200%) and semantics tests; re-record `empty_screen.png`.
- [x] `config/dependency-allowlist.txt` -- reviewed additions.

**Acceptance Criteria:**
- Given the branch, when `./gradlew qualityGate` runs, then it passes including the new screenshots, semantics tests, ViewModel tests and `CopyRulesTest`.
- Given the debug app on the phone, when the user adds, saves and reopens an alarm, then the editor round-trips every field.

## Implementation Notes

- `./gradlew qualityGate` passes locally (BUILD SUCCESSFUL). The on-device check (Galaxy A57) has not been run yet.
- **Navigation:** `Route` (sealed, `@Serializable`, `NavKey`) with `Alarms` and `AlarmEditor(alarmId)`, registered through `subclassesOfSealed` in `RouteSavedStateConfiguration`. `AppNavHost` uses `NavDisplay` with the saveable-state and ViewModel-store entry decorators (`lifecycle-viewmodel-navigation3`), so each editor gets a fresh ViewModel that is cleared on pop. Opening the editor only happens from the Alarms route (a double tap cannot stack two editors), and a close never pops the root. The editor handles Back itself through `NavigationBackHandler` (navigationevent-compose), so Back and the top-bar arrow go through the same `BackRequested` intent.
- **Dependencies:** `navigation3-ui`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-navigation3` (new alias, same JetBrains lifecycle 2.11.0), `koin-compose`, `koin-compose-viewmodel` (new alias) and `kotlinx-serialization-core` (new alias; Navigation 3 alone puts 1.7.3 on the compile classpath, and `subclassesOfSealed` needs 1.9+). 24 runtime artifacts added to `config/dependency-allowlist.txt` with a review comment; none opens a network connection. `:composeApp` still depends only on `:core`.
- **Editor ViewModel:** `StateFlow<EditorUiState>`, `onIntent(EditorIntent)`, `Channel<EditorEffect>` (`Close`, `ShowSaveFailed`). Existing alarms load through the `AlarmRepository` port (there is no get use case); a missing alarm closes the editor. Fields the editor does not show (sound, grace window) are carried over from the stored alarm. Save always sends `enabled = true`. `InvalidAlarm(Label)` shows the field error, which clears as soon as the label fits; other `InvalidAlarm` fields and `StorageFailure` show the approved snackbar "Couldn't save the alarm. Try again." (no other field error copy exists). To keep the ramp valid, the starting volume can never be above the volume: it is clamped and follows the volume down. "Unsaved changes" means the form differs from how it was opened, so a change undone by hand closes without the dialog.
- **Rings tomorrow:** computed in the ViewModel with `nextOccurrence` and the `Clock`/`TimeZoneProvider` ports, on open and on every change (it is not recomputed while the editor just sits open past the alarm time). Time formatting and the 12/24 h setting are `expect`/`actual` in `ui.format` (`DateFormat.is24HourFormat`, `java.time` patterns `HH:mm` / `h:mm a`, locale day names for the chips "M T W T F S S", TalkBack "Monday" and the "Mon, Wed, Fri" repeat summary).
- **Components** (`ui/components`, previews in `androidMain`): `PpsTimeInput` (Material 3 `TimeInput`, keyboard first), `DayChip`/`DayChipRow` (FlowRow, so seven 48 dp chips wrap on 360 dp screens instead of shrinking), `PpsSegmentedControl` (check icon drawn inside the label and equal-height segments, because the Material icon slot made "9 min" overflow at 200%), `PercentSlider` (48 dp thumb, since the Material slider is 44 dp tall; content description plus state description "80%"), `SwitchRow` (whole row toggles with the switch role), `ValueRow` (Sound), `NoteInline`, `ConfirmDialog` (safe action = dismiss and end position; "Discard" in `error`), `PpsTextField`, `PpsFilledButton`, `PpsTextButton`, `PpsFab`, `PpsTopAppBar`, `PpsSnackbarHost`. New Material Symbols Rounded drawables: add, arrow_back, check, info.
- **Alarms route:** app name as the headline (keeps the emulator smoke test valid), empty state, FAB at 20 dp from the edges, and interim rows (time in `title` with tabular figures, label or repeat summary, whole row is one button). A storage failure while observing shows the empty list.
- **Tests:** `AlarmEditorViewModelTest` (20 tests: defaults, tomorrow note incl. zone port, save/edit round trip, empty label, missing alarm, label validation, storage failure and retry, ramp clamp, gradual off, all discard paths, double Save), `AlarmsViewModelTest`, `UiModuleTest` (Koin builds both ViewModels). `androidApp`: `AlarmScreensScreenshotTest` (18 baselines: new, edit, tomorrow, discard dialog, empty Alarms, Alarms list, each in Light, Dark, Light 200%), `AlarmScreensSemanticsTest` (every actionable node has touch bounds of at least 48 dp, a label, a role or is a text field or slider, and toggle/selected/value state; this caught the 44 dp slider), and `MainActivityTest` now covers the real app with Koin, Room and navigation: empty state, add then save then reopen round trip, Back with no change, and discard dialog with Keep editing and Discard. `empty_screen.png` re-recorded (it now shows the Alarms empty state).
- **Strings:** only EXPERIENCE.md key strings and the owner-approved list, plus the EXPERIENCE.md repeat summary "Every day" / "Once". Locale time and day names come from the platform, not resources. `CopyRulesTest` passes.
- **Risk:** the 200% baselines were recorded on Windows; if Linux CI differs only by rendering noise, re-record from the CI artifact (Story 1.3 note). The Material 3 AM/PM toggles are 36 dp tall visually; their touch bounds pass 48 dp through Material's minimum interactive size.

### pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes). detekt raw-value rules pass; slider ticks use `Color.Transparent` (allowed).
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots. Sunrise N/A: no wake screen in this story.
- [x] Every colour pair used is in the `DESIGN.md` contrast table (text, text-secondary and outline on bg/surface/surface-variant, on-accent/accent, accent/bg, error/bg and error/surface, inverse pairs). The switch's unchecked track is `bg`, not `surface-variant`, so its outline pair is listed.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp. Wake actions N/A (no wake screen); 48 dp checked by `AlarmScreensSemanticsTest`.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present. 200% screenshots with nothing clipped, semantics tests for labels, roles and states. Outcome glyphs N/A (no outcomes on these screens).
- [x] Reduced-motion path works: the only motion is the Navigation 3 transition and Material component animations, which follow the system animator duration scale (0 = instant).
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources).
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: Home Empty, and Alarm editor "One-time alarm time already passed today". Loading uses a blank screen, not a skeleton (reads finish well before 300 ms); weakening under lock and "No check selected" N/A (Epics 2 and 3).
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced. N/A: no wake screen.
- [x] Compose `@Preview`s for each state (light/dark, empty/error) exist (`AppPreview.kt`, `AlarmEditorPreview.kt`, `ComponentsPreview.kt`); Roborazzi screenshot tests updated.

- Orchestrator: split `AlarmEditorViewModel.onIntent` into navigation and form intents to satisfy detekt CyclomaticComplexMethod after the review fixes; behaviour unchanged.

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter, edge-case-hunter, verification-gap):

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | New alarm can be saved twice (`isSaving` reset before close) | high | Second tap in the close gap passes the guard with `id = null` | patch |
| 2 | Back/Discard/edits accepted during save | medium | "Discard" closes while the save still stores; later edits dropped | patch |
| 3 | Edit save after external delete loops on "Couldn't save" | low | `NotFound` shown as generic failure | patch |
| 4 | Non-label `InvalidAlarm` fields never rendered (frozen spec: "map to field errors") | medium | Only reachable via hidden stored fields; sanitizing them makes the label the only reachable field error | patch |
| 5 | Volume slider silently clamps hidden ramp start | low | Clamp applied while gradual is off | patch |
| 6 | Tomorrow note shows chosen time in a DST gap | low | Formats `form.time`, not the occurrence | patch |
| 7 | Snackbar blocks `Close` effect | medium | `showSnackbar` suspends inside `collect` | patch |
| 8 | Alarms storage failure shows "No alarms yet." | medium | `.catch` emits empty state | patch (no copy) + defer copy |
| 9 | Time picker state not keyed on 12/24 h | low | `rememberTimePickerState` unkeyed | patch |
| 10 | No IME insets; keyboard covers label/Save | medium | No `imePadding` in repo | patch |
| 11 | Day chips wrap at 360 dp | medium | 7×48 dp + gaps > 360 − margins; screenshots at 411 dp only | patch |
| 12 | Time edits never reach the ViewModel in any test | medium | No test changes hour/minute/AM-PM | patch |
| 13 | Label-error state has no screenshot/semantics test | medium | No sample with `fieldError` | patch |
| 14 | Save-failure snackbar path untested | medium | No test asserts the snackbar | patch |
| 15 | Back-stack save/restore (Route serialization) untested | medium | No `recreate()` test | patch |
| 16 | Load `StorageFailure` closes the editor with no message | medium | Needs owner-approved copy | defer |
| 17 | 12 h format hardcoded `h:mm a` | low | App is English-only | reject |
| 18 | 12/24 h setting change not observed live (broadcast) | low | Rare; picker now keyed | reject |
| 19 | Tomorrow note not refreshed while the editor sits open past the time | low | Recomputed on every change | reject |
| 20 | `PpsSlider` step not validated | low | Internal component, fixed call sites | reject |
| 21 | Screen tests boot the whole app | low | Works; isolation is a refactor | reject |
| 22 | On-device round trip (AC 2) not yet run | n/a | Owner manual check on the Galaxy A57 before merge/after | owner |

## Design Notes

- Time formatting per system 12/24 h: read the platform setting through a small port or `expect/actual` in `:composeApp` (no clock reads; detekt `NoDirectTimeAccess` applies).
- Screenshot risk: text-heavy screens may exceed the 0.1% Linux/Windows threshold; if CI fails only on rendering noise, re-record from the CI artifact (Story 1.3 note).
- Environment (company PC): JDK 17 at `C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1` (export `JAVA_HOME`; `./gradlew --stop` after build-logic changes).

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.

**Manual checks (if no CLI):**
- On the Galaxy A57 (debug build): add an alarm, set every field, save, reopen, confirm values; change and press Back to see the discard dialog.
