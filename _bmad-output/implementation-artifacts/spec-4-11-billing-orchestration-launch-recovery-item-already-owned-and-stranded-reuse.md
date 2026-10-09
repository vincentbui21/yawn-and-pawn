---
title: 'Story 4.11: Billing orchestration: launch, recovery, ITEM_ALREADY_OWNED and stranded reuse'
type: 'feature'
created: '2026-10-09'
status: 'done'
baseline_revision: '8a38a11'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/docs/architecture.md'
  - '{project-root}/docs/spikes/S1.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-8-purchase-intents-install-id-and-the-runtime-db-intent-table.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-9-purchasereconciler-for-every-recovery-case.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-10-grant-ledger-purchase-records-and-consume-with-retry.md'
warnings:
  - 'Story 4.7 (snooze availability) is not merged; see the rebase notes.'
deferred:
  - 'Story 4.12: implement the extended Billing port in AndroidBilling: launch returns Launched once launchBillingFlow returned OK (the result then arrives on purchaseUpdates), ItemAlreadyOwned / Cancelled / Failed(kind) from the response-code table; onPurchasesUpdated maps OK to PurchaseUpdate.Purchases (pending ones included), the rest to Cancelled / ItemAlreadyOwned / Failed(kind); purchaseUpdates must not drop an update emitted before the coordinator collects (buffer or replay-free SharedFlow started at app start); queryPurchases returns every INAPP purchase mapped in PlayPurchaseMapping.kt; bind AndroidDeviceUnlocker as the single UnlockPort (engine and coordinator share it)'
  - 'Story 4.7: feed PurchaseCoordinator.strandedProducts into the snoozeAvailability env: EarlierPaymentRefunding only while declinedReuseProduct is the offered product AND that product is in the set, so the label goes away once a recovery no longer finds the token (no event needed); the amount comes from PurchaseLedger.refundingPrice(productId)'
  - 'Story 4.13: the already-paid sheet reads PurchaseCoordinator.reuseOffer and calls acceptReuse() / declineReuse() (the token never reaches the UI); call PurchaseCoordinator.onWakeScreenResumed() stays wired in WakeActivity.onResume'
  - 'Story 4.14: map PurchaseOutcome.Offline / Failed / Cancelled / UnlockFailed and ShowPaymentPending to the wake messages (exhaustive when over PurchaseFailureKind and PurchaseOutcome)'
---

<intent-contract>

## Intent

**Problem:** a Play payment can come back late, twice, never, or as `ITEM_ALREADY_OWNED`, and the app can die in the middle. The `LaunchBilling`, `RequestKeyguardDismiss` and `Consume` effects, Play's purchase updates and recovery queries have to be turned into session events and ledger writes so that a charge never costs an extra charge or a lost snooze, and an earlier unused payment is offered instead of charging again (FR-RNG-3, FR-RNG-4, FR-RNG-10, FR-PRG-4, NFR-11, AD-2, AD-7, AD-12, PRD §6.3).

**Approach:**
- **Billing port** (`core.session.Billing`, no longer a `fun interface`):
  - `launch(intent, installId): Outcome<LaunchResult, DomainError>`: `Launched` (the sheet is open; its result arrives on `purchaseUpdates`), `ItemAlreadyOwned`, `Cancelled` or `Failed(kind)`;
  - `queryPurchases(): Outcome<List<PurchaseSnapshot>, DomainError>`;
  - `consume(token)` (Story 4.10);
  - `purchaseUpdates: Flow<PurchaseUpdate>`: `Purchases(list)`, `Cancelled`, `ItemAlreadyOwned` or `Failed(kind)`;
  - `init()`.
  - `queryPurchases` and `purchaseUpdates` have defaults (`BillingUnavailable`, empty flow), so a double needs only `launch`. `UnavailableBilling` implements the whole port.
- **`PurchaseFailed(kind)`** with `PurchaseFailureKind` Offline, UnlockFailed and Error; `PurchaseOutcome.Offline` is added. **`ReuseOffered(productId, token, verdict)`** carries the stranded token.
- **`PurchaseCoordinator` (core.billing)** owns the orchestration. It runs every step on the app scope, outside the engine's Mutex, and dispatches results as events:
  - **Launch** (`LaunchBilling(intentId)`):
    1. Read the intent; a missing intent or install id gives `PurchaseFailed(Error)`.
    2. Stop when the session no longer pays this intent (ended, restored, a result came in).
    3. Settle the grant ledger (`PurchaseLedger.settleAll`), so a granted but unconsumed token for P is consumed first.
    4. Query the owned purchases and reconcile each one with `PreLaunch(P)`.
    5. `Grant(abortLaunch)` dispatches `PurchaseGranted` and never launches. `OfferReuse` dispatches `ReuseOffered(token)` and never launches; with `recordStrandedFirst` the token is recorded stranded first. A pending P for this session dispatches `PurchasePending` and never launches.
    6. Otherwise launch with the install id (`obfuscatedAccountId`) and the intent's session (`obfuscatedProfileId`). A failed query does not block the launch: an owned token comes back as `ITEM_ALREADY_OWNED`.
  - **`ITEM_ALREADY_OWNED`** (as a launch result or an update): query again and reconcile with `AlreadyOwned(P)`.
    - Grant → `PurchaseGranted`.
    - `ConsumeOnly(retryLaunch)` → consume through `PurchaseLedger.consumeOnly`, then retry the launch once. A second `ITEM_ALREADY_OWNED`, or a consume that did not settle, becomes `PurchaseFailed(Error)`.
    - Stranded → `ReuseOffered`.
    - Pending → `PurchasePending`.
    - Nothing usable → `PurchaseFailed(Error)`.
  - **Updates** (collected from app start): every purchase is reconciled with `Update`.
    - Grant → `PurchaseGranted` with the order id.
    - `ConsumeOnly` → `PurchaseLedger.consumeOnly`.
    - `LeaveForAutoRefund` → `recordStranded` (the alarm id when the profile is the active session).
    - `Ignore(Pending)` for the active, ringing session → `PurchasePending`, once per session (not while `paymentPending`) unless it answers the launch in flight.
    - `Ignore(OtherInstall | AlreadyHandled)` writes nothing.
    - `Cancelled` / `Failed(kind)` reach the session only while it pays the launch in flight: `PurchaseCancelled` / `PurchaseFailed(kind)`.
  - **Recovery** (`recover()`) runs on app start, `MainActivity` resume and every `WakeActivity` resume:
    - never before the first unlock (AD-15);
    - it waits until the engine has restored the stored session (4.9 deferral), so a lost callback for the ringing session is granted, never stranded;
    - one recovery at a time;
    - every result is reconciled with `Recovery`.
    - **Stranded set:** the products of this install's unspent PURCHASED tokens from the latest full query (`LeaveForAutoRefund` or `OfferReuse`), published as `strandedProducts` for 4.7's availability env.
  - **Reuse:** the coordinator keeps the offered token; `reuseOffer` exposes only its product id. `acceptReuse()` dispatches `ReuseAccepted(productId, token)` for the same session. The reducer writes the ledger row in the Snoozed commit, and the `Consume` settle turns the stranded record into reused (4.10; `markReused` is never called before the commit). `declineReuse()` dispatches `ReuseDeclined`.
  - **Unlock** (`RequestKeyguardDismiss`): `UnlockPort.requestUnlock()` outside the Mutex, then `UnlockSucceeded` / `UnlockFailed` (a throwing port counts as failed). When the wake screen resumes with `unlocking` set and no request in flight in this process (the callback was lost), an unlocked phone dispatches `UnlockSucceeded` and a locked one dispatches `UnlockFailed`.
- **Reducer:** `PurchaseFailed(kind)` → `ShowPurchaseOutcome(Failed | Offline | UnlockFailed)`. `ReuseAccepted` is accepted only for the product Snooze offers now: the same availability guard as Pay (4.7/4.11 deferral).
- **Wake runtime:** `LaunchBilling` and `RequestKeyguardDismiss` go to the coordinator. `ShowPurchaseOutcome`, `ShowPaymentPending`, `ShowReuseSheet` and `HideReuseSheet` call `reassertRingVolume()` once (Story 2.8 hand-off) and are still logged until 4.13/4.14 render them.

## Boundaries & Constraints

**Always:**
- **Alarm safety:** the runner only launches coordinator work on the app scope and returns at once. Nothing waits on `launch()`, `queryPurchases()`, `consume()` or the unlock inside an effect. "I'm up", the checks and the sound never wait for billing: a slow or lost result leaves `paying` set (cleared by any result, a restore, the ring's end or the 30-minute timeout) and the free path stays enabled.
- **Money safety:**
  - A snooze is granted only by a reconciler `Grant`: an unseen PURCHASED token for the active, ringing session's next product, from this install. A pending purchase never grants.
  - Consume runs only through `PurchaseLedger` for a granted token (`consumeOnly` refuses anything else).
  - A stranded token is never consumed except after a `ReuseAccepted` commit.
  - Every `abortLaunch` and reuse offer cancels the launch. A duplicate delivery gives one grant: the engine refuses a second ledger row for a token, and the coordinator serialises reconciliation with one Mutex.
- The coordinator never reads the purchase state itself (4.9 scan), and tokens are never logged.

**Never:**
- No Play Billing code (4.12), no UI (4.13/4.14), and no change to the reconciler, the ledger or any schema.
- No billing launch on restore, and no launch for a session that no longer pays the intent.

## Decisions (default taken, owner can change)

1. **Launch result vs updates:** `launch` returns `Launched` as soon as the sheet opens. The purchase itself arrives on `purchaseUpdates`, as PBL delivers it to the listener; only the response codes PBL returns from `launchBillingFlow` come back from `launch`.
2. **Step 1 settles the whole grant ledger** (`settleAll`), not only P's rows: settling granted payments is always right, and the ledger has no per-product read.
3. **A failed pre-launch query still launches:** Play answers `ITEM_ALREADY_OWNED` for an owned token, and that path reconciles again.
4. **Pending during a launch:** a pending purchase of P for this session stops the launch with `PurchasePending` (Play would refuse the launch as owned).
5. **Repeated pending messages:** recovery and updates dispatch `PurchasePending` for a session already marked `paymentPending` only when it answers the launch in flight. Every wake screen resume runs a recovery, so the message is not shown again on each resume.
6. **`ReuseAccepted` guard:** the product must be the one Snooze offers now (availability Available for it), as for Pay. Accepting while billing is in flight is still allowed, so the existing payment-safety table holds: the offer cleared `paying` anyway.
7. **Second `ITEM_ALREADY_OWNED`:** after the one retry it becomes `PurchaseFailed(Error)` without another query; the next recovery consumes the token.
8. **Lost launch callback with nothing found:** no timer fails it, and it is never called "No charge." while a PURCHASED may still come. A PURCHASED answer that grants nothing now (a lookup, install id or commit failure) keeps `paying`, and a later update or recovery grants it (row 1 needs no `paying`). Review 15 (default taken, owner can change): on a wake screen resume, a sheet open for 2 minutes or more that a successful recovery query found nothing for is cancelled ("Payment cancelled. No charge."), so Snooze can be bought again. A late PURCHASED still grants.
9. **Stranded set source:** only full queries (recovery, pre-launch and already-owned) refresh it; single updates do not.
10. **Lost unlock on resume** (revised by review 16): an unlocked keyguard always continues to Play, and a late callback is then dropped. A locked keyguard is read again after a 1.5 s settle, because its state lags the resume on ColorOS. It ends the payment only when no request *for this intent* has been waiting for less than 30 s, so a resume while the PIN prompt may still be up never says "Phone still locked".
11. **Its own app-wide scope:** the coordinator runs on a second `ApplicationScope` instance, not the shared one, because the update collector never ends and an app-start recovery may wait for a restore that a broadcast-only process never runs; the app start's finite jobs (and the tests that wait for them) are unaffected.

</intent-contract>

## Code Map

- **core:**
  - `billing/BillingResults.kt`: `LaunchResult`, `PurchaseUpdate`, `expectedNextProduct`.
  - `billing/PurchaseCoordinator.kt`.
  - `session/SessionPorts.kt`: the `Billing` port.
  - `session/SessionEvent.kt`: `PurchaseFailureKind`, `PurchaseFailed(kind)`, `ReuseOffered.token`.
  - `session/SessionEffect.kt`: `PurchaseOutcome.Offline`.
  - `session/PurchaseRules.kt` and `RingRules.kt`: failure kinds, the reuse guard.
  - `error/DomainError.kt`: `BillingUnavailable`.
- **androidApp:**
  - `UnavailableBilling.kt`.
  - `wake/WakeRuntime.kt` and `wake/WakeModule.kt`: effects to the coordinator, volume re-assert.
  - `YawnAndPawnApp.kt`: the coordinator, the `UnlockPort` single, start and recovery on app start.
  - `MainActivity.kt` (recovery on resume) and `wake/WakeActivity.kt` (`onWakeScreenResumed`).
- **testing:** `FakeBilling` (programmable launches, owned purchases, queries, updates, consume).
- **docs:** `docs/architecture.md` AD-2 rows R16 and R19, and the AD-7 orchestration paragraph.
- **Tests:**
  - core: `PurchaseCoordinatorTest` (decision → action table, launch and already-owned paths, unlock), `PurchaseCoordinatorSequenceTest` (the 4.9 sequences end to end through engine, ledger and fake Play), reducer rows;
  - androidApp: `WakeRuntimeBillingTest` (routing and volume), `SessionAdaptersTest`, `SessionConflictScenariosTest` (the launch is counted on `FakeBilling`).

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

## For 4.13 (integration, review 16)

The coordinator owns these. When 4.13 is rebased onto 4.11:
- **Resume while unlocking:** the coordinator owns it (`onWakeScreenResumed()` → `resolveLostUnlock()`, which already calls `WakeActivity.onResume`). It holds the 1.5 s ColorOS settle and the re-check (`UNLOCK_SETTLE`). 4.13 drops its own `onResume` `UnlockSucceeded`/`UnlockFailed` dispatch.
- **Unlock results are tagged with their intent.** `RequestKeyguardDismiss(intentId)` reaches `requestUnlock(intentId)`, and a result is dispatched only while the session still waits for that intent's unlock, so a late result never applies to a newer Pay. The request tracking is per intent, so a lost callback no longer blocks the resume rule for a newer Pay, and a request waiting longer than `UNLOCK_GRACE` (30 s) counts as lost.
- **One `UnlockPort` binding:** `single<UnlockPort>` in `YawnAndPawnApp` (`UnlockPort.Unlocked` until 4.12's `AndroidDeviceUnlocker`), shared by the engine and the coordinator. 4.13 removes its own binding.
- **Reuse:** the "already paid" sheet reads `PurchaseCoordinator.reuseOffer` (product id only) and calls `acceptReuse()` / `declineReuse()`. 4.13 never builds `ReuseAccepted` itself (the token stays in the coordinator).

## Review (2 reviewers, fast mode)

Two reviewers read `3ad7b985`: one for verification gaps, one for edge cases (the edge-case reviewer found no HIGH). Every item was fixed in `fix(4.11): review fixes` with its test (`PurchaseCoordinatorReviewTest` unless named):

1. **(HIGH) The update collector ended on its first exception.** Each update is guarded and logged. A failing stream is collected again after a backoff (1 s doubling to 60 s); a stream that ends normally is not.
2. **(HIGH) The second `ITEM_ALREADY_OWNED` guard could not fail.** The test now asserts 2 queries.
3. **(HIGH) "At once" never interleaved.** `FakePlay.queryHold` was added. An update for the token a held recovery is deciding grants once. A payment found by a held pre-launch query while its update arrives never opens Play.
4. **(HIGH, real bug) "No charge." after a PURCHASED.** A PURCHASED answer that grants nothing keeps the payment in flight (Decision 8), and recovery grants it later.
5. **`ITEM_ALREADY_OWNED` from the launch for an owned PURCHASED P:** Snoozed.
6. **The other `ITEM_ALREADY_OWNED` branches:** stranded → reuse offer, pending → pending, a consume that does not settle → Failed with 1 launch, and an update for the retry's sheet → Failed with no third query.
7. **(Real bug) Two launches for one intent.** Each intent launches once, and a sheet is never opened while another paying intent's sheet is open. A launch held in `settleAll` never opens after a newer Pay.
8. **A second pending message:** a pending answer to the open sheet says pending again while `paymentPending` is set.
9. **No volume re-assert during the grace mute** (`WakeRuntimeBillingTest`).
10. **`RequestKeyguardDismiss` wiring:** checked in the app's Koin graph (`BillingWiringTest`). The shared `UnlockPort` is asked, and the unlock opens Play.
11. **(Money) Updates before the restore.** `onUpdate` waits for the engine's restore before reconciling, so a payment for the ringing session is never recorded stranded.
12. **`ITEM_ALREADY_OWNED` handled twice** (the launch result and an update). Attempts are tracked by identity. A cancel, failure or `ITEM_ALREADY_OWNED` update answers only an attempt whose launch returned `Launched`. An update already delivered when `launch` returns is received (`yield`) before the attempt counts as open. The echo is ignored: one retry, no Failed.
13. **= 4:** an install id failure or a commit failure also keeps the payment in flight. A launch that throws before the sheet opens clears the attempt and says "No charge.".
14. **= 1.**
15. **Stale sheet** (default taken, owner can change): see Decision 8.
16. **Resume while unlocking:** owned here; see "For 4.13".

## Rebase notes

- **4.10 (PR #49):** it was squash-merged while this story was built. The branch was rebased with `git rebase --onto origin/main a8375d2`; the tree was the same, and there were no conflicts.
- **4.3 (price cache, PR #47):** it merged before the review fixes. The branch was rebased with `git rebase origin/main` onto `8a38a11`, keeping both sides of every conflict:
  - `DomainError` and `diagnostic()`: merged without a conflict.
  - `YawnAndPawnApp.onCreate`: the price refresh start and the coordinator's `start()` / `onAppResumed()` both stay.
  - `WakeApp` (test): both `productDetails` and `unlock` stay.
- **4.7 (snooze availability):**
  - `SessionData.paid` and the optional price on `PurchaseGranted` / `ReuseAccepted`: the coordinator builds `PurchaseGranted(productId, token, verdict, orderId)`, so add the price argument there if 4.7 needs it.
  - The refund label: see the 4.7 deferral above.
  - Pay while just unavailable: the reducer ignores it (the R14 guard) and the coordinator never sees it. Closing the sheet with the reason is 4.7/4.13's.
- **4.12:** the Billing port shape is fixed here (see the deferral). `UnavailableBilling` is the binding to replace, and `UnlockPort` is now a Koin single shared by the engine and the coordinator.

## Auto Run Result

Status: implemented in fast mode (one agent, unattended Epic 4 run), with no review pass yet. Branch `story/4-11-billing-orchestration`, rebased onto `origin/main` (`80143b5`, with 4.10 merged).

**Verification:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`: BUILD SUCCESSFUL (7 min 8 s). Two earlier runs failed only on known environment flakes:
- `spotlessKotlin` "Could not read path ... mergeReleaseResources" (the 4.8 race);
- `WakeServiceTest` "no service started" (the 4.10 cross-test flake), which passes alone.

**Residual risks:**
- Nothing launches in production until 4.7 offers Snooze and 4.12 binds Play: `UnavailableBilling` fails every launch and query, so recovery only logs "query purchases" failures until then.
- A launch whose result never arrives keeps `paying` until the next wake screen resume 2 minutes or more after the sheet opened (review 15), a restore, the ring's end or the timeout. "I'm up" is unaffected throughout.
- The echo rule (review 12) relies on Play's update arriving before `launch` returns plus one `yield`. An echo that arrives later, while the retry's sheet is open, would fail that sheet with "No charge.", which is still true because the retry's own result has not come. 4.12 should deliver each sheet result once.
- The resume rule for a lost unlock relies on the 1.5 s settle and the 30 s grace; 4.12/4.18 should confirm both on the device.
