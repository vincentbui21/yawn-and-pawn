---
title: 'Story 4.3: Cache Play prices for offline display'
type: 'feature'
created: '2026-10-08'
status: 'review'
baseline_revision: 'ee21604'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 4.3; boundaries with 4.7, 4.10, 4.12, 4.13)'
  - '{project-root}/docs/architecture.md (AD-7, AD-8, AD-12, AD-15, AD-17)'
  - '{project-root}/docs/prd.md (§6.2 currency rule, FR-RNG-1, FR-RNG-7, NFR-3, NFR-8)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-2-money-moneyformatter-and-the-feeladder-in-core.md'
warnings:
  - 'Stacked on story/4-2-money-and-feeladder (ee21604). If PR #44 squash-merges first, rebase with `git rebase --onto origin/main ee21604`.'
deferred:
  - 'Story 4.7: snoozeAvailability reads PriceCatalog.observe() and maps "no displayable price" (missing or expired) to UnavailableReason.CatalogueNotLoaded; the Connectivity port can then gate the session-start refresh on "online".'
  - 'Story 4.10: the "consume-retry" job joins BackgroundTaskKind and registers its BackgroundTask.'
  - 'Story 4.12: AndroidProductDetailsSource (queryProductDetailsAsync, unfetchedProductList) replaces UnavailableProductDetailsSource in Koin; it maps Play results to ProductPrice with Money.parse.'
---

<intent-contract>

## Intent

**Problem:** The ringing screen, the confirm sheet and the fee picker must show Play's own localized price (AD-8, PRD §6.2), also offline and before the user opens the app online again. Nothing caches prices yet, nothing runs deferred work (AD-17 has no adapter), and the WorkManager dependency is declared but unused.

**Approach:**
- **Core (`core.billing`):**
  - `PriceEntry(productId, formattedPrice, price: Money, fetchedAt: Instant)` and `PriceCatalogSnapshot(entries)` with `priceFor(productId)` (entry or null).
  - `PriceCatalog` port: `observe(): Flow<PriceCatalogSnapshot>` and `refresh(): Outcome<Unit, DomainError>`.
  - `ProductDetailsSource` port: `fetch(productIds): Outcome<ProductDetailsResult, DomainError>`, where the result has the `ProductPrice`s Play returned and the ids Play reported as unfetched. The Play adapter is 4.12's; 4.3 binds `UnavailableProductDetailsSource` (always a non-transient failure, so the cache stays as it is).
  - `PriceCacheStore` port: `observe()` and an atomic `update(transform)`.
  - `CachedPriceCatalog` (production `PriceCatalog`): fetches all 50 `SnoozeProducts`, merges with the pure `PriceCatalogSnapshot.mergedWith(result, requested, fetchedAt)` inside one store update, keeps the previous snapshot on failure, and runs one refresh at a time (Mutex).
  - Staleness: `PriceCachePolicy` and `PriceEntry.freshnessAt(now)` → Fresh / Stale / Expired; `snapshot.displayablePriceFor(productId, now)` hides Expired entries.
  - `PriceCatalogJson`: the stored form (versioned JSON, entries with micros + currency + Play string + fetchedAt).
  - `PriceRefreshTask` (the background task) and `PriceRefreshScheduler` (app start / first unlock: enqueue "price-refresh-now" and the daily "price-refresh", once per process, only when unlocked) and `PriceRefreshTrigger.onSessionStarted()` (one refresh launched on a scope, never awaited, only when unlocked).
- **Core (`core.work`, AD-17):** `BackgroundWork` port (`enqueue(BackgroundJob)`), `BackgroundJob(uniqueName, task, needsNetwork, repeatEvery)`, `BackgroundTaskKind` (PriceRefresh), `BackgroundTask` and `TaskResult` (Done / RetryLater / Failed).
- **`:data`:** `PriceCacheDataStore`: a separate device-protected Preferences DataStore `datastore/price_cache.preferences_pb` (`createWithPath`, `ReplaceFileCorruptionHandler` → empty). `DataStorePriceCacheStore` keeps the snapshot as one JSON string; an undecodable value is logged and read as empty.
- **`:androidApp`:**
  - `AndroidBackgroundWork` over WorkManager 2.11: unique work by name (one-time: KEEP; periodic: UPDATE), a CONNECTED network constraint when asked, exponential backoff. It refuses (Failure) while the user is locked, so WorkManager is never touched before the first unlock.
  - `BackgroundTaskWorker` (`CoroutineWorker`): runs the registered `BackgroundTask` for the job's kind; RetryLater → `Result.retry()` up to 3 attempts, then failure.
  - On-demand initialisation: `YawnAndPawnApp` is a `Configuration.Provider`; the manifest removes `androidx.work.WorkManagerInitializer` from `InitializationProvider`.
  - App start launches `PriceRefreshScheduler.start()` on `ApplicationScope` (never on the main thread, never awaited); `UnlockSignals.initialiseAfterUnlock()` calls it too (BOOT_COMPLETED / ACTION_USER_UNLOCKED), so a process started locked schedules after the unlock.
  - `WakeService`: after `AlarmFired` commits a new session, `PriceRefreshTrigger.onSessionStarted()`.
  - Backup rules exclude `datastore/price_cache.preferences_pb` in every section; `BackupRulesCoverageTest` creates the file.
  - Dependency allowlist: `androidx.work:work-runtime`, `work-runtime-ktx` and whatever new coordinates WorkManager brings (Room 2 and SQLite of `androidx.room`, reviewed, Apache-2.0), in the same change.

## Boundaries & Constraints

**Always:**
- Alarm safety: nothing on the wake path waits for a price, a refresh or WorkManager. The session-start refresh is launched after `AlarmFired` is committed (the ring already started) and never awaited; the cache read is a Flow the UI collects. A missing or expired price only makes snooze unavailable with the right reason (4.7: `CatalogueNotLoaded`); it never blocks "I'm up".
- Direct Boot: the price cache is device-protected, so it reads before the first unlock. WorkManager (credential-protected database, non-direct-boot components) is never initialised before the first unlock: no default initializer, every enqueue is gated on `isUserUnlocked`, and `AndroidBackgroundWork` re-checks it.
- Prices are shown as Play's `formattedPrice`; micros and currency are kept beside it (AD-8). No currency symbol in code.
- Ports return `Outcome` (AD-12); every port has a fake in `:testing` (AD-14).
- `core.billing` ≥ 90 % line coverage (existing Kover variant).

**Never:**
- No Play Billing dependency (4.12), no `snoozeAvailability` change (4.7), no UI change, no reducer/AD-2/`SessionJson` change, no schema change.
- No price cache in backup or device transfer (a phone restored in another country must not show the old currency).
- No WorkManager call on the main thread at app start (an alarm's cold start must not wait for it).

## Decisions (fast mode: default taken, owner can change)

1. **Staleness:** an entry is Fresh up to 24 h after its fetch, Stale up to 30 days (still shown: Play's own sheet shows the real price at purchase), and Expired after 30 days (treated as "Prices not loaded yet"). A fetch time in the future (clock moved back) counts as Fresh. Constants in `PriceCachePolicy`.
2. **Currency change:** a refresh whose prices are in another currency drops the previous entries of the old currency, even for products Play did not return this time, so one snapshot never mixes a travelled-from currency with the new one.
3. **Partial refresh:** products Play returns replace their entries; unfetched or missing products keep their previous entry (same currency); entries with an empty price string or a price ≤ 0 are ignored; ids outside the 50 snooze products are ignored. A result with no usable price is a success that changes nothing.
4. **Two jobs, one task:** WorkManager unique names cannot be shared by one-time and periodic work, so app start enqueues one-time "price-refresh-now" (KEEP) and periodic "price-refresh" (every 24 h, UPDATE), both needing a network connection and running the same `PriceRefresh` task.
5. **Session-start refresh is direct, not WorkManager:** launched on `ApplicationScope` when a real alarm (not a test alarm) starts a session and the user is unlocked. "Online" is left to the source (an offline fetch fails fast and keeps the cache) until 4.7's `Connectivity` port exists.
6. **Retries:** a transient failure (`ProductDetailsFailed(transient = true)`, storage) is `RetryLater` (exponential backoff, at most 3 attempts); anything else waits for the next run.
7. **New `DomainError`s:** `ProductDetailsFailed(transient, cause)` and `BackgroundWorkFailure(cause)`.
8. **Robolectric default:** `testAppModule` binds `FakeBackgroundWork`, so app tests never start WorkManager; the wiring test uses the real adapter with `WorkManagerTestInitHelper` and its `TestDriver`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| First refresh | empty cache; Play returns 01–50 USD | 50 entries, `fetchedAt` = now |
| Refresh fails | cache with 50 entries; source Failure | cache unchanged; `refresh()` returns the failure; logged |
| Partial | cache 01–50; Play returns 01–10, 11–50 unfetched | 01–10 new, 11–50 kept |
| Unfetched ignored | Play returns 05 and also lists 05 as unfetched | 05 not replaced |
| Currency change | cache 01–50 USD; Play returns 01–10 EUR | 01–10 EUR only; USD entries dropped |
| Bogus entries | empty `formattedPrice`, micros ≤ 0, unknown id | ignored |
| Nothing usable | Play returns nothing | Success, cache unchanged |
| Freshness | age 0, 24 h, 24 h + 1 ms, 30 d, 30 d + 1 ms, −1 h | Fresh, Fresh, Stale, Stale, Expired, Fresh |
| Store round trip | snapshot with USD and JPY entries | equal after write + new DataStore instance |
| Corrupt file | garbage bytes in `price_cache.preferences_pb` | reads empty, no crash; next write succeeds |
| Undecodable value | `{not json` under the key | reads empty, logged once per read; next write succeeds |
| Bad stored currency | one entry with "usd" | that entry dropped, others kept |
| App start, locked | user locked | nothing enqueued; WorkManager untouched |
| App start, unlocked | user unlocked | "price-refresh-now" + "price-refresh" enqueued once per process |
| Unlock later | started locked, then `initialiseAfterUnlock()` | both jobs enqueued then |
| Session start, unlocked | `AlarmFired` committed | one `refresh()` launched; ringing reached without waiting for it |
| Session start, locked | before first unlock | no refresh |
| Worker | job "price-refresh-now", constraints met (TestDriver) | worker runs the PriceRefresh task; cache filled |
| Worker retry | task RetryLater, attempt 1 / 3 | `Result.retry()` / `Result.failure()` |
| Unknown kind | input names no kind | `Result.failure()`, logged |

</intent-contract>

## Tasks

1. `core.billing`: `PriceCatalog.kt` (entry, snapshot, ports, merge, freshness, policy), `CachedPriceCatalog`, `PriceCatalogJson`, `PriceRefresh.kt` (task, scheduler, trigger).
2. `core.work`: `BackgroundWork.kt`.
3. `core.error.DomainError`: `ProductDetailsFailed`, `BackgroundWorkFailure`; `diagnostic()` cases.
4. `:testing`: `FakePriceCatalog`, `FakeProductDetailsSource`, `FakePriceCacheStore`, `FakeBackgroundWork`, `aPriceEntry`/`productPrices` builders.
5. `:data`: `PriceCacheDataStore`, `DataStorePriceCacheStore`; Koin bindings (`PriceCacheStore`).
6. `:androidApp`: WorkManager dependency, `AndroidBackgroundWork`, `BackgroundTaskWorker`, `UnavailableProductDetailsSource`, `Configuration.Provider`, manifest initializer removal, Koin wiring, app-start and unlock scheduling, `WakeService` session-start trigger.
7. Backup XMLs + `BackupRulesTest`/`BackupRulesCoverageTest`; dependency allowlist entries with review note.

## Test plan

- **core** `PriceCatalogTest`: merge table, freshness table, `priceFor`/`displayablePriceFor`, `CachedPriceCatalog` success / failure / partial / single-flight / store failure, JSON round trip and bad input.
- **core** `PriceRefreshTest`: task result mapping, scheduler gating (locked, unlocked, once, enqueue failure logged), trigger launched and not awaited (source suspended), locked → nothing.
- **testing** fakes tests.
- **data** `DataStorePriceCacheStoreTest` (Robolectric): round trip across instances, partial refresh through `CachedPriceCatalog` keeps missing entries, corrupt file → empty, undecodable value → empty, storage failure → `StorageFailure`, file is device-protected.
- **androidApp** `BackgroundWorkWiringTest` (Robolectric + `WorkManagerTestInitHelper`): unique names, network constraint, periodic interval, KEEP; TestDriver runs the worker and fills the cache; retry and unknown-kind results; locked → Failure and no WorkManager call; manifest has no `WorkManagerInitializer`; the app is a `Configuration.Provider`.
- **androidApp** `PriceRefreshAtSessionStartTest`: an alarm rings (Ringing, sound open) while the fake source is still suspended; the cached snapshot is readable at once; the refresh then completes. Locked → no fetch.
- **androidApp** `BackupRulesTest`/`BackupRulesCoverageTest`: price cache excluded in all three sections.
- **Gate:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.
