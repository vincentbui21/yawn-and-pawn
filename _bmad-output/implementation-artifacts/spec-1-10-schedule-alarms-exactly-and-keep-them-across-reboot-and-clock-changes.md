---
title: 'Story 1.10: Schedule alarms exactly and keep them across reboot and clock changes'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: 'c203941144d21863770e55a5b74d644a3fd818d1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/docs/architecture.md'
warnings: ['oversized']
deferred:
  - summary: >-
      Test ApplicationScope's exception handler, the receivers' 8 s timeout branch and that goAsync's pending result is
      finished when the work throws or times out.
    evidence: |-
      No test makes a launched job throw or exceed WORK_BUDGET; dropping the CoroutineExceptionHandler or the
      `?: logger.log(...)` timeout branches leaves every test green. Defensive branches; add with Story 1.14 when the
      fire handler gets real work.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/ApplicationScope.kt, SystemEventsReceiver.kt, AlarmFiredReceiver.kt
    severity: medium
  - summary: >-
      AndroidAlarmSchedulerTest and ReceiversManifestTest may race the app-start rescheduleAll against stopKoin().
    evidence: |-
      Both boot the real YawnAndPawnApp, whose onCreate launches rescheduleAll on Dispatchers.Default, and never wait
      for it before @After stopKoin(). Settle by running both classes 20 times, or wait for the ApplicationScope
      children as SchedulingApp does.
    location: >-
      androidApp/src/test/kotlin/com/yawnandpawn/app/android/
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Alarms are stored and shown, but nothing registers them with Android, so none of them ring. Nothing re-arms them after a reboot, an app update, or a clock or time-zone change. Request codes can also be reused after a delete, so a stale PendingIntent could fire for the wrong alarm.

**Approach:**
- **Port and adapter:** add the AD-4 `AlarmScheduler` port to `:core` with a `FakeAlarmScheduler`, and an `AndroidAlarmScheduler` adapter that uses only `setAlarmClock()`.
- **Scheduling helper:** one core helper keeps an alarm and the scheduler in sync. The four alarm use cases call it after a successful write, and `rescheduleAll()` reuses it.
- **Receivers:** add the manifest receivers for system events and for the alarm firing.
- **Request codes:** replace "highest in use + 1" with a persisted high-water mark in `app.db` (schema v2).

## Boundaries & Constraints

**Always:**
- **Port shape:** `AlarmScheduler` in `core.alarm` with `schedule(alarmId, requestCode, triggerAtWallMillis)`, `cancel(requestCode)`, `armSessionSlot(Deadline)`, `cancelSessionSlot()` and `scheduleTest(triggerAtWallMillis)`. Every method returns `Outcome<Unit, DomainError>`.
- **setAlarmClock call:** the adapter calls only `AlarmManager.setAlarmClock(AlarmClockInfo(trigger, showIntent), operation)`.
  - The trigger is exactly the scheduled epoch millis.
  - The show intent is an immutable activity PendingIntent opening `MainActivity`.
  - The operation is an immutable broadcast PendingIntent to `AlarmFiredReceiver` with an explicit component, a fixed action per kind (alarm, session slot, test), the extras `alarmId` and `scheduledAt`, and the alarm's `requestCode`.
  - `cancel` builds the equal PendingIntent and cancels it.
- **Session slot and test alarm:** they use `RequestCodes.SESSION_SLOT` and `RequestCodes.TEST_ALARM`, which are distinct from every alarm code (alarm codes are ≥ `FIRST_ALARM` = 1000). `armSessionSlot` converts the `Deadline` to wall time only here (AD-3): same boot means wall now + monotonic remaining; another boot means `wallMillis`.
- **Exact-alarm permission:** on API 31–32, when `canScheduleExactAlarms()` is false (or `setAlarmClock` throws `SecurityException`), the adapter returns `DomainError.ExactAlarmNotPermitted` and logs it. It never falls back to an inexact alarm.
- **Sync helper:** a core helper (`AlarmScheduling`) computes `nextOccurrence(alarm.toRule(), now, zone)` from `Clock` and `TimeZoneProvider`. It schedules enabled alarms and cancels disabled ones. Delete cancels.
  - `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm` call it inside their `AlarmWriteLock`, only after the repository write succeeds.
  - A scheduler failure is logged and does not fail the use case: the alarm stays stored, and Story 1.19 surfaces the missing permission.
- **rescheduleAll:** `rescheduleAll()` reads every alarm from `AlarmRepository`, schedules each enabled alarm and cancels each disabled alarm's code. It is idempotent, so two runs produce the same scheduler calls.
  - It runs on app start, and from `SystemEventsReceiver` for `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, inside `goAsync()`, finishing the pending result in all cases.
  - A restore of a backup restarts the app, so the app-start run covers AD-4's "after a backup restore".
- **Receivers:** every receiver is `directBootAware`, and none starts a foreground service or an activity. `SystemEventsReceiver` is `exported="true"` (protected system broadcasts only). `AlarmFiredReceiver` is `exported="false"`.
- **AlarmFiredHandler:** `AlarmFiredReceiver` hands `AlarmFired(alarmId, scheduledAt)` to an `AlarmFiredHandler` port in core, using `goAsync()`. Session-slot and test fires go to their own handler methods.
  - The Epic 1 default for a repeating alarm schedules the next occurrence after max(now, scheduledAt).
  - For a one-time alarm, it disables the alarm through `SetAlarmEnabled`, which cancels it.
  - A missing or disabled alarm is logged and ignored.
  - Slot and test fires are logged and ignored until Stories 1.12, 1.14 and 1.18 bind them.
- **Manifest permissions:** `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"` and `RECEIVE_BOOT_COMPLETED`. They are already in `config/permission-allowlist.txt`, and the allowlist check must pass.
- **High-water mark:** `app.db` moves to version 2 with a one-row request-code sequence table and an exported v2 schema. A migration from v1 keeps every alarm and seeds the mark with max(existing codes, 999).
  - A core port (`RequestCodeSequence.next()`, with a Room adapter and a fake) hands out mark + 1 and persists it atomically.
  - The use cases use it instead of `nextRequestCode(repository)`, so deleted codes are never reused, even across restarts.
- **Detekt rule:** `NoInexactAlarm` in `:detekt-rules` (enabled in `detekt.yml`, with tests) reports calls to `set`, `setExact`, `setExactAndAllowWhileIdle`, `setAndAllowWhileIdle`, `setRepeating`, `setInexactRepeating` and `setWindow` in files that import or reference `android.app.AlarmManager`.
- **Reboot clock note:** `docs/decisions/reboot-clock.md` records that after a reboot `rescheduleAll()` uses the wall clock, as AD-3 says. It also records the case where the wall clock is wrong until network time arrives (an early or late ring), carried to Story 2.2. No extra logic here.
- **Locked storage test:** a Robolectric test opens `app.db` through `buildAppDatabase` with a context whose credential-encrypted storage access throws (as before first unlock), and reads and writes an alarm.

**Never:**
- No inexact alarm APIs, no `WorkManager` or `JobScheduler` for alarms, and no `LOCKED_BOOT_COMPLETED` (that is Epic 2).
- No user-facing permission prompt or banner (that is Story 1.19), and no wake runtime, sound or notification (that is Story 1.14).
- No destructive migration fallback. No change to the Home and editor UI beyond what the use-case signatures force.
- No `Clock.System` outside adapters. No `println` or `Log` in `:core`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Save enabled | New 07:00 alarm saved at 06:00 (FakeClock, Berlin) | `schedule(id, code, epoch of today 07:00)` | No error expected |
| Disable | `SetAlarmEnabled(id, false)` | `cancel(code)` | No error expected |
| Delete | `DeleteAlarm(id)` | `cancel(code)` after the row is gone | Failed delete: no scheduler call |
| Duplicate | `DuplicateAlarm(id)` on an enabled alarm | The copy is scheduled under a new, higher code | No error expected |
| Write fails | Repository failure on save | No scheduler call; the use case returns the failure | Logged by the caller as today |
| Scheduler denied | API 31–32, exact alarms off | The use case succeeds; the alarm is stored; `ExactAlarmNotPermitted` is logged | No inexact fallback |
| rescheduleAll twice | 2 enabled, 1 disabled | Identical call lists both times: 2 schedules, 1 cancel | No error expected |
| DST gap | Berlin spring-forward day, 02:30 alarm, boot broadcast | Armed at 03:30 local | No error expected |
| Zone change | Berlin → New York, then TIMEZONE_CHANGED | Re-armed at 07:00 New York time | No error expected |
| Repeating fired | Weekday alarm fires Monday 07:00 | The next occurrence is armed, Tuesday 07:00 | No error expected |
| One-time fired | One-time alarm fires | The alarm is disabled and its code cancelled | No error expected |
| Fired but gone | alarmId deleted or disabled | Nothing armed, logged | No error expected |
| Code reuse | Codes 1000 and 1001; delete 1001; create | The new code is 1002 (also after rebuilding the DB) | No error expected |
| Migration | v1 DB with codes 1000 and 1005 | After migration all alarms are present; the next code is 1006 | No destructive fallback |

</intent-contract>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/alarm/AlarmUseCases.kt` -- `SaveAlarm` (line 50), `SetAlarmEnabled` (103), `DeleteAlarm` (121) and `DuplicateAlarm` (129), each inside `lock.withLock`. The writes are at lines 73/152 (`validateAndStore`), 115 and 125. Private `nextRequestCode(repository)` at line 155 is to be replaced. Add the scheduler sync after the successful writes.
- `core/.../alarm/RequestCodes.kt` -- `SESSION_SLOT = 1`, `TEST_ALARM = 2`, `FIRST_ALARM = 1000`, `nextAlarmCode`. Keep the constants; seed the sequence from them.
- `core/.../alarm/AlarmOccurrence.kt` -- `nextOccurrence` (line 24, strictly after now; a DST gap shifts forward, an overlap takes the earlier instance) and `durationUntil`. `Alarm.toRule()` is in `Alarm.kt:34`.
- `core/.../error/DomainError.kt` -- add `ExactAlarmNotPermitted`. Then update the exhaustive `when`s: `core/.../log/Logger.kt` `diagnostic()` (lines 41 to 46), `AndroidLogger`, and any UI `when` (the editor maps non-field errors to the generic save failure, so no UI copy is needed).
- `core/.../time/Deadline.kt`, `TimePorts.kt` -- `Deadline.remaining(TimeSnapshot)`, `TimeSnapshot.of`, plus `MonotonicClock`, `BootCounter` and `TimeZoneProvider`, for the slot conversion and the sync helper.
- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/db/AppDatabase.kt` -- `version = 1`, entities `[AlarmEntity]`. Bump to 2, add the sequence entity and DAO, and add a manual `Migration(1, 2)` that creates and seeds the table. Its KDoc says Story 1.13 adds `session_history`; update it to "v3".
- `data/src/androidMain/kotlin/com/yawnandpawn/app/data/db/AppDatabaseFactory.kt` -- `buildAppDatabase(context)` (line 23), device-protected, no destructive fallback. Register the migration here. `data/src/androidMain/.../data/DataModule.kt` binds the new repository.
- `data/schemas/com.yawnandpawn.app.data.db.AppDatabase/1.json` -- keep it. `2.json` is exported by the build (`room3 { schemaDirectory }` in `data/build.gradle.kts:56`).
- `data/src/androidHostTest/.../db/AppDatabaseFactoryTest.kt` -- currently asserts schema version 1 with only the `alarm` table. Update it for v2, and add the migration test (Room `MigrationTestHelper` or open a hand-built v1 file) and the locked-storage test.
- `data/src/commonMain/.../data/alarm/RoomAlarmRepository.kt` -- the `storage {}` wrapper pattern to copy for the sequence adapter.
- `androidApp/src/main/AndroidManifest.xml` -- add the permissions and both receivers (`directBootAware="true"`). Today it has no receivers or permissions.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt` -- `appModule` (lines 21 to 33) and the use-case factories (lines 29 to 32). Bind the scheduler, the helper, the handler and the sequence. Run `rescheduleAll()` on an application `CoroutineScope` after `startKoin`.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/` -- the home for `AndroidAlarmScheduler`, `AlarmFiredReceiver` and `SystemEventsReceiver` (with the `Android<Port>` naming). `AndroidTimeChangeSignal.kt` shows the receiver style.
- `androidApp/src/test/resources/robolectric.properties` -- sdk 34. Use `@Config(sdk = [31])` / `[32]` for the permission-denied tests. Every Robolectric test class must call `stopKoin()` in `@After` (the real app starts Koin). Override bindings with `loadKoinModules(module(override) {...})` for the fake clock and zone in the broadcast tests.
- `config/detekt-rules/src/main/kotlin/com/yawnandpawn/app/detekt/` -- the `YawnAndPawnRuleSetProvider` map. Follow the `NoDirectTimeAccess.kt` pattern, its tests (`rule.lint`) and the service file. `config/detekt/detekt.yml` (lines 23 to 48) must enable the new rule; `DetektConfigTest` checks that every rule is active.
- `config/permission-allowlist.txt` -- already lists USE_EXACT_ALARM, SCHEDULE_EXACT_ALARM (maxSdk 32 is enforced by `PermissionAllowlist.kt`) and RECEIVE_BOOT_COMPLETED.
- `testing/src/commonMain/kotlin/com/yawnandpawn/app/testing/` -- add `FakeAlarmScheduler` (records calls in order, programmable failure) and `FakeRequestCodeSequence`. The existing `AlarmFakes.kt`, `TimeFakes.kt` and `FakeLogger.kt` are there.
- Use-case constructor call sites to update:
  - `core/.../AlarmUseCasesTest.kt` lines 88 to 91;
  - `testing/.../AlarmFakesTest.kt`;
  - `composeApp/.../UiModuleTest.kt` lines 59 to 63;
  - `HomeViewModelTest.kt` lines 86 to 88;
  - `AlarmEditorViewModelTest.kt` lines 70 to 77;
  - `data/.../RoomAlarmRepositoryTest.kt` lines 197 and 230;
  - `androidApp/.../ui/AlarmScreensSemanticsTest.kt` lines 257 and 290 to 305;
  - `YawnAndPawnApp.kt`.

## Tasks & Acceptance

**Execution:**
- `core/.../alarm/AlarmScheduler.kt`, `AlarmScheduling.kt` (sync and `rescheduleAll`), `AlarmFiredHandler.kt` (port plus the Epic 1 default `RearmOnFire`), `RequestCodeSequence.kt`; `DomainError.ExactAlarmNotPermitted` -- the core contract and logic, at least 90% Kover.
- `core/.../alarm/AlarmUseCases.kt` -- inject the sequence and the scheduling helper, sync after writes, and remove `nextRequestCode(repository)`.
- `testing/...` -- `FakeAlarmScheduler` and `FakeRequestCodeSequence`, with tests.
- `data/...` -- the v2 entity, DAO, migration, `RoomRequestCodeSequence`, binding and exported schema.
- `androidApp/...` -- `AndroidAlarmScheduler`, the two receivers, the manifest, the Koin bindings and `rescheduleAll` on app start.
- `config/detekt-rules/...`, `config/detekt/detekt.yml` -- `NoInexactAlarm`, with tests.
- `docs/decisions/reboot-clock.md` -- the decision note.
- Tests:
  - every I/O matrix row, as core unit tests with fakes;
  - Robolectric `ShadowAlarmManager` tests (trigger == `nextOccurrence` epoch ms, show intent → MainActivity, immutable operation with the extras and the code, cancel, slot and test codes, the API 31/32 denied path);
  - a test that the manifest receivers are present with `directBootAware`, the exported flags and the actions;
  - a broadcast test for each action (boot, time set with a DST-gap day, zone change Berlin → New York, package replaced, exact-alarm permission changed);
  - the fired receiver for repeating and one-time alarms;
  - the migration test, the locked-storage test, the sequence persistence across a DB rebuild, and the detekt rule tests.
- `_bmad-output/implementation-artifacts/deferred-work.md` -- add a line for Story 1.13: `app.db` is now v2, so `session_history` is the v2 → v3 migration.

**Acceptance Criteria:**
- Given an enabled alarm saved in the app, when Robolectric inspects `ShadowAlarmManager`, then exactly one alarm clock is scheduled at the alarm's next occurrence, and no inexact alarm API appears anywhere in production code (the detekt rule passes on the codebase and fails on a fixture).
- Given the app restarts after a reboot, an update or a clock or zone change, when the matching broadcast or app start runs, then every enabled alarm is armed at its recomputed time and every disabled alarm's code is cancelled.
- Given `./gradlew qualityGate`, when it runs, then it passes with the permission and dependency allowlists green and `data/schemas/.../2.json` committed.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
- verdicts: 39 findings — high 0, medium 9, low 20, false 9, maybe-false 1
- findings:
  - (verification-gap) `[medium]` `[patch]` App-start `rescheduleAll()` never observed to arm a stored alarm (the only path after a backup restore) — added a Robolectric test that stores alarms, restarts the app start-up path and checks ShadowAlarmManager.
  - (verification-gap) `[medium]` `[patch]` The `SecurityException` branch of `AndroidAlarmScheduler.arm` is never reached — added a throwing-AlarmManager test (Failure, logged, nothing armed) and sdk 33 coverage.
  - (verification-gap) `[medium]` `[defer]` ApplicationScope's exception handler, the receivers' 8 s timeout log and `pending.finish()` in all cases are untested — defensive branches; recorded in `deferred` for Story 1.14, when the fire handler gets real work.
  - (blind) `[medium]` `[patch]` A one-time alarm whose time passed while the phone was off is re-armed for tomorrow by `rescheduleAll()` — spec gap; resolved with the only reading consistent with PRD FR-ONB-5 (powering off is an accepted escape): a passed one-time alarm is stored disabled, cancelled and logged, not armed; core tests added.
  - (blind) `[low]` `[reject]` No reconciliation of orphan system alarms after a process death between delete and cancel — millisecond window; an orphan fire is ignored and logged (no ring); a fix needs a tombstone table.
  - (blind) `[medium]` `[patch]` SecurityException path untested and the "API 33" test only runs on sdk 34 — grouped with the verification-gap row above.
  - (blind) `[low]` `[patch]` Port KDoc and fakes say cancel can fail with ExactAlarmNotPermitted while the real adapter never does, so two core tests count failures production cannot produce — contract, fakes and expectations aligned (only arming calls can be denied).
  - (blind) `[medium]` `[defer]` "Finish the pending result in all cases" never tested (timeout or throwing handler) — grouped with the verification-gap defer row.
  - (blind) `[low]` `[reject]` A failed or timed-out boot reschedule is never retried — needs a storage failure at boot; the next app start or system event re-arms; a retry adds scheduling machinery.
  - (blind) `[low]` `[reject]` Two `rescheduleAll()` runs on a cold start from a broadcast, and a possible early-fire re-arm — the runs are idempotent; `setAlarmClock` delivers at or after the trigger, so `now < scheduledAt` needs a backwards clock change in the same milliseconds, which TIME_SET then reschedules.
  - (blind) `[low]` `[reject]` `RearmOnFire` releases the lock before `SetAlarmEnabled`, so a user edit in between could be switched off — needs an edit within milliseconds of the fire; the fix restructures the handler's locking.
  - (blind) `[low]` `[patch]` `assertTrue(weekday.enabled)` checks the local value and can never fail — the test now reads the alarm back from the repository.
  - (blind) `[low]` `[patch]` `NoInexactAlarm` misses `AlarmManagerCompat` calls in files that never name `AlarmManager` — the rule also triggers on `AlarmManagerCompat`; test added.
  - (blind) `[maybe-false]` `[defer]` `AndroidAlarmSchedulerTest` and `ReceiversManifestTest` don't wait for the app-start reschedule before `stopKoin()` — could make them flaky; settle by running both classes 20 times or waiting for the scope's children; recorded in `deferred` (medium, unverified).
  - (blind) `[low]` `[patch]` `FakeAlarmScheduler.armSessionSlot` records the raw deadline wall time, unlike the adapter — documented on the `armed` map.
  - (blind) `[false]` `[reject]` No device check, and nothing on ColorOS auto-launch or force-stop — the intent forbids using the owner's phone; device verification is Stories 1.20 and 1.21, OEM guidance Story 5.5.
  - (blind) `[false]` `[reject]` The zone-change test sets the default zone before the broadcast and cannot catch late zone propagation on a device — same reason as the device row; Story 1.21 item 9 covers it.
  - (blind) `[low]` `[patch]` `AlarmsRescheduled.cancelled` counts cancel calls, not system alarms removed — renamed to `disabled`.
  - (edge-case) `[low]` `[patch]` A non-SecurityException from `setAlarmClock` (the per-app alarm limit) escapes, failing a use case after its write and aborting `rescheduleAll` — the adapter now catches RuntimeException, logs it and returns a Failure.
  - (edge-case) `[medium]` `[patch]` Passed one-time alarm moved to tomorrow — grouped with the blind row (passed one-time alarms are disabled).
  - (edge-case) `[low]` `[reject]` Early repeating fire plus the app-start run re-arms the same occurrence — same as the blind row; same reason.
  - (edge-case) `[low]` `[reject]` A stale fire disables a one-time alarm the user just edited — same as the blind locking row; same reason.
  - (edge-case) `[low]` `[reject]` A timeout between the lock block and `SetAlarmEnabled(false)` leaves a fired one-time alarm enabled — the passed-one-time patch makes the next `rescheduleAll` disable it instead of arming it for tomorrow.
  - (edge-case) `[low]` `[reject]` An orphan alarm after the process dies between delete and cancel — same as the blind row; same reason.
  - (edge-case) `[low]` `[patch]` `AlarmManagerCompat` blind spot in `NoInexactAlarm` — grouped with the blind row (rule extended).
  - (edge-case, claim) `[low]` `[patch]` KDoc says a failed scheduler call heals on the next `rescheduleAll`, false for a cancel after a delete — sentence corrected to stored alarms only.
  - (edge-case, claim) `[low]` `[patch]` The `rescheduleAll` loop has no per-alarm exception guard — covered by the adapter's RuntimeException catch above; the 8 s budget cutting the loop short is rejected (Room reads take milliseconds).
  - (intent) `[false]` `[reject]` Locked storage tested at the database factory, not through the receivers and Koin — the added AC asks exactly for opening `app.db` with credential storage locked (device-protected context only); the receivers' direct-boot path is Epic 2 (`LOCKED_BOOT_COMPLETED`).
  - (intent) `[low]` `[reject]` Broadcast tests use the real `AndroidTimeZoneProvider` with `TimeZone.setDefault` rather than `FakeTimeZoneProvider` — it exercises the real adapter, which is stronger than the AC's fake; core tests use the fake-style zone.
  - (intent) `[false]` `[reject]` Idempotency checked with a core recording double, not `FakeAlarmScheduler` — `:core` cannot depend on `:testing` (AD-1); the double records the same call list.
  - (intent) `[low]` `[reject]` Robolectric compares against hand-computed instants, not `nextOccurrence` — the independent values are a stronger check; a core test compares with `nextOccurrence` directly.
  - (intent) `[false]` `[reject]` The inexact-alarm ban is static only — the AC allows "a detekt or lint rule".
  - (intent) `[medium]` `[patch]` App-start reschedule untested — grouped with the verification-gap row (test added).
  - (intent) `[medium]` `[defer]` No test for "never start a foreground service" or finishing the pending result — grouped with the verification-gap defer row; no FGS or activity start exists in the receivers (grep).
  - (intent) `[low]` `[reject]` System alarms cleared by a reboot are not simulated — boot tests start from an unarmed state, which is what a reboot leaves.
  - (intent) `[false]` `[reject]` DST gap tested via TIME_SET rather than boot — the AC lists the broadcasts together; the core test covers the boot path.
  - (intent) `[false]` `[reject]` Ringing on a device is not shown — ringing is Story 1.14 and the intent forbids the phone.
  - (intent) `[false]` `[reject]` PR, CI, merge and sprint status not in the diff — they run after review.
  - (intent) `[false]` `[reject]` Additions beyond the AC (ApplicationScope, timeouts, extra log events, per-kind actions, test fixture) — supporting surface, not a divergence.

- patch verification (2026-10-01): the first full gate after the patches failed once. `MainActivityTest` got a Robolectric `UnsatisfiedLinkError` in RenderNode, then the remaining cases cascaded into `KoinApplicationAlreadyStartedException`. That evidences the `maybe-false` row about tests not waiting for the app-start reschedule. It was patched: a shared `StopAppRule` / `stopApp()` (androidApp/src/test/.../TestApp.kt) waits for the ApplicationScope jobs, cancels the scope and stops Koin even when the activity launch fails, in every class that boots the app. Three clean runs of `:androidApp:testDebugUnitTest` and the full gate passed. The frontmatter `deferred` entry for it is kept (append-only) but is resolved.

## Design Notes

- **Sequence allocation:** inside one Room transaction, read `last_used` and write `last_used + 1`. Use cases already hold `AlarmWriteLock`, but the transaction is what makes it crash-safe. A save that later fails validation still burns a code. That is fine, because codes are never reused.
- **Receiver dependencies:** use `KoinComponent`, and launch on an injected application scope (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`). Call `pendingResult.finish()` in `finally`. Keep the receiver work well under the 10 s broadcast limit.
- **Fake clock in broadcast tests:** the receivers read `Clock` and `TimeZoneProvider` from Koin, so tests override those bindings, then set the system zone to match where Robolectric needs it.
- **Environment (company PC):** export `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`, and run `./gradlew --stop` after build-logic or detekt-rule changes. Never use the owner's phone.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, including detekt with `NoInexactAlarm`, Kover on core at 90% or more, and both allowlists.
- `git status --porcelain androidApp/src/test/screenshots` -- expected: empty (no screenshot changes in this story).

## Auto Run Result

**Summary:** alarms are now registered with Android.
- **Scheduling:** the `AlarmScheduler` port has a `FakeAlarmScheduler` and an `AndroidAlarmScheduler`. The adapter uses only `setAlarmClock`, with immutable PendingIntents and a show intent to MainActivity. The session slot uses reserved code 1 and the test alarm code 2.
- **Permission handling:** `ExactAlarmNotPermitted` is returned on API 31 and 32. Any other refusal returns `SchedulerFailure`, and nothing falls back to inexact alarms.
- **Keeping alarms in sync:** the core helper `AlarmScheduling` keeps every write in sync. `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm` sync after a successful write. `rescheduleAll()` is idempotent. It disables a one-time alarm whose time passed while the phone was off, and it runs on app start and from `SystemEventsReceiver` on boot, time set, zone change, package replaced and the exact-alarm permission change.
- **Firing:** `AlarmFiredReceiver` passes the fire to `RearmOnFire`, which re-arms a repeating alarm and disables a one-time alarm.
- **Request codes:** a persisted high-water mark replaces the old scheme (`app.db` v2 with `request_code_sequence` and a v1 to v2 migration). Codes are never reused.
- **Also added:** the detekt rule `NoInexactAlarm`, `docs/decisions/reboot-clock.md`, and a locked-credential-storage database test.

**Files changed (main ones):**
- `core/.../alarm/AlarmScheduler.kt`, `AlarmScheduling.kt`, `AlarmFiredHandler.kt`, `RequestCodeSequence.kt` and `AlarmUseCases.kt`, plus `DomainError` (`ExactAlarmNotPermitted`, `SchedulerFailure`) and `Logger` (`FireIgnored`, `AlarmsRescheduled`, `OneTimeAlarmPassed`).
- `data/.../db/AppDatabase.kt` (v2), `AppDatabaseMigrations.kt`, `RoomRequestCodeSequence` and `data/schemas/.../2.json`.
- `androidApp/.../android/AndroidAlarmScheduler.kt`, `AlarmFiredReceiver.kt`, `SystemEventsReceiver.kt` and `ApplicationScope.kt`, plus the manifest (permissions and receivers) and the Koin wiring with `rescheduleAll` on start.
- `testing/.../SchedulerFakes.kt` (`FakeAlarmScheduler`, `FakeRequestCodeSequence`, `AlarmUseCasesFixture`).
- `config/detekt-rules/.../NoInexactAlarm.kt` and `detekt.yml`.
- Tests: core, data (migration, locked storage, sequence), Robolectric (scheduler, receivers, manifest, app start), and the shared `StopAppRule` teardown.
- `docs/decisions/reboot-clock.md`, and `deferred-work.md` (Story 1.13 is now the v2 to v3 migration).

**Review findings breakdown:** 39 findings: 0 high, 9 medium, 20 low, 9 false, 1 maybe-false.
- **Patched (13 entries):**
  - **4 medium:** passed one-time alarms disabled; app-start reschedule test; SecurityException plus sdk 33 tests; the flaky teardown, promoted from maybe-false.
  - **9 low:** RuntimeException catch; cancel contract aligned; a tautological assertion fixed; `AlarmManagerCompat` added to the detekt rule; fake slot documented; `cancelled` renamed `disabled`; KDoc claim corrected; per-alarm guard via the adapter catch.
- **Deferred:** tests for the ApplicationScope handler, the receiver timeout and finishing the pending result (to Story 1.14). The test-teardown race was also deferred, then resolved.
- **Rejected:** every rejected row has its reason in the Review Triage Log.

**Follow-up review recommendation:** `true`. Four medium entries were patched on this first pass. The unverified risks:
- **Passed one-time alarm rule:** it derives the armed occurrence from `updatedAt` in the current zone. It hasn't been reviewed, and it hasn't been tried on a device with a real power-off.
- **Test teardown:** the change touches about 25 test classes and rests on three clean runs. The original flake was intermittent.

**Verification:**
- `./gradlew qualityGate` gave BUILD SUCCESSFUL after all patches.
- Three clean `:androidApp:testDebugUnitTest` runs passed, with 987 tests each.
- No screenshot changed.
- Every I/O-matrix row has a passing core or data test.

**Residual risks:**
- None of this has been tried on a device. ColorOS auto-launch and force-stop behaviour is unchecked; Stories 1.20, 1.21 and 5.5 cover it.
- CI downloads the Robolectric SDK 31, 32 and 33 jars.
- Fire-handler locking is accepted as low risk; see the rejected rows.
