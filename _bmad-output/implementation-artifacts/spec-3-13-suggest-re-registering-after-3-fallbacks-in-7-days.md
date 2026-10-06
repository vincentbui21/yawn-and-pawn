---
title: 'Story 3.13: Suggest re-registering after 3 fallbacks in 7 days'
type: 'feature'
created: '2026-10-06'
status: 'in-progress'
baseline_revision: '4899c12'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-9-fallback-check-picker.md'
warnings: []
deferred:
  - 'Story 3.10: real CheckRegistrations, the uiCheckType mapping and QR registration behind "Re-register" (deferred-work.md)'
---

<intent-contract>

## Intent

**Problem:** A user whose QR/Barcode code keeps failing can lean on the fallback check every morning and never fix the real check (FR-PWK-11). After 3 fallbacks in 7 days, Home must suggest re-registering that check.

**Approach:**
- **Pure rule:** `reRegisterSuggestion(history, alarms, checkConfigs, now, dismissedAt, usesCamera)` lives in `core.stats` and gives back the alarm and check type, plus the count. It counts the sessions of that alarm that meet all of these:
  - the first ring (`first_ring_at`) was in the last 7 × 24 h and not in the future;
  - the session used the fallback, with `fallback_from` equal to a camera type the alarm still has configured;
  - it was not a Test session;
  - it came after that check's last registration and after the banner's last dismissal for that alarm and type.

  The rule needs 3 such sessions. When several checks qualify, the one whose last counted fallback is newest wins.
- **Camera seam (3.9):** "is a camera check" is `CheckType::usesCamera` by default and can be injected, so the rule works for any camera check id. The tests use the placeholder as a stand-in, as `FakeCameraCheck` does.
- **Ports:**
  - `FallbackHistory`: Room's `observeFallbacks`, the rows with a known `fallback_from`, newest first.
  - `CheckRegistrations`: `None` until 3.10.
  - `ReRegisterDismissals`: the device-protected settings DataStore, one epoch-millis key per `reregister_dismissed/<alarm>/<type>`.
  - `ReRegisterSuggestions` combines the stored inputs and stores a dismissal at the clock's time.
- **Home:** `HomeViewModel` combines the inputs with the alarms it already lists and its own ticks. The 7-day window therefore moves with the clock, with no second read of the alarms and no second time receiver. It fills `reregisterCheck`, which the approved `home-missed` banner already renders. "Dismiss" stores the dismissal, and "Re-register" opens that alarm (see the deviations).

## Boundaries & Constraints

**Always:**
- The info variant of `banner-warning` is reused unchanged, below the reliability banner. The strings are the existing resources, verbatim from EXPERIENCE.md: "Fallback check used 3 times this week. Re-register your {checkName}?", "Re-register" and "Dismiss".
- The suggestion is derived, never stored (AD-18). Only the dismissal is stored, and it changes no alarm, fee or history row, so it may run during a session (lock-guard scan: `ReRegisterSuggestions` is allowed as display state, and `core.stats` is fully scanned).
- A failing read is logged and shows no banner, then retries with the missed note's growing pause. A failed dismissal is logged and the banner stays.
- The banner never shows while the alarms cannot be read, or for a type without a screen (the placeholder).

**Never:**
- No change to the AD-2 table, the reducer or the schema: this story only reads 3.9's `fallback_from`.
- No new screen state or string.

## AC deviations (QR/Barcode arrives with Lane 2's 3.10)

- **Inert in production:** before 3.10 no camera check type exists. Production therefore binds `CheckRegistrations.None`, and `uiCheckType` maps no core type to `QrBarcode`, so the banner cannot show yet. The rule, the DataStore, the ViewModel and the banner are all tested with the stand-in.
- **"Re-register" target:** QR registration for the alarm is 3.10's screen. Until it exists, "Re-register" opens that alarm's editor (`HomeEffect.OpenEditor(alarmId)`).
- **Clearing on a new code:** "saving a new code clears the banner" holds by construction, since only fallbacks after the last registration count (tested). 3.10 provides the registration time.

All three are in deferred-work.md, assigned to 3.10.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| Below threshold | 2 fallbacks this week | none |
| Threshold | 3 fallbacks, 1–7 days ago (7 days exactly counts) | suggested, count 3 |
| Window | one of 3 is 7 days + 1 h old; or the clock moves 4 days on | none (Home updates on the next tick) |
| Excluded | a Test session; a fallback from another check; `fallback_used` false; a ring in the future | not counted |
| Re-registered | registration after the fallbacks | none until 3 newer fallbacks |
| Dismissed | dismissal after the fallbacks; or older than the registration | none until 3 newer fallbacks; or no effect |
| Gone | check no longer configured; alarm deleted; no camera type | none |
| Two alarms | 2 each | none; with two suggestions, the newest last fallback wins |
| Home | Re-register / Dismiss; with no banner | opens the editor of the alarm / stores (alarm, type) at now; does nothing |
| Failures | fallback read fails; dismissal write fails | logged, no banner, retry after 1 s; logged, banner stays |

</intent-contract>

## Code Map

- **core:** `stats/ReRegisterSuggestion.kt` contains:
  - `ConfiguredCheck`, `CheckKey`, `ReRegisterSuggestion` and `reRegisterSuggestion`;
  - `ReRegisterRule` (3 fallbacks, 7 days);
  - the ports `FallbackHistory`, `CheckRegistrations` (`None`) and `ReRegisterDismissals`;
  - `ReRegisterInputs` and `ReRegisterSuggestions`.
- **data:**
  - `SessionHistoryDao.observeFallbacks`, with `RoomSessionHistoryRepository` implementing `FallbackHistory`;
  - `DataStoreReRegisterDismissals`;
  - the `DataModule` bindings for `FallbackHistory`, `CheckRegistrations.None` and `ReRegisterDismissals`.
- **testing:**
  - `FakeSessionHistoryRepository` is also a `FallbackHistory`;
  - `FakeReRegisterDismissals.kt` holds `FakeReRegisterDismissals` and `noReRegisterSuggestions()`.
- **composeApp:**
  - `HomeViewModel` takes `reRegister` and `uiTypeOf` (default `uiCheckType`), and handles the `ReregisterClicked` and `ReregisterDismissed` intents;
  - `UiModule` passes the new argument.
- **androidApp:** Koin `ReRegisterSuggestions`.
- **Tests:**
  - core: `ReRegisterSuggestionTest` (table);
  - data: `DataStoreReRegisterDismissalsTest`, `RoomSessionHistoryRepositoryTest` (fallback rows), `DataModuleTest`, `SessionLockGuardScanTest`;
  - testing: `FakeReRegisterDismissalsTest`;
  - composeApp: `HomeReRegisterBannerTest` (7 cases; `HomeViewModelTest` was already at detekt's size limit) and `UiModuleTest`;
  - androidApp: `HomeScreenshotTest` (6 new screenshots), `AlarmScreensSemanticsTest` (order, 48 dp, intents, 200% floor) and `BackupRulesCoverageTest` (writes the new key).

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md`: the approved `banner-warning` info variant, unchanged.
- [x] Screenshots with and without the reliability banner in Light, Dark and Light at 200% (`home_reregister_*`, `home_reregister_reliability_*`).
- [x] Every colour pair is in the contrast table (no new pair: the existing info variant).
- [x] Targets: "Re-register" and the close icon are ≥ 48 dp (asserted), and Home with both banners meets the accessibility floor at 200%.
- [x] TalkBack: the close icon reads "Dismiss", and "Re-register" is a button (asserted).
- [x] Reduced motion: no animation added.
- [x] Copy is verbatim from EXPERIENCE.md, with no new strings. `CopyRulesTest` is unchanged and passes.
- [x] State rows: `home-missed` (missed note and re-register banner) is the approved state; this story adds no new state.
- [x] Previews unchanged (`git status --porcelain androidApp/src/test/screenshots/preview` is empty).

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## Auto Run Result

Status: implemented in fast mode (one agent), waiting for review. Branch `story/3-13-suggest-re-registering-after-3-fallbacks-in-7-days`, stacked on 3.9 (`4899c12`).

**Verification:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest`: BUILD SUCCESSFUL (21 min). Kover (core, session, checks) is green.
- The preview baselines are unchanged.
- There are 6 new screenshots: `home_reregister_{light,dark,light_font200}` and `home_reregister_reliability_{light,dark,light_font200}`.

**Residual risks:**
- **Inert banner:** the banner stays hidden in production until 3.10 (see the deviations and deferred-work.md).
- **Schema:** no schema change. The rule reads 3.9's `fallback_from` (`app.db` v6 on this stack, renumbered at the rebase).
