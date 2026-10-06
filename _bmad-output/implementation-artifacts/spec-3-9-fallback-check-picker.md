---
title: 'Story 3.9: Fallback check picker'
type: 'feature'
created: '2026-10-06'
status: 'in-progress'
baseline_revision: '4e52a53'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-2-solve-math-to-stop-the-alarm.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-4-quiet-time-grace-window-with-the-countdown-ring.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** When a camera check cannot be done (camera or permission unavailable, code lost), nothing lets the user wake up some other way. Waking up must always be free (FR-PWK-11). Before this story the fallback was a stub (`NoFallbackPolicy`, `FallbackRequested` without a type), and it kept the old seeds and failed attempts.

**Approach:**
- **Policy:** the production `CameraFallbackPolicy` allows the fallback when all of these hold:
  - the current entry is a camera check;
  - the fallback was not used yet;
  - the chosen type is one of `CheckType.fallbackChoices` and needs no camera;
  - the camera is unavailable, or the entry failed at least 5 times.
- **Camera test seam:** the policy asks "is this a camera check" through an injectable function, which defaults to the type's own `usesCamera` (coordinator decision 1(c)). `FakeCameraCheck` in `:testing` treats the placeholder as a camera check.
- **Session:** `FallbackRequested(type, reason)` replaces the rest of the plan with one entry at Hard × 2 × the type's default count. The fallback gets its own seeds, no failed attempts and `StartCheckStep(0)`. Timers do not change. The plan stays for the session (`fallbackSource`, resolved again each ring), and `fallbackFrom` keeps the replaced type id for history.
- **Wake screen:** it shows the approved `fallback-link` and `fallback-picker`. History writes `session_history.fallback_from`, in `app.db` v6.

## Boundaries & Constraints

**Always:**
- The AD-2 rows are unchanged (R12 carries the type and reason).
- The link never shows after the fallback was used.
- Grace keeps counting and Loud keeps ringing in the picker. Back does nothing, and "Back to check" returns without using the fallback.
- Math is always first. The picker lists the fallback choices that have a core plugin and a screen: Math on this stack; Word Unscramble and Memory Sequence join with 3.7 and 3.8.
- `SessionRecorder` is the only writer of `fallback_from`.
- New code uses main's 3.1 names (`fallbackSource`, `nextRingPlan`).

**Never:** No matcher-error input (Story 7.7; the policy input `FallbackRequest` stays open for it). No camera screen or scanning (3.10, 3.11).

## AC deviations (decided 2026-10-06)

- **Picker footer:** the AC asks for the snooze control in the picker's footer. The approved `fallback-picker` screen has none, and the coordinator decided to keep it without (decision 2). Snooze is unavailable until Epic 4, and a deferred-work line asks Epic 4 to revisit.
- **Link in production:** no camera check type exists before 3.10, so the production link and picker are inert until 3.10 sets `usesCamera` on its QR/Barcode type. The flow is tested with a fake policy (Robolectric) and with `FakeCameraCheck` (reducer table).
- **Fallback reason:** until the camera check reports its camera state (3.10, 3.11), the wake screen offers the fallback for `FailedAttempts` only. `CameraUnavailable` is already handled by the policy and the reducer.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| Allowed | camera entry, reason CameraUnavailable, Math chosen | plan Math·Hard·6, own seeds, 0 failed attempts, `StartCheckStep(0)`, grace unchanged, `fallbackFrom` = replaced id |
| Failures | working camera, 4 / 5 failed attempts | denied / allowed |
| Denied | non-camera entry; already used; camera type chosen; type not a choice; Ringing; passed check | no change |
| Next ring | fallback used, paid snooze, re-ring | the fallback again with ring 2's fallback seed, never the camera check |
| Picker | link → picker → "Back to check" | the check again, no fallback; Back does nothing |
| History | session that used the fallback | `fallback_used` true, `fallback_from` = replaced id; old rows keep null (v5 → v6) |

</intent-contract>

## Code Map

- **core:**
  - `checks/CheckType.kt`: `fallbackChoices`.
  - `session/SessionPolicies.kt`: `FallbackReason`, `FallbackRequest`, the `FallbackPolicy` signature, `offers`, and `CameraFallbackPolicy` (`NoFallbackPolicy` is removed).
  - `SessionEvent.kt`: `FallbackRequested(type, reason)`.
  - `CheckRules.kt`, `CheckRun.kt` (`fallbackSource`, `fallbackFrom`, `nextRingPlan`) and `SnoozedRules.kt`.
  - `SessionRecorder.kt` and `history/SessionHistory.kt` (`fallbackFrom`).
- **data:** `SessionHistoryEntity`, the mapping, `MIGRATION_5_6`, `SCHEMA_VERSION` 6 and `schemas/.../6.json`.
- **testing:** `FakeCameraCheck`, and `FakeFallbackPolicy` (requests).
- **composeApp:** `wake/CheckMapping.kt` (`uiCheckType`, `coreCheckType`, `fallbackPickerUiState`).
- **androidApp:** `WakeCheck` (link, picker and intents), `WakeActivity` (`WakeScreen.Fallback`) and the Koin wiring.
- **Tests:**
  - core: `CameraFallbackPolicyTest`, transition-table R12, `SessionRecorderTest`;
  - testing: `FakeCameraCheckTest`;
  - data: the v5→v6 migration test;
  - composeApp: `CheckMappingTest`;
  - androidApp: `FallbackPickerFlowTest` and `FallbackScreenshotTest`.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md`: the approved composables, unchanged.
- [x] Sunrise screenshots of the link and the picker at 100% and 200%.
- [x] Every colour pair is in the contrast table (no new pair).
- [x] Targets: the link is ≥ 48 dp (asserted), the picker cards ≥ 64 dp (`targetWake`), the close button 48 dp.
- [x] 200% font and TalkBack: the title is a heading, the close button reads "Back to check", and the cards have role button (asserted).
- [x] Reduced motion: no animation added.
- [x] Copy is verbatim from EXPERIENCE.md ("Can't do this check?", "Pick a fallback check", "Back to check"), with no new strings.
- [x] State rows: the fallback link and the fallback picker; camera unavailable is 3.11.
- [x] "I'm up" is unchanged, and snooze shows on the check screen. The picker has no footer (the AC deviation above).
- [x] Previews unchanged (`fallback-picker`, `check-qr-camera-unavailable`). Roborazzi screenshots added (`wake_fallback_*`).

## Notes for the merge

- **Schema:** `app.db` is v6 on this stack (`fallback_from`, v5→v6). It renumbers at the rebase: after Lane 2's 3.5 (v5→v6) it becomes v6→v7.
- **Seeds:** the fallback seeds use this stack's `SeedDeriver.FALLBACK_BASE` and `CheckRun.seedKey`, which main renamed to `seed(..., fallback = true)` and `seedOf`. `totalFailedAttempts` (main) is not added here: the policy reads the entry's `failedAttempts`, as the AC says ("failedAttempts ≥ 5").

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## Auto Run Result

Status: implemented in fast mode (one agent), waiting for review. Branch `story/3-9-fallback-check-picker`, on the 3.4 review fixes (`e4dd3ea`) and the stopped spec (`4e52a53`).

**Verification:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest` gives BUILD SUCCESSFUL (14 min). Kover (core, session, checks) is green and the preview baselines are unchanged. There are 4 new screenshots: `wake_fallback_link_sunrise[_font200]` and `wake_fallback_picker_sunrise[_font200]`.

**Residual risks:**
- **Inert link:** the production link stays inert until 3.10 gives QR/Barcode `usesCamera = true`. The first real-device check of the whole flow is in 3.11 and 3.14.
- **Schema:** `app.db` v6 renumbers at the rebase.
