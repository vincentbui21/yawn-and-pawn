---
title: 'Story 4.11: Billing orchestration: launch, recovery, ITEM_ALREADY_OWNED and stranded reuse'
type: 'feature'
created: '2026-10-09'
status: 'done'
baseline_revision: '80143b5'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/docs/architecture.md'
  - '{project-root}/docs/spikes/S1.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-8-purchase-intents-install-id-and-the-runtime-db-intent-table.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-9-purchasereconciler-for-every-recovery-case.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-10-grant-ledger-purchase-records-and-consume-with-retry.md'
warnings:
  - 'Story 4.3 (price cache, PR #47) and Story 4.7 (snooze availability) are not merged; see the rebase notes.'
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
8. **Lost launch callback with nothing found:** no timeout fails it. `paying` stays until a result, a restore, the ring's end or the timeout, because a late PURCHASED still grants (row 1 needs no `paying`) and a "No charge." message could then be false.
9. **Stranded set source:** only full queries (recovery, pre-launch and already-owned) refresh it; single updates do not.
10. **Lost unlock on resume:** an unlocked keyguard always continues to Play (a late callback is then ignored); a locked one ends the payment only when no unlock request waits in this process, so a resume while the PIN prompt may still be up never says "Phone still locked".
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

## Rebase notes

- **4.10 (PR #49):** it was squash-merged while this story was built. The branch was rebased with `git rebase --onto origin/main a8375d2`; the tree was the same, and there were no conflicts.
- **4.3 (price cache, PR #47):**
  - `DomainError`: 4.3 appends two errors at the end, and `BillingUnavailable` sits after `RecordNotReusable`, so both apply. Keep both `diagnostic()` arms.
  - `YawnAndPawnApp.onCreate`: keep 4.3's WorkManager start next to the coordinator's `start()` / `onAppResumed()`.
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
- A launch whose result never arrives (no update and no listing on recovery) keeps `paying` until a restore, the ring's end or the timeout (decision 8). Snooze cannot be bought again during that ring; "I'm up" is unaffected.
- The resume rule for a lost unlock trusts that the activity is not resumed while the PIN prompt is up. 4.12/4.18 should confirm this on the device.
