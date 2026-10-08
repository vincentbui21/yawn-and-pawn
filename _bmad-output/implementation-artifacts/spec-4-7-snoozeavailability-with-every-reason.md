---
title: 'Story 4.7: snoozeAvailability with every reason'
type: 'feature'
created: '2026-10-08'
status: 'review'
baseline_revision: '2ec338c'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 4.7; boundaries with 4.8, 4.11, 4.12, 4.13, 4.15)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md (snooze copy, button-snooze)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-2-money-moneyformatter-and-the-feeladder-in-core.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-3-cache-play-prices-for-offline-display.md'
warnings:
  - 'Built on story/4-3-price-cache 2ec338c, then rebased onto its rebased tip 778a537 (main 2d7a396 with 4.4 merged). Once 4.3 squash-merges: git rebase --onto origin/main 778a537.'
deferred:
  - 'Story 4.8 / 4.11: fill PurchaseGranted.price and ReuseAccepted.price from the purchase intent, so SessionData.paid fills; PersistPurchaseIntent reads the price from SnoozeOffer.price.'
  - 'Story 4.11: feed the stranded product ids (reconciler) into SnoozeConditions (today an empty set).'
  - 'Story 4.12: AndroidProductDetailsSource fills the price cache; until then snooze stays "Prices not loaded yet" in the app (billing unavailable).'
  - 'Story 4.15: the session line from SessionData.paid.'
---

<intent-contract>

## Intent

**Problem:** The Snooze button must say exactly why it can't be used (FR-RNG-7), from one pure policy (AD-7), and change in place when the phone goes online, the prices load, the phone is unlocked or a stranded payment clears. Today `NoBillingSnoozeAvailability` only knows test mode, the first unlock and "prices not loaded".

**Approach:**
- **Core (`core.billing`):**
  - `SnoozeEnv(online, prices: PriceCatalogSnapshot, now, strandedProducts, userUnlocked)` and the pure `snoozeAvailability(session, env, ladder, logger)`, checking the reasons in the order of the table below. It reuses `FeeLadder.nextAvailability` (4.2) for the cap and the invalid fee, and `PriceCatalogSnapshot.displayablePriceFor` (4.3) for the price.
  - `SnoozeConditions(connectivity, catalog, userLock, clock, stranded)`: `observe()` combines the env flows; `current()` is the latest combined env (with `now` and the live lock state), for the reducer. `LiveSnoozeAvailability(conditions, ladder, logger)` is the production `SnoozeAvailabilityPolicy`, replacing `NoBillingSnoozeAvailability` (kept for tests and previews).
  - `SnoozeOffer` gains `price: PriceEntry? = null`; `SnoozeAvailability.Unavailable` gains `price: PriceEntry? = null` (the refunding price). So `ShowSnoozeConfirm`/`PersistPurchaseIntent` carry the shown price to 4.8/4.13.
- **Core (`core.net`):** `Connectivity` port, `observeOnline(): Flow<Boolean>` (current value first).
- **Core (`core.session`):** `SessionData.paid: List<Money> = emptyList()` (`@Serializable` with a lenient serializer that drops a malformed amount), appended on a paid snooze when `PurchaseGranted.price` / `ReuseAccepted.price` (new, default null) is known. No AD-2 row added or changed.
- **Session-start refresh (4.3):** `SessionStartPriceRefresh` waits (up to 30 min) for `Connectivity` to say online, then refreshes once.
- **`:testing`:** `FakeConnectivity` (toggle `online`).
- **`:androidApp`:** `AndroidConnectivity` (default network callback, online = `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_VALIDATED`), `ACCESS_NETWORK_STATE` in the manifest, Koin swap, `WakeActivity` recomputes the availability on every `SnoozeConditions` change (the button changes in place).
- **`:composeApp`:** UI `SnoozeOffer.Available`/`StrandedRefund` carry Play's `formattedPrice`; `snoozeOffer()` uses the price from the policy result.

## Reasons: condition → copy (checked top to bottom, first match wins)

| # | Result | Condition | Button label (EXPERIENCE.md) | TalkBack |
|---|---|---|---|---|
| 1 | `TestMode` | `config.testMode` | "Test · no charge" | "Snooze unavailable, Test · no charge" |
| 2 | `BeforeFirstUnlock` | `!env.userUnlocked` | lock icon, "Unlock your phone to snooze" | "Snooze unavailable, Unlock your phone to snooze" |
| 3 | `MaxSnoozesReached` | `snoozesGranted >= config.maxSnoozes` | "Snooze unavailable: max snoozes reached" | "Snooze unavailable, max snoozes reached" |
| 4 | `PriceCapReached` | ladder: B × (snoozesGranted + 1) > 50 | "Snooze unavailable: price cap reached" | "Snooze unavailable, price cap reached" |
| 4b | `InvalidFee` | ladder: frozen B outside 1..10 (damaged config, logged) | "Prices not loaded yet" | "Snooze unavailable, prices not loaded yet" |
| 5 | `PaymentPending` | `session.paymentPending` | "Snooze unavailable: payment pending" | "Snooze unavailable, payment pending" |
| 6 | `EarlierPaymentRefunding(price)` | `declinedReuseProduct` = expected product and it is in `env.strandedProducts` | "An earlier {price} payment is being refunded" (Play's string; "Prices not loaded yet" when no price is cached) | "Snooze unavailable, An earlier {price} payment is being refunded" |
| 7 | `Offline` | `!env.online` | "Snooze unavailable: offline" | "Snooze unavailable, offline" |
| 8 | `CatalogueNotLoaded` | no displayable (cached, not expired) price for the expected product | "Prices not loaded yet" | "Snooze unavailable, prices not loaded yet" |
| — | `Available(offer + price)` | otherwise | "Snooze · {formattedPrice}" | "Snooze · {formattedPrice}" |

"I'm up" stays enabled and visible in every row (FR-RNG-9). A stranded token for the expected product that the user has not declined keeps Snooze Available (the reuse offer comes on "Pay", 4.11). "Billing unavailable" (Epic 1–4.11: `UnavailableProductDetailsSource`, `UnavailableBilling`) is row 8: no price can be cached until 4.12.

## Boundaries & Constraints

**Always:**
- Alarm safety: availability is only shown and checked by the reducer's SnoozeTapped/PayConfirmed rows. It never blocks "I'm up", the check, the sound or any timer; nothing on the wake path waits for connectivity or a price.
- The policy is pure given the env; `snoozeAvailability` reads no clock and no port.
- The reducer and the screen read the same env (`SnoozeConditions.current()`), so a tap on an enabled button is accepted.
- All strings are resources; key strings match EXPERIENCE.md verbatim.

**Never:**
- No AD-2 row added or changed; no new reducer event.
- No Play Billing dependency; no billing launch (4.11/4.12).
- No eager collection on `ApplicationScope` (it would never finish; tests wait for its children).

## Decisions (fast mode: default taken, owner can change)

1. **Env before the first emission:** until the combined flows have emitted once (nobody collected yet), `current()` is online with no prices and the live lock state: the result is at best "Prices not loaded yet", never Available, and never a false "offline".
2. **"Online"** = the default network has `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED` (captive portals read as offline; Play may still fail offline, 4.14 handles it). A failing system service reads as online (logged once), so Play gets to decide.
3. **Expired prices** (30 days, 4.3) count as not loaded (row 8); stale prices are shown.
4. **Refunding without a cached price** shows "Prices not loaded yet" (no invented price).
5. **`paid` amounts** come from the event (`PurchaseGranted.price`, `ReuseAccepted.price`, default null); 4.8/4.11 fill them from the intent. A malformed stored amount is dropped on decode, not the whole session.
6. **Session-start refresh** waits for online for at most 30 minutes (the no-interaction timeout), then gives up.
7. **Fallback picker:** no Snooze footer (owner decision Q2, 2026-10-08).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| Available | unlocked, online, 01 cached, B=1, 0 granted | Available(snooze_usd_01 #1, price 01) |
| Test + offline | testMode, offline | TestMode |
| Locked + offline | locked, offline | BeforeFirstUnlock |
| Max + offline | granted 5 of 5, offline | MaxSnoozesReached |
| Cap (B=10, max 6) | granted 5 | PriceCapReached |
| Pending + offline | paymentPending, offline | PaymentPending |
| Refunding + offline | declined 01, 01 stranded, B=1, 0 granted, offline | EarlierPaymentRefunding(price 01) |
| Declined but cleared | declined 01, stranded empty | Available |
| Stranded not declined | 01 stranded, nothing declined | Available |
| Offline, no cache | offline, empty | Offline |
| No cache | online, empty | CatalogueNotLoaded |
| Expired cache | entry 31 days old | CatalogueNotLoaded |
| Invalid fee | B=0 | InvalidFee (logged) |
| Live change | FakeConnectivity off → on | Offline → Available, in place |
| Old row | state_json without `paid` | decodes, `paid` empty |
</intent-contract>

## Tasks

1. `core.net.Connectivity`; `core.billing.SnoozeAvailabilityRules.kt` (`SnoozeEnv`, `snoozeAvailability`, `SnoozeConditions`, `LiveSnoozeAvailability`).
2. `SessionPolicies.kt`: `SnoozeOffer.price`, `Unavailable.price`; `SessionState.kt`: `paid` + serializer; `SessionEvent.kt`: `price` on `PurchaseGranted`/`ReuseAccepted`; `PurchaseRules`: append.
3. `PriceRefresh.kt`: `SessionStartPriceRefresh` gated on `Connectivity`.
4. `:testing` `FakeConnectivity`.
5. `:androidApp`: `AndroidConnectivity`, manifest permission, Koin (`LiveSnoozeAvailability`, `SnoozeConditions`, `Connectivity`), `WakeActivity` env flow; `testAppModule` binds `FakeConnectivity(online = true)`.
6. `:composeApp`: `WakeContract`, `RingingMapping`, `WakeComponents`.
7. Tests and screenshots.

## Test plan

- **core** `SnoozeAvailabilityTest`: one row per reason, the overlaps, Available with price, stranded, expired, invalid fee logged; `SnoozeConditions` with `FakeConnectivity` toggling, prices arriving, unlock; `LiveSnoozeAvailability` through the reducer (SnoozeTapped accepted only when Available).
- **core** `SessionJsonTest`: a pre-4.7 row decodes with `paid` empty; `paid` round trips; a malformed amount is dropped. Reducer: a grant with a price appends it.
- **core** `PriceRefreshTest`: the session-start refresh waits for online.
- **composeApp** `RingingMappingTest`: every reason → UI offer; Play's `formattedPrice` used.
- **androidApp** Robolectric `SnoozeVariantsTest`: the label and TalkBack text of every variant on Ringing and on the Check footer, "I'm up" enabled in each; `AndroidConnectivityTest` (shadow network callback); `WakeActivity` live change with `FakeConnectivity`; Roborazzi for the new variants on Ringing and the Check footer at 100 % and 200 %.

## Verification

- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used: no new component; `button-snooze` and its disabled variant are the Epic 1 ones.
- [x] Sunrise checked with screenshots (wake screens are Sunrise only): every variant on Ringing and on the Check footer (`SnoozeVariantsTest`, plus the existing Ringing and Check baselines).
- [x] Every colour pair used is already in the `DESIGN.md` contrast table (no new pair).
- [x] Touch targets: the wake snooze stays 64 dp, "I'm up" 72 dp.
- [x] 200 % font scale screenshots for every new variant; TalkBack reads "Snooze unavailable, {reason}" (asserted for each disabled variant).
- [x] Reduced motion: no new motion.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` verbatim, from string resources (no new string).
- [x] Every snooze state row of `EXPERIENCE.md > State Patterns` (snooze unavailable, test alarm, before first unlock, already paid "Not now") is mapped.
- [x] "I'm up" stays the most prominent wake action, enabled in every variant; snooze is visible, plain and priced with Play's string.
- [x] Screenshot tests updated (Roborazzi); the design preview already holds these states (`ringing-offline`, `ringing-max-snoozes`, `ringing-stranded`).

## Auto Run Result

Status: implemented in fast mode (Epic 4 Lane 1), waiting for review. Branch `story/4-7-snooze-availability` on `origin/story/4-3-price-cache` 778a537 (main 2d7a396 + 4.3).

**Verification:** `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`: BUILD SUCCESSFUL (15 min). 22 new Roborazzi baselines (`wake_ringing_snooze_*`, `wake_check_snooze_*`), no existing baseline changed.

**Residual risks:**
- In the app snooze still reads "Prices not loaded yet" until Story 4.12 fills the price cache; the Available, refunding and paid paths are exercised by tests only.
- `SnoozeConditions.current()` is only as fresh as the last collection (the wake screen collects it); a reducer call with no wake screen open sees the last env, which is safe (no snooze tap without the screen).
- An invalid frozen fee is logged on each recomposition that recomputes availability (rare: a damaged config).
- `NET_CAPABILITY_VALIDATED` can lag a fresh connection by a few seconds; the button then changes in place.

## Review (2 reviewers, fast mode)

Two reviewers looked at the branch, one for verification gaps and one for edge cases. Neither found a HIGH defect in production code; "I'm up", the checks and the sound never wait on connectivity or prices. Every item is fixed in `fix(4.7): review fixes`, each with a test:

1. **Production wiring:** `SessionWiringTest` starts the app's own modules without `testAppModule` and finds `AndroidConnectivity` under `LiveSnoozeAvailability`, whose `conditions` is the Koin single the wake screen collects.
2. **Visible labels:** `SnoozeVariantsTest` asserts each disabled variant's visible label (unmerged tree) and its icon (lock before the first unlock, block otherwise; new test tags on the icon).
3. **AndroidConnectivity failures:** no default network reads offline; a missing system service reads online and logs "read connectivity". The current value is now read before the callback is registered (the system calls back at once), so an older read can never overtake a callback.
4. **Permission:** the app requests `ACCESS_NETWORK_STATE`.
5. **Orderings:** B = 10, max 5, 5 granted → max snoozes (not the cap); B = 0 with pending and offline → invalid fee; B = 0 in test mode → test mode.
6. **`paid` never loses the session:** the serializer reads JSON element by element; a bad micros or currency drops that amount only, and `null` or a non-list reads as nothing paid.
7. **Play's string:** the samples use "US$N.00", so the screenshots show the button and the refund label use Play's `formattedPrice`, not the app's formatting.
8. **Prices loading in place:** online with an empty cache shows "prices not loaded yet", then a refresh turns it into "Snooze · USD 1.00" on the same resumed activity.
9. **Sharper tests:** the wiring test checks the shared instance; scenario 6 pins the production policy type; the reducer test drives `SnoozeTapped` and `PayConfirmed` through the live policy for every row of the reasons table; the in-place test checks the exact sequence of envs.
10. **Stale env across rings:** when the last collector of `SnoozeConditions.observe()` stops, `current()` falls back to the safe env (online, no prices), so the next ring never starts from the previous ring's connection or prices.
11. **Pay while just unavailable:** deferred to 4.11/4.13 in deferred-work.md (the sheet closes with the reason, or a new AD-2 row answers `PayConfirmed` while unavailable). Also deferred: the refund label should show the amount paid (4.10/4.11), not today's cached price.
