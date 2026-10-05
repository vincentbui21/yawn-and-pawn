---
title: 'Story 2.3: Ring before the first unlock after a reboot'
type: 'feature'
created: '2026-10-05'
status: 'done'
baseline_revision: 'b3ea320649097c9707bfe691620e3e78bb2f61fc'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-2-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** After an overnight reboot the alarm has to ring before the user unlocks the phone. Story 2.2 already re-arms on `LOCKED_BOOT_COMPLETED` and both databases are device-protected. What is missing:
- A lock-state port, so the core knows whether the phone is locked.
- The Direct Boot substitutions (a non-built-in sound becomes the default sound; checks that are not Direct-Boot-safe are swapped).
- A snooze policy that says "Unlock your phone to snooze" while locked.
- `direct_boot` in history for restored rings.
- A detekt rule that keeps credential-protected storage out of the wake path.
- Tests proving the whole path works with credential storage closed.

**Approach:**
- A `UserLockState` port (core), with an Android adapter and a `:testing` fake.
- `SessionEngine` reads the port and hands `userLocked` to the reducer, which marks the session `beforeFirstUnlock` (and `startedBeforeUnlock` for history) on `AlarmFired`, `TestAlarmFired` and `ProcessRestored`.
- The pure `DirectBootSubstitution.apply(config, locked)` gives the ring's sound (via `entryEffects`) and the first check plan.
- `NoBillingSnoozeAvailability` takes the live lock state, with precedence TestMode > BeforeFirstUnlock > CatalogueNotLoaded.
- A detekt rule `CredentialStorageAccess`.
- The existing approved `LockedBeforeUnlock` snooze rendering gets production screenshots.

## Boundaries & Constraints

**Always:**
- **The reducer table is unchanged** (no new rows or events). `reduce(state, event, now, userLocked = false)` gains an environment input:
  - Idle + `AlarmFired` / `TestAlarmFired`: `beforeFirstUnlock` and `startedBeforeUnlock` = `event.beforeFirstUnlock || userLocked`.
  - Ring + `ProcessRestored` while locked: sets both flags. Snoozed + `ProcessRestored` once over (ring immediately) does the same for the new ring.
  - Snoozed not over stays ignored. Unlocked restores clear nothing; clearing is Story 2.4's job, outside the table.
- **DirectBootSubstitution** (core, pure):
  - unlocked: the config comes back unchanged;
  - locked: a `soundRef` that is not `SoundRef.BuiltIn` becomes `Alarm.DEFAULT_SOUND_REF`, and every check step that is not Direct Boot safe becomes `DIRECT_BOOT_CHECK` (the Placeholder; Placeholder is safe, so the Epic 2 plan stays).
  - The frozen `SessionConfig` is never rewritten. `entryEffects` plays `apply(config, beforeFirstUnlock).soundRef`, and a session started locked gets its `CheckRun` from the substituted plan. Fee tier, max snoozes and snooze length never change.
- **UserLockState port:** `isUserUnlocked(): Boolean` and `observe(): Flow<Boolean>`.
  - `AndroidUserLockState` reads `UserManager.isUserUnlocked` and observes `ACTION_USER_UNLOCKED`, registered while collected.
  - `FakeUserLockState` lives in `:testing`. The engine and the policy default to unlocked.
- **NoBillingSnoozeAvailability** becomes a class over a lock reader. Precedence: TestMode, then BeforeFirstUnlock while locked, then CatalogueNotLoaded. Koin binds it to the Android adapter.
- **History:** `direct_boot` = `startedBeforeUnlock`, which is true if any ring rang before the first unlock.
- **Detekt `CredentialStorageAccess`:** active on `:data` and `:androidApp` main sources.
  - It reports `getDatabasePath(`, `getSharedPreferences(`, `dataStoreFile(`, `preferencesDataStore(`, `filesDir`, `cacheDir` and `dataDir` unless the receiver is `createDeviceProtectedStorageContext()` or a name containing `device` / `Device`.
  - Exempt: package `…android.media`, and test sources.
  - The rule has a violating and a compliant snippet test.
  - `AndroidNotificationPermission` moves its preferences to device-protected storage.
- **UI:** reuse `SnoozeOffer.LockedBeforeUnlock` as it is (lock icon, "Unlock your phone to snooze", TalkBack "Snooze unavailable, Unlock your phone to snooze"), with new Sunrise screenshots at 100 % and 200 % plus semantics. The Direct Boot note ("…today's check is Math.") is Epic 3 and is not shown.

**Never:** No new strings or screen states. No reducer rows. No credential-protected storage on the wake path. No Billing or Firebase initialisation while locked. No rebase onto main. No edit to the backup rule files: Story 2.12 owns them on main, so the moved reliability preferences file is listed for the rebase instead.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Locked fire | AlarmFired while locked | Ringing, beforeFirstUnlock and startedBeforeUnlock true | — |
| System sound locked | soundRef `system:` | `SoundAt(builtin:default)` | — |
| Built-in sound locked | `builtin:x` | Plays unchanged | — |
| Unlocked | any config | `apply` returns it unchanged | — |
| Restore locked | Ring stored, started unlocked | Flags set, default sound, fee tier, max snoozes and snooze length unchanged | — |
| Policy | test / locked / else | TestMode / BeforeFirstUnlock / CatalogueNotLoaded | — |
| History | a locked ring at start, at restore only, or none | direct_boot true / true / false | — |
| Locked boot path | user locked; credential storage throws | LOCKED_BOOT → rescheduleAll → fire → Ringing with sound and notification; Firebase not started; no billing launch | Any credential access fails the test |
| Locked then unlocked boot | LOCKED_BOOT then BOOT | Identical scheduler calls | — |
| Detekt | `context.filesDir` vs `createDeviceProtectedStorageContext().filesDir` | Reported vs not reported | — |

</intent-contract>

## Code Map

- `core/.../session/SessionPorts.kt` (or a new `UserLockState.kt`): the port.
- `core/.../session/DirectBootSubstitution.kt` (new).
- `SessionReducer.kt`, `IdleRules.kt`, `RingRules.kt` (`restored`), `SnoozedRules.kt` (`ringImmediately`): the `userLocked` input.
- `SessionRuntime.kt`: `soundOf` uses the substitution.
- `SessionEngine.kt`: the `userLockState` port.
- `SessionPolicies.kt`: the `NoBillingSnoozeAvailability` class.
- `SessionState.kt`: KDoc of `startedBeforeUnlock`.
- `testing/.../SessionFakes.kt`: `FakeUserLockState`.
- `androidApp/.../android/AndroidUserLockState.kt` (new). `YawnAndPawnApp.kt`: Koin wiring.
- `androidApp/.../reliability/AndroidReliability.kt`: device-protected preferences.
- `config/detekt-rules/.../CredentialStorageAccess.kt` (new), with its test, the provider and `config/detekt/detekt.yml`.
- `androidApp/src/test/.../ui/RingingSamples.kt`, `RingingScreenshotTest.kt`, `RingingSemanticsTest.kt`: the locked state.
- New Robolectric `DirectBootRingTest`.

## Tasks & Acceptance

**Execution:** implement the Code Map, plus core tests:
- a `DirectBootSubstitution` test;
- reducer and engine tests with locked flags and history;
- policy precedence tests.

**Acceptance Criteria:**
- Given any matrix row, then its test passes in `./gradlew qualityGate`.
- Given the qualityGate run, then the new screenshots `wake_ringing_locked_sunrise(_font200)` are recorded and verified, the preview baselines are unchanged, and the Kover gates are green.
- Given the pps-design Done checklist, then it is ticked below.

## Design Notes

**AD-2 `UserUnlocked` (deferred to 2.3):** settled without correct-course, the 2.4 way. Unlock handling for Grace, Loud and Snoozed stays outside the table:
- snooze availability reads the live `UserLockState`, so the label updates on its own;
- `beforeFirstUnlock` keeps the substituted check and sound for the current ring;
- Billing and Firebase start from the unlock signal (Story 2.4 / Epic 4).

So no rows are added.

**pps-design Done checklist** (no new UI; the existing approved composable is only reached from production):
- [x] tokens only;
- [x] strings are resources and match EXPERIENCE.md;
- [x] Sunrise 100 %/200 % screenshots;
- [x] targets ≥ 64 dp;
- [x] TalkBack order is clock, "I'm up", snooze;
- [x] `CopyRulesTest` unchanged and passing.

## Verification

**Commands:**
- `./gradlew :androidApp:recordRoborazziDebug -Proborazzi.test.verify=false --tests …RingingScreenshotTest`: records the two new baselines only.
- `./gradlew qualityGate`: expected BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview`: expected empty.

## Spec Change Log

## Review Triage Log

## Auto Run Result

Status: done. Fast mode: one agent planned and implemented, with no separate review pass. Stacked on Story 2.2 (b3ea320).

**Summary**

- **Lock-state port:** a new `UserLockState` port, with `AndroidUserLockState` and `FakeUserLockState`.
- **Engine and reducer:** `SessionEngine` reads the port and passes `userLocked` to `reduce`. A ring that starts or is restored while locked is marked `beforeFirstUnlock` and `startedBeforeUnlock`. This includes a session stored unlocked and restored after `LOCKED_BOOT_COMPLETED` (the 2.2 review finding). The reducer table is unchanged.
- **Direct Boot substitution:** the pure `DirectBootSubstitution.apply` gives the ring's sound (a non-built-in sound becomes the default) and its first check plan. The frozen config is never rewritten.
- **Snooze availability:** `NoBillingSnoozeAvailability` reads the live lock state. Precedence: TestMode, then BeforeFirstUnlock, then CatalogueNotLoaded.
- **Storage rule:** the new detekt rule `CredentialStorageAccess`. The reliability preferences moved to device-protected storage.
- **Screen:** the approved lock-icon snooze now has production screenshots and semantics tests.

**Files changed**

Core:
- `session/DirectBoot.kt` (new);
- `SessionReducer`, `IdleRules`, `RingRules`, `SnoozedRules`, `SessionTimers`, `SessionRuntime`, `SessionEngine`, `SessionPolicies`, `SessionState`;
- tests: `DirectBootTest` (10 tests), plus updated `SessionEngineTest`, `SessionPoliciesTest` and `SessionTestDoubles`.

Testing:
- `FakeUserLockState`.

Android:
- `AndroidUserLockState.kt` (new);
- `YawnAndPawnApp.kt` (wiring);
- `AndroidReliability.kt` (device-protected preferences);
- tests: `DirectBootRingTest` (3 tests), the locked samples, 2 screenshots and 3 semantics tests, and `SessionWiringTest`.

Detekt:
- `CredentialStorageAccess` and its test (5 tests);
- the provider, `detekt.yml`, and the provider list test.

Compose:
- `RingingMappingTest` (the policy is now constructed).

Docs:
- `deferred-work.md`: the AD-2 `UserUnlocked` item is settled, and the rebase notes are added.

**Review findings**

- No review pass ran.
- During verification, two test setups needed fixing:
  - the media shadow in the locked-storage test;
  - a fixed wall clock for the identical-calls comparison.

**Verification**

- `./gradlew qualityGate`: BUILD SUCCESSFUL (8m 49s).
- New baselines: `wake_ringing_locked_sunrise(_font200).png`, recorded and then verified.
- `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

**Residual risks**

- Real locked reboots are human-verify in Story 2.13.
- The locked-storage test guards only the Koin-routed context; components that use their own `Context` are not guarded.
- The move of the reliability preferences needs Story 2.12's backup rules on the rebase (see `deferred-work.md`).
