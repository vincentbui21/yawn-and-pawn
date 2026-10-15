---
title: 'Story 5.3: Full reliability probe: Do Not Disturb, battery and the locked-screen test'
type: 'feature'
created: '2026-10-15'
status: 'ready-for-dev'
baseline_revision: 'be7a2f04'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-19-permissions-for-reliable-alarms-and-crash-reporting.md'
warnings: ['oversized']
deferred:
  - 'Story 5.4: the checklist screen, the Fix deep links for DND/Battery/Camera/Test alarm, and rewiring the Home banner from ReliabilityStatus.allOk to ReliabilityChecklist.alarmBlocking (EvaluateReliability is ready for it)'
  - 'Story 5.5: the maker mapping (DeviceInfo.makerCovered) and the guidance screen that calls ReliabilityRecords.confirmMaker'
---

<intent-contract>

## Intent

**Problem:** the Story 1.19 probe only sees notifications, the full-screen intent and exact alarms. Do Not Disturb, battery optimisation, background restriction, the restricted standby bucket and a camera grant can all stop an alarm, and nothing records whether the user ever rang a test with the phone locked.

**Approach:** extend `ReliabilityStatus`/`AndroidReliabilityProbe` with the five new values; freeze "the keyguard was locked when the test fired" into `SessionConfig.testStartedLocked`; the engine stores `lockedTestPassedAt` in the settings DataStore when such a test reaches Completed; a pure `ReliabilityChecklist` in core turns probe + alarms + device + stored records into the nine ordered rows and `alarmBlocking`. A small `EvaluateReliability` use case glues it together (and keeps the "last OK" set) for Story 5.4.

## Boundaries & Constraints

**Always:** core stays pure (AD-1); ports return `Outcome` (AD-12); settings only in the device-protected settings DataStore (AD-6); new `SessionConfig` field has a default so stored sessions and the pending test ring still decode; each Android call is gated on `sdkInt` (injectable); permission and dependency allowlists unchanged.

**Never:** no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_NOTIFICATION_POLICY` or `PACKAGE_USAGE_STATS`; no new screen, string or Home banner change (Story 5.4); no maker mapping (Story 5.5); no new `SessionEffect` (the transition table stays as is).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DND off, policy allows alarms | filter ALL, policy has ALARMS | `dndAllowsAlarms = true` | — |
| DND total silence | filter NONE | false | — |
| Scheduled DND would block | API 28+, filter ALL, policy lacks ALARMS | false | null policy counts as allowed |
| Battery | not ignoring / bg restricted (28+) / bucket RESTRICTED (30+) | matching flag; below the API level the value is the "good" one | — |
| Locked test passes | test fired with keyguard locked (or before first unlock), then Completed | `lockedTestPassedAt = now` stored | write failure logged, session unaffected |
| Unlocked test | keyguard unlocked at fire | nothing stored | keyguard read throws → counts as unlocked |
| Row was OK, now fails | `lastOk` contains row, row fails | Revoked (stays Revoked until fixed) | — |
| Row never OK, fails | not in `lastOk` | NeedsFix | — |

</intent-contract>

## Code Map

- `core/.../core/reliability/Reliability.kt` -- `ReliabilityStatus` (add 5 fields, defaults = good value; `firstFailing`/`allOk` keep the three Story 1.19 Home items), `ReliabilityProbe`.
- `core/.../core/reliability/ReliabilityChecklist.kt` (new) -- `ChecklistRow`, `RowStatus`, `ChecklistEntry`, `DeviceInfo`, `DeviceInfoSource`, `MakerConfirmation`, `StoredReliability`, `ReliabilityRecords`, `LockedTestRecorder`, `ReliabilityChecklist.evaluate/alarmBlocking/lastOkAfter`, `EvaluateReliability`.
- `core/.../core/session/SessionConfig.kt:29` -- add `testStartedLocked: Boolean = false`.
- `core/.../core/session/IdleRules.kt:24` + `SessionReducer.reduce` -- pass `keyguardLocked` to `idleRow`; TestAlarmFired sets `testStartedLocked = keyguardLocked || beforeFirstUnlock`.
- `core/.../core/session/SessionEngine.kt:219` -- read `unlock.isKeyguardLocked()` also for an Idle `TestAlarmFired` (failure → false); new ctor param `lockedTest: LockedTestRecorder = None`, called once on the transition into Completed of a locked test.
- `core/.../core/session/SessionPorts.kt:179` -- `UnlockPort` KDoc ("PayConfirmed only" → also TestAlarmFired).
- `androidApp/.../android/reliability/AndroidReliability.kt` -- probe values (`NotificationManager`, `PowerManager`, `ActivityManager`, `UsageStatsManager`, `checkSelfPermission(CAMERA)`); new `AndroidDeviceInfoSource`.
- `androidApp/.../android/reliability/ReliabilityModule.kt` -- bind `DeviceInfoSource`, `EvaluateReliability`.
- `androidApp/.../YawnAndPawnApp.kt:211` -- engine gets `lockedTest = get<ReliabilityRecords>()`.
- `data/src/commonMain/.../data/settings/DataStoreReliabilityRecords.kt` (new, template `DataStoreReRegisterDismissals`) + `DataModule.kt:91` binding.
- `testing/.../testing/ReliabilityFakes.kt` -- `FakeReliabilityRecords`.
- Tests: `androidApp/src/test/.../reliability/ReliabilityProbeTest.kt` (Robolectric `@Config(sdk=…)`; API 36 via `sdkInt = 36` on the SDK 34 sandbox, like Story 1.19), `core/src/commonTest/.../reliability/ReliabilityChecklistTest.kt`, `core/src/commonTest/.../session/SessionEngineLockedTestTest.kt`, `data/src/androidHostTest/.../settings/DataStoreReliabilityRecordsTest.kt`, `DataModuleTest.kt`.

## Tasks & Acceptance

**Execution:**
- `core/.../reliability/Reliability.kt` -- add `dndAllowsAlarms`, `batteryOptimizationIgnored`, `backgroundRestricted`, `standbyBucketRestricted`, `cameraGranted` (+ `batteryOk` helper).
- `core/.../reliability/ReliabilityChecklist.kt` -- pure checklist + ports + use case (see Design Notes).
- `core/.../session/SessionConfig.kt`, `IdleRules.kt`, `SessionReducer.kt`, `SessionEngine.kt`, `SessionPorts.kt` -- testStartedLocked and the locked-test record.
- `data/.../settings/DataStoreReliabilityRecords.kt`, `DataModule.kt` -- keys `reliability_last_ok` (string set of row names), `locked_test_passed_at` (epoch ms), `maker_confirmed_maker`/`maker_confirmed_fingerprint`.
- `androidApp/.../reliability/AndroidReliability.kt`, `ReliabilityModule.kt`, `YawnAndPawnApp.kt` -- probe values, device info, wiring.
- `testing/.../ReliabilityFakes.kt` -- `FakeReliabilityRecords`.
- Tests listed in the Code Map.

**Acceptance Criteria:**
- Given each of API 26, 28, 30, 31, 33, 34 and 36, when the probe runs, then every new value follows the epic's rule for that level (Robolectric).
- Given a test fired while the keyguard was locked, when it reaches Completed, then `lockedTestPassedAt` is stored; an unlocked test stores nothing.
- Given every row state and the Revoked transition, when `ReliabilityChecklist.evaluate` runs, then rows come in the epic's order and `alarmBlocking` is true only for Notifications, Full-screen alarm, Exact alarms, DND or Battery not OK (Hidden never blocks).
- Given the targeted tests, `spotlessCheck` and `detekt`, they pass; core Kover stays ≥ 90%.

## Spec Change Log

## Review Triage Log

## Design Notes

- **Rows** (all nine always returned, Hidden included): Notifications; FullScreenAlarm; ExactAlarms (Hidden unless `device.sdkInt` in 31..32); DoNotDisturb; Battery (`batteryOptimizationIgnored && !backgroundRestricted && !standbyBucketRestricted`); Manufacturer (Hidden unless `makerCovered`; OK when the stored confirmation equals the current maker and fingerprint); Camera (Hidden unless an enabled alarm has a check whose `type.usesCamera`); Microphone (always Hidden until Epic 7); LockedScreenTest (OK when `lockedTestPassedAt != null`).
- **DEFAULT TAKEN (owner can change): Revoked stays until fixed.** `lastOkAfter(rows, previous)`: OK and Revoked rows are kept in the stored set, NeedsFix rows leave it, Hidden rows keep their previous membership. So a setting the user had on and that a phone update turned off reads "turned back on" every time the screen opens, not only the first time.
- **DEFAULT TAKEN: the Home banner stays on the Story 1.19 three items** (`ReliabilityStatus.allOk`) until Story 5.4 rewires it to `alarmBlocking` together with the DND/Battery Fix targets, so "Fix" never opens a screen that does not fit.
- **DEFAULT TAKEN: a locked test before the first unlock counts as locked**, and a keyguard read that throws counts as unlocked (never claims a pass by mistake; the Pay path keeps "counts as locked").
- **DEFAULT TAKEN: API 36 runs on the SDK 34 sandbox with `sdkInt = 36`** (Robolectric 4.17 needs Java 21 for SDK 35+; same approach as Story 1.19).
- **Known limit:** the settings DataStore is backed up, so `lockedTestPassedAt` and the last-OK set restore onto a new phone (Manufacturer is protected by the fingerprint). A kill between the Completed commit and the DataStore write loses the pass (the user rings another test).
- `getNotificationPolicy()` needs no permission; on API 35+ it returns the user's policy because the app never sets an implicit zen rule.

## Verification

**Commands:**
- `./gradlew --no-daemon --max-workers=2 :core:jvmTest --tests "*Reliability*" --tests "*SessionEngineLockedTest*" --tests "*SessionJson*" --tests "*SessionTransitionTable*" --tests "*DirectBoot*"` -- expected: pass
- `./gradlew --no-daemon --max-workers=2 :data:testAndroidHostTest --tests "*DataStoreReliabilityRecords*" --tests "*DataModuleTest*"` -- expected: pass
- `./gradlew --no-daemon --max-workers=2 :androidApp:testDebugUnitTest --tests "*ReliabilityProbeTest*"` and `:testing:allTests` -- expected: pass
- `./gradlew --no-daemon --max-workers=2 spotlessCheck detekt` -- expected: pass
