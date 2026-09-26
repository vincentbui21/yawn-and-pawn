---
type: review
reviewer: adversarial (unit-pair incompatibility attack)
target: ARCHITECTURE-SPINE.md (draft, 2026-09-26)
context: prd-pay-per-snooze-2026-09-26/prd.md v0.2
date: 2026-09-26
method: For each area, build two units (epic/story) that an AI loop could implement independently, each obeying AD-1..AD-15 literally, and show they still build incompatibly. Each such pair is a hole; each hole gets a proposed new/tightened AD rule.
---

# Adversarial review — Architecture Spine

## Summary

| Severity | Count |
|---|---|
| Critical | 5 |
| High | 10 |
| Medium | 12 |
| Low | 6 |
| **Total** | **33** (of which 7 are Android/Play/KMP behaviour claims, marked **[PLATFORM]**) |

Severity key: **Critical** = alarm fails to ring/stop correctly, or money is charged/granted wrongly; **High** = two stories will build incompatible code with near certainty; **Medium** = likely rework or a latent bug; **Low** = cosmetic or easy to catch in review.

Top pattern: the AD-2 transition table is normative but under-specified in exactly the places where two epics meet (E2 alarm core, E3 check engine, E5 billing, E7 progress). The table names effects ("save progress", "log", "freeze config", "arm backup") without saying *which store*, *which owner*, *which type*, or *what re-running them means*. Most holes below close by (a) moving data into `SessionState` with a named type, and (b) giving every piece of persisted data exactly one writer.

---

## Critical

### C1 — Snooze re-ring alarm and backup alarm: two alarms, one moment, no event mapping
- **Unit A (E2 "Backup alarm", FR-SES-2):** obeys AD-4 — fixed request code `BACKUP`, `setAlarmClock(now+60s)` on each "arm backup" effect; during a snooze, per FR-SES-2, it sits at snooze end. Its receiver emits `ProcessRestored` (the only row that sounds like recovery).
- **Unit B (E2 "Snooze re-ring", FR-RNG-6):** obeys AD-4 — fixed request code `SNOOZE`, `setAlarmClock(until)`; its receiver emits `SnoozeElapsed`.
- **Clash:** at snooze end both fire. Depending on order: `SnoozeElapsed` → Ringing(n+1), then `ProcessRestored` → re-runs Ringing entry effects (double play/ramp restart, double "arm backup"; see C4). Or `ProcessRestored` first on Snoozed → "re-run entry effects" of Snoozed, which the table does not define. Worse: during a normal ring nobody re-arms the backup ("re-armed continuously" has no event or effect in the table), so it fires every 60 s while the process is healthy, and whatever event it maps to is either a no-row "bug" or a spurious restore. A third story could equally map the backup broadcast to `AlarmFired`, which has a guard only for Idle.
- **Proposed rule (AD-4a "one session wake slot"):** While a session is active, exactly one system alarm exists for it, request code `SESSION_SLOT`. In Ringing/Grace/Loud it is the heartbeat backup at `now+60 s`, re-armed by a `BackupTick` effect every ≤ 30 s while `WakeService` runs. In Snoozed it is the re-ring at `until` (no separate backup). Its receiver always emits one event, `SessionSlotFired(firedAtWall, firedAtElapsed)`. The engine interprets it by state: Snoozed and deadline passed → `SnoozeElapsed`; active and service alive → re-arm only; active and process cold → `ProcessRestored`. Delete the separate "snooze re-ring" request code.

### C2 — Grant ledger and session transition live in different databases (no atomic grant)
- **Unit A (E2 SessionEngine):** obeys AD-2 — persists `Snoozed(until)` to `runtime.db` write-ahead, then runs the "consume" effect.
- **Unit B (E5 billing, AD-7):** obeys AD-7 — "each token grants at most once (unique index on token)". AD-6 puts "purchase records" in `app.db`, so the unique index goes there.
- **Clash:** Room cannot do one transaction across two databases. If the app crashes after the `runtime.db` commit and before the `app.db` ledger insert, the next `queryPurchasesAsync` sees a PURCHASED, unconsumed token that is not in the ledger. The session is now Snoozed, so per PRD §6.3 that is a **stranded** token: it is never consumed and is auto-refunded. The user gets the snooze for free. With the order reversed (ledger first), a crash means ledger = granted and session = not snoozed: the reconciler returns `ConsumeOnly` and the user pays without getting a snooze. The same problem appears if the ledger is put in `runtime.db` instead, because "Logged → clear runtime.db session" can delete it before consumption succeeds.
- **Proposed rule (AD-7a "atomic grant"):** The grant ledger is a table in `runtime.db` holding `(token UNIQUE, sessionId, productId, snoozeIndex, grantedAt, consumed)`. The `PurchaseGranted` transition commits the new `SessionState` and the ledger row in one `runtime.db` transaction. Ledger rows are deleted only when `consumed = true` **and** the matching `PurchaseRecord` exists in `app.db`. "Clear runtime.db session" never touches ledger or intent rows.

### C3 — `n` means rings in one row and snoozes in another; the fee ladder index is ambiguous
- **Unit A (E2 session):** follows the table literally: `Ringing(n=1)` on the first ring; `PurchaseGranted` does `n++`; `SnoozeElapsed` → `Ringing(n+1)`. After one snooze, n = 3.
- **Unit B (E5 FeeLadder, AD-7):** `FeeLadder(baseFee, snoozeIndex)` with `snoozeIndex = n` because that is the only counter in the state. The first snooze costs B×1 (n=1), the second costs B×3 instead of B×2.
- **Unit C (E7 stats):** logs `snoozes = n - 1` ("rings minus one"). This gives 2 after one snooze and misclassifies On time vs Snoozed.
- **Proposed rule (AD-2a "named counters"):** `SessionState` carries `ringIndex: Int` (1-based; incremented only by `SnoozeElapsed` / `OverlapAlarmFired` from Snoozed) and `snoozesGranted: Int` (0-based; incremented only by `PurchaseGranted`). The next price is always `FeeLadder.productFor(config.baseFeeTier, snoozesGranted + 1)`. The bare `n` is banned from the table.

### C4 — `ProcessRestored` "re-runs the state's entry effects", but the table lists transition effects, not entry effects
- **Unit A (E2 restore story):** re-runs the effects of the row that entered the current state. For Ringing that means "freeze config" (a new snapshot from **live** settings, which breaks the §6.2 fee lock and commitment lock), "log start" (a duplicate history row), "play at ramp", "arm backup". For Grace it means "start grace timer" (a restart, so every restore extends the mute; PRD §6.4 requires persisted timers). For Snoozed it means "schedule re-ring" plus "consume" (a second consume attempt with an already-consumed token, and "discard check progress").
- **Unit B (E3 grace story):** treats the grace timer as an absolute persisted deadline and expects restore to resume it.
- **Clash:** the two stories produce different grace behaviour after process death. Unit A's replay also corrupts history and the frozen config.
- **Proposed rule (AD-2b "entry vs transition effects"):** Split effects into `onTransition` (one-shot: FreezeConfig, AppendHistory, PersistIntent, LaunchBilling, Consume) and `onEnter(state)` (idempotent and derived only from persisted state: StartService, Play(volume from state), ArmSessionSlot(deadline from state), ShowUi). `ProcessRestored` runs only `onEnter`. All timers are stored in `SessionState` as absolute `Deadline`s (see H1), never as "start timer" effects.

### C5 — Grant is impossible unless `paying` is set, but PRD recovery grants without it
- **Unit A (E5 PurchaseReconciler):** implements PRD §6.3 recovery row 1 literally: PURCHASED, profileId = current active session, not snoozed, not in ledger → `Grant`. Typical cases are pending → purchased 5 minutes after `PurchasePending` cleared `paying`, a lost callback after a crash, and a Play error followed by later completion (UJ4 variant while still ringing).
- **Unit B (E2 SessionEngine):** has the only grant row, "any active **with paying** | PurchaseGranted". With `paying = null` there is no row, so by AD-2 the event is a bug and is dropped. The token then lingers, and on the next query the reconciler still says `Grant`, forever. Once the session ends it becomes "stranded". The user was charged while the alarm kept ringing.
- **Proposed rule (tighten AD-2 table):** `Ringing|Grace|Loud (paying any) | PurchaseGranted(token, sessionId) | sessionId == state.sessionId ∧ token ∉ ledger ∧ state ≠ Snoozed | Snoozed(until), paying = null, snoozesGranted++ | stop sound; ledger write (C2); consume`. Also state that the reconciler takes `(purchase, currentSessionSnapshot, ledger)` and its `Grant` verdict is delivered to the engine as exactly this event, never applied by the adapter.

---

## High

### H1 — Timer representation: "monotonic only" vs `setAlarmClock` (wall only) vs reboot **[PLATFORM]**
- AD-3 says session timers (grace, timeout, backup) use monotonic time. AD-4 says the adapter uses `setAlarmClock()` only, and `setAlarmClock` accepts **only RTC wall-clock time**. `elapsedRealtime` also resets on reboot, and the PRD §6.4 requires "wall-clock + monotonic time + boot count", which the spine dropped.
- **Unit A (E2 scheduler adapter)** converts `elapsedDeadline` to wall time once at scheduling. After `TIME_SET`, `rescheduleAll()` "recomputes from the database" but finds only a monotonic deadline and no boot count. **Unit B (E2 restore)** stores `wallUntil` for the snooze. Two stories, two persisted shapes, and neither survives both a clock change and a reboot.
- **Proposed rule (AD-3a):** a core `Deadline(elapsedMs, bootCount, wallEpochMs)` type is the only persisted timer shape. On the same boot, elapsed time is authoritative; after a reboot (bootCount changed), wall time is used and a deadline already in the past fires immediately (§6.4). The scheduler adapter always derives the `setAlarmClock` trigger from `Deadline` at call time. `TIME_SET` / `TIMEZONE_CHANGED` re-derive every session-slot trigger. `BootCount` port: Android `Settings.Global.BOOT_COUNT`, readable without a permission.

### H2 — Settings: DataStore "preferences" vs `app.db` "settings snapshot" vs "freeze config"
- AD-6 puts a "settings snapshot" in `app.db` **and** preferences in DataStore. AD-2 "freeze config" does not say where the frozen config goes.
- **Unit A (E8 Settings):** writes base fee / max snoozes / default grace to DataStore ("preferences"). **Unit B (E5 fee engine):** reads base fee from `app.db` "settings snapshot" because AD-6 lists it there and it is backed up. **Unit C (E2):** "freeze config" writes a copy to `app.db` (the snapshot), which is backed up. A restore therefore brings back a frozen config without its session, which AD-6 said it prevents.
- Where the Direct Boot substitution decision and the commitment-lock-effective value are computed is also unowned (see M7).
- **Proposed rule (AD-6a):** Global settings have exactly one store (DataStore, device-protected). Remove "settings snapshot" from `app.db`. A core `SessionConfig` (immutable, `@Serializable`: baseFeeTier, maxSnoozes, snoozeLength, graceSeconds, checkPlanSpec, sound, bootState, isTest) is built only by `EffectiveConfigResolver(alarm, settings, pendingChanges, now, userUnlocked)` in core and stored **inside** `SessionState` in `runtime.db`. No other code reads live settings during a session.

### H3 — Check progress: who validates, where progress lives, and what "last step" means
- AD-9 says "progress events go to the session engine as `CheckStepDone` / `CheckCompleted` only", so the **UI** calls `validate()` and reports results. The AD-2 table says "save progress" is an effect, which suggests a separate store.
- **Unit A (E3 engine):** keeps `stepIndex` in `SessionState`, guards on `not last step` from its own `CheckPlan`. **Unit B (E4 Math UI):** calls `validate` in the ViewModel, keeps `puzzle` and `seed` in `UiState`, and emits `CheckStepDone` per problem. **Unit C (E4 Memory Sequence):** emits one `CheckStepDone` per *round*. On `ProcessRestored`, B regenerates the puzzle with a new seed (the seed was never persisted), which means different problems and lost progress. The fallback guard "after 5 failed attempts" and "once per session" need a failure counter that no event carries (wrong answers are not events). In "All" mode, "last step" spans several check types, but no shared plan type exists.
- **Proposed rule (AD-9a):** `SessionState.check = CheckRun(plan: List<CheckStep(type, difficulty, count, seed)>, stepIndex, itemIndex, failedAttempts, fallbackUsed, substituted)`. Seeds are derived as `hash(sessionId, ringIndex, stepIndex)`. The UI emits only `CheckAnswerSubmitted(answer)` / `CheckSensorResult(result)`. The engine calls `validate`, updates `CheckRun` in the same write-ahead commit, and emits nothing else. Delete the "save progress" effect; progress is state.

### H4 — Purchase intent vs purchase record: lifecycle, key and history for stranded tokens
- The ER diagram says `PURCHASE_INTENT ||--o| PURCHASE_RECORD "becomes"` but never says when or by whom. AD-6 puts intents in `runtime.db`, and "Logged → clear runtime.db session" may delete them.
- **Unit A (E5 billing adapter):** creates a `PurchaseRecord` in `app.db` on every `PurchasesUpdatedListener` callback (a record of a charge). **Unit B (E5 reconciler story):** creates a record only on `Grant`. **Unit C (E7 history):** needs stranded rows ("Not used, refunded automatically") with priceMicros/currency/snoozeIndex. If the token surfaces after `Logged` cleared the intent (UJ4: 20 minutes later), only the token's productId remains; micros and currency are gone, and AD-8 forbids re-deriving money from a display string. For reuse (FR-RNG-10), "the history row changes from Not used to a normal paid snooze": A and C will each update a different row, or re-parent a record from the old sessionId, which breaks `SESSION ||--o{ PURCHASE_RECORD`.
- Also: `profileId = sessionId` cannot tell apart two intents in one session for the same product (retry after an error). Matching must be `(sessionId, productId)`, with the latest intent winning.
- **Proposed rule (AD-7b):** A core `PurchaseLedger` is the single writer of `PurchaseRecord(id, token UNIQUE, sessionId?, productId, snoozeIndex?, price: Money, status: Granted|Stranded|Reused|ConsumedOnly, reusedForSessionId?, purchasedAt)`. It writes only on reconciler verdicts. Intents in `runtime.db` are kept until their token resolves or 4 days pass, independent of session clear. Price for a record comes from the intent matched by `(sessionId, productId)`, else from cached ProductDetails micros (M4), and the record is flagged `priceSource`. Reuse creates a status change `Stranded → Reused(reusedForSessionId)` on the same row. Stats count `Granted` and `Reused` records.

### H5 — `OfferReuse` verdict has no state, event or UI owner
- AD-7 lists `OfferReuse` as a verdict, but the AD-2 table has no state or event for it. The PRD needs a dialog, "Use it / Not now", and a disabled Snooze with "being refunded".
- **Unit A (E5 adapter)** shows its own dialog from the Billing adapter (an adapter changing UI and flow, against AD-2's spirit). **Unit B (wake UI)** waits for a `UiState.reuseOffer` that never arrives. On "Use it", A grants directly by consuming, bypassing the engine, which is a second grant path.
- **Proposed rule (AD-2 rows):** `Ringing|Grace|Loud with paying | ReuseOffered(token, price) | | same, reuseOffer=token | show offer`, then `ReuseAccepted` → the same effects as `PurchaseGranted` with that token (ledger status `Reused`), and `ReuseDeclined` → `paying=null`, and `blockedProducts += productId` until a reconciler run no longer sees the token.

### H6 — "Snooze available (AD-7)" refers to a predicate AD-7 never defines
- The inputs are scattered: online (NFR-3), unlocked (FR-ALM-11), cap $50, max snoozes, test mode, earlier payment being refunded (FR-RNG-7), and ProductDetails cached. None of them is a `SessionEvent` or state field.
- **Unit A (wake ViewModel)** computes availability from `ConnectivityManager` and `UserManager` in `composeApp/androidMain` and disables the button. **Unit B (SessionEngine)** evaluates the guard with its own logic and accepts `SnoozeTapped` when offline. The UI and the engine then disagree: the button is enabled but nothing happens, or the reverse.
- **Proposed rule (AD-7c):** core `SnoozeAvailability.evaluate(state, config, env: WakeEnvironment): Available(productId, displayPrice) | Unavailable(reason)`. `WakeEnvironment` (online, userUnlocked, productCacheFresh, blockedProducts) lives in `SessionState` and is updated only by events `ConnectivityChanged`, `UserUnlocked`, `ProductDetailsLoaded`. The UI renders `evaluate()` output from `UiState` and never probes the platform itself.

### H7 — "CallStarted (audio focus loss)" pauses on *any* focus loss, which creates an escape **[PLATFORM]**
- On Android, `AUDIOFOCUS_LOSS` / `LOSS_TRANSIENT` is delivered when **any** app requests focus: a music app, YouTube, a voice note, navigation prompts. It is not specific to calls. Unit A (audio adapter) maps every focus loss to `CallStarted` exactly as AD-2 says. The user opens a video app from the notification shade and the alarm pauses indefinitely, with the 30-minute timer also paused. This is a free escape, and PRD Q14 already worries about the milder VoIP case.
- **Proposed rule (AD-5a):** `CallStarted` is emitted only when focus is lost **and** `AudioManager.getMode()` ∈ {`MODE_RINGTONE`, `MODE_IN_CALL`, `MODE_IN_COMMUNICATION`}. Checking the mode needs no `READ_PHONE_STATE`. `AudioModeListener` is available on API 31+; on older versions poll on focus change. Any other focus loss is ignored (keep playing on `USAGE_ALARM`) and focus is re-requested. Add a max call-pause cap as a config constant.

### H8 — Who executes effects? Billing needs an Activity; the service owns effects
- AD-2 says "adapters execute Effects"; AD-5 makes `WakeService` the owner of the player; `launchBillingFlow` requires an `Activity`. Nothing says who runs the effect loop.
- **Unit A (E2)** runs the effect loop in `WakeService` and cannot execute `LaunchBilling` (no Activity). **Unit B (E5)** creates a `BillingClient` inside `WakeActivity` and launches from there. When the activity is recreated (rotation, keyguard dismiss for Spike S1), the `PurchasesUpdatedListener` goes with it and the update is lost until the next query. There are now two BillingClients (activity and recovery-on-app-start).
- **Proposed rule (AD-5b):** `WakeService` hosts the single effect executor, which drains effects sequentially in list order. The BillingClient is an app-scoped singleton (Koin `single`). Activity-bound effects go through an `ActivityHandle` port registered by `WakeActivity` in `onResume` and cleared in `onPause`. If no handle is present within 2 s, the executor feeds back `PurchaseFailed(NoActivity)`.

### H9 — `AlarmFired` vs `OverlapAlarmFired`: the receiver cannot know which to send, and next occurrences are unowned
- **Unit A (E2 receiver)** emits `AlarmFired` for every occurrence broadcast. In Ringing that has no row, so it is a "bug" and the overlap is lost. **Unit B (E2 merge story)** expects the receiver to query the session and emit `OverlapAlarmFired`. That puts state logic in an adapter, against AD-2.
- No row schedules the **next occurrence** of the alarm that fired (normal or merged). `rescheduleAll()` runs only on boot or time change. A repeating alarm therefore never re-arms for tomorrow, and nothing disables a fired one-time alarm (so it re-fires after `rescheduleAll`).
- **Proposed rule (AD-4b):** receivers emit only `OccurrenceFired(alarmId, scheduledAt)`. The engine classifies it by state (Idle → start session; active → merge). Every occurrence transition carries `ScheduleNextOccurrence(alarmId)`, executed via core `AlarmService`, which also disables fired one-time alarms. The guard "alarm enabled" is evaluated against the DB at event time.

### H10 — "AlarmScheduler is the only way to schedule, setAlarmClock only" forbids non-alarm scheduled work
- FR-PRG-5 needs a weekly summary notification and PRD §6.3 needs WorkManager consume retries. Under AD-4 read literally, **Unit A (E7 summary)** schedules through `AlarmScheduler` with `setAlarmClock`. That shows an alarm icon and makes Sunday 19:00 the system "next alarm" (lock screen, and FR-ALM-7 if Home reads `getNextAlarmClock`). **Unit B (E5)** uses WorkManager, which violates AD-4's wording.
- **Proposed rule (tighten AD-4):** `AlarmScheduler` schedules **wake alarms only**, meaning occurrences and the session slot. A separate `BackgroundWork` port (WorkManager adapter) handles the weekly summary, consume retry and product-cache refresh. `setAlarmClock` is banned outside `AndroidAlarmScheduler`.

---

## Medium

### M1 — No `Interaction` event, so the 30-minute timer cannot be reset
FR-ALM-9 says any tap on a wake screen restarts the timer, but no event carries "a tap happened". One story resets the timer on `SnoozeTapped`/`ImUpTapped` only. Another has the UI track `lastInteraction` in `UiState` (lost on process death).
**Rule:** add `Interaction(elapsed)` (emitted by every wake-screen tap, throttled to 1/s). The engine updates `timeoutDeadline` in `SessionState`.

### M2 — "log" means three things
"log start", "log outcome", "log merged" and "log Missed" could each be the `Logger` port (debug), `SESSION_EVENT_LOG` (app.db), `SessionRecord` history, or `Telemetry`. Story A writes a history row at "log start" (so a Missed session has 2 rows and a crash leaves an orphan backed-up row). Story B writes at "log outcome" only. Outcome (On time vs Snoozed) is not a state; it is derived, and each story will derive it differently.
**Rule:** Effects `AppendSessionEvent(type)` (runtime.db), `WriteSessionRecord(record)` (app.db, only at Completed/Missed, built by core `OutcomeClassifier`), and `EmitTelemetry(event)` are distinct. `Logger` is never an Effect.

### M3 — Stats: stored vs derived, day boundaries, source of money
One E7 story stores `currentStreak`/`bestStreak` in DataStore and updates it on Logged. Another computes it from history, so the two diverge after a backup restore or Delete-all. "This week" and "day" are undefined (scheduled vs first ring, and which time zone after travel). The PRD says the session log records "amount paid" per session and purchases are separate records, so the money total has two sources, and stranded/reused tokens are double- or zero-counted. "Average minutes first ring to up" computed from wall timestamps goes negative across a clock change.
**Rule (AD-16 "stats are pure projections"):** `core.stats` functions take `List<SessionRecord>` + `List<PurchaseRecord>` + `TimeZone` and store nothing. A session's day = `scheduledAt` local date in the zone stored on the record. Money totals come only from `PurchaseRecord` with status Granted/Reused, grouped by currency. `SessionRecord.durationMs` is computed from monotonic deadlines at write time.

### M4 — Product price cache has no owner
AD-8 says "formattedPrice cached with its micros", but not where. One story puts it in DataStore, another in app.db (backed up, so restored prices could be stale in a new currency region). The intent's `priceMicros` and the Snooze button label can come from different caches.
**Rule:** `ProductCatalogCache` in `runtime.db` (not backed up), written only by the Billing adapter via `ProductDetailsLoaded`. The button label, the intent and fallback history price all read from it.

### M5 — Camera ownership split across `composeApp/androidMain` and `androidApp`
The spine places the camera preview in composeApp androidMain and CameraX/ML Kit/MediaPipe in androidApp. The preview story binds `Preview` to the lifecycle. The scanner story separately binds `ImageAnalysis`. Two `bindToLifecycle` calls from separate code, and possibly `unbindAll()` in one, silently kill the other.
**Rule:** one `CameraSession` adapter in androidApp binds Preview + ImageAnalysis together. composeApp receives only a `SurfaceRequest` provider through a port.

### M6 — Backup restore and exact-alarm permission changes are not reschedule triggers **[PLATFORM]**
After an Auto Backup restore, `app.db` has enabled alarms but no system alarms exist, and none of AD-4's broadcasts fire. On API 31–32, revoking `SCHEDULE_EXACT_ALARM` cancels all alarms; `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` is missing.
**Rule:** add a triggers list: `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, first process start after restore (detected via a DataStore install marker that is not backed up), and every app process start (a cheap idempotent `rescheduleAll`).

### M7 — Commitment lock: storage and application of "pending weakening"
The alarm editor (E2) and Settings (E8) each implement "saved but takes effect after that alarm". One writes a `pendingBaseFee` preference, the other a `pending_*` column on the alarm row. Which event applies it is unowned: `Logged`, occurrence time passing, or a Missed/disabled alarm. Delete/recreate (Q16) also bypasses it.
**Rule:** `PendingChange(target: Global|Alarm(id), field, value, effectiveAfter: OccurrenceRef)` in app.db, written only by core `ChangePolicy`. It is applied by core `AlarmService` when the referenced occurrence's session reaches Logged or its scheduled time +30 min passes. `EffectiveConfigResolver` (H2) is its only reader.

### M8 — Request codes: `alarmId.hash` collisions and PendingIntent identity **[PLATFORM]**
`String.hashCode()` of UUIDs can collide with each other and with the fixed codes. PendingIntent identity is also `(requestCode, Intent.filterEquals)`: two stories building the "same" PendingIntent with a different action or data won't cancel each other, which gives orphaned alarms. That is exactly what AD-4 claims to prevent.
**Rule:** a single `WakePendingIntents` factory in androidApp builds all alarm PendingIntents (fixed action, `data = pps://alarm/{id}`, `FLAG_IMMUTABLE|FLAG_UPDATE_CURRENT`). Occurrence request codes come from an `INTEGER` `requestCode` column assigned by the DB. Codes 1–99 are reserved for the session slot.

### M9 — WakeService lifetime during Snoozed and Completed is unowned
One story keeps the FGS running during a 9-minute snooze (safe against OEM kills but against NFR-8, and the mediaPlayback FGS isn't playing media). Another stops it on "stop sound". On `Completed`, "play motivation" races with `Logged → clear session`, which a third story takes as "stop service", cutting the recording.
**Rule:** `StartForeground` / `StopForeground` are explicit effects in the table. Snoozed stops the service (re-ring restarts it via the session slot). Completed stops only after `MotivationFinished` or 60 s.

### M10 — Write-ahead vs "persist purchase intent" listed as an effect; effect order unspecified
AD-2 persists the transition before effects, yet "persist purchase intent" is an effect, and AD-7 requires it before billing. An executor that runs effects concurrently (reasonable with coroutines) can launch billing before the intent is written.
**Rule:** every DB write is part of the transition commit, never an Effect. Effects run sequentially in list order. Results re-enter only as events through the engine Mutex.

### M11 — Direct Boot: no unlock event, and SDKs that are not direct-boot-safe **[PLATFORM]**
FR-ALM-11 says Snooze becomes available once unlocked, but nothing emits `UserUnlocked`. Firebase (Crashlytics init via `FirebaseInitProvider`), Koin modules that open credential-protected files, and Play Billing are not usable before first unlock. A story wiring Crashlytics in `Application.onCreate` can crash the Direct Boot ring, and AD-12 would route that crash to Crashlytics, which isn't initialised.
**Rule:** `ACTION_USER_UNLOCKED` receiver emits `UserUnlocked`. `Application.onCreate` initialises only direct-boot-safe graph parts when `!UserManager.isUserUnlocked`. Firebase/Billing/media are initialised lazily after unlock. Add a Robolectric test starting the app in locked state.

### M12 — Test alarm has no path through the state machine
FR-ALM-8 needs the full flow without payment and outcome `Test`. `AlarmFired` guards "alarm enabled", and the test alarm isn't a saved alarm. One story fakes a disabled alarm row; another adds a `TestMode` flag in the UI.
**Rule:** `TestAlarmRequested` → `Ringing` with `config.isTest = true`. The availability rule (H6) returns `Unavailable(Test)`. `OutcomeClassifier` returns `Test`. No `ScheduleNextOccurrence`.

---

## Low

- **L1 [PLATFORM] — `showWhenLocked` / `turnScreenOn` are API 27+.** minSdk is 26, so API 26 needs `FLAG_SHOW_WHEN_LOCKED | FLAG_TURN_SCREEN_ON` window flags. **Rule:** the WakeActivity story sets both paths, and add a lint check.
- **L2 [PLATFORM] — Auto Backup rules.** `dataExtractionRules` applies to API 31+. API 26–30 needs `fullBackupContent` too, and both must exclude the `runtime.db` files in the **device** domain (`device_database`), not `database`. **Rule:** a unit test parses both XMLs and asserts equal exclusions.
- **L3 [PLATFORM] — FGS type.** `mediaPlayback` works, but Android documents `systemExempted` for apps holding `USE_EXACT_ALARM`/`SCHEDULE_EXACT_ALARM` that run an FGS to deliver alarms. Its Play declaration may be simpler, and it avoids future media-FGS limits (the Deferred note on targetSdk 37). **Rule:** Spike S2 decides between the two; AD-5 should name "FGS type TBD by S2" instead of fixing `mediaPlayback`.
- **L4 — `:core` "commonMain only" but `:core:allTests` / Kover need declared targets.** **Rule:** state the targets (`jvm()`, `androidLibrary`, `iosArm64/iosSimulatorArm64`) and say which one Kover measures.
- **L5 — GraceElapsed row silent on `paying`.** A literal implementation builds `Loud` without copying `paying`, and the Play sheet result is dropped. **Rule:** a table-wide note that "same"/new state preserves every field not named.
- **L6 — Two price formatters.** AD-8 formats via `MoneyFormatter`(`NumberFormat`) while prices before purchase use Play's `formattedPrice`. The button can show "$1.00" and history "US$1.00". **Rule:** history uses `MoneyFormatter`; wake screens use cached `formattedPrice`; a screenshot test documents both.

---

## Platform claims checked and believed correct

- The Android 15+ ban on starting `mediaPlayback` FGS from `BOOT_COMPLETED` is correct, and it also applies to `LOCKED_BOOT_COMPLETED`. Re-arming via an exact alarm is the right workaround, but FR-SES-1 "resume ringing after reboot" then needs the session slot armed at `now + few s` by `rescheduleAll()` (make this explicit under C1/H1).
- `setAlarmClock` alarms grant the FGS-from-background and full-screen-intent start allowances, so the broadcast → `WakeService` path is valid.
- Unacknowledged purchases are auto-refunded after 3 days, and an owned unconsumed consumable blocks re-purchase with `ITEM_ALREADY_OWNED`. The stranded/reuse design is sound.
- `obfuscatedProfileId` round-trips on `Purchase.getAccountIdentifiers()`, but it can be **null** on some paths (e.g., purchases made outside the app or promo redemption). The reconciler must map null → stranded (the PRD says "or missing"). Add a table row to AD-7's test list. Spike S1 should confirm the pending path.
- Room KMP, DataStore and Auto Backup all work in device-protected storage, provided the DB and DataStore are created from `createDeviceProtectedStorageContext()`. State that in AD-6, otherwise a story will use the default context.
