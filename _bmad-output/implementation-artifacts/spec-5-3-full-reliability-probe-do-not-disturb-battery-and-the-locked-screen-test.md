---
title: 'Story 5.3: Full reliability probe: Do Not Disturb, battery and the locked-screen test'
type: 'feature'
created: '2026-10-15'
status: 'done'
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

### 2026-10-15 — Review pass (fast mode: one combined self-review of the diff; the coordinator runs the independent review)
- verdicts: 6 findings — high 0, medium 0, low 1, false 3, maybe-false 2
- findings:
  - `[maybe-false]` `reject` `AndroidReliabilityProbe.check()` now calls `getNotificationPolicy()` on every Home start; an OEM that throws there would crash Home — the platform method enforces no permission (API 28–36), and on API 35+ it returns the user's policy for an app with no implicit zen rule; settling it needs a device run (Story 5.13). If true it would be medium, but no evidence of a throwing OEM; not deferred.
  - `[maybe-false]` `reject` The API 28 Robolectric sandbox must be downloaded by CI — existing tests already run `@Config(sdk = [28])` (2 uses before this story), so CI already fetches it.
  - `[false]` `reject` A restored Completed session could record the locked test twice — `EngineKeyguard.afterStep` returns when `from` is Completed, and the restore step starts from the loaded Completed state.
  - `[false]` `reject` `TestAlarmFired` arriving while an ended session is still being recorded skips the keyguard read — `dispatch` settles history (Recorded → Idle) before `step`, so `from` is Idle when the keyguard is read.
  - `[false]` `reject` Adding an R02 example breaks the AD-2 doc/table parity test — the row count is unchanged; `Ad2TableDocTest` passes.
  - `[low]` `reject` `EvaluateReliability` has no production caller yet — intended: Story 5.4 calls it (spec `deferred`); deleting it would push the last-OK bookkeeping into a UI story.

## Review (fast mode)

2026-10-16: two independent reviewers (verification gaps, edge cases) found 17 items. All fixes are applied on the story branch.

Each fix has its test. Items 15 and 16 change no code: they pin today's behaviour and are listed for the owner below.

1. **[high] Production wiring of the locked-test record was untested.** `SessionWiringTest`: the app's engine reads the real keyguard adapter (`ShadowKeyguardManager`) on a test fire and stores the pass in the real records; a test fired unlocked stores nothing.
2. **[medium] `DeviceInfoSource`, the `EvaluateReliability` factory and `AndroidDeviceInfoSource` were never resolved.** `ReliabilityProbeTest`: the app graph resolves the device info (API level, maker, build, no maker covered) and evaluates all nine rows in order; `AndroidDeviceInfoSource(31, "X", "f")` unit check.
3. **[medium] The DND policy rule was tested on API 28 only.** `assertReadsEverything` (API 30, 31, 33, 34, 36) now checks a policy without alarms (blocked), "alarms only" (allowed) and no policy (allowed).
4. **[medium] The "record only once" guard and the process-kill path.** `SessionEngineLockedTestTest`: a stored Completed locked test whose history write fails is not recorded again on restore and two ticks; a stored Ringing locked test restored unlocked and completed passes once with no keyguard read.
5. **[low] R02 example:** a test fired before the first unlock with no keyguard showing is frozen as locked and runs the Direct Boot plan.
6. **[low] Snoozed then completed after unlocking.** A test can never snooze (it never charges), so the test pins that the snooze tap is ignored and the completion after the unlock passes once with one keyguard read.
7. **[low] Deleted the test that could not fail** ("records nothing without a recorder"); item 1 covers the wiring.
8. **[low] Probe tests granted notification-policy access the app never has.** `goodPhone()` now revokes it; on API 28 and 34 a policy without alarms reads blocked without access and nothing throws (decision above).
9. **[low] Unreadable alarms keep a Camera row seen OK.** `ReliabilityChecklistTest`: Camera stays in `lastOk`, nothing is written.
10. **[medium] A test the wake service ends for a real alarm recorded a pass.** `WakeService.endTestSession` calls `SessionEngine.testPreempted(sessionId)` first; `EngineKeyguard` skips that session's Completed. Tests: core (`SessionEngineLockedTestTest`) and Robolectric (`TestAlarmFlowTest`: a locked test ended by a real alarm stores nothing).
11. **[high, ring path] The DataStore write ran inside the engine's Mutex, NonCancellable and unbounded.** The engine now reports to a non-suspending `LockedTestReporter`; production's `LockedTestReporter.Launching` runs the write on `ApplicationScope` and logs failures, throws and cancellation. Test: a write that never returns, then a real alarm still rings. This also covers item 17 (a `CancellationException` from the adapter no longer reaches `dispatch`; tested).
12. **[medium] The five new platform reads were unguarded on Home's start.** Each is wrapped: a throw is logged and counts as the good value. Test: a context whose Story 5.3 services throw still gives a status, with five logged failures.
13. **[medium] The DND automatic-rule limit.** KDoc and this spec corrected (Design Notes); a Robolectric test pins that a schedule rule with its own `ZenPolicy` is not seen; a Story 5.13 check is added to `epic-5-context.md`. The "trust the filter" part is taken for total silence and "alarms only" only (DEFAULT TAKEN above).
14. **[medium] The records were backed up and restored to a new phone.** Moved to their own `ReliabilityDataStore` (`datastore/reliability.preferences_pb`), excluded in `data_extraction_rules.xml` (both sections) and `backup_rules.xml`. Tests: `BackupRulesTest` (every section excludes it, nothing includes it), `BackupRulesCoverageTest` (the morning flow writes it and every rule section covers it), `DataModuleTest`, `DataStoreReliabilityRecordsTest`.
15. **[low] Revoked semantics: pinned, no code change.** A table test in `ReliabilityChecklistTest`: a Hidden row keeps its old membership (and comes back Revoked if it shows again failing), Revoked stays until fixed, and a failed records read shows the locked test as "Fix".
    - **DEFAULT TAKEN (owner can change):** these three rules stay as they are for Story 5.4.
16. **[low] Screen lock "None" and screen-on tests: pinned, no code change.** A phone whose keyguard never shows can never pass the locked-screen test (test added); a test rung with the screen on and unlocked counts as unlocked.
    - **OPEN QUESTION for owner (decide by Story 5.12):** should a phone with no screen lock pass with any completed test, or be told to set a screen lock? Today it can never tick the row.
17. Covered by 11.

Review verification (JDK 17, `--no-daemon --max-workers=2`): `:core:jvmTest` (`core.session.*`, `core.reliability.*`), `:data:testAndroidHostTest` (`DataStoreReliabilityRecords`, `DataModuleTest`, `Ad2TableDocTest`), `:testing:allTests`, `:androidApp:testDebugUnitTest` (`ReliabilityProbeTest`, `SessionWiringTest`, `TestAlarmFlowTest`, `BackupRules*`, `WakeServiceTest`), `spotlessCheck`, `detekt` and `:core:koverVerify`. All pass. `SessionEngine.testPreempted` is a function-typed property because detekt's `TooManyFunctions` caps the engine at 11 functions.

## Design Notes

- **Rows** (all nine always returned, Hidden included): Notifications; FullScreenAlarm; ExactAlarms (Hidden unless `device.sdkInt` in 31..32); DoNotDisturb; Battery (`batteryOptimizationIgnored && !backgroundRestricted && !standbyBucketRestricted`); Manufacturer (Hidden unless `makerCovered`; OK when the stored confirmation equals the current maker and fingerprint); Camera (Hidden unless an enabled alarm has a check whose `type.usesCamera`); Microphone (always Hidden until Epic 7); LockedScreenTest (OK when `lockedTestPassedAt != null`).
- **DEFAULT TAKEN (owner can change): Revoked stays until fixed.** `lastOkAfter(rows, previous)`: OK and Revoked rows are kept in the stored set, NeedsFix rows leave it, Hidden rows keep their previous membership. So a setting the user had on and that a phone update turned off reads "turned back on" every time the screen opens, not only the first time.
- **DEFAULT TAKEN: the Home banner stays on the Story 1.19 three items** (`ReliabilityStatus.allOk`) until Story 5.4 rewires it to `alarmBlocking` together with the DND/Battery Fix targets, so "Fix" never opens a screen that does not fit.
- **DEFAULT TAKEN: a locked test before the first unlock counts as locked**, and a keyguard read that throws counts as unlocked (never claims a pass by mistake; the Pay path keeps "counts as locked").
- **DEFAULT TAKEN: API 36 runs on the SDK 34 sandbox with `sdkInt = 36`** (Robolectric 4.17 needs Java 21 for SDK 35+; same approach as Story 1.19).
- **Known limit (superseded by review fix 14):** the records were first kept in the backed-up settings DataStore. They now live in their own `datastore/reliability.preferences_pb`, excluded from cloud backup, device transfer and the API 30 backup, so a new phone never inherits a locked-test pass or "turned off" rows. A kill between the Completed commit and the DataStore write still loses the pass (the user rings another test).
- **Known limit (review fix 13), DND rules the app cannot see:** `getNotificationPolicy()` needs no permission but returns only the default policy (the one manual DND and a schedule rule without its own policy use; on API 35+ this app's own, which it never sets). A schedule rule with its own `ZenPolicy` (API 29+) or a mode with its own policy (API 35+) is invisible without `ACCESS_NOTIFICATION_POLICY`, which stays off the allowlist. Such a rule that blocks alarms reads as allowed; one that allows them while the default policy does not reads as blocked. `ReliabilityProbeTest` pins it; Story 5.13 checks it on the Oppo A96.
- **DEFAULT TAKEN (owner can change), review fix 13: the filter decides only total silence and "alarms only".** Under "priority only" the probe still reads the default policy instead of trusting the filter alone, because "priority only" says nothing about alarms, and manual DND (the most common case) uses exactly that default policy; trusting the filter would read a manual DND without alarms as OK. Changing it is one line in `AndroidReliabilityProbe.dndAllowsAlarms`.
- **DEFAULT TAKEN (owner can change), review fixes 8 and 12: a probe read that throws counts as the good value and is logged.** The probe runs as Home starts, so a throw must never crash Home, and a "Fix" for a value the probe cannot read is a banner the user can never clear. Robolectric shows the DND policy read needs no notification-policy access (the app never has it), and the platform enforces none either.

## Verification

**Commands:**
- `./gradlew --no-daemon --max-workers=2 :core:jvmTest --tests "*Reliability*" --tests "*SessionEngineLockedTest*" --tests "*SessionJson*" --tests "*SessionTransitionTable*" --tests "*DirectBoot*"` -- expected: pass
- `./gradlew --no-daemon --max-workers=2 :data:testAndroidHostTest --tests "*DataStoreReliabilityRecords*" --tests "*DataModuleTest*"` -- expected: pass
- `./gradlew --no-daemon --max-workers=2 :androidApp:testDebugUnitTest --tests "*ReliabilityProbeTest*"` and `:testing:allTests` -- expected: pass
- `./gradlew --no-daemon --max-workers=2 spotlessCheck detekt` -- expected: pass

## Auto Run Result

Status: done (fast mode; not pushed — the coordinator pushes, runs the independent review and CI, and merges)

**Summary:** the probe now reads Do Not Disturb, battery optimisation, background restriction, the restricted standby bucket and the camera grant. A test ring is frozen as `testStartedLocked` (keyguard showing, or before the first unlock) and stores `lockedTestPassedAt` in the settings DataStore when it reaches Completed. The pure `ReliabilityChecklist` (nine rows, the Revoked transition, `alarmBlocking`) and the `EvaluateReliability` use case are ready for Story 5.4. Home is unchanged.

**Files:**
- `core/.../reliability/Reliability.kt` — five new `ReliabilityStatus` values (with good defaults) and `batteryOk`.
- `core/.../reliability/ReliabilityChecklist.kt` (new) — rows, statuses, device info, stored records, ports, rules and the use case.
- `core/.../session/SessionConfig.kt`, `IdleRules.kt`, `SessionReducer.kt` — `testStartedLocked` is frozen when a test fires.
- `core/.../session/SessionEngine.kt`, `EngineKeyguard.kt` (new), `SessionPorts.kt` — reads the keyguard when a test fires and records a locked test on Completed (moved out of the engine because of detekt's complexity limits).
- `data/.../settings/DataStoreReliabilityRecords.kt` (new), `DataModule.kt` — the records, kept in the settings DataStore.
- `androidApp/.../reliability/AndroidReliability.kt`, `ReliabilityModule.kt`, `YawnAndPawnApp.kt` — the probe values, `AndroidDeviceInfoSource` and the wiring.
- `testing/.../ReliabilityFakes.kt` — `FakeReliabilityRecords`.
- Tests: `ReliabilityProbeTest` (17), `ReliabilityChecklistTest` (12), `SessionEngineLockedTestTest` (6), `DataStoreReliabilityRecordsTest` (4), `SessionJsonTest`, `SessionTransitionTable` (an R02 example), `DataModuleTest`, `ReliabilityFakesTest`.

**Review:** 6 findings: 0 patched, 0 deferred, 6 rejected (the reasons are in the triage log). Follow-up review recommended: false, because nothing was patched.

**Verification** (all with `--no-daemon --max-workers=2` on JDK 17): `:core:jvmTest` for the `core.session.*` and `core.reliability.*` packages; `:testing:allTests`; `:data:testAndroidHostTest` (`DataStoreReliabilityRecords`, `DataModuleTest`, `Ad2TableDocTest`); `:androidApp:testDebugUnitTest` (`ReliabilityProbeTest`, `WakeServiceTest`, `TestAlarm*`); `spotlessCheck detekt`. All pass. Not run here because of the run's constraints: `qualityGate` and Kover (CI runs them).

**Residual risks:**
- Kover's core ≥ 90% is not checked locally.
- (Fixed by review fix 14: the records now have their own DataStore file, which is never backed up.)
- If the app is killed between the Completed commit and the DataStore write, that pass is lost.
- The DND and battery values have not been checked on a real phone yet (Story 5.13).
