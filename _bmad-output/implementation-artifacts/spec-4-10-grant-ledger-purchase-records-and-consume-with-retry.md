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
warnings:
  - 'Story 4.3 (BackgroundWork + WorkManager) is not merged: the port file is copied verbatim from its branch plus the ConsumeRetry kind, and an in-process stand-in runs the job until 4.3 is bound.'
deferred:
  - 'Story 4.3 rebase: keep 4.3''s core/work/BackgroundWork.kt and add `ConsumeRetry` to BackgroundTaskKind; in workModule register `BackgroundTaskKind.ConsumeRetry to ConsumeRetryTask(get())` in BackgroundTasks; delete InProcessBackgroundWork (+ test) and its two bindings in appModule; in TestYawnAndPawnApp keep one test BackgroundWork binding (4.3''s FakeBackgroundWork; RecordingBackgroundWork can go); bind PurchaseLedger''s PriceSnapshotLookup to the price cache (`{ catalog snapshot priceFor(it)?.price }`) instead of PriceSnapshotLookup.None'
  - 'Story 4.11: build ReconcileInput from PurchaseLedger.lookup(token); apply ConsumeOnly with PurchaseLedger.consumeOnly(snapshot), LeaveForAutoRefund and OfferReuse(recordStrandedFirst) with PurchaseLedger.recordStranded(snapshot, alarmId); fill PurchaseGranted.orderId from the snapshot; validate ReuseAccepted against the offered product (the reducer now writes a ledger row for it); call markReused only if the reuse flow needs it before the commit (settling a ledger row for a stranded record already makes it reused); run ReplayGrantLedger on WakeActivity open too'
  - 'Story 4.11: a ConsumeOnly without a ledger row that fails is retried only by the next recovery query (the job replays the ledger only)'
  - 'Story 4.7/4.11: the "An earlier {price} payment is being refunded" label takes PurchaseLedger.refundingPrice(productId): what the stranded payment actually cost, from its record'
  - 'Story 4.12: map BillingClient.consumeAsync to ConsumeResult; a repeat consume of a consumed token (ITEM_NOT_OWNED after our own consume) is Consumed; never log the token'
  - 'Story 4.16: read PurchaseRecordRepository.all() (newest first); a stranded record with null alarmId/snoozeNumber shows date and "Not used, refunded automatically by Google"'
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
| after the record is consumed | row deleted | 1 |

Each ends with one record (consumed, or reused for a reused stranded payment), no ledger row and `snoozesGranted` incremented once (engine test). Duplicate grants of the same token fail their commit.

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

Status: implemented in fast mode (one agent, unattended Epic 4 run). Branch `story/4-10-grant-ledger` on `origin/story/4-8-purchase-intents` (`1ad600f`), rebased onto `origin/main` after 4.8 merged.

**Residual risks:**
- Nothing consumes in production until 4.12 (`UnavailableBilling.consume` fails), and nothing grants until 4.11; a ledger row can only exist after both.
- Until 4.3 is bound, the retry job lives only in the process (no network constraint); app start and resume replay the ledger anyway.
- A `ConsumeOnly` without a ledger row (lost `runtime.db`) is retried only by later recovery queries (4.11).
