---
title: 'Story 4.9: PurchaseReconciler for every recovery case'
type: 'feature'
created: '2026-10-08'
status: 'in-review'
baseline_revision: '75b1877'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/docs/prd.md'
  - '{project-root}/docs/architecture.md'
  - '{project-root}/docs/spikes/S1.md'
warnings: []
deferred:
  - 'Story 4.10: map its grant_ledger and purchase_record statuses onto LedgerStatus and RecordStatus (or replace them) and run ConsumeOnly through the consume path'
  - 'Story 4.11: build ReconcileInput for every purchase, apply each decision, dispatch PurchasePending from Ignore(Pending) for the active session'
  - 'Story 4.12: map Play Purchase to PurchaseSnapshot in androidApp/.../android/billing/PlayPurchaseMapping.kt (the only path the scan exempts)'
---

<intent-contract>

## Intent

**Problem:** Google Play reports purchases through updates, queries and `ITEM_ALREADY_OWNED`, sometimes late, twice, or after a crash. Each one has to be checked against the same rules: exactly one snooze per charge, and nothing kept for a charge that gave nothing (FR-RNG-4, FR-RNG-10, FR-PRG-4, AD-7, PRD §6.3).

**Approach:**
- **Pure core:** `core.billing.PurchaseReconciler.decide(ReconcileInput): PurchaseDecision`. It is a synchronous, total function: it does not suspend, does not throw and has no state. It never touches the session, so reconciling can never block "I'm up" or stopping the alarm. The callers (4.11) apply the decision as an event or a ledger write.
- **Input:**
  - `PurchaseSnapshot`: the token, the product id, `PlayPurchaseState` (Purchased or Pending), the profile id (nullable), the order id (nullable) and the purchase time.
  - `ActiveSessionSummary?`: the session id, an `ActiveSessionKind` (Ringing, Grace, Loud, Snoozed, Completed or Missed), the expected next product (null when max snoozes or the price cap is reached) and test mode. `ActiveSessionSummary.of(state, expectedNext)` builds it from the stored `SessionState`.
  - `LedgerStatus` (Absent, Granted or Consumed) and `RecordStatus` (Absent, Granted, Consumed, Stranded or Reused).
  - `ReconcileContext`: Update, Recovery, `PreLaunch(productId)` or `AlreadyOwned(productId)`.
- **Output:** exactly one of `Grant`, `ConsumeOnly(retryLaunch)`, `LeaveForAutoRefund`, `OfferReuse` or `Ignore(Pending | AlreadyHandled)`. Each decision carries the existing `PurchaseVerdict`, so 4.11 can fill `PurchaseGranted` and `ReuseOffered` without mapping.
- **Rules (first match wins):**
  1. Pending → `Ignore(Pending)`.
  2. Already granted, not yet consumed (a granted ledger row, or a granted record with no ledger row) → `ConsumeOnly`. It retries the launch only for `AlreadyOwned` of this product.
  3. Consumed (in the ledger or the record) or reused → `Ignore(AlreadyHandled)`.
  4. Unseen, for the active session, which is Ringing, Grace or Loud, not test mode, and this is its expected next product → `Grant`.
  5. A launch of this product, and the session can buy it (unseen or stranded token) → `OfferReuse`.
  6. Anything else → `LeaveForAutoRefund`.

## Recovery cases → decision

Rows 1–14 are the epic's numbering, and each has its own test in `PurchaseReconcilerTest`. Rows A–H are cases the table leaves open. For each of them a default was taken; the owner can change it.

| # | Purchase | Session | Ledger / record | Context | Decision |
|---|---|---|---|---|---|
| 1 | PURCHASED, profile = active, product = expected next | Ringing, Grace or Loud | absent / absent | Update, Recovery | `Grant` |
| 2 | PURCHASED, profile = active, product ≠ expected next | ringing | absent / absent | Update, Recovery | `LeaveForAutoRefund` |
| 3 | PURCHASED, profile = an ended session | Completed or Missed (same id), or another session now rings | absent / absent | any | `LeaveForAutoRefund` |
| 4 | PURCHASED, profile = another session id | ringing | absent / absent | Update, Recovery | `LeaveForAutoRefund` |
| 5 | PURCHASED, profile = active | Snoozed | absent / absent | any | `LeaveForAutoRefund` |
| 6 | PURCHASED, no profile id (promo code, OQ-1) | ringing | absent / absent | Update, Recovery | `LeaveForAutoRefund` |
| 7 | PURCHASED, any profile | any or none | granted / any | Update, Recovery | `ConsumeOnly(retryLaunch = false)` |
| 8 | PENDING | any | any | any | `Ignore(Pending)` |
| 9 | duplicate of a granted token, profile = active | ringing | granted / granted | Update | `ConsumeOnly(false)`, never a second `Grant` |
| 10 | duplicate after consume | any | absent / consumed or reused | any | `Ignore(AlreadyHandled)` |
| 11 | owned token for P, any profile | ringing, not test, expected next = P | absent / absent or stranded | `PreLaunch(P)`, `AlreadyOwned(P)` | `OfferReuse` |
| 12 | owned token for P | any | granted / any | `AlreadyOwned(P)` | `ConsumeOnly(retryLaunch = true)` (for `PreLaunch(P)`: `false`, the launch follows anyway) |
| 13 | PURCHASED, any | test mode, any kind | absent / absent or stranded | any | `LeaveForAutoRefund`, never a grant |
| 14 | PURCHASED | none | absent / absent | any | `LeaveForAutoRefund` |
| A | PURCHASED, profile = active, product = expected next (lost callback found before the next launch) | ringing | absent / absent | `PreLaunch(P)`, `AlreadyOwned(P)` | `Grant` (not a reuse offer: the earlier Pay tap was the consent) |
| B | PURCHASED, any profile | any | absent / granted (ledger row lost) | Update, Recovery / `AlreadyOwned(P)` | `ConsumeOnly(false)` / `ConsumeOnly(true)`. It granted once, so consuming keeps money for a snooze given. A granted ledger row beats any record status. |
| C | PURCHASED, profile = active, product = expected next | ringing | absent / stranded | Update, Recovery | `LeaveForAutoRefund`. A stranded token is used only through reuse, with consent. |
| D | PURCHASED | ringing, expected next = none (max snoozes or price cap) | absent / absent | any | `LeaveForAutoRefund`, no reuse offer |
| E | token for another product than the launch | any | any | `PreLaunch(Q)`, `AlreadyOwned(Q)` | the same as Recovery (rows 1–14) |
| F | PURCHASED | any | consumed / any | any | `Ignore(AlreadyHandled)` |
| G | stranded token for P | Snoozed, Completed or Missed, or expected next ≠ P | absent / any but granted | `PreLaunch(P)` | `LeaveForAutoRefund` |
| H | PENDING for P | ringing | any | `AlreadyOwned(P)` | `Ignore(Pending)`. 4.11 shows "payment pending". |

**Sequences** (`PurchaseReconcilerSequenceTest`, run against a small model of the 4.10 stores):
- Pending, then PURCHASED in Grace or Loud: one `Grant`.
- Pending, then PURCHASED after the session ended: stranded and never consumed.
- A lost callback found by Recovery: `Grant`.
- The same token across a restart: `Grant`, then `ConsumeOnly`, then `Ignore`. One grant in total.
- A duplicate update after a grant: `ConsumeOnly`.
- PRD UJ4: stranded, then `OfferReuse` the next morning.
- `ITEM_ALREADY_OWNED` for an unconsumed grant: `ConsumeOnly(true)`, then `Ignore` after the consume.

**Invariants** (`PurchaseReconcilerInvariantsTest`, every combination of the inputs, about 40,000):
- `Grant` only when all of row 1 holds.
- `ConsumeOnly` only for a token that granted a snooze. `retryLaunch` only for `AlreadyOwned` of its own product.
- `OfferReuse` only for the launched product, and only when the session can buy it.
- `LeaveForAutoRefund` only for PURCHASED tokens that never granted anything.
- Test mode never grants or reuses, and Pending is always ignored.
- Every decision is reachable and deterministic.

## Boundaries & Constraints

**Always:**
- The rules live only in `PurchaseReconciler`. `PurchaseStateReaderScanTest` (`:data` host test) scans the shipped sources of `:core`, `:data`, `:androidApp` and `:composeApp`, with comments blanked out. It looks for any read of Play's `purchaseState`, `getPurchaseState()` or `PurchaseState.*`, and of the snapshot's `.purchaseState` or `PlayPurchaseState.*`. Only the reconciler and the future adapter mapping (`androidApp/src/main/kotlin/com/yawnandpawn/app/android/billing/PlayPurchaseMapping.kt`) are exempt.
- Tokens stay redacted: a `PurchaseSnapshot` or a `ReconcileInput` prints no token (tested).
- Kover: a `core.billing` variant with a 90 % line rule, which runs inside `koverVerify`.

**Never:**
- No change to the reducer, the AD-2 table, the ports or any schema. Only new files in `core.billing` change, plus the Kover variant.
- No consume decision without a granted ledger row or record: consuming keeps the money.
- No suspend, I/O or lock in the reconciler.

## Decisions (default taken, owner can change)

1. **Lost callback before a new launch (row A):** `Grant`, not a reuse offer, when the owned token is for this session, this product and has never been seen.
2. **Granted record without a ledger row (row B):** `ConsumeOnly`. A granted ledger row wins over any record status. That covers a crash between "mark consumed" and "delete ledger row" (a repeat consume is a success) and a reuse still being consumed.
3. **Stranded stays stranded (row C):** Update and Recovery never grant a stranded token, even when it now matches the ringing session. Only the reuse offer with consent can use it.
4. **Other products during a launch (row E):** decided as Recovery would decide them.
5. **Pending for the active session:** the reconciler says only `Ignore(Pending)`. 4.11 compares the profile id with the active session to dispatch `PurchasePending`, so it never reads the purchase state itself.
6. **`declinedReuseProduct`:** not an input. Availability already disables Snooze for that product, so no `PreLaunch` reaches the reconciler.
7. **Scan location:** the scan lives in `:data`'s host tests next to the other scan tests (`:core` has no JVM-only test source set). It covers `:data` and `:composeApp` as well as the `:core` and `:androidApp` the epic names.

</intent-contract>

## Code Map

- **core:** `billing/PurchaseReconciler.kt` holds:
  - `PlayPurchaseState`, `PurchaseSnapshot`, `ActiveSessionKind`, `ActiveSessionSummary` (with `of`);
  - `LedgerStatus`, `RecordStatus`, `ReconcileContext` and `ReconcileInput`;
  - `IgnoreReason` and `PurchaseDecision` (with `verdict`);
  - `PurchaseReconciler.decide`.
- **build:** the `billing` Kover variant (`core/build.gradle.kts`) and `koverVerifyBilling` in the root `koverVerify`.
- **Tests:**
  - core: `PurchaseReconcilerTest` (rows 1–14 and A–H, verdicts), `PurchaseReconcilerInvariantsTest` (sweep, `ActiveSessionSummary.of`, redaction), `PurchaseReconcilerSequenceTest` and `ReconcilerFixtures`;
  - data: `PurchaseStateReaderScanTest`.

## Rebase notes

- **4.2 (Money, FeeLadder `productFor`, FeeRules; not merged):**
  - 4.9 uses no `Money` and takes the expected next product as a plain `String?`. 4.11 fills it from 4.2's `FeeLadder.productFor` (a `PriceCapReached` result becomes null).
  - The Kover `billing` variant and the `koverVerifyBilling` dependency are byte-for-byte 4.2's lines, so whichever merges second applies them cleanly. If git still conflicts, keep one copy.
  - 4.2 adds `FeeLadder.kt`, `Money.kt` and `MoneyFormatter.kt` in `core.billing`. 4.9 adds only `PurchaseReconciler.kt`, so no file overlaps.
- **4.8 (intents):** no overlap. The reconciler does not read intents. The price on the record comes from the intent in 4.10.
- **4.10 (ledger and records):** its `grant_ledger.status` and `purchase_record.status` map 1:1 onto `LedgerStatus` and `RecordStatus` (Absent = no row). It can keep these enums as the core types, or map to them in `PurchaseLedger`. `ConsumeOnly` must go through its consume path.
- **4.11 (coordinator):** it builds `ReconcileInput` per purchase and uses `decision.verdict` for `PurchaseGranted`/`ReuseOffered`. It must not branch on `purchaseState` (the scan fails).
- **4.12 (adapter):** the Play → `PurchaseSnapshot` mapping must live at the exempt path above, or the scan's `ADAPTER_MAPPING_PATH` must move with it. It drops `UNSPECIFIED_STATE`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: implemented in fast mode (one agent, unattended Epic 4 run), waiting for review. Branch `story/4-9-purchase-reconciler` on `origin/main` (`75b1877`) plus the Epic 4 context commits.

**Verification:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`: BUILD SUCCESSFUL (17 min 40 s). Kover `core.billing` line coverage is 100 %. 38 core tests and 3 scan tests were added.

**Residual risks:**
- **Promo codes (OQ-1):** S1 did not test whether a promo-code purchase carries a profile id. Without one it is always stranded, which is safe (refunded) but gives no snooze.
- **Stale queries:** Play can still list a token just after it was consumed. With the record consumed, that gives `Ignore`. With only the ledger row granted, it gives a repeat consume, which is harmless.
- **The scan is textual:** an aliased import of Play's `PurchaseState` would slip past it. The reviewers of 4.11 and 4.12 should check that.
