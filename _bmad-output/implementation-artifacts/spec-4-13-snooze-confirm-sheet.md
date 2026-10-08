---
title: 'Story 4.13: Snooze confirm sheet'
type: 'feature'
created: '2026-10-09'
status: 'in-progress'
baseline_revision: '8778afe'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md'
  - '{project-root}/docs/spikes/S1.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-8-purchase-intents-install-id-and-the-runtime-db-intent-table.md'
warnings: []
deferred:
  - 'Story 4.11: execute RequestKeyguardDismiss (UnlockPort.requestUnlock outside the Mutex, dispatch result.event()) and LaunchBilling; bind ReuseTokens to the stranded set behind ReuseOffered'
  - 'Story 4.12: bind LivePriceSource to ProductDetails, BillingCountry to the cached getBillingConfigAsync country, and UnlockPort to AndroidDeviceUnlocker'
  - 'Story 4.3/4.7: bind DisplayPrices to the price cache and replace NoBillingSnoozeAvailability (the sheet already follows any policy)'
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
- **Unlocking:** the engine's R32 sets `unlocking`; the sheet shows "Unlock to pay {price}" and "Cancel". Cancel sends `UnlockFailed` (R34: "Phone still locked. No charge."). On resume with `unlocking` set, the keyguard decides: unlocked sends `UnlockSucceeded` (R33, billing launches), still locked `UnlockFailed`.
- **Already paid:** `ShowReuseSheet(productId)` opens it; "Use it" sends `ReuseAccepted(productId, token)` with the token from the `ReuseTokens` port (4.11); "Not now", Back or a swipe sends `ReuseDeclined`.
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
5. **The resume check runs on every resume with `unlocking` set**: S1 showed the PIN prompt pauses the wake screen, so a resume follows the prompt. A recreated screen resumed under a still-open PIN prompt would read as cancelled ("Phone still locked. No charge."); 4.18 checks it on the phone.
6. **UnlockFailed message:** shown until the next tap or 10 s; 4.14 owns the full snackbar rules.
7. **Swipe down** closes the sheet after a 64 dp (`target-wake`) downward drag, on the fixed part or past the top of the scrolling text.
8. **The snooze button shows the live price once known** (same `priceOf` as the sheet), else the cached one.

</intent-contract>

## Code Map

- **core:** `billing/SnoozePrices.kt` (`LivePriceSource`, `DisplayPrices`, `ReuseTokens`, `BillingCountry`, `TaxNote`, `FeeLadder.followingProduct`).
- **composeApp:** `ui/wake/SnoozeSheetMapping.kt` (`SheetRequest`, `stillWanted`, `snoozeSheet`, `InputGuard`, `LocalWakeClock`); `WakeComponents.kt` (`SnoozeConfirmSheet`: monotonic guard keyed on the sheet value, swipe down, pane title, heading, dismiss action).
- **androidApp:** `wake/ConfirmSheetHost.kt` (host + `SheetEffectRunner`), `WakeActivity.kt` (snooze, sheet intents, overlay, resume check, clock), `WakeCheck.kt` (price lookup), `WakeModule.kt` (port defaults, host), `YawnAndPawnApp.kt` (runner, engine `UnlockPort`).
- **testing:** `PriceFakes.kt` (`FakeLivePriceSource`, `FakeDisplayPrices`, `FakeReuseTokens`, `FakeBillingCountry`); `TaxExclusiveCountriesFileTest`.
- **config:** `tax-exclusive-countries.txt`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.

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
