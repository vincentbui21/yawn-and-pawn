---
title: 'Story 4.13: Snooze confirm sheet'
type: 'feature'
created: '2026-10-09'
status: 'done'
baseline_revision: '8778afe'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md'
  - '{project-root}/docs/spikes/S1.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-8-purchase-intents-install-id-and-the-runtime-db-intent-table.md'
warnings: []
deferred:
  - 'Story 4.11 (done, merged first): the coordinator runs RequestKeyguardDismiss and LaunchBilling, resolves lost unlock callbacks on resume and owns reuse (acceptReuse / declineReuse)'
  - 'Story 4.12: bind LivePriceSource to ProductDetails, BillingCountry to the cached getBillingConfigAsync country, and the one UnlockPort (4.11's binding) to AndroidDeviceUnlocker'
  - 'Story 4.7: replace NoBillingSnoozeAvailability (the sheet already follows any policy); DisplayPrices reads the 4.3 price cache (CatalogDisplayPrices)'
  - 'Story 4.14: map every PurchaseOutcome to its WakeMessage with the wake snackbar rules (10 s minimum); 4.13 shows only UnlockFailed'
---

<intent-contract>

## Intent

**Problem:** snooze costs real money, and the user is half asleep. Before any charge the user must see the honest price and the next one, with the free "I'll get up" under the thumb, taps ignored for a moment, and the phone's PIN asked only after the price (S1 option B). Nothing on this path may block "I'm up", the checks or the alarm sound.

**Approach:** the approved `sheet-snooze-confirm` composable (design preview, `sheet_confirm_*` baselines) is wired to the engine.

- **Open:** `button-snooze` sends `SnoozeTapped`; the engine's `ShowSnoozeConfirm(offer)` reaches the process-wide `ConfirmSheetHost` through `SheetEffectRunner` (a tap in front of `WakeRuntime`, which still runs and logs every effect). The host keeps a `SheetRequest` for that session and ring, so a recreated wake screen shows the same sheet.
- **Pure mapping** (`ui/wake/SnoozeSheetMapping.kt`): `snoozeSheet(request, session, availability, priceOf, ladder, showTaxNote, unlockingPrice)`. While `unlocking` it is *unlocking*; while billing is in flight none (Play is on top); a confirm shows only while availability is still Available for the same offer; `FeeLadder.followingProduct` (core) gives the next snooze or none (max snoozes, $50 cap), so the body is "The next one costs {nextPrice}." or only "This one costs {price}.".
- **Prices:** new core ports `LivePriceSource` (Play `ProductDetails`, 4.12) and `DisplayPrices` (price cache, 4.3/4.7), with `None` defaults. The sheet asks for the live price at open (this snooze and the next) and again at Pay; the cached price is display-only until the live one arrives.
- **Pay:** live price equal to the one on screen sends `PayConfirmed(new UUID v4 intent id, live price)`. A different live price is shown instead (the guard re-arms) and nothing is sent. No live price closes the sheet with no charge.
- **Guard:** `InputGuard` reads the monotonic clock (`LocalWakeClock`, the app's `MonotonicClock` in `WakeActivity`) and accepts input only 500 ms after the sheet's value last changed (open, state change, price change), with any animation setting.
- **Unlocking:** the engine's R32 sets `unlocking`; the sheet shows "Unlock to pay {price}" and "Cancel". Cancel sends `UnlockFailed` (R34: "Phone still locked. No charge."). A lost unlock callback (resume with `unlocking` set) belongs to Story 4.11's `PurchaseCoordinator.resolveLostUnlock` (hook in `WakeActivity.onResume`); the sheet sends no unlock result of its own.
- **Already paid:** `ShowReuseSheet(productId)` opens it; "Use it" calls the 4.11 coordinator's `acceptReuse()` and "Not now", Back or a swipe `declineReuse()` (`ReuseChoices`); the token never reaches the UI.
- **Close:** "I'll get up", Back, a swipe down, a tap outside or TalkBack's dismiss action close the sheet with no charge and no intent. "I'm up" drops a Pay that is still waiting for its price.
- **Tax note:** `TaxNote.shows(BillingCountry.countryCode())` for the countries in `config/tax-exclusive-countries.txt` (US, CA; mirrored in `TaxNote.TAX_EXCLUSIVE_COUNTRIES`, checked by a file test).

## Boundaries & Constraints

**Always:**
- The sheet never blocks "I'm up", the check or the sound: closing returns to the ringing or check screen underneath, the engine's sound and timers run unchanged, and the scrim and sheet never stop a dispatch of `ImUpTapped`.
- `PayConfirmed` carries only a live price; the cached price never reaches an intent. One new intent id per Pay tap.
- Strings are the existing resources (verbatim EXPERIENCE.md Key strings); tokens only.

**Never:**
- No change to the reducer, the AD-2 table or `WakeRuntime`.
- No launch of billing or of the keyguard dismiss here (4.11 runs those effects).
- No message other than UnlockFailed (4.14).

## Decisions (default taken, owner can change)

1. **A changed live price is shown, not charged:** Pay re-reads the live price; if it differs from the screen the sheet shows the new price (500 ms guard again) and the user taps Pay again. Charging silently at a new price would break "price first".
2. **No live price at Pay closes the sheet with no message:** billing could not launch, so nothing was charged; the snooze button keeps its availability label. 4.14 may add an Offline message.
3. **A next price that exists but is not known yet** shows the shorter body "This one costs {price}." rather than an invented number.
4. **The request stays open after Pay** until the engine's payment state or outcome closes it, so the sheet does not flicker between confirm and unlocking, and a Pay the engine ignores leaves the sheet usable.
5. **Lost unlock callbacks are Story 4.11's** (integration decision 2026-10-09): its coordinator resolves them on resume (settle about 1.5 s, then recheck the keyguard, results tagged with the intent id) and keeps the only `UnlockPort` binding. 4.13 leaves a marked hook in `WakeActivity.onResume`; the engine reads `getOrNull<UnlockPort>() ?: UnlockPort.Unlocked`.
6. **UnlockFailed message:** shown until the next tap or 10 s; 4.14 owns the full snackbar rules.
7. **Swipe down** closes the sheet after a 64 dp (`target-wake`) downward drag, on the fixed part or past the top of the scrolling text.
8. **The snooze button shows the live price once known** (same `priceOf` as the sheet), else the cached one.

</intent-contract>

## Code Map

- **core:** `billing/SnoozePrices.kt` (`LivePriceSource`, `DisplayPrices`, `BillingCountry`, `TaxNote`, `FeeLadder.followingProduct`).
- **composeApp:** `ui/wake/SnoozeSheetMapping.kt` (`SheetRequest`, `stillWanted`, `snoozeSheet`, `InputGuard`, `LocalWakeClock`); `WakeComponents.kt` (`SnoozeConfirmSheet`: monotonic guard keyed on the sheet value, swipe down, pane title, heading, dismiss action).
- **androidApp:** `wake/ConfirmSheetHost.kt` (host + `SheetEffectRunner`), `wake/SheetPorts.kt` (`ReuseChoices` over the 4.11 coordinator, `CatalogDisplayPrices` over the 4.3 cache), `WakeActivity.kt` (snooze, sheet intents, overlay, 4.11 resume hook, clock), `WakeCheck.kt` (price lookup), `WakeModule.kt` (port defaults, host), `YawnAndPawnApp.kt` (runner, engine `UnlockPort`).
- **testing:** `SheetPriceFakes.kt` (`FakeLivePriceSource`, `FakeDisplayPrices`, `FakeBillingCountry`); `TaxExclusiveCountriesFileTest`.
- **config:** `tax-exclusive-countries.txt`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

## Auto Run Result

Status: implemented in fast mode (one agent, unattended Epic 4 run), no review pass yet. Branch `story/4-13-confirm-sheet` on `origin/main` (`8778afe`).

- **Tests:** `SnoozePricesTest` (core), `TaxExclusiveCountriesFileTest` (testing), `SnoozeSheetMappingTest` (composeApp, incl. the 499/500 ms `InputGuard`), `ConfirmSheetHostTest`, `SnoozeConfirmSheetTest` (Robolectric, real engine and wake screen), `SnoozeSheetGuardTest` (499/500 ms on open, state change and price change; swipe; TalkBack dismiss), `SnoozeSheetScreenshotTest` (20 new `wake_sheet_*` baselines). Existing preview baselines unchanged.
- **Changed tests:** `SessionWiringTest` (the runner is `SheetEffectRunner` over `WakeRuntime`); `TapThroughTest` passes the guard with main-looper time (the guard reads the monotonic clock, not the compose test clock). The design preview provides the app's `MonotonicClock` as `LocalWakeClock`.

**Rebase notes:**
- **4.3 (merged):** `DisplayPrices` is `CatalogDisplayPrices` over `PriceCatalog`; the sheet and both snooze buttons use `ConfirmSheetHost.priceOf` (live, then display). **4.7:** its `SnoozeOffer` carries the `PriceEntry`, so on rebase the button price comes from availability; keep the sheet's live-then-display lookup for the sheet itself.
- **4.11 (merged):** `EffectRunner` is `SheetEffectRunner(WakeRuntime, ConfirmSheetHost, logger)`; `WakeRuntime` hands `LaunchBilling` and `RequestKeyguardDismiss` to the coordinator, so the sheet never runs them.
- **4.12:** bind `LivePriceSource`, `BillingCountry` and replace 4.11's `UnlockPort.Unlocked`.
- **4.14:** replace the UnlockFailed-only message mapping in `ConfirmSheetHost.onEffect` with the outcome table.

## Review (2 reviewers, fast mode)

Two reviewers read `56e8e40c`: one for verification gaps, one for edge cases. Every item was fixed in `fix(4.13): review fixes`:

1. **(HIGH) The tax note scrolled away at 200%:** it now sits above the buttons, outside the scrolling text. `wake_sheet_confirm_tax_ringing_sunrise_font200` was re-recorded (the only baseline that changed); the screenshot test asserts the note and every button are whole on screen.
2. **(HIGH) The guard and the app's clock:** `SnoozeConfirmSheetTest` runs on `FakeMonotonicClock`; moving only that clock 499 ms leaves Pay inert, +1 ms pays. The design preview provides `AndroidMonotonicClock` as `LocalWakeClock`.
3. **Whole buttons at 200%:** bounds inside the window, not just displayed.
4. **TalkBack order:** title, then price, then Pay (pre-order of the pane's unmerged tree).
5. **`SheetEffectRunner`:** other effects pass through as the same instance with the sheet unchanged; a throwing sheet is logged and the runtime still runs the effect.
6. **The sheet closes on its own:** unavailable with no tap, and a new ring after a paid snooze shows none.
7. **Dismiss paths:** decision: the 500 ms guard covers every input, dismiss too, as EXPERIENCE.md says ("Ignores all input for 500 ms"); a tap outside at 499 ms does nothing and at 500 ms closes. TalkBack's dismiss on the real screen closes with no intent and the ring goes on.
8. **Epic rows:** `ReuseOffered` over confirm switches to already paid with a new guard; the grace window ending keeps the sheet open with the alarm back at full gain; Back and a swipe on already paid send `ReuseDeclined`.
9. and 12. **One Pay per request:** a request whose Pay was sent sends no other (by identity) until the outcome clears it; "is the sheet still open, then send" and closing (I'll get up, I'm up) share a lock, so no Pay follows a close. Tested with a held price, double taps and a send the engine never applies.
10. **Shipped placeholder ports:** with the default bindings no sheet can open and the ring goes on; `SessionWiringTest` asserts the `None` bindings.
11. **10 s message timeout** tested; an unreadable keyguard makes Pay ask to unlock first.
13. **Live price timeout:** 3 s (`LIVE_PRICE_TIMEOUT`), then the sheet closes with no charge.
14. **Resume while unlocking:** moved to Story 4.11 (integration decision 2026-10-09, Decision 5). The sheet no longer sends `UnlockSucceeded`/`UnlockFailed` on resume, `WakeActivity.onResume` has a marked hook for `resolveLostUnlock`, and the sheet adds no `UnlockPort` binding.
15. **A changed price is announced:** the price is a polite live region.
- Low: the unlock step after a new process shows the price stored with the intent (`PurchaseIntentStore`), not the cached offer price.

**Rebased onto 4.11 (2026-10-09):** "Use it" and "Not now" call the coordinator's `acceptReuse()` / `declineReuse()` (the `ReuseTokens` port is gone), the coordinator's `onWakeScreenResumed()` in `WakeActivity.onResume` resolves lost unlocks, 4.11's `single<UnlockPort>` is the only binding, and `DisplayPrices` is `CatalogDisplayPrices` over the 4.3 price cache (its own app-wide scope, so app-start job waits never wait on it).

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes): the approved composable; the swipe threshold is `target-wake`.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots: wake screens are Sunrise only; new `wake_sheet_*` screenshots over Ringing and Check, 100% and 200%.
- [x] Every colour pair used is in the `DESIGN.md` contrast table (`glass-strong+text`, outline on the sheet, accent button): no new pair.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp: asserted for every sheet button at 100% and 200%.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present: both buttons on screen at 200% (asserted), title is a heading and the pane title, dismiss action; payment outcomes stay text with "No charge." (no colour).
- [x] Reduced-motion path works: the guard is clock-based, so animator duration scale 0 changes nothing; the "I'm up" pulse already stops while the sheet is open.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone`: existing resources only; `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled: confirm, last snooze, tax note, unlocking, already paid, closed by unavailability.
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced.
- [x] Compose `@Preview`s for each state exist (design preview `sheet_*`); Roborazzi screenshots added.
