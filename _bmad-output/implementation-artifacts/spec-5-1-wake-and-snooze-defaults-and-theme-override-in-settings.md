---
title: 'Story 5.1: Wake and snooze defaults and theme override in Settings'
type: 'feature'
created: '2026-10-15'
status: 'done'
baseline_revision: 'be7a2f04a6275b452b9177cc1695c9e4f203d296'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-5-snooze-settings-base-fee-and-max-snoozes.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Settings saves still queued when the user leaves the Settings tab are dropped, now for the defaults and the theme too.
    evidence: |-
      The ViewModel is scoped to the tab's nav entry, which a tab switch removes (Story 1.9 tab-state item), and onCleared drops the queue (Story 4.5). Needs an app-scoped save path.
    location: >-
      composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/settings/SettingsViewModel.kt (onCleared)
    severity: medium
  - summary: >-
      API 26-28 with 3-button navigation may draw nav icons in the scrim's colour when the theme overrides the system.
    evidence: |-
      enableEdgeToEdge picks the API < 29 nav scrim from the system night mode; SystemBarIcons follows the app theme. Settle on an API 28 emulator.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/MainActivity.kt (enableEdgeToEdge)
    severity: medium (unverified)
  - summary: >-
      Owner decision: the shared PpsSegmentedControl wraps the selected label mid-word at 200 % font ("Syste / m").
    evidence: |-
      androidApp/src/test/screenshots/settings_defaults_appearance_light_font200.png; nothing is clipped.
    severity: low
  - summary: >-
      Story 5.9 "Delete all data" must also clear the settings_fallback shared preferences, which now hold the theme and defaults.
    evidence: |-
      SharedPreferencesSettingsCache keeps the last-known snapshot; the 5.9 plan lists the DataStores and the price cache only.
    location: >-
      data/src/androidMain/kotlin/com/yawnandpawn/app/data/config/SharedPreferencesSettingsCache.kt
    severity: medium
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
- [x] Core: the model, the use cases and the port, plus their tests.
- [x] Data: the store, the cache codec and the fakes, plus their tests. Update the scan.
- [x] ViewModel and route: the rows, the intents, immediate apply and failure revert.
- [x] Editor: the defaults for a new alarm.
- [x] App theme: wiring and the real-app tests.
- [x] Screenshots: the Snooze, Wake and Appearance cards, the Default snooze length and Default quiet time
  sub-screens (Light, Dark, 200 %), and Home under System (day and night), Light and Dark.

**Acceptance Criteria:**
- Given Settings, when it renders, then the Snooze card has "Default snooze length" ›, the Wake card has "Default
  quiet time" › and the "Vibrate during quiet time" switch, and the Appearance card has System / Light / Dark. Every
  target is ≥ 48 dp and the slider value is announced.
- Given a picked theme, when it changes, then app screens re-render at once, the choice survives an activity restart,
  System follows the system night mode, and a wake surface stays Sunrise.
- Given new defaults, when "+" opens the editor, then the form uses them; an existing alarm keeps its values.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used: the approved `SettingsScreen`, `NavRow`, `SwitchRow`, `RadioRow`,
  `StepSlider` and `PpsSegmentedControl`. No new visuals.
- [x] Light, Dark (and Sunrise where relevant) checked with screenshots: the Settings cards and sub-screens in Light,
  Dark and 200 %, Home under every mode, and a wake surface stays Sunrise (test).
- [x] Every colour pair used is in the contrast table: no new pairs.
- [x] Touch targets ≥ 48 dp: the semantics test covers rows (56 dp), radio rows, the switch row, the segments and
  Back. There is no wake action here.
- [x] Works at 200 % font scale and with TalkBack: 200 % screenshots. The slider exposes its range and value text. The
  segments are selectable with their labels. Owner to look at: at 200 % the selected "System" segment wraps mid-word
  beside its check ("Syste / m", `settings_defaults_appearance_light_font200.png`), the shared component's accepted
  wrap; nothing is clipped.
- [x] Reduced-motion path works: the shared `subScreenTransition`; no new motion.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: existing resources only, key strings verbatim, and
  `CopyRulesTest` unchanged.
- [x] Every state row for Settings is handled: the session lock (unchanged). There is no loading flash: the title
  shows until the store is read.
- [x] "I'm up" / snooze: not a wake screen.
- [x] Previews and screenshot tests: the design-preview catalogue already has these states. New Roborazzi baselines,
  and the 4.5 main-card baselines re-recorded because the card now has its third row (intended).

## Spec Change Log

- Implementation notes (no change to the intent):
  - The ViewModel takes the four use cases in one `DefaultSettingSetters` holder. A default request carries an id, so
    a switch toggled back and forth during a save settles only its own request. A value already shown is not
    queued again (a slider drag repeats it every frame).
  - The editor's new-alarm form is built in `EditorNewAlarm.kt`. `AlarmEditorViewModel` already sat at detekt's
    `LargeClass` limit, so it now suppresses it next to `TooManyFunctions`, and the stored alarm and the new alarm
    share one `open` step.
  - `FakeGlobalSettingsRepository` suppresses `TooManyFunctions` (one function per port function).
  - `MainActivityTest`'s "unbuilt tabs" test now expects the Story 5.1 rows and still checks that Bright wake screen
    and the checklist are hidden.

## Review Triage Log

### 2026-10-16 — Review pass (fast mode: one combined reviewer)
- verdicts: 14 findings — high 0, medium 4, low 8, false 0, maybe-false 2
- findings:
  - `[medium]` `[defer]` A default or theme save still queued when the user leaves the Settings tab is dropped (`SettingsViewModel.onCleared`). The root cause predates this story: the nav entry is removed on a tab switch (the Story 1.9 tab-state item) and the queue is dropped (the Story 4.5 design). The fix needs an app-scoped save path. Recorded in `deferred-work.md`.
  - `[low]` `[patch]` `App.kt`'s KDoc said a failed read keeps the current theme, but `observe()` reads an unreadable store as the defaults (System). Fixed the comment: an unreadable store reads as System, like every other setting.
  - `[low]` `[reject]` `lastKnown()` reads SharedPreferences on the main thread at the first frame. It is one small read, once per process. Moving it off the main thread would bring back the theme flash this read prevents.
  - `[maybe-false]` `[defer]` On API 26–28 with 3-button navigation, the nav icons may match the scrim when the theme overrides the system. Medium if true. Settle on an API 28 emulator. Recorded in `deferred-work.md`.
  - `[low]` `[reject]` The new-alarm defaults read has no timeout. A stalled DataStore blocks opening a stored alarm the same way. A timeout would add a new branch for a case nobody has seen.
  - `[medium]` `[patch]` `SessionLockScreenTest` asserted "Save" right after "+", but a new alarm now opens asynchronously. It now waits for "Save" (`waitUntil`).
  - `[medium]` `[patch]` No real-app test checked that "+" uses the stored defaults. `SnoozeSettingsRouteTest`'s Story 5.1 test now stores 25 s through `SetDefaultGrace`, taps "+", and checks "25 seconds" and "15 min" in the Koin-wired editor.
  - `[medium]` `[patch]` In `ThemeOverrideTest`, the first-frame test passed without using `lastKnown()`. The new test "the first frame uses the last-known theme before the store is read" uses a store whose `observe()` never emits and whose last-known snapshot is Dark. It fails if `App` ignores `lastKnown()`. The System-on-a-light-phone part of the finding is not a defect: System is the default, and the override tests (Dark on light, Light on dark) plus the screenshot baselines show the colours.
  - `[low]` `[reject]` The wake-surface test does not go through the stored theme. `WakeSurface` hard-codes `PpsTheme(wake = true)`, `WakeActivity` never reads the setting, and `PpsThemeTest` covers every mode with wake = true.
  - `[low]` `[reject]` The main "each control saves" ViewModel test runs unconfined. The gated tests (slider, toggle back and forth, failure) already cover the queue. Interleaving more fields would only repeat that coverage.
  - `[low]` `[patch]` `onCleared` logged dropped default saves as "save snooze setting". Each kind is now logged under its own operation (`SAVE_SETTING` / `SAVE_DEFAULT`).
  - `[low]` `[reject]` `LargeClass` is suppressed on `AlarmEditorViewModel`. The class was already at the limit before this story, and splitting it is a refactor outside this story. Named in the report for the coordinator.
  - `[low]` `[reject]` The Code Map names `SnoozeSettingsScreenshotTest.kt`, but only its baselines changed. The fix would edit this build's spec.
  - `[maybe-false]` `[defer]` Two points:
    - At 200 % the selected "System" segment wraps mid-word. This is the shared approved component, so it is an owner decision.
    - The `settings_fallback` cache now holds the theme and the defaults, so Story 5.9 must clear it.
    Both are recorded in `deferred-work.md`.

## Verification

**Commands (targeted, `--no-daemon --max-workers=2`):**
- `:core:jvmTest` (or the core host test task) for `DefaultSettingsUseCasesTest`, `ConfigResolverTest`, `SettingsUseCasesTest`.
- `:data:testAndroidHostTest` for `ConfigRepositoriesTest` and `SessionLockGuardScanTest`.
- `:composeApp` host tests for the settings and editor ViewModel tests.
- `:androidApp:testDebugUnitTest` for the new and updated UI tests, with Roborazzi record, then verify.
- `spotlessCheck detekt`.

## Review (fast mode)

The coordinator's independent review of PR #67 had one combined reviewer and no HIGH findings. All 7 items are fixed
in `fix(5.1): review fixes`, each with its test.

1. **(medium) Queued default saves were dropped on a tab switch.** A tab switch clears the Settings ViewModel, which
   cancelled the save under way and dropped the queue.
   - Fix:
     - The running default or theme save finishes under `NonCancellable`.
     - A queued default superseded by a newer value of its field is skipped, so a slider drag saves its first and
       last steps only.
     - The latest queued value of each field is saved after it in the app scope (`outlivingScope`, Koin
       `SettingsViewModel.SAVE_SCOPE` → `ApplicationScope`).
     - Base fee and max snoozes saves keep the Story 4.5 behaviour: dropped and logged.
   - Test: `SettingsDefaultsViewModelTest`. With the gate closed, 21 to 30 is sent, the ViewModelStore is cleared and
     the gate opened: 30 is stored, the writes are [21, 30], and nothing is logged as dropped. Without an outliving
     scope, a left value is logged as dropped and the running save still finishes. The slider and toggle tests now
     expect the coalesced writes. The deferred-work entry is marked resolved.
2. **(low) The window theme was always Light,** so Dark on a light phone flashed a light window.
   - Fix:
     - A `Theme.YawnAndPawn` window theme, light in `values` and dark in `values-night`. `WakeActivity` keeps the light
       `Theme.Material`.
     - `MainActivity` paints the window background with the stored theme's `bg` (the last-known snapshot) before the
       first frame.
     - `UiModeManager.setApplicationNightMode` is not used: it recreates the app on every change. So the system splash
       follows the phone (documented in `MainActivity`).
   - Test: `ThemeOverrideTest`. With Dark stored on a light phone, the window is the Dark `bg` at once, with no wait,
     and again after a restart.
3. **(low) A read error in `observe()` read as the defaults,** so the theme flipped to System.
   - Fix: `DataStoreGlobalSettings.observe()` now falls back to the last-known settings (else the defaults) on a read
     error.
   - Test: `ConfigRepositoriesTest`. With a broken file and Dark last known, the flow reads Dark.
4. **(low) Test gap.**
   - Test: `ConfigResolverTest`. A stored alarm (20 s, 9 min, vibration on) keeps its own values under changed defaults
     (30 / 15 / off).
5. **(low) The out-of-range test could not fail.**
   - Fix: it now also asserts that no save failed and that the screen never showed 14, 31 or 7.
6. **(low) The first-frame tests only proved "eventually Dark".**
   - Fix: both theme tests check the window colour at once, with no wait. The first-frame test uses a store that never
     emits, so its Dark can only come from the snapshot.
7. **(low) The TalkBack path of the slider was not exercised.**
   - Test: `SnoozeSettingsRouteTest`. `SetProgress(26)` on the real slider stores 26, "26 seconds" is its state
     description, and the card shows it.

## Auto Run Result

Status: done (fast mode, one combined review pass; the coordinator runs the independent review and CI).

- **Built:**
  - `GlobalSettings` holds the new-alarm defaults (quiet time, its vibration, snooze length) and the theme. They are
    stored in the settings DataStore and edited through `SetDefaultGrace`, `SetDefaultVibrateInGrace`,
    `SetDefaultSnoozeLength` and `SetThemeMode`, which go through `SaveDefaultSetting` (range check, session lock, no
    commitment lock).
  - Settings shows the Default snooze length, Default quiet time, Vibrate during quiet time and Appearance rows.
  - A new alarm opens with the stored defaults.
  - The app is themed from the stored mode. The first frame uses the last-known snapshot, and wake screens stay
    Sunrise.
- **Files:**
  - core: `DefaultSettingsUseCases.kt` (new), `ThemeMode.kt` (new), `SessionConfig.kt`, `ConfigPorts.kt`,
    `PendingChange.kt` (snapshot codec), `SettingsUseCases.kt`, `DomainError.kt`.
  - data: `DataStoreGlobalSettings.kt`.
  - testing: `ConfigFakes.kt`.
  - composeApp: `App.kt`, `UiModule.kt`, `SettingsViewModel.kt`, `SettingsRoute.kt`, `AlarmEditorViewModel.kt`,
    `EditorNewAlarm.kt` (new), `PpsTheme.kt` (mode converters).
  - androidApp: `YawnAndPawnApp.kt` (wiring).
  - Tests and baselines as in the Code Map, plus `SessionLockScreenTest`, `MainActivityTest` and `AlarmWiringTest`
    updates. There are 15 new baselines, and three 4.5 baselines (`settings_snooze_*`) were re-recorded on purpose.
- **Review:**
  - 4 patches applied: 3 medium (the test-sync wait, the real-app "+" defaults test, the first-frame test) and 1 low
    (the KDoc fix and the dropped-save log names together).
  - 3 entries deferred (4 deferred items): queued saves dropped on tab leave, API 26–28 nav icons, the 200 %
    segmented wrap, and the 5.9 cache clear.
  - 7 rejected, each with its reason in the triage log.
- **Follow-up review:** recommended (true): three medium patches.
  - The risk to confirm in CI on Linux: the new real-app tests (`SnoozeSettingsRouteTest` "+" defaults and
    `ThemeOverrideTest` first frame) are stable, and the Windows-recorded baselines verify within the threshold.
- **Verification (all `--no-daemon --max-workers=2`):**
  - `spotlessCheck` and `detekt` pass.
  - `:core:jvmTest`, `:data:testAndroidHostTest` and `:composeApp:testAndroidHostTest`, filtered to the touched
    classes, pass.
  - `:androidApp:testDebugUnitTest` passes for the new and updated UI tests (Roborazzi verify), `MainActivityTest`,
    `EditorRouteLifecycleTest`, `SessionLockScreenTest`, `AlarmScreensSemanticsTest`, `TryItNoStakesTest`,
    `HomeRouteLifecycleTest` and `CommitmentLockWakeTest`.
  - The full `qualityGate` was not run (CI runs it).
- **Residual risks:**
  - A new alarm now opens one DataStore read later.
  - `LargeClass` is suppressed on `AlarmEditorViewModel`.
  - The theme cache is not backed up, so the first frame after a restore follows System.
