---
title: 'Story 4.10: Grant ledger, purchase records and consume with retry'
type: 'feature'
created: '2026-10-09'
status: 'done'
baseline_revision: '1ad600f'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/docs/architecture.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-8-purchase-intents-install-id-and-the-runtime-db-intent-table.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-9-purchasereconciler-for-every-recovery-case.md'
review_loop_iteration: 1
followup_review_recommended: false
warnings:
  - 'Story 4.3 (BackgroundWork + WorkManager) is not merged: the port file is copied verbatim from its branch plus the ConsumeRetry kind, and an in-process stand-in runs the job until 4.3 is bound.'
deferred:
  - 'Story 4.3 rebase: keep 4.3''s core/work/BackgroundWork.kt and add `ConsumeRetry` to BackgroundTaskKind; in workModule register `BackgroundTaskKind.ConsumeRetry to ConsumeRetryTask(get())` in BackgroundTasks; delete InProcessBackgroundWork (+ test) and its two bindings in appModule; in TestYawnAndPawnApp keep one test BackgroundWork binding (4.3''s FakeBackgroundWork; RecordingBackgroundWork can go); bind PurchaseLedger''s PriceSnapshotLookup to the price cache (`{ catalog snapshot priceFor(it)?.price }`) instead of PriceSnapshotLookup.None'
  - 'Story 4.11: build ReconcileInput from PurchaseLedger.lookup(token) (a settled marker reads Consumed, an unconsumed reuse reads Granted); apply ConsumeOnly with PurchaseLedger.consumeOnly(snapshot), LeaveForAutoRefund and OfferReuse(recordStrandedFirst) with PurchaseLedger.recordStranded(snapshot, alarmId); fill PurchaseGranted.orderId from the snapshot; validate ReuseAccepted against the offered product (the reducer writes a ledger row for it); do NOT call markReused before the ReuseAccepted commit (it is refused without the grant row; settling the row makes the record reused anyway)'
  - 'Story 4.11: a ConsumeOnly without a ledger row that fails is retried only by the next recovery query (the job replays the ledger only)'
  - 'Story 4.7/4.11: the "An earlier {price} payment is being refunded" label takes PurchaseLedger.refundingPrice(productId): what the stranded payment actually cost, from its record; null (no amount in the label) when that record was not priced by an intent'
  - 'Story 4.12: map BillingClient.consumeAsync to ConsumeResult: OK = Consumed, ITEM_NOT_OWNED = NotOwned (never Consumed: PurchaseLedger decides by age whether it was our own earlier consume or Google''s refund), anything else = Failed; never log the token'
  - 'Story 4.3 rebase: InProcessBackgroundWork also ran CONSUME_RETRY_PERIODIC (6 h); 4.3''s WorkManager adapter runs it as unique periodic work (UPDATE)'
  - 'Story 4.16: read PurchaseRecordRepository.all() (newest first); a stranded record with null alarmId/snoozeNumber shows date and "Not used, refunded automatically by Google"; show an amount only when priceSource is Intent (PriceSource.isAmountPaid), never the Snapshot/Tier/Unknown estimate'
---

<intent-contract>

## Intent

**Problem:** a paid snooze must be recorded and its payment consumed exactly once, even if the app dies between the session commit, the history write and Play's consume. Nothing may be consumed that did not grant a snooze, and every charge must appear in purchase history (FR-RNG-4, FR-RNG-6, FR-PRG-4, AD-7, NFR-14).

**Approach:**
- **Grant ledger in `runtime.db` v3** (`grant_ledger`: `token` PK, `session_id`, `alarm_id`, `product_id`, `snooze_number`, `order_id` nullable, `status` granted/consumed, `created_at`). The reducer's paid snooze (`PurchaseGranted` and `ReuseAccepted`) emits the engine-owned `SessionEffect.PersistGrant(GrantLedgerEntry)`; `SessionEngine` turns it into `RuntimeWrite.PutGrant`, committed in the same transaction as the Snoozed state (as 4.8 does for intents). A token already in the ledger fails the whole commit, so a duplicate can never grant twice. Only then does the runner get `Consume(token)`; `WakeRuntime` launches `PurchaseLedger.settle(token)` on the app scope and returns at once.
- **Purchase records in `app.db` v10** (`purchase_record`: `token_hash` PK = SHA-256 hex, `order_id`, `product_id`, `session_id`, `alarm_id`, `snooze_number` (nullable for stranded), `price_micros`, `currency`, `purchased_at`, `status` granted/consumed/stranded/reused, `updated_at`; index on `purchased_at`; no foreign key). `app.db` is already backed up, so history is user data restored with the alarms; the raw token is never in it.
- **`PurchaseLedger` (core.billing)**: the only writer of records and the only caller of `Billing.consume`. Settling one ledger row: upsert the record (absent → granted; stranded → reused by this session/alarm/snooze; others unchanged) → consume → ledger row consumed → record consumed (reused stays reused) → delete the row. Any failure leaves the row, enqueues the unique `consume-retry` job (`needsNetwork`, exponential backoff from 30 s, at most 3 runs) and is replayed on app start and on resume (`ReplayGrantLedger`, only after the first unlock, AD-15). One Mutex serializes all triggers.
- **Statuses map 1:1** onto 4.9's `LedgerStatus` and `RecordStatus` (Absent = no row); `PurchaseLedger.lookup(token)` gives the reconciler both.
- **`consumeOnly(snapshot)`** (reconciler `ConsumeOnly`): with a ledger row, the same settle path; without one, only a *granted* record is consumed (then marked consumed, order id filled in); anything else is `Refused` and nothing is consumed.
- **`recordStranded(snapshot, alarmId)`** (reconciler `LeaveForAutoRefund`, `OfferReuse(recordStrandedFirst)`): a stranded record, never consumed, no ledger row; idempotent and never downgrades an existing record.
- **`markReused(tokenHash, sessionId, alarmId, snoozeNumber)`**: only from stranded; the same reuse again succeeds; anything else is `RecordNotReusable` (or `NotFound`).
- **Pricing:** the newest intent of the session and product (4.8 deferral), else the price snapshot (`PriceSnapshotLookup` port; 4.3's cache), else the product's USD tier (`snooze_usd_NN` → NN USD), else zero (logged). The snooze number comes from the intent, else the ledger row.
- **Billing port:** `consume(token): ConsumeResult` (`Consumed` / `Failed(cause)`), defaulting to Failed; `UnavailableBilling` fails until 4.12.

## Boundaries & Constraints

**Always:**
- **Alarm safety:** the snooze starts at the commit; settling runs on the app scope after it and never touches the session, so a slow or failing consume never delays the alarm, the next ring or "I'm up". Restore replays nothing into the session: the engine restores Snoozed from `runtime.db` and the ledger is settled separately.
- **Money safety:** nothing is consumed without a granted ledger row or a granted record; nothing is consumed while its record cannot be written (the charge is in history first); a stranded token is never consumed. Tokens are never logged (tests), and `app.db` holds only token hashes.
- Scan test (`PurchaseLedgerWriterScanTest`, `:data` host tests): only `PurchaseLedger` calls `PurchaseRecordRepository.put`, `GrantLedgerStore.markConsumed/delete` and any `consume`; only the Room adapters use the DAO writes.
- Migration tests for both databases, exported schemas `RuntimeDatabase/3.json` and `AppDatabase/10.json`, and backup tests (records restored with `app.db`, no ledger comes with a restore).

**Never:**
- No change to `WakeService`, `WakeActivity` or the reconciler; no Play Billing code (4.12) and no orchestration (4.11).
- No raw token in `app.db`, logs or `toString`.

## Crash points (table-tested)

Between the two databases and Play, each followed by a new process replaying the ledger (`PurchaseLedgerCrashTest` in core, `RoomPurchaseRecordRepositoryTest` over real Room databases):

| Process dies | Replay | Consumes in total |
|---|---|---|
| after the Snoozed + ledger commit | record → consume → settle | 1 |
| after the record upsert | consume → settle | 1 |
| after Play consumed, before the ledger says so | consume again (Play: already consumed = success) | 2 |
| after the ledger row is consumed | record consumed, row deleted | 1 |
| after the record is consumed | row settled | 1 |

Each ends with one record (consumed, or reused and consumed for a reused stranded payment), the ledger row a settled marker and `snoozesGranted` incremented once (engine test). A redelivered grant of the same token fails its commit, also after the payment was settled.

## Review fixes (default taken, owner can change)

- **Settled marker instead of delete (review 2/14):** a settled `grant_ledger` row keeps `settled_at` for 30 days (`purgeSettled` on app start), so a token redelivered after its payment was settled fails its commit and can never grant a second snooze.
- **Price source (review 10):** `purchase_record.price_source` (`intent`, `snapshot`, `tier`, `unknown`). Only `intent` is the amount paid; `refundingPrice` and (4.16) history show no amount otherwise.
- **`ConsumeResult.NotOwned` (review 11):** our own earlier consume while the payment (record `purchased_at`) is younger than 60 h; older, Google refunded it: the record becomes stranded, nothing shows it as paid, the row is settled and it is logged.
- **Reused vs consumed (review 13):** `purchase_record.consumed_at`. A reused record not consumed yet reads as granted to the reconciler and `consumeOnly` consumes it (a restored `app.db` without its ledger row). `markReused` needs the reuse's pending grant row, so a crash before the `ReuseAccepted` commit leaves the record stranded.
- **Retries (review 12):** every unlock signal (`UnlockSignals`: the first unlock and each wake screen resume) replays the ledger; a failed settle also enqueues `consume-retry-periodic` (6 h, network), which keeps retrying after the one-time job gives up; a row unsettled after 48 h is logged once per process.

## Decisions (default taken, owner can change)

1. **Extra ledger columns** (`alarm_id`, `snooze_number`, `order_id`): a replay after the session ended must still fill the record; the epic's column list had no alarm.
2. **`PurchaseGranted.orderId`** (optional) carries Play's order id into the ledger and the record (4.17's email needs it); 4.11 fills it.
3. **The ledger row also says consumed** before the record does, so a replay after a consume never consumes again unless the ledger write itself failed.
4. **Record first, consume second:** if `app.db` cannot be written, the payment waits rather than risk a charge missing from history.
5. **A ledger row for a stranded record makes it reused** (only `ReuseAccepted` grants a stranded token, 4.9 row C), so the reuse is crash-safe without a separate step; `markReused` stays for 4.11.
6. **Price fallback** to the product's USD tier when there is neither an intent nor a cached price; zero (logged) only for a product outside the catalogue.
7. **Retry triggers:** the grant itself, app start (after the first unlock), `MainActivity` start, and the `consume-retry` job. Until 4.3 merges, the job runs in-process (`InProcessBackgroundWork`, same name, KEEP, 30 s doubling backoff, 3 runs, no network constraint).
8. **Record ids:** no foreign key to `alarm` or `session_history`: a charge outlives an alarm the user deletes.

</intent-contract>

## Code Map

- **core:** `billing/PurchaseRecords.kt` (`hash()`, `GrantLedgerEntry`, `PurchaseRecord`, `GrantLedgerStore`, `PurchaseRecordRepository`, `PriceSnapshotLookup`), `billing/PurchaseLedger.kt` (`PurchaseLedger`, `SettleResult`, `PurchaseLookup`, `ConsumeRetryTask`, `ReplayGrantLedger`), `work/BackgroundWork.kt` (4.3's port + `ConsumeRetry`); `session/SessionPorts.kt` (`RuntimeWrite.PutGrant`, `ConsumeResult`, `Billing.consume`), `SessionEffect.kt` (`PersistGrant`), `SessionEvent.kt` (`PurchaseGranted.orderId`), `PurchaseRules.kt`, `RingRules.kt`, `SessionEngine.kt`; `error/DomainError.kt` (`RecordNotReusable`).
- **data:** `session/GrantLedgerEntity.kt`, `GrantLedgerDao.kt`, `RoomGrantLedgerStore.kt`, `ActiveSessionDao.kt` (inserts grants in the commit), `RoomActiveSessionStore.kt`; `billing/PurchaseRecordEntity.kt`, `PurchaseRecordDao.kt`, `RoomPurchaseRecordRepository.kt`; `db/RuntimeDatabase.kt` (v3), `RuntimeDatabaseMigrations.kt`, `AppDatabase.kt` (v10), `AppDatabaseMigrations.kt`; `DataModule.kt`; schemas.
- **androidApp:** `InProcessBackgroundWork.kt`, `YawnAndPawnApp.kt` (bindings, replay on start), `MainActivity.kt` (replay on start), `wake/WakeRuntime.kt` + `WakeModule.kt` (`Consume` → settle on the app scope), `UnavailableBilling.kt`.
- **testing:** `PurchaseLedgerFakes.kt` (`FakeGrantLedgerStore`, `FakePurchaseRecordRepository`, `RecordingBackgroundWork`, `aGrant`, `aPurchaseRecord`, `aPurchaseSnapshot`), `FakeBilling` consume, `FakeActiveSessionStore` grants.
- **docs:** `docs/architecture.md` AD-7 and the R18 row.

## Rebase notes

- **4.8 (PR #48):** squash-merged; rebase with `git rebase --onto origin/main 1ad600f`.
- **4.3 (PR #47):** add/add conflict on `core/work/BackgroundWork.kt`: take 4.3's file and add `ConsumeRetry`. Then follow the 4.3 deferral above. `DomainError`: 4.3 appends two errors at the end; `RecordNotReusable` sits after `NotFound`, so both apply. Backup XMLs are untouched here.
- **4.7:** `SessionData.paid` is display-only; the record's price comes from the intent, not from `paid`. The refund label uses `refundingPrice`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: implemented in fast mode (one agent, unattended Epic 4 run), no review pass yet. Branch `story/4-10-grant-ledger` on `origin/story/4-8-purchase-intents` (`1ad600f`), rebased onto `origin/main` (`8778afe`, 4.8 squash-merged; same tree, no conflicts).

**Verification:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` after the rebase: BUILD SUCCESSFUL (13 min 18 s). Re-run after the review fixes: see the review note.

**Residual risks:**
- Nothing consumes in production until 4.12 (`UnavailableBilling.consume` fails), and nothing grants until 4.11; a ledger row can only exist after both.
- Until 4.3 is bound, the retry job lives only in the process (no network constraint); app start and resume replay the ledger anyway.
- A `ConsumeOnly` without a ledger row (lost `runtime.db`) is retried only by later recovery queries (4.11).

## Review (2 reviewers, fast mode)

Two reviewers read `a51fb4c`: one for verification gaps, one for edge cases (the alarm path was untouched). The money-safety review found 2 HIGH defects. Every item was fixed in `fix(4.10): review fixes`, with its test; the schemas were amended in place (app.db v10, runtime.db v3; nothing shipped) and re-exported.

1. **(HIGH) Mutex untested:** a settle and a cold-start `settleAll` run together: one consume, two record writes.
2. **Never grants twice only in the fake / 14. the guard ended at delete:** settled markers (above); engine test: grant, settle, redeliver → commit fails, one snooze; Room test: the marker refuses the token until purged.
3. **Room crash table:** exact consumes per crash point (2 after Play consumed, 1 otherwise).
4. **Pricing order pinned:** the intent beats a cached price (price and snooze number); intents unreadable → the snapshot.
5. **Scan too narrow:** every write now has its own name (`putRecord`, `markSettled`, `setSettled`, `insertGrant`, `PutGrant(`…), so chained DAO calls, concrete adapters, `commit(grants = …)` and `PutGrant` outside the engine are reported (rogue sources for each).
6. **Locked app start:** no record, row unchanged, no job.
7. **`MainActivity` replay:** tested.
8. **Real wiring:** the app's `InProcessBackgroundWork` runs the job; over the real ledger with Play offline: 4 consumes in the first hour ("gave up after 3 runs"), then the periodic job goes on and settles once Play is back.
9. **Backup:** a grant committed before the backup; the restore brings the record and no ledger row.
10. **(HIGH) Estimated prices shown as paid:** `price_source` (above).
11. **(HIGH) `ITEM_NOT_OWNED` as consumed:** `NotOwned` with the age rule (above).
12. **Retries stopped after about 90 s:** unlock-signal replay, the periodic job and the 48 h alert (above).
13. **Reused ambiguity:** `consumed_at` and the guarded `markReused` (above).

**Verification after the fixes:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`: BUILD SUCCESSFUL (8 min 36 s). One earlier run failed only in `WakeServiceTest` ("no service started"), the known cross-test flake; it passes alone and on the rerun.
