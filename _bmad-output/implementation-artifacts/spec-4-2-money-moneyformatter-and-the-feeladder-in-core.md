---
title: 'Story 4.2: Money, MoneyFormatter and the FeeLadder in core'
type: 'feature'
created: '2026-10-08'
status: 'in-progress'
baseline_revision: '75b1877'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 4.2)'
  - '{project-root}/docs/architecture.md (AD-7, AD-8, AD-12)'
  - '{project-root}/docs/prd.md (§6.2, §6.3, FR-RNG-6, FR-RNG-7, NFR-10, NFR-11)'
warnings:
  - 'Lane 2 (Story 4.1) also creates config/snooze-products.txt. Both write the same 50 ids, one per line; the 4.2 reader skips blank lines and # comments, so a header 4.1 adds does not break it. Take either version when merging.'
deferred:
  - 'Story 4.7: snoozeAvailability maps FeeStep.PriceCapReached to UnavailableReason.PriceCapReached, and MaxSnoozesReached from config.maxSnoozes (FeeRules.MAX_SNOOZES).'
  - 'Story 4.3: prices shown before purchase are Play formattedPrice strings (AD-8); MoneyFormatter formats totals and history.'
  - 'Story 4.5/4.6: ladder previews from Play prices per tier instead of Money.times on the local base fee.'
---

<intent-contract>

## Intent

**Problem:** The Epic 1 placeholder ladder prices snooze N at tier B + N − 1, but the PRD (§6.2) says the Nth snooze costs B × N, capped at $50. It never reports the cap, and it throws on bad input. There are also two money types: `ui.format.Money` (UI only, `plus` throws on mixed currencies) and none in core, although AD-8 says `Money(micros, currency)` is the only money type and formatting goes through a `MoneyFormatter` port.

**Approach:**
- **`core.billing.Money(micros: Long, currency: String)`** replaces `ui.format.Money` everywhere (one type, AD-8):
  - The currency must be 3 upper-case ASCII letters. The constructor `require`s it (a programming error); `Money.parse(micros, currency)` returns `Outcome<Money, DomainError.InvalidCurrency>` for adapter input (Play, DataStore).
  - `plus` never throws: it returns `Outcome<Money, DomainError.CurrencyMismatch>`.
  - `totalsByCurrency(List<Money>)` returns one `Money` per currency, in first-seen order.
  - `Money.of(units, currency)` and `times(Int)` remain for previews and ladder approximations.
- **`MoneyFormatter`** port (`fun format(money): String`) and `MoneyFormatter.formatTotals(list)`: one amount per currency joined with " + " (EXPERIENCE.md "Money, mixed currencies").
  - `AndroidMoneyFormatter` (composeApp androidMain) uses `NumberFormat.getCurrencyInstance(locale)` with the currency's own fraction digits.
  - UI `formatMoney(...)` delegates to it, so the UI formats money in no other way.
  - `FakeMoneyFormatter` lives in `:testing`. Koin binds `MoneyFormatter`.
- **`core.billing.FeeLadder.productFor(baseFeeTier, snoozeNumber)`** returns `Outcome<FeeStep, DomainError.InvalidFee>`, where `FeeStep` is `Product(productId, usdTier)` or `PriceCapReached`.
  - `UsdFeeLadder` is the production ladder: NN = B × N, a product when 1 ≤ NN ≤ 50, `PriceCapReached` above.
  - B outside 1–10 or N < 1 is `InvalidFee`.
  - `FeeRules` holds the limits: base fee tiers 1–10, max snoozes 1–5 (default 5), cap tier 50.
  - `SnoozeProducts` holds the 50 ids, checked against `config/snooze-products.txt`.
- **`nextStep(session)`/`nextOffer(session)`** move with the interface. `nextOffer` is null at the cap or on an invalid config.
- **Detekt rule `NoFloatingPointMoney`:** in `:core` and `:data`, no `Double`/`Float` property or parameter whose name contains price, amount, fee or paid.

## Boundaries & Constraints

**Always:**
- Core stays platform-free (AD-1); nothing in `core.billing` throws for expected input (AD-12). The `Money` constructor's currency check is the only `require`.
- `core.billing` ≥ 90 % line coverage (new Kover variant `billing`, run by `koverVerify`).
- `NoUnseededRandom`, `NoHostageApis` and every existing rule stay green.

**Never:**
- No change to the reducer, AD-2 rows, `SessionState`/`SessionJson` (the `paid` list is 4.7) or any schema.
- No new user-facing string resource. " + " is EXPERIENCE.md's mixed-currency pattern, built in the formatter.
- No hard-coded currency symbol (`CopyRulesTest` is unchanged).

## Decisions (fast mode: default taken, owner can change)

1. **"$1.00", not "$1":** the formatter always uses the currency's fraction digits, as the AC asserts. Play's own `formattedPrice` for the $1 product is "$1.00" too. The approved previews showed whole amounts without decimals, so every money-bearing preview and screenshot baseline is re-recorded (intended). To revert, drop the decimals for whole amounts in `AndroidMoneyFormatter` and re-record.
2. **Invalid currency:** the constructor `require`s, and `Money.parse` returns `DomainError.InvalidCurrency`. It is a new `DomainError` case beside the AC's `CurrencyMismatch` and `InvalidFee`.
3. **Package:** `FeeLadder`, `FeeStep`, the ids and `FeeRules` move to `core.billing`. `SnoozeOffer` stays in `core.session` (availability is 4.7's).
4. **Production ladder name:** `UsdFeeLadder` (an object) replaces `TierFeeLadder`.
5. **`AndroidMoneyFormatter` location:** in `:composeApp` androidMain (package `ui.format`), because the UI's `formatMoney` must reach it. It is bound in Koin from `:androidApp`, and its Robolectric test lives in `:androidApp`.
6. **Mixed totals in the UI:** Day detail's "paid" becomes the per-currency totals. Home, Progress and Success keep one `Money` until 4.7/4.15 feed them.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior |
|----------|--------------|---------------------------|
| Ladder B = 1 | N 1..5 | 01, 02, 03, 04, 05 |
| Ladder B = 3 | N 1..5 | 03, 06, 09, 12, 15 |
| Ladder top | B 10, N 5 | 50 (exactly the cap) |
| Cap | B 10, N 6; B 9, N 6 (54); B 1, N 51 | `PriceCapReached` |
| Not capped | B 1, N 50 | 50 |
| Invalid B | 0, −1, 11 | `InvalidFee(B, N)` |
| Invalid N | 0, −3 | `InvalidFee(B, N)` (checked before the cap) |
| Overflow | B 10, N `Int.MAX_VALUE` | `PriceCapReached` (no Int overflow) |
| Reachable set | B 1..10 × N 1..5 | 31 distinct ids, all in `config/snooze-products.txt` |
| Catalogue file | `config/snooze-products.txt` | exactly `SnoozeProducts.all`, in order (comments and blanks skipped) |
| Money currency | "usd", "US", "USDX", "", "U1D" | constructor throws; `parse` → `InvalidCurrency` |
| Plus | USD + USD; USD + EUR | sum; `CurrencyMismatch("USD","EUR")` |
| Negative / zero | −1 µ, 0 µ | allowed (refund corrections); `0` formats as "$0.00" |
| Totals | [], [USD 1, EUR 2, USD 3] | []; [USD 4, EUR 2] (first-seen order) |
| Format en-US | USD 1 000 000 µ | "$1.00" |
| Format de-DE | EUR 1 000 000 µ | "1,00 €" |
| Format ja-JP | JPY 150 000 000 µ | "￥150" |
| Format vi-VN | VND 25 000 000 000 µ | "25.000 ₫" |
| Format sub-unit | USD 1 990 000 µ; USD 1 234 567 µ | "$1.99"; "$1.23" (half-even to the currency's digits) |
| Mixed list | [USD 1, EUR 2] en-US | "$1.00 + €2.00" |
| Detekt | `val price: Double`, `fun f(amountUsd: Float?)`, `val paidTotal = 1.5` | reported |
| Detekt | `val price: Money`, `val ratio: Double`, `val feeTier: Int` | not reported |

</intent-contract>

## Tasks

1. `core.billing`: `Money`, `totalsByCurrency`, `MoneyFormatter` + `formatTotals`, `FeeLadder`, `FeeStep`, `UsdFeeLadder`, `FeeRules`, `SnoozeProducts`.
2. `core.error.DomainError`: `CurrencyMismatch`, `InvalidCurrency`, `InvalidFee`; `diagnostic()` cases.
3. `core.session`: remove `FeeLadder`, `TierFeeLadder` and `snoozeProductId` from `SessionPolicies.kt`; `nextStep`/`nextOffer` in `core.billing`.
4. `config/snooze-products.txt` (50 ids); a `core` `jvmTest` reads it through a system property.
5. `:testing`: `FakeFeeLadder` (B × N by default, records calls, cap above 50), `FakeSnoozeAvailability` (cap → `PriceCapReached`), `FakeMoneyFormatter`.
6. `:composeApp`: delete `ui.format.Money`; `formatMoney(Money)` and `formatMoney(List<Money>)` through `AndroidMoneyFormatter`; migrate fields (`micros`, `currency`) and Day detail totals.
7. `:androidApp`: Koin `FeeLadder` → `UsdFeeLadder`, `MoneyFormatter` → `AndroidMoneyFormatter`; previews and tests migrated.
8. `:detekt-rules`: `NoFloatingPointMoney` + tests; `detekt.yml` scope (core, data); provider list; `DetektConfigTest`.
9. Kover variant `billing` (90 %), wired into `koverVerify`.
10. Re-record the money-bearing screenshot baselines (decision 1).

## Test plan

- **core** `MoneyTest`: currency validation (table), `parse`, `plus`, `times`, `of`, `totalsByCurrency`.
- **core** `FeeLadderTest`: the edge-case table; examples; 31 reachable; ids in `SnoozeProducts.all`; `nextStep`/`nextOffer` for a session; `formatTotals` through a recording formatter.
- **core** `jvmTest` `SnoozeProductsFileTest`: the shared file equals `SnoozeProducts.all`; every reachable id is in it.
- **testing** `SessionFakesTest`: fakes follow B × N and the cap; `FakeMoneyFormatter` is deterministic.
- **androidApp** `AndroidMoneyFormatterTest` (Robolectric): the four locales, sub-unit rounding, zero, the mixed list.
- **androidApp** `SessionWiringTest`: Koin binds `UsdFeeLadder` and `AndroidMoneyFormatter`.
- **detekt-rules** `NoFloatingPointMoneyTest`: violating and compliant snippets; the provider lists the rule; `DetektConfigTest` checks the core and data scope.
- **Gate:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots` -- expected: only the re-recorded money baselines (decision 1).
