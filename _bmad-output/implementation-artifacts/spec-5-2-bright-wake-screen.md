---
title: 'Story 5.2: Bright wake screen'
type: 'feature'
created: '2026-10-22'
status: 'done'
baseline_revision: 'ae9743af13583f44f8c65e5d269426c9f68dd53b'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-1-wake-and-snooze-defaults-and-theme-override-in-settings.md'
  - '{project-root}/docs/design-preview/round-4-bright-wake.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** the wake screens use the phone's own brightness, so a dark bedroom stays dark while the alarm rings. The
Settings Wake card already draws "Bright wake screen", but production hides it and nothing stores or applies it.

**Approach (owner decision 2026-10-22, option C "Ringing only", first A then changed to C):** store
`brightWakeScreen` (default on) like the Story 5.1 defaults, freeze it into `SessionConfig` at the fire, and while the
Ringing screen shows (with the snooze confirm sheet over it) set the `WakeActivity` window to
`BRIGHTNESS_OVERRIDE_FULL`. Every other wake screen (Check, Fallback check picker, Snoozed, Success) and every exit
path is at the phone's own level (`BRIGHTNESS_OVERRIDE_NONE`).

- **Core:** `GlobalSettings.brightWakeScreen` (true). `DefaultSetting.BrightWakeScreen` through `SaveDefaultSetting`
  (session lock, no commitment lock) and a `SetBrightWakeScreen` use case. `SessionConfig.brightWakeScreen` (default
  true so stored sessions decode), copied by `ConfigResolver.resolve` and `resolveTest`. The snapshot codec keeps it.
- **Data:** `DataStoreGlobalSettings.BRIGHT_WAKE_SCREEN` (`bright_wake_screen`, missing reads true).
- **Settings:** `BuiltSettingsRows` adds `BrightWakeScreen`; the ViewModel handles `BrightWakeScreenToggled` through the
  5.1 default queue.
- **Wake:** a `WakeBrightness` helper owns the window attribute. It applies full only when the shown screen is a
  session's Ringing screen, its frozen config says on, the activity is started, and Extra dim is off.
- **Preview:** round 4 keeps option C and the Settings switch; A and B are dropped; the doc records the decision.

## Boundaries & Constraints

**Always:**
- Window-scoped `screenBrightness` only. Permission allowlist unchanged (no `WRITE_SETTINGS`).
- The ring path reads the setting only from the frozen config, which the fire resolves through `ReadFireSettings`
  (500 ms budget, then the last-known snapshot, then defaults). A test ring takes it from the last-known snapshot at
  its fire (synchronous cache, never the store).
- Brightness never delays or breaks a ring: no store read, no suspend call and no throw on the wake screen path. An
  Extra dim read that fails counts as Extra dim off (full brightness), logged once per process (Story 5.13 device fix,
  see the Change Log).
- Restored on every exit: Check, Fallback, Snoozed, Success, `onStop` (Home, Recents, screen off), finish. Re-applied
  on `onStart` while the Ringing screen shows.
- With the setting off, the window attribute is never written.
- Sunrise look unchanged; no new tokens, components or colours.

**Never:** no brightness ramp (option B), no torch flash (EXPERIENCE.md D2), no blue-light or health claim in copy, no
change to the onboarding note (Story 5.12 uses it).

## DEFAULTS TAKEN (owner can change)

1. **Caption:** option C makes "Raises screen brightness on alarm screens." inaccurate. New caption "Raises screen
   brightness while the alarm rings." (the coordinator's suggested wording), in `strings.xml` and both EXPERIENCE.md
   copies. Listed for sign-off. Nuance: in the Loud phase on a check the alarm rings at the phone's level.
2. **Snooze sheet over a check:** stays at the phone's level (the check's level); only the sheet over the Ringing
   screen is full.
3. **Emergency ring and the brief wait before the session shows:** phone's level (no frozen config exists).
4. **Extra dim unreadable** (a `SecurityException` or any failure on API 31+): treated as off, so the Ringing screen
   turns bright; logged once per process. Changed 2026-10-26 after the 5.13 device check (was "treated as on"; see the
   Change Log).
5. **Test ring:** uses the last-known settings snapshot at its fire, else its stored value (true).
6. **Deferred work:** `deferred-work.md` assigns no item to Story 5.2.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Set on/off | toggle | stored at once; switch shows it |
| During a session | `SetBrightWakeScreen` | `SessionActive`, nothing written |
| Missing key | fresh store | reads true |
| Fire with setting off | resolve | `config.brightWakeScreen == false` |
| Setting changed mid-session | toggle after fire | frozen config unchanged |
| Ringing, on | WakeActivity | window `screenBrightness == 1f` |
| Ringing, off | WakeActivity | `screenBrightness` stays `-1f` (NONE), never written |
| "I'm up" → Check | Grace | `-1f` |
| Snoozed / Success / Done | screens | `-1f` |
| Home on Ringing | `onStop` | `-1f`; back (`onStart`) → `1f` |
| Extra dim on (API 31+) | `reduce_bright_colors_activated = 1` (readable) | `-1f` |
| Extra dim unreadable (API 31+) | read throws `SecurityException` | `1f`; the failure logged once per process |
| Delete all data | 5.9 | key cleared with the whole store and fallback cache |

</intent-contract>

## Code Map

- `core/src/commonMain/.../core/session/SessionConfig.kt` -- `GlobalSettings`, `SessionConfig`, `ConfigResolver.resolveLive` / `resolveTest`: add the field and copy it.
- `core/src/commonMain/.../core/config/DefaultSettingsUseCases.kt` -- `DefaultSetting`, `with`, `SaveDefaultSetting.write/invalidField`: add `BrightWakeScreen` and `SetBrightWakeScreen`.
- `core/src/commonMain/.../core/config/ConfigPorts.kt` -- `GlobalSettingsRepository.setBrightWakeScreen`.
- `core/src/commonMain/.../core/config/PendingChange.kt` -- `StoredSnapshot.brightWakeScreen: Boolean? = null` and the codec.
- `data/src/commonMain/.../data/config/DataStoreGlobalSettings.kt` -- key, setter, `settingsOf`.
- `testing/src/commonMain/.../testing/ConfigFakes.kt` -- fake setter.
- `composeApp/src/commonMain/.../ui/settings/SettingsViewModel.kt` -- `DefaultSettingSetters` (fifth setter), intent, `toUiState`, `shows`, `isValid`.
- `composeApp/src/commonMain/.../ui/settings/SettingsRoute.kt` -- `BuiltSettingsRows`.
- `composeApp/src/commonMain/.../ui/UiModule.kt`, `androidApp/.../YawnAndPawnApp.kt` -- Koin: `SetBrightWakeScreen`.
- `androidApp/src/main/.../android/wake/WakeBrightness.kt` (new) -- the window owner; `WakeActivity.kt` drives it from `setContent` (Ringing vs other) and `onStart`/`onStop`.
- `androidApp/src/main/.../android/wake/WakeService.kt` `onTest` -- test ring takes `lastKnown()` bright value.
- `composeApp/src/commonMain/composeResources/values/strings.xml` -- `settings_bright_wake_caption`.
- `docs/EXPERIENCE.md`, `_bmad-output/planning-artifacts/ux-designs/.../EXPERIENCE.md`, `docs/DESIGN.md`, planning `DESIGN.md` -- caption and the "brightness to maximum" lines.
- Preview: `androidApp/src/debug/.../preview/PreviewRound4.kt`, `PreviewCatalog.kt`, `PreviewIndex.kt`, `docs/design-preview/round-4-bright-wake.md`, `states.md`, baselines `androidApp/src/test/screenshots/preview/bright_*` from `origin/design/5-2-bright-wake-preview`.
- Read-only: `ReadFireSettings` (500 ms rule), `DeleteAllData` (clears the store; `DeleteAllDataIntegrationTest` gets the key).

## Tasks & Acceptance

**Execution:**
- [x] Core model, use case, codec + `DefaultSettingsUseCasesTest`, `ConfigResolverTest`, snapshot codec test.
- [x] Data key + `ConfigRepositoriesTest`; `DeleteAllDataIntegrationTest` fills the key; fakes.
- [x] Settings row, ViewModel, Koin; `SettingsDefaultsViewModelTest`; `MainActivityTest` rows.
- [x] `WakeBrightness` + WakeActivity + WakeService test ring; Robolectric `WakeBrightnessTest` for every matrix row.
- [x] Caption string and docs.
- [x] Preview round 4 (C only), states.md, doc note; Roborazzi re-record of the affected Settings and preview baselines.

**Acceptance Criteria:**
- Given Settings, when it renders, then the Wake card shows "Bright wake screen" on by default with the new caption, its switch is ≥ 48 dp and TalkBack reads its state.
- Given a ringing session with the setting on, when the Ringing screen or its snooze sheet shows, then the window is at full brightness; on every other wake screen and after any exit it is at the system level.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used: the existing `SwitchRow`; no new tokens, components or colours. The preview's
  phone-level scrim (Sunrise `inverse-surface`) is a debug-only simulation and does not ship.
- [x] Light, Dark and Sunrise checked with screenshots: Settings main screens (Light, Dark, 200 %) re-recorded with the
  new row; preview `bright_settings_*` (Light, Dark, 200 %) and `bright_c_*` (Sunrise, 200 %).
- [x] Every colour pair used is in the contrast table: no new pairs.
- [x] Touch targets ≥ 48 dp: `DefaultsSettingsSemanticsTest` checks the switch row at 200 % on a small phone. No wake
  action changed.
- [x] Works at 200% font scale and with TalkBack: the row is one `Role.Switch` node with label, caption and On/Off state
  (semantics test); the 200 % screenshots show no clipping.
- [x] Reduced-motion path works: no new motion (option B's ramp was dropped).
- [x] Copy: one changed string, the caption "Raises screen brightness while the alarm rings." (DEFAULTS TAKEN 1, listed
  for the owner), in both EXPERIENCE.md copies; no blue-light or health claim; the onboarding note is unchanged.
- [x] Every state row for this surface is handled: the matrix rows are covered by `WakeBrightnessTest`.
- [x] "I'm up" / snooze: wake screens unchanged.
- [x] Previews and screenshot tests updated: round 4 keeps C and the Settings switch; states.md regenerated.

## Spec Change Log

- Implementation notes (no change to the intent):
  - `WakeBrightness` (androidApp) owns the window attribute and writes it only when the wanted level changes, so with
    the setting off it is never written. `WakeActivity` feeds it from the composition (`SideEffect`): full only when the
    screen is `WakeScreen.Ringing`, no emergency ring, and the shown session's frozen config is on; Success always off.
    `onStart`/`onStop` drive it; `onStart` first sets the level from the session as it is now, after
    `runtime.wakeScreenShown`. Extra dim is read through `Settings.Secure` on API 31+ only when full would apply, at most
    once per start; a failed read counts as on and is logged.
  - PR #80 review fixes (all low): no full brightness while an "I'm up" tapped before the session waits for its replay
    (`pendingImUp`, and then that ring's key until the replayed "I'm up" lands: `!pendingImUp` alone still flashed for the
    frames between the replay and its dispatch, which the new test caught); no stale full level on `onStart` after the session moved on while stopped; Extra dim read lazily (not
    on every start); `extraDimOn`'s failure and pre-API 31 branches tested through an internal overload that takes the
    API level and the read; the after-Done check that could not fail replaced by a finish-while-bright test.
  - To stay under detekt's limits, the Success branch of `WakeActivity.onCreate` moved to `SuccessShown`, and the
    Settings default intents map through one `defaultOf(intent)`.
  - The test ring: `WakeService.onTest` copies `lastKnown()?.settings?.brightWakeScreen` into the pending config
    (wrapped in `runCatching`), else keeps the value `resolveTest` stored when "Test alarm" was tapped.
  - `SessionJsonTest` also checks that a session stored before 5.2 decodes with the setting on.
  - EXPERIENCE.md (both copies): the Themes line, the caption, the Success row ("at the phone's own brightness") and flow
    F2 (brightness returns on "I'm up", not on "Done"). DESIGN.md (both copies): the theme-strategy sentence.
  - Tests that expected the row hidden now expect it: `MainActivityTest`, `SnoozeSettingsRouteTest`,
    `DefaultsSettingsSemanticsTest`.

- 2026-10-26, Story 5.13 device finding (Oppo A96, ColorOS, Android 13, Play build 0.1.3): the Ringing screen never
  turned bright. Every wake screen start logged `OperationFailed operation=read extra dim cause=SecurityException`; the
  phone was at about 22 % manual brightness and it never changed during the ring. This is the deferred review finding
  (high if true), now confirmed.
  - **Cause:** `reduce_bright_colors_activated` is `@hide` and has no `@Readable` annotation in AOSP (Android 12, where
    it was added, through main). From S, `SettingsProvider.enforceSettingReadable` throws `SecurityException` for such a
    key unless the caller is a system app, platform-signed or installed `testOnly` (an Android Studio debug run is
    `testOnly`, so dev builds can read it while Play builds never can). Robolectric does not enforce it, so the tests
    passed. No public API exposes Extra dim: `ColorDisplayManager` is hidden and system-only, and `android.jar` (API 36
    and 37) has no Extra dim method or `Settings.Secure` constant.
  - **Decision (DEFAULTS TAKEN 4 reversed):** an unreadable Extra dim counts as off, so the window goes to full
    brightness. The failure is passed to `onFailure` (the `read extra dim` log line) at most once per process, not on
    every start. Trade-off: on Play builds the app cannot tell when Extra dim is on, so it raises the window to full
    while Extra dim still dims the panel on top; the "respects Extra dim" wording (UX-DR10, EXPERIENCE.md) only holds
    where the key is readable. 5.13 checklist item 27 (v2 ok) will show the screen forced bright with Extra dim on.
  - **Read kept, not skipped on API 31+:** on every API 31+ phone the read fails for this app, but it stays because it
    costs one settings call, only when full brightness could apply and at most once per start, and it still respects
    Extra dim where the key is readable (`testOnly` debug installs, an OEM build that exposes it). Skipping it would
    leave the Extra dim branch as dead code; removing it is an owner call for v2.
  - Tests: `WakeBrightnessTest` "an Extra dim read that throws counts as off and reports its cause once per process"
    (two failing reads, one report) and "with Extra dim unreadable the Ringing screen is at full brightness". The
    `round-4-bright-wake.md` note now says an unreadable Extra dim does not stop the screen turning bright.

## Review Triage Log

### 2026-10-22 — Review pass (fast mode: one combined reviewer covering blind, edge-case, verification-gap and intent lenses)
- verdicts: 17 findings — high 0, medium 1, low 11, false 4, maybe-false 1
- findings:
  - `[low]` `[patch]` Grace or Loud on a placeholder step (or a check whose puzzle cannot be built) falls back to the Ringing screen, so it stayed bright after "I'm up". — `ringsBright` now also needs `state is SessionState.Ringing`.
  - `[low]` `[reject]` The emergency ring and the brief wait before the session stay at the phone's level, then jump to full when the session shows. — DEFAULTS TAKEN 3: there is no frozen config there; the wait is under the 500 ms fire budget, the emergency ring is a failure fallback, and the fix adds a settings read and a branch to the wake screen.
  - `[maybe-false]` `[defer]` `reduce_bright_colors_activated` is a hidden `Settings.Secure` key; if it is not `@Readable`, apps targeting S+ get a `SecurityException` and the feature never turns on. — If true: high (unverified). Settled on the Oppo in Story 5.13. The patch below now logs a failed read ("read extra dim"), so the device check can tell it from Extra dim being on.
  - `[low]` `[patch]` The Extra dim setting was read on every recomposition of the Ringing screen. — `WakeBrightness` now reads it once per `onStart` and caches it; the KDoc says so. The helper test counts one read per start.
  - `[false]` `[reject]` The docs say a mid-session change "has no effect", but the save is refused. — Settings is unreachable during a session (session lock panel), so a user never meets the refusal; the frozen config is what the docs describe (AD-16).
  - `[medium]` `[patch]` `epics.md` still describes option A (UX-DR10, the 5.2 caption and ACs, the 5.13 device check "returns to normal on Done"). — Updated UX-DR10, the 5.2 caption AC, an option C AC line, and the 5.13 checklist item 7.
  - `[low]` `[patch]` The emergency ring had no test. — `WakeBrightnessTest` "the emergency ring's Ringing screen stays at the phone's level".
  - `[low]` `[patch]` No test finished the activity while it was at full brightness. — `WakeBrightnessTest` "a ring that ends Missed while at full brightness closes the screen at the phone's level". The Success `SideEffect` stays as a guard, not separately tested.
  - `[low]` `[patch]` A `ConfigResolverTest` case could never fail (it copied an immutable object). — Deleted; the Robolectric fire test covers the freeze.
  - `[low]` `[reject]` More cases have no test: the sheet over a check, the fallback picker, Loud on a check, restore after death, recreate, API < 31, a failed read, `lastKnown()` throwing. — They all take the tested "not the Ringing screen" or `onStart` branches; covering each adds harness work for no new branch.
  - `[low]` `[patch]` A ViewModel test named "during a session" only set the engine as not restored. — Renamed to say "before the session is restored", which is what it checks; the active-session refusal is tested in `DefaultSettingsUseCasesTest`.
  - `[low]` `[reject]` `defaultOf` has `else -> null`, so a new default intent could be dropped silently. — Each default intent comes with its own ViewModel test; restructuring trips detekt's complexity rule.
  - `[false]` `[reject]` `lastKnown()` in `WakeService.onTest` is a disk read on the main thread. — It is the one small SharedPreferences read the fire already relies on (Story 4.4 review fix 11); "never the store" means the DataStore, which is accurate.
  - `[low]` `[patch]` The preview scrim's KDoc said "about a third" for alpha 0.55. — The comments now say it is a dim look, not a measured level.
  - `[low]` `[reject]` The round 4 mock-up shows only Ringing and the Math check. — The owner chose from this mock-up; Snoozed and Success look as in rounds 1 to 3.
  - `[false]` `[reject]` The caption is not fully accurate, and asking for sign-off goes against deferring wording. — The coordinator asked for this wording and for it to be listed; the Loud nuance is recorded in DEFAULTS TAKEN 1.
  - `[false]` `[reject]` A recreate drops to the phone's level for a frame. — The new activity has a new window whose attribute starts at the phone's level anyway; skipping the reset in `onStop` would not change that.

## Verification

**Commands (`--no-daemon --max-workers=2`, targeted):**
- `:core:jvmTest` filtered to `DefaultSettingsUseCasesTest`, `ConfigResolverTest`, `PendingChange*`.
- `:data:testAndroidHostTest` filtered to `ConfigRepositoriesTest`, `DeleteAllDataIntegrationTest`, `SessionLockGuardScanTest`.
- `:composeApp:testAndroidHostTest` filtered to `com.yawnandpawn.app.ui.settings.*`.
- `:androidApp:testDebugUnitTest` filtered to `WakeBrightnessTest`, `MainActivityTest`, `AlarmWiringTest`, the Settings screenshot tests and `com.yawnandpawn.app.debug.preview.*`, Roborazzi record then verify.
- `spotlessCheck detekt`.

## Auto Run Result

Status: done (fast mode, one combined review pass; the coordinator runs the independent review and CI).

- **Built (option C, "Ringing only"):**
  - `GlobalSettings.brightWakeScreen` (on by default), stored as `bright_wake_screen` in the settings DataStore and the last-known snapshot, saved by `SetBrightWakeScreen` through `SaveDefaultSetting` (session lock, no commitment lock).
  - `SessionConfig.brightWakeScreen`, frozen by `ConfigResolver` at the fire (real rings through `ReadFireSettings`, so the 500 ms rule and the last-known fallback apply); a test ring takes it from the last-known snapshot at its fire.
  - `WakeBrightness` sets the wake window to full brightness only on a session's Ringing step and screen (the sheet over it too), while started and with Extra dim off (read only when full could apply, at most once per start; a failed read counts as on and is logged). Every other screen and every exit is at the phone's own level; with the setting off the window is never written.
  - Settings Wake card shows the switch, with the caption "Raises screen brightness while the alarm rings.".
  - After the rebase on Story 5.12: onboarding step 8's `brightWakeNote` follows the stored setting.
  - Design preview round 4 keeps option C and the Settings switch; the doc records the owner's choice.
- **Files:** core (`SessionConfig.kt`, `DefaultSettingsUseCases.kt`, `ConfigPorts.kt`, `PendingChange.kt`), data (`DataStoreGlobalSettings.kt`), testing (`ConfigFakes.kt`), composeApp (`SettingsViewModel.kt`, `SettingsRoute.kt`, `UiModule.kt`, `OnboardingViewModel.kt`, `strings.xml`), androidApp (`WakeBrightness.kt` new, `WakeActivity.kt`, `WakeService.kt`, `YawnAndPawnApp.kt`, preview `PreviewRound4.kt` new, `PreviewCatalog.kt`, `PreviewIndex.kt`), docs (`EXPERIENCE.md` and `DESIGN.md` both copies, `epics.md`, `round-4-bright-wake.md`, `states.md`), tests and baselines.
- **Review:** 17 findings. 8 patched (1 medium: `epics.md`; 7 low: the Ringing-step gate, Extra dim read once per start with a logged failure, two new tests, a test that could not fail removed, a test renamed, a preview comment). 1 deferred (the Extra dim key's readability, high if true, settled in 5.13). 8 rejected, each with its reason in the triage log.
- **Follow-up review:** not recommended (false): no high patched, one medium patched.
- **Verification (`--no-daemon --max-workers=2`):** `spotlessCheck` and `detekt` pass; `:core:jvmTest` (`DefaultSettingsUseCasesTest`, `ConfigResolverTest`, `PendingChangeJsonTest`, `SessionJsonTest`), `:data:testAndroidHostTest` (`ConfigRepositoriesTest`, `DeleteAllDataIntegrationTest`, `SessionLockGuardScanTest`), `:composeApp:testAndroidHostTest` (`ui.settings.*`, `ui.onboarding.*`) and `:androidApp:testDebugUnitTest` (`WakeBrightnessTest`, `Onboarding*`, `MainActivityTest`, `AlarmWiringTest`, `DefaultsSettings*`, `SnoozeSettings*`, `UsageStatsSettingsTest`, `TestAlarmFlowTest`, `debug.preview.*`) pass with Roborazzi verify. The full `qualityGate` was not run.
- **Residual risks:** the Extra dim read on a real API 31+ phone (5.13); the screen visibly turning bright is device-only; baselines recorded on Windows.
