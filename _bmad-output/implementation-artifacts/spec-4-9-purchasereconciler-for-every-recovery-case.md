---
title: 'Story 4.9: PurchaseReconciler for every recovery case'
type: 'feature'
created: '2026-10-08'
status: 'done'
baseline_revision: '75b1877'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/docs/prd.md'
  - '{project-root}/docs/architecture.md'
  - '{project-root}/docs/spikes/S1.md'
warnings: []
deferred:
  - 'Story 4.10: map its grant_ledger and purchase_record statuses onto LedgerStatus and RecordStatus (or replace them) and run ConsumeOnly through the consume path'
  - 'Story 4.10: markReused starts only from stranded; consume completion keeps a reused record reused (history still shows the reuse)'
  - 'Story 4.11: build ReconcileInput for every purchase (with installId from 4.8), apply each decision, dispatch PurchasePending from Ignore(Pending) when the profile id is the active session'
  - 'Story 4.11: Grant in PreLaunch/AlreadyOwned (abortLaunch = true) cancels the launch and clears paying; never open the Play sheet after it'
  - 'Story 4.11: OfferReuse(recordStrandedFirst = true) upserts the stranded record before ReuseOffered, so ReuseAccepted can markReused'
  - 'Story 4.11: Ignore(OtherInstall) writes nothing (no record, no consume, no reuse)'
  - 'Story 4.11: Recovery waits for the active session to be restored at cold start; otherwise a lost callback for the ringing session is recorded stranded (row C makes it permanent; the user is still refunded)'
  - 'Story 4.12: map Play Purchase to PurchaseSnapshot (with obfuscatedAccountId) in androidApp/.../android/billing/PlayPurchaseMapping.kt (the only path the scan exempts)'
---

<intent-contract>

## Intent

**Problem:** Google Play reports purchases through updates, queries and `ITEM_ALREADY_OWNED`, sometimes late, twice, or after a crash. Each one has to be checked against the same rules: exactly one snooze per charge, and nothing kept for a charge that gave nothing (FR-RNG-4, FR-RNG-10, FR-PRG-4, AD-7, PRD §6.3).

**Approach:**
- **Pure core:** `core.billing.PurchaseReconciler.decide(ReconcileInput): PurchaseDecision`. It is a synchronous, total function: it does not suspend, does not throw and has no state. It never touches the session, so reconciling can never block "I'm up" or stopping the alarm. The callers (4.11) apply the decision as an event or a ledger write.
- **Input:**
  - `PurchaseSnapshot` (not a data class, so it cannot be destructured): the token, the product id, `PlayPurchaseState` (Purchased or Pending), the profile id (nullable), the account id (`obfuscatedAccountId`, nullable), the order id (nullable) and the purchase time.
  - `ActiveSessionSummary?`: the session id, an `ActiveSessionKind` (Ringing, Grace, Loud, Snoozed, Completed or Missed), the expected next product (null when max snoozes or the price cap is reached) and test mode. `ActiveSessionSummary.of(state, expectedNext)` builds it from the stored `SessionState`.
  - `LedgerStatus` (Absent, Granted or Consumed) and `RecordStatus` (Absent, Granted, Consumed, Stranded or Reused).
  - `ReconcileContext`: Update, Recovery, `PreLaunch(productId)` or `AlreadyOwned(productId)`.
  - `installId`: this install's id (4.8).
- **Output:** exactly one of these, each carrying the existing `PurchaseVerdict`, so 4.11 can fill `PurchaseGranted` and `ReuseOffered` without mapping:
  - `Grant(abortLaunch)`;
  - `ConsumeOnly(retryLaunch)`;
  - `LeaveForAutoRefund`;
  - `OfferReuse(recordStrandedFirst)`;
  - `Ignore(OtherInstall | Pending | AlreadyHandled)`.
- **Rules (first match wins):**
  1. Another install launched it (the account id is set and is not `installId`), and it has no granted ledger row here → `Ignore(OtherInstall)`.
  2. Pending → `Ignore(Pending)`.
  3. Already granted, not yet consumed (a granted ledger row, or a granted record with no ledger row) → `ConsumeOnly`. It retries the launch only for `AlreadyOwned` of this product.
  4. Consumed (in the ledger or the record) or reused → `Ignore(AlreadyHandled)`.
  5. Unseen, for the active session (Ringing, Grace or Loud, not test mode), and this is its expected next product → `Grant`. `abortLaunch` is true in `PreLaunch` and `AlreadyOwned`.
  6. A launch of this product, and the session can buy it (an unseen or stranded token) → `OfferReuse`. `recordStrandedFirst` is true when there is no record yet.
  7. Anything else → `LeaveForAutoRefund`.

## Recovery cases → decision

Rows 1–14 are the epic's numbering, and each has its own test in `PurchaseReconcilerTest`. Rows A–I are cases the table leaves open. For each of them a default was taken; the owner can change it. Every row assumes this install's payment (account id = install id, or none) unless it says otherwise.

| # | Purchase | Session | Ledger / record | Context | Decision |
|---|---|---|---|---|---|
| 1 | PURCHASED, profile = active, product = expected next | Ringing, Grace or Loud | absent / absent | Update, Recovery | `Grant(abortLaunch = false)` |
| 2 | PURCHASED, profile = active, product ≠ expected next | ringing | absent / absent | Update, Recovery | `LeaveForAutoRefund` |
| 3 | PURCHASED, profile = an ended session | Completed or Missed (same id), or another session now rings | absent / absent | any | `LeaveForAutoRefund` |
| 4 | PURCHASED, profile = another session id | ringing | absent / absent | Update, Recovery | `LeaveForAutoRefund` |
| 5 | PURCHASED, profile = active | Snoozed | absent / absent | any | `LeaveForAutoRefund` |
| 6 | PURCHASED, no profile id (promo code, OQ-1) | ringing | absent / absent | Update, Recovery | `LeaveForAutoRefund` |
| 7 | PURCHASED, any profile | any or none | granted / any | Update, Recovery | `ConsumeOnly(retryLaunch = false)` |
| 8 | PENDING | any | any | any | `Ignore(Pending)` |
| 9 | duplicate of a granted token, profile = active | ringing | granted / granted | Update | `ConsumeOnly(false)`, never a second `Grant` |
| 10 | duplicate after consume | any | absent / consumed or reused | any | `Ignore(AlreadyHandled)` |
| 11 | owned token for P, any profile | ringing, not test, expected next = P | absent / absent or stranded | `PreLaunch(P)`, `AlreadyOwned(P)` | `OfferReuse(recordStrandedFirst = record absent)` |
| 12 | owned token for P | any | granted / any | `AlreadyOwned(P)` | `ConsumeOnly(retryLaunch = true)`. For `PreLaunch(P)` it is `false`, because the launch follows anyway. |
| 13 | PURCHASED, any | test mode, any kind | absent / absent or stranded | any | `LeaveForAutoRefund`, never a grant |
| 14 | PURCHASED | none | absent / absent | any | `LeaveForAutoRefund` |
| A | PURCHASED, profile = active, product = expected next (a lost callback found when Pay is tapped again) | ringing | absent / absent | `PreLaunch`, `AlreadyOwned` | `Grant(abortLaunch = true)`, not a reuse offer, because the earlier Pay tap was the consent. The launch is cancelled; otherwise the user would be charged twice. |
| B | PURCHASED, any profile | any | absent / granted (ledger row lost) | Update, Recovery / `AlreadyOwned(P)` | `ConsumeOnly(false)` / `ConsumeOnly(true)`. It granted once, so consuming keeps money for a snooze given. A granted ledger row beats any record status. |
| C | PURCHASED, profile = active, product = expected next | ringing | absent / stranded | Update, Recovery | `LeaveForAutoRefund`. A stranded token is used only through reuse, with consent. |
| D | PURCHASED | ringing, expected next = none (max snoozes or price cap) | absent / absent | any | `LeaveForAutoRefund`, no reuse offer |
| E | token for another product than the launch | any | any | `PreLaunch(Q)`, `AlreadyOwned(Q)` | the same as Recovery (rows 1–14), except that a `Grant` still cancels the launch |
| F | PURCHASED | any | consumed / any | any | `Ignore(AlreadyHandled)` |
| G | stranded token for P | Snoozed, Completed or Missed, or expected next ≠ P | absent / any but granted | `PreLaunch(P)` | `LeaveForAutoRefund` |
| H | PENDING for P | ringing | any | `AlreadyOwned(P)` | `Ignore(Pending)`. 4.11 shows "payment pending". |
| I | any, account id = another install (same Google account, another device) | any or none | not granted here / any | any | `Ignore(OtherInstall)`: no record, no consume, no reuse, no grant. With a granted ledger row here, this install granted it, so `ConsumeOnly`. |

**Sequences** (`PurchaseReconcilerSequenceTest`): these run against a model of the 4.10 stores. The ledger and the records are written one at a time.
- Pending, then PURCHASED in Grace or Loud: one `Grant`. A repeated update and a later Recovery both give `ConsumeOnly`.
- Pending, then PURCHASED after the session ended: stranded and never consumed.
- A lost callback:
  - found by Recovery: `Grant`;
  - found when Pay is tapped again: `Grant`, and the Play sheet is never opened.
- The same token across a restart: `Grant`, then `ConsumeOnly`, then `Ignore`. One grant in total.
- A crash between the two stores' writes gives `ConsumeOnly`, never a second grant:
  - after the ledger commit and before the record;
  - with only the record left;
  - after "mark consumed" and before the ledger row is deleted.
- PRD UJ4: stranded → `OfferReuse` → accepted → `ConsumeOnly` → consumed → `Ignore`. One grant, one consume, and one record with status reused.
- UJ4 with no Recovery before the next Pay: `OfferReuse(recordStrandedFirst = true)` records it stranded, so `markReused` works and the reuse ends with one reused record.
- `ITEM_ALREADY_OWNED` for an unconsumed grant: `ConsumeOnly(true)`, then `Ignore` after the consume.
- Another install's payment in every context: nothing is written.

**Invariants** (`PurchaseReconcilerInvariantsTest`): every combination of the inputs, about 120,000, including three account ids (this install, another install, none):
- **Must grant:** every unseen PURCHASED payment for the ringing, charging session's next product, from this install, gives `Grant` in every context and every ringing state, with `abortLaunch` exactly in launches.
- `Grant` is given only when all of those conditions hold.
- `ConsumeOnly` is given if and only if the token is PURCHASED and this install granted it. `retryLaunch` is set only for `AlreadyOwned` of the token's own product.
- `Ignore(OtherInstall)`, `Ignore(Pending)` and `Ignore(AlreadyHandled)` are each given if and only if their condition holds.
- `OfferReuse` is given only for the launched product, only for this install's unspent token, and `recordStrandedFirst` exactly when there is no record.
- `LeaveForAutoRefund` is given only for this install's PURCHASED tokens that never granted anything.
- Test mode never grants or reuses, and every decision is reachable.

## Boundaries & Constraints

**Always:**
- **The rules live only in `PurchaseReconciler`:**
  - `PurchaseStateReaderScanTest` (a `:data` host test) scans the shipped sources of `:core`, `:data`, `:androidApp` and `:composeApp`, with comments blanked out.
  - It flags any use at all of the words `purchaseState`, `getPurchaseState`, `PurchaseState` (Play's type) and `PlayPurchaseState`. That covers a scope function, a property reference, a wildcard or aliased import, and a named argument.
  - Only the reconciler and the future adapter mapping (`androidApp/src/main/kotlin/com/yawnandpawn/app/android/billing/PlayPurchaseMapping.kt`) are exempt.
  - A reflection test checks that `PurchaseSnapshot` has no `componentN`, so it cannot be destructured.
- Tokens stay redacted: a `PurchaseSnapshot` or a `ReconcileInput` prints no token (tested).
- Kover: a `core.billing` variant with a 90 % line rule, which runs inside `koverVerify`.

**Never:**
- No change to the reducer, the AD-2 table, the ports or any schema. Only new files in `core.billing` change, plus the Kover variant.
- No consume decision without a granted ledger row or record: consuming keeps the money.
- No suspend, I/O or lock in the reconciler.

## Decisions (default taken, owner can change)

1. **A lost callback during a new launch (row A):** `Grant(abortLaunch = true)`, not a reuse offer, when the owned token is for this session and this product and has never been seen. Any grant found during a launch cancels it.
2. **Granted record without a ledger row (row B):** `ConsumeOnly`. A granted ledger row wins over any record status. That covers a crash between "mark consumed" and "delete ledger row" (a repeat consume is a success) and a reuse still being consumed.
3. **Stranded stays stranded (row C):** Update and Recovery never grant a stranded token, even when it now matches the ringing session. Only the reuse offer with consent can use it.
4. **Other products during a launch (row E):** decided as Recovery would decide them.
5. **Pending for the active session:** the reconciler says only `Ignore(Pending)`. 4.11 compares the profile id with the active session to dispatch `PurchasePending`, so it never reads the purchase state itself.
6. **`declinedReuseProduct`:** not an input. Availability already disables Snooze for that product, so no `PreLaunch` reaches the reconciler.
7. **Scan location:** the scan lives in `:data`'s host tests next to the other scan tests (`:core` has no JVM-only test source set). It covers `:data` and `:composeApp` as well as the `:core` and `:androidApp` the epic names.
8. **Another install (row I):**
   - A payment launched by another install on the same Google account is left entirely to that install.
   - If that install is gone (for example a reset tablet), Google refunds it after 3 days.
   - A purchase with no account id (a promo code) follows the normal rules.
   - A granted ledger row here overrides this rule. `runtime.db` is never restored, so the row proves this install granted it.
9. **Reuse with no record yet (row 11):** `OfferReuse(recordStrandedFirst = true)`. 4.11 records the token stranded before offering, so 4.10's `markReused` (stranded only) works, and declining leaves the correct stranded record.

</intent-contract>

## Code Map

- **core:** `billing/PurchaseReconciler.kt` holds:
  - `PlayPurchaseState`, `PurchaseSnapshot`, `ActiveSessionKind`, `ActiveSessionSummary` (with `of`);
  - `LedgerStatus`, `RecordStatus`, `ReconcileContext` and `ReconcileInput`;
  - `IgnoreReason` and `PurchaseDecision` (with `verdict`);
  - `PurchaseReconciler.decide`.
- **build:** the `billing` Kover variant (`core/build.gradle.kts`) and `koverVerifyBilling` in the root `koverVerify`.
- **Tests:**
  - core: `PurchaseReconcilerTest` (rows 1–14 and A–I, verdicts), `PurchaseReconcilerInvariantsTest` (sweep, `ActiveSessionSummary.of`, redaction, equality), `PurchaseReconcilerSequenceTest` and `ReconcilerFixtures`;
  - data: `PurchaseStateReaderScanTest`.

## Rebase notes

- **4.2 (Money, FeeLadder `productFor`, FeeRules; not merged):**
  - 4.9 uses no `Money` and takes the expected next product as a plain `String?`. 4.11 fills it from 4.2's `FeeLadder.productFor` (a `PriceCapReached` result becomes null).
  - The Kover `billing` variant and the `koverVerifyBilling` dependency are byte-for-byte 4.2's lines, so whichever merges second applies them cleanly. If git still conflicts, keep one copy.
  - 4.2 adds `FeeLadder.kt`, `Money.kt` and `MoneyFormatter.kt` in `core.billing`. 4.9 adds only `PurchaseReconciler.kt`, so no file overlaps.
- **4.8 (intents, install id):** no file overlap. 4.11 passes 4.8's `InstallIdProvider` value as `ReconcileInput.installId`. The reconciler does not read intents; the price on the record comes from the intent in 4.10.
- **4.10 (ledger and records):**
  - Its `grant_ledger.status` and `purchase_record.status` map 1:1 onto `LedgerStatus` and `RecordStatus` (Absent = no row). It can keep these enums as the core types, or map to them in `PurchaseLedger`.
  - `ConsumeOnly` must go through its consume path.
  - Marking a reused record consumed keeps it reused, and the sequence tests model that.
- **4.11 (coordinator):** it builds `ReconcileInput` per purchase and uses `decision.verdict` for `PurchaseGranted`/`ReuseOffered`. It must honour `abortLaunch` and `recordStrandedFirst` (see the deferrals). It must not name `purchaseState` (the scan fails).
- **4.12 (adapter):** the Play → `PurchaseSnapshot` mapping must live at the exempt path above, or the scan's `ADAPTER_MAPPING_PATH` must move with it. It maps `obfuscatedAccountId` to `accountId` and drops `UNSPECIFIED_STATE`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: implemented in fast mode (one agent, unattended Epic 4 run), then reviewed and fixed. Branch `story/4-9-purchase-reconciler` on `origin/main` (`75b1877`) plus the Epic 4 context commits.

**Verification (first pass):** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`: BUILD SUCCESSFUL (17 min 40 s). Kover `core.billing` line coverage was 100 %.

**Residual risks:**
- **Promo codes (OQ-1):** S1 did not test whether a promo-code purchase carries a profile id. Without one it is always stranded, which is safe (refunded) but gives no snooze.
- **Stale queries:** Play can still list a token just after it was consumed. With the record consumed, that gives `Ignore`. With only the ledger row granted, it gives a repeat consume, which is harmless.
- **The scan is textual:** it now flags the words themselves, so only code that never names the state (reflection, for example) could get past it.

## Review (2 reviewers, fast mode)

Two reviewers read `1ea36dd`: one for verification gaps, one for edge cases. The edge-case reviewer found no HIGH issue in the pure rules. Everything was fixed in `fix(4.9): review fixes`:

1. **(HIGH) No "must grant" invariant.** The sweep now requires `Grant` for every unseen PURCHASED payment for the ringing, charging session's next product, in every context and every ringing state.
2. **Ignore and ConsumeOnly were checked in one direction only.** They now have "if and only if" invariants over the whole sweep.
3. **The UJ4 sequence stopped at the offer, and the model wrote both stores at once.**
   - UJ4 now runs accept → `ConsumeOnly` → consume → `Ignore`, ending with one grant and one reused record.
   - The model writes the ledger and the record separately, so the crash points between them are tested.
4. **Test names:** the "one Grant" sequence now repeats the update and a Recovery after the grant. The determinism check, which could not fail, was removed.
5. **Scan bypasses** (a scope function, wildcard imports, destructuring):
   - The scan now flags the words themselves.
   - `PurchaseSnapshot` is no longer a data class, and a reflection test checks it has no `componentN`.
   - New rogue sources cover each bypass.
6. **Double charge:** a Grant in `PreLaunch`/`AlreadyOwned` now carries `abortLaunch = true`, and a sequence test checks that the Play sheet is never opened. 4.11 deferral: the Grant cancels the launch and clears `paying`.
7. **Reuse with no record:** `OfferReuse(recordStrandedFirst)` tells 4.11 to record the token stranded before offering. The UJ4 variant with no prior Recovery ends with one reused record. Deferrals were added for 4.10 and 4.11.
8. **Another install's payments:** `PurchaseSnapshot.accountId` and `ReconcileInput.installId` were added. A foreign payment gives `Ignore(OtherInstall)` in every context. Row I and a sequence test check that nothing is written.

**Note for the 4.11 reviewers (deferred):** Recovery must wait for the active session to be restored at cold start. Otherwise a lost callback for the ringing session is recorded stranded, and row C makes that permanent. The user is still refunded.
