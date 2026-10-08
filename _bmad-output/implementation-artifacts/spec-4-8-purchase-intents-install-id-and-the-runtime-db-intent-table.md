---
title: 'Story 4.8: Purchase intents, install id and the runtime.db intent table'
type: 'feature'
created: '2026-10-08'
status: 'in-progress'
baseline_revision: '7535d6c'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/docs/architecture.md'
  - '{project-root}/docs/spikes/S1.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-9-purchasereconciler-for-every-recovery-case.md'
warnings: []
deferred:
  - 'Story 4.11: execute LaunchBilling(intentId) by reading the intent with PurchaseIntentStore.get, launch outside the engine Mutex with obfuscatedAccountId = InstallIdProvider.installId(); a missing intent is PurchaseFailed(Error)'
  - 'Story 4.11/4.13: execute RequestKeyguardDismiss through UnlockPort.requestUnlock() outside the Mutex and dispatch result.event(); on WakeActivity resume with the keyguard still locked and `unlocking` set, dispatch UnlockFailed (lost callback)'
  - 'Story 4.11/4.13: fill PayConfirmed.quote with the LIVE ProductDetails price at Pay (PriceSource.Live), falling back to the price the sheet showed (PriceSource.Cached)'
  - 'Story 4.12: bind AndroidDeviceUnlocker as UnlockPort in Koin and pass it to SessionEngine (prod uses UnlockPort.Unlocked until then; snooze is never Available before 4.7 anyway)'
  - 'Story 4.14: map PurchaseOutcome.UnlockFailed to WakeMessage.UnlockFailed ("Phone still locked. No charge.")'
  - 'Story 4.10: runtime.db v2 -> v3 adds grant_ledger as a new RuntimeWrite variant in the same commit'
  - 'Epic 5 (delete my data): regenerate the install id (InstallIdDataStore holds only that key, so clearing the file is enough)'
---

<intent-contract>

## Intent

**Problem:** a payment attempt must be saved, with its session and price, before Google Play opens, so a crash in the middle of paying can never lose or double a snooze (FR-RNG-3, FR-SES-1, AD-2, AD-7). Play also needs a stable, non-personal account id per install (`obfuscatedAccountId`), and Spike S1 showed the Play sheet never appears over a keyguard, so "Pay" while locked first asks for the PIN (option B, owner decision 1: the Unlocking rows go into the state machine now).

**Approach:**
- **Intent in `core.billing`:** `PurchaseIntent(intentId, sessionId, productId, snoozeNumber, price: Money, formattedPrice, priceSource, createdAt)` moves from `core.session` to `core.billing`. The price comes from a `PriceQuote(productId, price, formattedPrice, source)` carried by `PayConfirmed`; `source` is `Live` (ProductDetails read at Pay, the 4.3 review's handoff) or `Cached` (the price the sheet showed). `createdAt` is the step's wall time. The reducer builds the whole intent, so it stays pure.
- **Write-ahead in one transaction:** `ActiveSessionStore.commit(state, writes)` takes a `List<RuntimeWrite>`; 4.8 has `RuntimeWrite.PutPurchaseIntent(intent)` (4.10 adds the ledger). The reducer emits `SessionEffect.PersistPurchaseIntent(intent)`; like the history effects, the engine owns it: it becomes a write in the step's commit and never reaches the `EffectRunner`. Only after the commit does `LaunchBilling` (or `RequestKeyguardDismiss`) run. A failed commit leaves no intent row, no launch and the previous state.
- **`PurchaseIntentStore` (read side + purge):** `get(intentId)`, `forSession(sessionId)`, `forProduct(sessionId, productId)`, `purgeOlderThan(instant)`. No `save`: the commit is the only writer. `PurgeOldPurchaseIntents` runs on app start and deletes intents older than 7 days.
- **runtime.db v1 → v2:** `purchase_intent` (`intent_id` PK, `session_id`, `product_id`, `snooze_number`, `price_micros`, `currency`, `formatted_price`, `price_source`, `created_at`), exported schema `2.json`, `MIGRATION_1_2` for the runtime database, migration test keeping an `active_session` row. runtime.db stays excluded from backup.
- **Install id:** `core.billing.InstallIdProvider.installId(): Outcome<String, DomainError>`. `:data`'s `DataStoreInstallIdProvider` keeps a random UUID v4 (from `IdGenerator`, never from a device or account identifier) in its own device-protected DataStore file `datastore/install_id.preferences_pb`, created once (atomic `edit`), stable across restarts, never logged. The file is excluded from cloud backup and device transfer (both XML files), so another phone never gets this install's id.
- **S1 Unlocking rows (AD-2):** `SessionData.unlocking` (default false; set only with `paying`). `reduce` gets the environment input `keyguardLocked`, which the engine reads from the new `UnlockPort` only for `PayConfirmed`. New events `UnlockSucceeded` / `UnlockFailed` (`SessionEvent.UnlockEvent`, adapter results). New effect `RequestKeyguardDismiss(intentId)` and outcome `PurchaseOutcome.UnlockFailed`. `UnlockPort` (`isKeyguardLocked()`, `suspend requestUnlock(): UnlockResult`) with `UnlockPort.Unlocked` and `FakeUnlockPort` in `:testing`.

## AD-2 rows (changed and new)

| Id | From | Event | Guard | To | One-shot effects |
|---|---|---|---|---|---|
| R14 | Ringing, Grace, Loud | PayConfirmed | Available, not paying, quote for the offered product, keyguard not locked | same, paying = intentId | persist `PurchaseIntent` (in the commit); launch billing |
| R32 | Ringing, Grace, Loud | PayConfirmed | Available, not paying, quote for the offered product, keyguard locked | same, paying = intentId, unlocking | persist `PurchaseIntent` (in the commit); request keyguard dismiss |
| R33 | Ringing, Grace, Loud (unlocking) | UnlockSucceeded | | same, unlocking = false | launch billing |
| R34 | Ringing, Grace, Loud (unlocking) | UnlockFailed | cancelled or error | same, paying = null, unlocking = false | show "Phone still locked. No charge." (`PurchaseOutcome.UnlockFailed`); sound continues |

Everything that clears `paying` (purchase results, a grant, a restore, the next ring) also clears `unlocking`. PayConfirmed with a failing guard is ignored and logged (it still counts as a user event); a wrong PIN sends nothing, so the state waits and the 30-minute timeout stays the backstop.

## Boundaries & Constraints

**Always:**
- **Alarm safety:** nothing on the pay path stops or delays the alarm. While `paying` or `unlocking` is set, the sound and timers keep running (entry effects unchanged), "I'm up", answers and the fallback work exactly as before (table-tested), and an Unlock or purchase result after the session completed, was missed or snoozed is ignored.
- The intent and the state commit in one transaction; the runner never sees `PersistPurchaseIntent`.
- The install id never appears in a log event (Logger fake test), and its file is named by `BackupRulesCoverageTest`.
- Table-driven tests: the AD-2 table (34 rows, coverage test), the every-state-by-every-event matrix, the store queries, the migration.

**Never:**
- No change to `WakeService`; `WakeRuntime` only logs the new effect like `LaunchBilling` today.
- No billing launch on restore; a restore clears `paying` and `unlocking`, and keeps the intent row.
- No install id in the backed-up settings DataStore.

## Decisions (default taken, owner can change)

1. **Live price field:** the intent records the quote's price with a `price_source` column (`live` / `cached`). The epic lists no source field; it is added so 4.10's purchase record can tell a ProductDetails price from a display price.
2. **The intent is written at Pay, also when locked:** a cancelled PIN leaves an unused intent row, purged after 7 days. This keeps one write path (the price the user confirmed) and nothing needs the offer at `UnlockSucceeded`.
3. **`LaunchBilling` carries only the intent id and session id:** the runner reads the committed intent. The offer is no longer in the effect.
4. **A quote for another product is ignored** (logged), so a price can never be recorded against the wrong product.
5. **An intent id is written once:** a second write of the same id fails the commit (`ABORT`), so a buggy caller is visible instead of silently overwriting a price.
6. **Install id in its own DataStore file** (not the backed-up settings file the epic text names), as the epic context and NFR-14 require.
7. **`UnlockSucceeded` does not re-check availability:** nothing in the session can change while the PIN prompt is up; a connectivity change surfaces as Play's own Offline result ("No charge.").
8. **The engine reads the keyguard only for `PayConfirmed`**, so no other step makes a platform call.

</intent-contract>

## Code Map

- **core:** `billing/PurchaseIntent.kt` (intent, quote, source, store port, retention, `PurgeOldPurchaseIntents`), `billing/InstallId.kt` (port); `session/SessionPorts.kt` (`RuntimeWrite`, `commit(state, writes)`, `UnlockPort`, `UnlockResult`), `SessionEvent.kt`, `SessionEffect.kt`, `SessionState.kt` (`unlocking`), `PurchaseRules.kt`, `RingRules.kt`, `SnoozedRules.kt`, `SessionReducer.kt` (`keyguardLocked`), `SessionEngine.kt` (writes, `UnlockPort`).
- **data:** `db/RuntimeDatabase.kt` (v2), `db/RuntimeDatabaseMigrations.kt`, `session/PurchaseIntentEntity.kt`, `PurchaseIntentDao.kt`, `RoomPurchaseIntentStore.kt`, `RoomActiveSessionStore.kt` (one transaction), `settings/InstallIdDataStore.kt`, `DataStoreInstallIdProvider.kt`, `DataModule.kt`, `schemas/.../RuntimeDatabase/2.json`.
- **androidApp:** backup XMLs, `YawnAndPawnApp.kt` (purge on start), `UnavailableBilling.kt` (import).
- **testing:** `FakeActiveSessionStore` (writes), `FakePurchaseIntentStore` (queries, purge), `FakeUnlockPort`, `FakeInstallIdProvider`, `aPriceQuote`/`aPayConfirmed`.
- **docs:** `docs/architecture.md` AD-2 rows and AD-7 intent fields; `docs/prd.md` FR-RNG-3 wording.

## Rebase notes

- **4.3 (price cache, not merged):** both add a `device_file` exclude in the three backup sections and a file to `BackupRulesCoverageTest`'s expected list: keep both lines. Both touch `YawnAndPawnApp.onCreate` (4.3 adds WorkManager start; 4.8 adds the intent purge) and `DataModule`: keep both. 4.3's `WakeService` session-start refresh does not meet 4.8 (no WakeService change here).
- **4.4 (PR #46):** app.db v9 only; no runtime.db or reducer overlap. `ReadFireSettings` in WakeService is untouched here.
- **4.7:** when `SnoozeOffer`/`Available` gains the price, the sheet's displayed price becomes the `Cached` fallback of `PriceQuote`; the reducer guard stays "quote for the offered product". `SessionState.paid` sits next to `unlocking`.
- **4.10:** add `RuntimeWrite.PutGrant` (or similar) and `runtime.db` v3; `RoomActiveSessionStore.commit` already applies writes inside the transaction.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.
