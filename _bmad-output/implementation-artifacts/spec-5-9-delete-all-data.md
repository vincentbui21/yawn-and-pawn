---
title: 'Story 5.9: Delete all data'
type: 'feature'
created: '2026-10-16'
status: 'review'
baseline_revision: '7035b861d48f15974c67f4939f716c0c9043a087'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/core/src/commonMain/kotlin/com/yawnandpawn/app/core/billing/PurchaseLedger.kt'
  - '{project-root}/core/src/commonMain/kotlin/com/yawnandpawn/app/core/billing/PurchaseReconciler.kt'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** FR-SET-4 asks for one step that removes everything the app keeps about the user on the phone. The You tab already draws the "Delete all data" row and its `dialog-confirm`, but the row is hidden in production and nothing deletes anything. A careless delete could cost the user money, or keep money that is owed back:
- **Lost tokens:** dropping an unconsumed purchase means a snooze already used gets auto-refunded, or a consume never happens.
- **Racing a session:** deleting during a ring or snooze collides with the session's own writes.
- **Orphaned alarms:** alarms stay armed in AlarmManager after their rows are gone.

**Approach:**
- **The use case:** a core `DeleteAllData` runs under `AlarmWriteLock` and `SessionLockGuard.whenIdle`, like every guarded writer, and also requires `SessionState.Idle`.
- **Resumable:** it writes a marker first, then runs fixed, idempotent steps through ports, then removes the marker. A marker left behind makes the app re-run every step before it shows any screen.
- **Money:** this state is settled by keeping it, never by dropping it. The whole `grant_ledger` stays, and so does every purchase record still owed a consume. Purchase records are deleted only through `PurchaseLedger`, the only writer of purchase records.
- **Wiring:** the You row and dialog (already approved in the design preview) are wired to the use case.

## Boundaries & Constraints

**Always:**
- **Refusals change nothing.** `DeleteAllData` returns `DomainError.SessionActive` and changes nothing when the guard is locked (not yet restored, Ring, Snoozed or emergency ring). It does the same when the state is Completed or Missed, still waiting for its history row (stricter than `inProgress`; DEFAULT TAKEN).
- **Step order.** It runs, in this order:
  1. Write the marker: a file in device-protected `noBackupFilesDir`, so it is never backed up and survives the settings clear.
  2. Cancel the alarms through `AlarmScheduler`: every stored alarm's request code, every code from `RequestCodes.FIRST_ALARM` up to the `request_code_sequence` high-water mark, `RequestCodes.TEST_ALARM` and `cancelSessionSlot()`.
  3. Delete every row in `app.db` except purchase records still owed: alarms, check configs, pending changes, commitment events, session history and `session_merge`.
  4. Through `PurchaseLedger`, delete every purchase record except those whose `reconcilerStatus == RecordStatus.Granted`.
  5. Delete every `purchase_intent` row and clear the active session (a no-op when Idle).
  6. Clear the whole settings DataStore (every key, including ones later stories add), the `settings_fallback` cache and the price cache.
  7. Reset the install id, only when nothing owed was kept (no `grant_ledger` row without `settledAt`, and no owed record kept).
  8. Remove the marker.
  9. Log one `LogEvent.AllDataDeleted`.
- **After the guarded block:**
  - Ask Crashlytics to delete unsent reports (when it has started).
  - Enqueue the existing immediate price refresh, so snooze prices come back.
  - Reset the app's navigation to the empty Home.
- **Each step is safe to repeat.** A failed step returns its error and leaves the marker. The next attempt starts again at step 2.
- **No deadlocks.** It never calls another guarded use case such as `DeleteAlarm`; the guard Mutex is not reentrant. It never calls `AlarmScheduling.rescheduleAll()`.
- **Pending deletion on start.** While the marker exists, `AppNavHost` shows `RestoringScreen` and starts `DeleteAllData` once per process when the guard unlocks. If that run fails, the app goes on to Home and retries on the next start. A ring or snooze is never blocked by this.
- **Scan tests.** The new writer is registered in `SessionLockGuardScanTest`. Purchase-record and history deletes go through the adapters the writer scan tests allow (`PurchaseLedger` and its Room adapter, `RoomSessionHistoryRepository`).

**Never:**
- Delete or modify a `grant_ledger` row (pending rows settle through consume-retry; settled markers keep a token from granting twice and are purged after 30 days as today).
- Delete a purchase record still owed a consume.
- Cancel the consume-retry jobs.
- Reset `request_code_sequence`.
- Touch per-device runtime prefs (`wake_runtime`, `reliability`, `backup_restore`). They hold no user content, and `wake_runtime` restores the user's volume.
- Add network calls inside `whenIdle`.
- Add new user-facing strings.
- Build onboarding (5.10).
- Delete Epic 7 media (it doesn't exist yet; deferred).
- Call `resetAnalyticsData()`: Story 5.6 is not on main (deferred). Consent lives in the settings DataStore, which this clears.

## AC deviations and decisions (fast mode: DEFAULT TAKEN (owner can change))

1. **Grant ledger:** all of `grant_ledger` is kept, not just the granted rows. Settled markers stop a token from granting twice and are purged after 30 days.
2. **Owed purchase records** (`reconcilerStatus == Granted`) are kept. Without them, a token restored without its ledger row reads as "never seen", so it would be auto-refunded after the snooze was already used. Once consumed, they become ordinary records, and the next delete removes them.
3. **The install id** is reset only when nothing owed was kept. Reconciler rule 1 ignores tokens with a different account id unless a ledger row is Granted, so a reset with an owed record kept would leave that payment unconsumed.
4. **BackgroundWork:** no job is cancelled. The only other job is price refresh, which holds no user data and refills the price cache this clears. An immediate refresh is enqueued instead.
5. **The `request_code_sequence` high-water mark is kept:** a request code is never reused.
6. **Completed or Missed** waiting for history also refuses with `SessionActive`.
7. **Logging:** one `AllDataDeleted(alarms, keptRecords, at)` event, with no `AlarmDeleted` per alarm and no commitment events (they are deleted anyway). Deferred item (Story 1.9 "Move the AlarmDeleted log into the core delete use case"): resolved. `DeleteAlarm` already logs in core, and "Delete all data" logs once.
8. **Failure UI:** a failure only logs `OperationFailed`, because EXPERIENCE.md defines no error string. This is listed for the owner.
9. **Deferred:** media (Epic 7), `resetAnalyticsData()` (whichever of 5.6 and 5.9 merges second), and onboarding after a delete (5.10).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy path | Idle; alarms armed, test alarm armed, history, settled records, intents, settings, price cache | All deleted, every code cancelled, install id new, marker gone, `AllDataDeleted` logged | none |
| Session active | Ring, Snoozed, emergency, not restored, Completed or Missed | `SessionActive`; no store or scheduler call; no marker | returned, not logged as a failure |
| Owed money | a pending ledger row, and a Granted record with no ledger row | Ledger untouched, owed record kept, other records deleted, install id **not** reset | none |
| Crash midway | a fake fails, or throws, after step k (each k) | marker stays; the next run completes every step | error returned |
| Resume on start | marker present at launch | Restoring screen, deletion runs, then Home | a failure goes on to Home and retries on the next start |
| Cancel | user taps "Keep it", or dismisses | nothing deleted | none |

</intent-contract>

## Code Map

- `core/.../core/session/SessionLockGuard.kt` -- `whenIdle` (not reentrant), `isLocked`, `state`.
- `core/.../core/alarm/AlarmUseCases.kt:375-399` -- the pattern to copy: `DeleteAlarm` with `lock.withLock { sessionLock.whenIdle { withContext(NonCancellable) { ... } } }`. `AlarmWriteLock` is at :77.
- `core/.../core/alarm/AlarmScheduler.kt` -- `cancel(requestCode)`, `cancelSessionSlot()`. `RequestCodes.kt`: `TEST_ALARM = 2`, `FIRST_ALARM = 1000`. `RequestCodeSequence.kt` holds the high-water mark.
- `core/.../core/billing/PurchaseLedger.kt` -- the only writer of records. Add a method under its `mutex` that deletes non-owed records. `PurchaseRecords.kt:132` is `PurchaseRecordRepository`. `PurchaseRecord.reconcilerStatus` is at :100. `GrantLedgerStore.all()` gives the pending check (`settledAt == null`).
- `core/.../core/billing/InstallIdProvider.kt` -- add a reset. Check whether `DataStoreInstallIdProvider` or `PurchaseCoordinator.installId()` (:692) caches it.
- `core/.../core/billing/PriceRefresh.kt:27` -- the `"price-refresh-now"` job to enqueue afterwards.
- `core/.../core/crash/CrashReporter.kt`, `androidApp/.../crash/FirebaseCrashReporter.kt` -- `CrashSink` (`isReady`). Add a delete-unsent port with an Android adapter.
- `core/.../core/log/Logger.kt` -- add `LogEvent.AllDataDeleted`. `androidApp/.../AndroidLogger.kt` needs a matching branch.
- `data/.../db/AppDatabase.kt`, `RuntimeDatabase.kt`, `data/.../settings/*` (`SettingsDataStore.kt`, `PriceCacheDataStore.kt`, `InstallIdDataStore.kt`), `data/.../config/SharedPreferencesSettingsCache.kt` -- the adapters for each deletion port. They are bound in `data/src/androidMain/.../DataModule.kt`.
- `data/src/androidHostTest/.../SessionLockGuardScanTest.kt:42-52` -- the exact set of guarded use-case names; add `DeleteAllData`. `billing/PurchaseLedgerWriterScanTest.kt` and `history/SessionHistoryWriterScanTest.kt` are the writer scans.
- `androidApp/.../YawnAndPawnApp.kt` -- Koin `appModule`. `SessionLockGuard` is at :235.
- `composeApp/.../ui/you/YouScreen.kt` -- `YouUiState.showDeleteDialog`, `YouIntent.DeleteAllClicked/DeleteConfirmed/DeleteCancelled`, `YouRow`, `ConfirmDialog(destructive = true)`.
- `composeApp/.../ui/nav/AppNavHost.kt:69-78` -- the Restoring gate; `YouTab`/`YouTabRows` is at :213-226. `composeApp/.../ui/UiModule.kt` holds the ViewModels.
- `androidApp/src/test/.../AndroidAlarmSchedulerTest.kt` -- the ShadowAlarmManager pattern. `androidApp/src/test/.../ui/EditorLockScreenshotTest.kt:116-155` -- the dialog Roborazzi pattern (Light, Dark, `fontScale = 2.0f`).

## Tasks & Acceptance

**Execution:**
- `core/.../core/reset/DeleteAllData.kt` (new package) -- the use case, the marker port, the deletion ports and `pending: StateFlow<Boolean>`. Add unit tests for every matrix row, with a fake that fails, then throws, after each step. Kover must reach at least 90%.
- `core/.../billing/PurchaseLedger.kt` and `PurchaseRecords.kt` -- the non-owed record delete and its repository method.
- `core/.../billing/InstallIdProvider.kt`, `crash/`, `log/` -- the reset, the delete-unsent port and the log event.
- `testing/` -- fakes for the new ports.
- `data/...` -- the Room DAO deletes, the DataStore, prefs and marker-file adapters and the DI bindings. Add a `:data` host integration test: fill both databases and the DataStores, run the deletion, then assert every table and file is empty except the ledger rows and owed records, and the marker is gone.
- `androidApp/...` -- the Crashlytics adapter, the logger branch and the Koin wiring. Add a Robolectric test: arm 2 alarms, the test alarm and the session slot through `AndroidAlarmScheduler`, run the deletion, and assert `ShadowAlarmManager` has no alarm left.
- `composeApp/.../ui/you/` and `ui/nav/AppNavHost.kt` -- show `YouRow.DeleteAll`, keep the dialog state, run the use case on "Delete", reset to Alarms afterwards, and add the pending-deletion gate.
- `androidApp/src/test/.../ui/YouDeleteDialogScreenshotTest.kt` -- Roborazzi of the dialog in Light, Dark and at 200% font scale.
- `SessionLockGuardScanTest.kt` -- register `DeleteAllData`.
- `deferred-work.md` -- add the media, `resetAnalyticsData()` and onboarding items, and resolve the Story 1.9 AlarmDeleted item.

**Acceptance Criteria:**
- Given the You tab, when "Delete all data" is tapped, then `dialog-confirm` shows "Delete all data?" with the key-string body, "Delete" in `error` and "Keep it" as the default dismiss. "Keep it" deletes nothing.
- Given the dialog, when "Delete" is confirmed and no session is active, then the app returns to an empty Home, with no alarm left in AlarmManager.
- Given the story file, then the `pps-design` Done checklist is copied with every item ticked, no new strings are added, and `CopyRulesTest` passes.

## pps-design Done checklist

The surface is the approved You row and `dialog-confirm` (design preview `you-main`, `you-delete-dialog`), wired, not redrawn.

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes). No new UI code: `YouScreen`, `NavRow` (`error` title) and `ConfirmDialog` as approved.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: `YouDeleteDialogScreenshotTest` (Light, Dark). Not a wake screen, so no Sunrise.
- [x] Every colour pair used is in the `DESIGN.md` contrast table: `error` on `surface` and `text` on `surface`, unchanged from the approved dialog.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: the existing `NavRow` and dialog buttons; no wake action here.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: `you_delete_dialog_light_font200`; the dialog is Material's `AlertDialog` (title, body and both buttons are read). No outcome is shown.
- [x] Reduced-motion path works: no new motion (the dialog is Material's; the return to Home is a stack reset).
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: the dialog uses the existing `settings_delete_*` resources verbatim; no new strings; `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: row, dialog, "Keep it"/dismiss, "Delete" to the empty Home; the session lock hides You during a session. A failure only logs (decision 8: no error string is defined).
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced: not a wake surface, and nothing here blocks a ring or "I'm up".
- [x] Compose `@Preview`s for each state exist; screenshot tests updated: the design preview already shows `you-main` and `you-delete-dialog`; Roborazzi baselines `you_delete_dialog_{light,dark,light_font200}.png` recorded.

Strings for the owner: none added. The failure case shows nothing (decision 8).

## Spec Change Log

### 2026-10-18: rebase onto c03bdee0 (5.3 and 5.7 merged)

- **Trigger:** Story 5.3 added its own reliability DataStore, `datastore/reliability.preferences_pb`. It holds the locked-test pass, the rows seen OK and the maker confirmation, is never backed up, and isn't covered by step 6. The coordinator relayed that "Delete all data" must also clear it, along with the `settings_fallback` cache (theme and defaults once 5.1 lands).
- **Amendment:** step 6 now also clears the reliability DataStore (`AndroidLocalDataEraser`, with `ReliabilityDataStore` injected), so a fresh start runs the checklist and the locked test again. `settings_fallback` was already cleared. The "Never" item about per-device prefs still holds: it means the `reliability` SharedPreferences (the notification-asked flag), which is untouched. DEFAULT TAKEN (owner can change).
- **Avoids:** a false "locked test passed" and stale "seen OK" rows surviving a delete.
- **KEEP:** the grant ledger, owed records, the request-code high-water mark, `wake_runtime` and `backup_restore` are never touched.

### 2026-10-20: rebase onto a7246758 (Story 5.6 merged first)

- **Trigger:** 5.6 merged before 5.9, so the deferred `resetAnalyticsData()` hook is 5.9's (coordinator). 5.6 keeps the usage stats choice in its own non-backed-up DataStore (`datastore/usage_stats_consent.preferences_pb`), not in the settings DataStore as the earlier deferred note assumed.
- **Amendment:**
  - Step 6 also clears that consent DataStore: off, and not asked, so onboarding asks again.
  - After the guarded part, `DeleteAllData` calls a new core port, `AnalyticsDataReset`. In production it is `SinkAnalyticsDataReset`, which calls `AnalyticsSink.resetData()` once Firebase has started and never throws.
  - `AnalyticsConsentGate` then turns collection off on its own, because the consent reads off.
  - The deferred item is resolved.
- **Avoids:** stats staying on after a delete, and later stats being linked to earlier ones through the old analytics id.
- **KEEP:** the reset runs only after a completed deletion, never inside the guard (no network or SDK call holds a ring).

## Review Triage Log

### Pass 1 (2026-10-18, one combined self-review, fast mode; the coordinator runs the independent review)

Verdicts: high 0, medium 1, low 0, false 1, maybe-false 0.

| # | Verdict | Route | Finding and evidence | Action |
|---|---------|-------|----------------------|--------|
| 1 | medium | patch | After the rebase, the 5.3 reliability DataStore was not cleared (see Spec Change Log). | Cleared in step 6. `DeleteAllDataIntegrationTest` fills it (a locked test pass and a row seen OK) and asserts `StoredReliability()` afterwards. |
| 2 | false | none | Suspected: `KeptPayments.pendingGrants` counts settled markers, which would keep the install id for 30 days. | `GrantLedgerStore.all()` returns pending rows only. The integration test has a settled marker with nothing owed and the install id is new. |

Money safety, checked against the code:
- The `grant_ledger` is never written.
- The records delete runs only in `PurchaseLedger`, under its Mutex. Its query deletes only `consumed`, `stranded` and `reused` with `consumed_at` set. Granted, reused-not-consumed and unknown statuses stay.
- The install id is reset only when `KeptPayments.owed` is false.
- Refused unless `whenIdle` passes and the state is `Idle`.
- Alarms are cancelled before their rows go (stored codes, the issued range from 1000 to the high-water mark, test alarm 2, the session slot), and `ShadowAlarmManager` is empty afterwards (`DeleteAllDataAlarmsTest`).
- The consume-retry jobs are never cancelled. No job is cancelled at all (decision 4).

## Verification

**Commands:**
- `./gradlew --no-daemon --max-workers=2 :core:jvmTest --tests '*DeleteAllData*'` (or the core host test task that runs commonTest) -- expected: passes.
- `./gradlew --no-daemon --max-workers=2 :data:testAndroidHostTest --tests '*DeleteAllData*' --tests '*ScanTest*'` -- expected: passes.
- `./gradlew --no-daemon --max-workers=2 :androidApp:testDebugUnitTest --tests '*DeleteAllData*' --tests '*YouDeleteDialog*'` -- expected: passes, with screenshots recorded.
- `./gradlew --no-daemon --max-workers=2 :composeApp:testAndroidHostTest --tests '*CopyRules*' --tests '*You*'` -- expected: passes.
- `./gradlew --no-daemon --max-workers=2 :core:koverVerify spotlessCheck detekt` -- expected: passes.

## Auto Run Result

Status: review. The coordinator pushes, reviews, runs CI and merges.

- **Built:**
  - Core: `DeleteAllData` and `ResumePendingDeletion` (`core/reset`), `PurchaseLedger.deleteSettledRecords()`, and the `InstallIdReset`, `UnsentCrashReports` and `RequestCodeHighWaterMark` ports, plus `LogEvent.AllDataDeleted`.
  - Storage: the Room deletes (`AlarmDataDao`, history, records, intents), `AndroidLocalDataEraser` (settings, settings fallback, price cache, reliability records) and `FileDeletionMarker`.
  - Crashlytics: `CrashlyticsUnsentReports`.
  - UI: `YouViewModel`, the You "Delete all data" row and dialog, and the pending-deletion gate (`PendingDeletion.kt`).
  - Scan tests register the new writers. `deferred-work.md` resolves the Story 1.9 item and adds three items (Epic 7 media, `resetAnalyticsData()`, 5.10 onboarding).
- **Verification after the rebase onto c03bdee0** (2026-10-18, all with `--no-daemon --max-workers=2`, all passed):
  - `:core:jvmTest --tests '*DeleteAllData*'` (15 tests)
  - `:data:testAndroidHostTest`: `DeleteAllDataIntegrationTest` (3), the four scan tests and `DataModuleTest`
  - `:composeApp:testAndroidHostTest`: YouViewModel, TabNavigation, CopyRules, UiModule
  - `:androidApp:testDebugUnitTest`: `DeleteAllDataAlarmsTest`, `YouDeleteDialogScreenshotTest` (3 baselines), `MainActivityTest` (21), `BackupRulesTest`, `BackupRulesCoverageTest`, `CrashReportingTest` and `AndroidLogAndTimeSignalTest`
  - `spotlessCheck` and `detekt` (all modules)
  - Before the rebase: `:core:koverVerify`, including the new `koverVerifyReset` rule at 90%. The full `qualityGate` is left to CI.
- **Rebased again onto 5ae48f45 (Story 5.5):**
  - One import conflict in `UiModule.kt` (two ViewModels, both kept).
  - detekt then flagged `AppNavHost` (LongMethod 62, TooManyFunctions 12), so the You tab composables moved to `ui/nav/YouTab.kt`.
  - Re-run after that, all passed: composeApp YouViewModel, TabNavigation, UiModule, CopyRules; androidApp `DeleteAllDataAlarmsTest`, `MainActivityTest` (21) and `YouDeleteDialogScreenshotTest` (3); `spotlessCheck`; and detekt for composeApp and androidApp.
- **Rebased onto 620fb48d (Story 5.1), 2026-10-19:**
  - Conflicts in `YawnAndPawnApp.kt` imports and `deferred-work.md` were resolved keeping both sides. The 5.1 item "Delete all data must also clear `settings_fallback`" is marked resolved.
  - 5.1's new keys (`default_grace_seconds`, `default_snooze_length_minutes`, `default_vibrate_in_grace`, `theme_mode`) live in the settings DataStore, which is cleared whole. The `settings_fallback` snapshot, with its defaults and theme, is cleared with `SharedPreferences.edit().clear()`.
  - `DeleteAllDataIntegrationTest` now fills those keys and a snapshot with 5.1's fields, and asserts both are empty afterwards.
  - Re-run, all passed: core `DeleteAllDataTest` (15); `:data` `DeleteAllDataIntegrationTest` (3), the four scan tests and `DataModuleTest`; composeApp YouViewModel (5), TabNavigation (13), UiModule (4) and CopyRules (15); androidApp `DeleteAllDataAlarmsTest`, `YouDeleteDialogScreenshotTest` (3), `MainActivityTest` (21), `BackupRulesTest`, `BackupRulesCoverageTest`, `CrashReportingTest` and `AndroidLogAndTimeSignalTest`; `spotlessCheck` and detekt for every module.
- **Owner review:**
  - The You "Delete all data" row and its `dialog-confirm` (screenshots `androidApp/src/test/screenshots/you_delete_dialog_{light,dark,light_font200}.png`).
  - A failed deletion shows no message (decision 8).
  - Defaults 1 to 9 and the reliability-store clear.
- **Follow-up review recommended:** false. One medium was patched, and it is covered by the integration test.

## Review (fast mode)

The coordinator's review of PR #72 (one reviewer, two lenses) found 1 HIGH, 3 MEDIUM and 6 LOW. Money core confirmed sound. Every item is applied in `fix(5.9): review fixes`.

| # | Severity | Finding | Fix | Tests |
|---|----------|---------|-----|-------|
| 1 | HIGH | A failed delete let the user carry on; the next start then wiped what they did, or left rows enabled but unarmed. | A failed run that left the marker holds the app (`PendingDeletion`, one per process). `SessionLockGuard.whenIdle` refuses every write but the deletion's own (`whenIdleForDeletion`). The restoring screen shows while `ResumePendingDeletion` runs it again. A ring is never blocked: `startingSession` ignores it, and the session lock screen shows first. | `DeleteAllDataTest`: every step failing or throwing leaves it pending, and `whenIdle` refused, until a run completes. `SessionLockGuardTest`, `YouViewModelTest`. |
| 2 | MEDIUM | The resume only started from the UI, while background starts re-armed half-deleted alarms. | `YawnAndPawnApp.onCreate` calls `ResumePendingDeletion.onAppStart()` before `rescheduleAll`. `AlarmScheduling.rescheduleAll` arms nothing while pending. With no session stored, the resume restores the empty session itself, which runs no entry effect. `RestoreEntryPointsTest` allows only that one guarded call. | `PendingDeletionStartTest`: a start with no activity finishes and arms nothing; with a session stored, nothing is armed and the deletion is held. `AlarmSchedulingTest`. |
| 3 | MEDIUM | The resume could wait forever (Completed/Missed whose history write never lands), and a crashing resume looped. | The resume gives up to Home after `IDLE_WAIT` (10 s) unlocked but not Idle; a ring or snooze has no limit. Each process retries a failed run 3 times, 2 s apart. The marker counts app starts (`countStart`), and after 3 starts that found it, the next gives up at once. Giving up releases the app, keeps the marker and logs. | `DeleteAllDataTest`: idle timeout, 3 failed runs, the start cap. `DeleteAllDataIntegrationTest`: the count in the file. |
| 4 | LOW | Records with a pending ledger row, and their intents, were deleted; a later settle rebuilt them priced by tier. | `PurchaseLedger.deleteSettledRecords` reads the pending rows first. The record delete keeps their token hashes, and the intent delete keeps their sessions (`KeptPayments.pendingSessions`). | Core and `:data` integration (a stranded record and an intent of the pending row's session stay). |
| 5 | LOW | A recovery racing steps 4 to 7 left a stranded record behind. | Step 4 runs again after step 7 (re-run approach). | `DeleteAllDataTest`: a record inserted during the intent step is gone. |
| 6 | LOW | `deleteUnsentReports` may do nothing while automatic collection is on. | Collection is turned off, the delete runs, then collection is turned back on (in `finally`). **Not verified** against the Firebase reference: the docs pages fetched did not show the method's text. This is harmless either way, because `FirebaseStartup` turns collection on at every start. | `CrashReportingTest`: the call order. |
| 7 | LOW | A backup restore after a delete brings the old data back. | Owner note below. One sentence added to `docs/site/privacy.md` "Deleting your data". | `checkSitePages` |
| 8 | LOW | The guard scan's WRITES missed the new ports. | WRITES now matches the deletion ports whatever the receiver, and `*Session.clear(`; GUARD accepts `whenIdleForDeletion`. New `DeleteAllDataWriterScanTest`: the ports only in `DeleteAllData`, `deleteAllAlarmData` (renamed from `deleteAll`) and `deleteAllExcept` only in `AndroidLocalDataEraser`, `deleteRowsAndMerges` only in `RoomSessionHistoryRepository`. | Rogue samples in both scans. |
| 9 | MEDIUM | The real start-up wiring was never run. | Covered with real start-ups. | `PendingDeletionStartTest`: a start with no activity; a stored session holding the deletion; MainActivity landing on the empty Home with nothing armed and the marker gone. The restoring screen is transient, so it is not asserted. |
| 10 | LOW | Guard hold time and UI event timing. | The deletion runs on `Dispatchers.Default` (injectable). Inside the guard only the stored codes, the test alarm and the slot are cancelled; the unused issued codes are cancelled after it (`DeletionAlarmCodes`, failures logged with a count). The return to Home follows `PendingDeletion.completed`, collected by `AppNavHost` (not the tab's view model). "Delete all data" is disabled while the session is not Idle or a deletion runs (`YouUiState.deleteEnabled`, no new string; an open dialog closes). | `YouViewModelTest` refusal states; `DeleteAllDataTest` unused-code logging. |

**Owner notes (known limits):**
- **The cloud backup copy survives.** "Delete all data" deletes the data on the phone only. Restoring a phone from the Google backup made before the delete brings the old alarms, history, purchase records and settings back. The privacy page now says so. An app can't delete its Auto Backup copy; the user removes it in Google Drive (Storage › Backups).
- **Firebase identifiers survive.** The Firebase installation id (FID) and Crashlytics' own installation UUID are not reset; only an uninstall resets them. The app's own install id is reset when nothing owed is kept.
- **The resume can give up.** After it gives up (3 failed runs, a session left unfinished for 10 s, or 3 starts), the app runs as usual with the marker kept, and the next start tries again until the start cap. A new "Delete all data" from the You tab resets the count.
- **UI for owner review:** "Delete all data" now shows disabled (the standard disabled `NavRow`) during the moment a session waits for its history row. No new strings.

**Verification of the review fixes and the 5.6 reset, on a7246758** (2026-10-20, `--no-daemon --max-workers=2`):
- **Run 1, all checks together.** All passed except two:
  - `DeleteAllDataTest`: the give-up-after-3-runs test used `advanceUntilIdle`, which does not run `backgroundScope` work. Fixed with `advanceTimeBy` and `runCurrent`.
  - `:composeApp:compileAndroidMain`: failed on a Kotlin incremental-compilation cache error ("FqNames can't be derived from DirtyData"), not on the code.

  Passed in run 1: the `:data` tests (`DeleteAllDataIntegrationTest` 3, `DeleteAllDataWriterScanTest` 3, `SessionLockGuardScanTest` 4, `PurchaseLedgerWriterScanTest` 3, `SessionHistoryWriterScanTest` 4, `PurchaseStateReaderScanTest` 4, `DataModuleTest` 7, `DataStoreConsentStoreTest` 9), `checkSitePages`, `spotlessCheck`, and detekt on every module.
- **Run 2, after deleting `composeApp/build/kotlin` (the incremental caches).** Every check that failed or was skipped in run 1 now passes; the build was successful:
  - `koverVerify` (all core tests, including `DeleteAllDataTest` 21, `SessionLockGuardTest` 9, `AlarmSchedulingTest` 30, with the 90% rules);
  - composeApp: YouViewModel 5, TabNavigation 13, UiModule 4, CopyRules 15;
  - androidApp: `PendingDeletionStartTest` 3, `DeleteAllDataAlarmsTest`, `MainActivityTest` 21, `RestoreEntryPointsTest` 14, `AppStartRescheduleTest`, `CrashReportingTest` 16, `BackupRulesTest` 7, `BackupRulesCoverageTest` 4, `AndroidLogAndTimeSignalTest` 7, `SinkAnalyticsDataResetTest` 2, `TelemetryWiringTest` 2, `AnalyticsConsentGateTest` 17, `YouDeleteDialogScreenshotTest` 3;
  - `spotlessCheck` and core detekt.
- The full `qualityGate` is left to CI.

**CI on PR #72 (head 6456f0d4): 1 of 2255 tests failed, and it is not caused by 5.9.**
- **The failing test:** `WakeServiceTest` "a refused alarm start through the receiver arms the session slot one heartbeat ahead carrying the alarm", at its last assertion (`WakeServiceTest.kt:766`, "no service started"). The message, from the CI reports artifact, is "Expected value to be null, but was: Intent { act=…WAKE_RESTORE … }".
- **What passed:** the slot assertions before it passed (armed, carrying alarm A, one heartbeat ahead). `asked == [ACTION_ALARM]` also passed, which proves this test's own Koin graph asked for nothing else.
- **Where the start came from:** a `WAKE_RESTORE` start is only made by `WakeRuntime.ensureService()` through a real `WakeServiceStarter`. This test replaced its starter with a throwing one, so the start came from another graph. That fits the test run just before it ("the emergency ring … after a kill …"), which builds two graphs and ends with a session ringing. It is the cross-test leftover that `stopApp`'s KDoc already names: "WakeServiceTest's 'no service started' failed".
- **Why 5.9 is not involved:** none of 5.9's code runs on this path.
  - `restartKoin` (WakeApp) never calls `onCreate`, so `onAppStart` does not run for these graphs.
  - With no marker, `onAppStart` launches nothing anyway, and `deletionPending` stays false.
  - `startingSession` never reads `deletionPending`.
- **Local result:** `WakeServiceTest` passed 38/38 in three local runs (below). No code change was needed.

**Rebased onto 2d35e706 (Story 5.8), 2026-10-21.** The earlier commits were squashed into one feature commit plus the sprint-status chore before the rebase, so the You conflicts were resolved once.
- **YouViewModel:** 5.8's view model (links, email support, snackbars) gained 5.9's `deleteAllData` and `sessionLock`, with the delete logic and `deleteEnabled`.
- **Rows and screen:** `BuiltYouRows` now includes `DeleteAll`. `YouScreen`'s `PrivacyCard` passes `deleteEnabled`.
- **Files:** 5.9's `ui/nav/YouTab.kt` was dropped in favour of 5.8's `YouEntries.kt` and `YouRoute`.
- **Wiring:** `UiModule` binds both. A new testing helper, `aDeleteAllData`, builds the use case on fakes for `YouViewModelTest`, the new `YouDeleteAllDataTest` and `UiModuleTest`.
- **Tests:** `YouSemanticsTest`, `YouRouteTest` and `MainActivityTest` now expect the "Delete all data" row, and the "Privacy policy" row is shown.
- **Baselines:** the six `YouScreenshotTest` baselines (`you_{light,dark,light_font200}` and `you_no_email_app_*`) were re-recorded, because 5.8 made them and 5.9 adds the row.
- **detekt:** `AppNavHost` went over LongMethod (61 lines), so its two restoring-screen returns were merged into one.
- **Re-run on the merged tree, all passed:**
  - core `DeleteAllDataTest` 21, `SessionLockGuardTest` 9, `AlarmSchedulingTest` 30;
  - the `:data` reset, scan and module tests;
  - composeApp `YouViewModelTest` 11, `YouDeleteAllDataTest` 4, `TabNavigationTest` 17, `UiModuleTest` 5, `CopyRulesTest` 15;
  - androidApp `WakeServiceTest` 38 (×3), `MainActivityTest` 21, `PendingDeletionStartTest` 3, `DeleteAllDataAlarmsTest`, `YouScreenshotTest` 12, `YouSemanticsTest` 7, `YouRouteTest` 10, `YouDeleteDialogScreenshotTest` 3, `RestoreEntryPointsTest` 14, `AppStartRescheduleTest`, `CrashReportingTest` 16, `BackupRules*`, `SinkAnalyticsDataResetTest`, `TelemetryWiringTest`;
  - `checkSitePages`, `spotlessCheck`, and detekt on every module.

