---
title: 'Story 5.1: Wake and snooze defaults and theme override in Settings'
type: 'feature'
created: '2026-10-15'
status: 'ready-for-dev'
baseline_revision: 'be7a2f04a6275b452b9177cc1695c9e4f203d296'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-5-snooze-settings-base-fee-and-max-snoozes.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Settings shows only Base fee and Max snoozes (Story 4.5). New alarms always start at 9 min snooze, 20 s
quiet time with vibration, and the app always follows the system theme (FR-SET-1 remaining, UX theme override). The
approved design preview already draws the Snooze row "Default snooze length", the Wake card, the Appearance card and
their sub-screens.

**Approach:** store the defaults and the theme in the settings DataStore, edit them through four core use cases, show
the approved rows, give a new alarm the stored defaults, and theme the app from the stored mode.

- **Core:** `GlobalSettings` gets `defaultGraceSeconds` (15–30, 20), `defaultVibrateInGrace` (on), `defaultSnoozeLengthMinutes`
  (5/9/10/15, 9) and `themeMode` (`ThemeMode` System/Light/Dark, System). `SetDefaultGrace`, `SetDefaultVibrateInGrace`,
  `SetDefaultSnoozeLength` and `SetThemeMode` go through one `SaveDefaultSetting`. It rejects out-of-range values with
  `DomainError.InvalidSetting` (new `SettingField`s) and writes nothing. It runs inside `SessionLockGuard.whenIdle`. It has
  no commitment lock and no pending change.
- **Data:** the four new keys in `DataStoreGlobalSettings`. A missing or out-of-range value reads as the default. The
  last-known snapshot cache also keeps them, so the first frame uses the stored theme.
- **Settings:** `BuiltSettingsRows` adds Default snooze length, Default quiet time, Vibrate during quiet time and
  Appearance. `SettingsViewModel` shows the stored values and handles their intents through a sequential save queue.
  The new value shows at once. A failed save is logged and the stored value comes back.
- **Editor:** a new alarm (not an edit, not a Duplicate) opens with the stored defaults (`newAlarmDefaults`).
- **Theme:** `App` themes every app screen with the stored mode. Wake screens stay Sunrise (`WakeSurface`).

## Boundaries & Constraints

**Always:**
- Every write goes through the core use cases. Defaults affect only alarms created later and never alter stored alarms
  or a running session (AD-16).
- Use the approved layout, existing components and existing string resources only. The key strings are verbatim.
- Switches and the segmented control apply at once (no Save). The slider saves each step in order.

**Never:**
- No Bright wake screen (Story 5.2), weekly summary, usage stats or reliability rows. They stay hidden.
- No new tokens, colour pairs or strings. No commitment lock on these fields. Wake screens never take the app theme.

## DEFAULTS TAKEN (owner can change)

1. **Session lock:** default and theme writes are refused during a session, like every user-facing write (Story 2.6
   scan). Settings is unreachable then anyway.
2. **Layout:** "Vibrate during quiet time" is a switch on the Wake card (approved preview and EXPERIENCE.md), not on
   the Default quiet time sub-screen.
3. **First frame:** the theme comes from the last-known settings snapshot (device-protected, not backed up). After a
   backup restore the first frame may follow System until the store is read.
4. **New-alarm editor:** it shows only the background until the defaults are read, as when an alarm opens. If the read
   fails, it logs and uses the built-in defaults.
5. **Duplicate:** a duplicate keeps the copied alarm's values, not the defaults.
6. **Failed save:** logged, and the value reverts. No snackbar, as in Story 4.5 (no approved copy).
7. **Deferred work:** `deferred-work.md` assigns no item to Story 5.1. The open "keep each tab's saved state" item
   (Story 1.9) is not assigned here, so it stays open.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Set grace | 25 | stored 25; row "25 seconds" |
| Grace out of range | 14 or 31 via the use case | `InvalidSetting(DefaultGrace)`, nothing written; ViewModel ignores it |
| Snooze length | 10 / 7 | stored / `InvalidSetting(DefaultSnoozeLength)` |
| Vibrate / theme | off / Dark | stored at once |
| During a session | any set | `SessionActive`, nothing written |
| Stored garbage | grace 99, theme "Purple" | reads 20 / System |
| New alarm after defaults 15 min, 30 s, vibrate off | editor "+" | form 15 / 30 / off, no unsaved changes |
| Existing alarm | edit after changing defaults | keeps its own values |
| Theme Dark, system light | app screens | Dark set; Home re-renders at once; survives restart |
| Theme System | system flips to night | app follows |
| Theme Light or Dark | wake screen | Sunrise |
| Save fails | toggle vibrate | switch returns to stored value, failure logged |

</intent-contract>

## Code Map

- `core/.../core/session/SessionConfig.kt` (`GlobalSettings`) and a new `core/.../core/session/ThemeMode.kt`: the
  renamed default fields (`graceSeconds` → `defaultGraceSeconds`, etc.; only `ConfigResolverTest` used them),
  vibrate defaults to `Alarm.DEFAULT_VIBRATE_IN_GRACE`, and `themeMode`.
- `core/.../core/config/SettingsUseCases.kt`: the new `SettingField` entries. A new `DefaultSettingsUseCases.kt` holds
  `DefaultSetting`, `SaveDefaultSetting` and the four `Set*` use cases, plus `GlobalSettings.with(DefaultSetting)`.
- `core/.../core/config/ConfigPorts.kt`: four `GlobalSettingsRepository` setters.
- `core/.../core/config/PendingChange.kt`: `StoredSnapshot` gets optional fields. The theme is stored as a string, so
  an unknown value never breaks the fire's fallback.
- `data/.../config/DataStoreGlobalSettings.kt`: the keys and validated reads. Tested in `data/src/androidHostTest/.../ConfigRepositoriesTest.kt`.
- `data/src/androidHostTest/.../SessionLockGuardScanTest.kt`: add `SaveDefaultSetting` to the expected writers.
- `testing/.../ConfigFakes.kt`: `FakeGlobalSettingsRepository` setters.
- `composeApp/.../ui/settings/SettingsViewModel.kt`, `SettingsRoute.kt`: the rows, intents, the queue and the overlay.
- `composeApp/.../ui/editor/AlarmEditorViewModel.kt`: `newAlarmDefaults: GlobalSettingsRepository?`.
- `composeApp/.../ui/App.kt`: the theme from `GlobalSettingsRepository` (initial value from `lastKnown()`).
- `composeApp/.../ui/UiModule.kt`, `androidApp/.../YawnAndPawnApp.kt`: the Koin wiring.
- Tests: `core/src/commonTest/.../config/DefaultSettingsUseCasesTest.kt`;
  `composeApp/src/commonTest/.../settings/SettingsDefaultsViewModelTest.kt`;
  `composeApp/src/commonTest/.../editor/AlarmEditorDefaultsTest.kt`; and in `androidApp/src/test/.../ui/`:
  `DefaultsSettingsScreenshotTest.kt`, `DefaultsSettingsSemanticsTest.kt`, `ThemeOverrideTest.kt` (real app, Home
  screenshots per mode), and updates to `SnoozeSettingsRouteTest.kt` and `SnoozeSettingsScreenshotTest.kt`.

## Tasks & Acceptance

**Execution:**
- [ ] Core: the model, the use cases and the port, plus their tests.
- [ ] Data: the store, the cache codec and the fakes, plus their tests. Update the scan.
- [ ] ViewModel and route: the rows, the intents, immediate apply and failure revert.
- [ ] Editor: the defaults for a new alarm.
- [ ] App theme: wiring and the real-app tests.
- [ ] Screenshots: the Snooze, Wake and Appearance cards, the Default snooze length and Default quiet time
  sub-screens (Light, Dark, 200 %), and Home under System (day and night), Light and Dark.

**Acceptance Criteria:**
- Given Settings, when it renders, then the Snooze card has "Default snooze length" ›, the Wake card has "Default
  quiet time" › and the "Vibrate during quiet time" switch, and the Appearance card has System / Light / Dark. Every
  target is ≥ 48 dp and the slider value is announced.
- Given a picked theme, when it changes, then app screens re-render at once, the choice survives an activity restart,
  System follows the system night mode, and a wake surface stays Sunrise.
- Given new defaults, when "+" opens the editor, then the form uses them; an existing alarm keeps its values.

## pps-design Done checklist

- [ ] Only tokens from `DESIGN.md` used: the approved `SettingsScreen`, `NavRow`, `SwitchRow`, `RadioRow`,
  `StepSlider` and `PpsSegmentedControl`. No new visuals.
- [ ] Light, Dark (and Sunrise where relevant) checked with screenshots: the Settings cards and sub-screens in Light,
  Dark and 200 %, Home under every mode, and a wake surface stays Sunrise (test).
- [ ] Every colour pair used is in the contrast table: no new pairs.
- [ ] Touch targets ≥ 48 dp: the semantics test covers rows (56 dp), radio rows, the switch row, the segments and
  Back. There is no wake action here.
- [ ] Works at 200 % font scale and with TalkBack: 200 % screenshots. The slider exposes its range and value text. The
  segments are selectable with their labels.
- [ ] Reduced-motion path works: the shared `subScreenTransition`; no new motion.
- [ ] Copy matches `EXPERIENCE.md > Voice and Tone`: existing resources only, key strings verbatim, and
  `CopyRulesTest` unchanged.
- [ ] Every state row for Settings is handled: the session lock (unchanged). There is no loading flash: the title
  shows until the store is read.
- [ ] "I'm up" / snooze: not a wake screen.
- [ ] Previews and screenshot tests: the design-preview catalogue already has these states. New Roborazzi baselines,
  and the 4.5 main-card baselines re-recorded because the card now has its third row (intended).

## Spec Change Log

## Review Triage Log

## Verification

**Commands (targeted, `--no-daemon --max-workers=2`):**
- `:core:jvmTest` (or the core host test task) for `DefaultSettingsUseCasesTest`, `ConfigResolverTest`, `SettingsUseCasesTest`.
- `:data:testAndroidHostTest` for `ConfigRepositoriesTest` and `SessionLockGuardScanTest`.
- `:composeApp` host tests for the settings and editor ViewModel tests.
- `:androidApp:testDebugUnitTest` for the new and updated UI tests, with Roborazzi record, then verify.
- `spotlessCheck detekt`.
