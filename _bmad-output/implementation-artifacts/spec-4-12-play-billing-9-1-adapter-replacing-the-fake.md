---
title: 'Story 4.12: Play Billing 9.1 adapter replacing the fake'
type: 'feature'
created: '2026-10-09'
status: 'done'
baseline_revision: '8d6d7a8'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/docs/spikes/S1.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-3-cache-play-prices-for-offline-display.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-8-purchase-intents-install-id-and-the-runtime-db-intent-table.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-9-purchasereconciler-for-every-recovery-case.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-10-grant-ledger-purchase-records-and-consume-with-retry.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-11-billing-orchestration-launch-recovery-item-already-owned-and-stranded-reuse.md'
warnings:
  - 'Story 4.13 (confirm sheet, with the LivePriceSource, DisplayPrices and BillingCountry ports) is not merged; see the rebase notes.'
deferred:
  - 'Story 4.13: make PlayLivePrices, CachedDisplayPrices and PlayBillingCountry implement LivePriceSource, DisplayPrices and BillingCountry and swap them for the None bindings in confirmSheetModule (one line each; see the rebase notes)'
  - 'Story 4.18: confirm on the phone that the sheet opens from WakeActivity after the PIN (the 5 s resume wait), that the echo window covers Play''s posted copy, and the 2-minute PIN prompt limit'
---

<intent-contract>

## Intent

**Problem:** every billing port still has a stand-in: `UnavailableBilling` fails every launch, query and consume; `UnavailableProductDetailsSource` leaves the price cache empty; and `UnlockPort.Unlocked` would launch Play behind the keyguard, where S1 found the sheet never shows. Snooze cannot be paid for (FR-RNG-3, FR-RNG-4, FR-RNG-5, NFR-5, NFR-6, AD-5, AD-7, AD-12, AD-13, AD-15).

**Approach:** Play Billing Library 9.1.0 (`billing-ktx`) behind a thin `BillingClientFacade`. The facade makes one call per Play call, with callbacks and plain values, so Robolectric tests drive the adapter through a fake. Everything else lives in `:androidApp` `android/billing/`:
- **`AndroidBilling`** is the one `Billing`.
  - **Connection:** nothing connects before the first unlock. Every call then answers `BillingUnavailable("before first unlock")`. `init()` (the unlock signal) only launches the connection.
  - **Joining a setup:** a call made while Play connects waits for the same setup. Play refuses a second `startConnection` while connecting.
  - **Reconnect:** after a lost service, it retries with a backoff (1 s doubling to 60 s, 6 tries). A permanent answer stops it, for example no Play Store. Play's own automatic reconnection also runs.
  - **Thread model:** calls run on the caller's coroutine (the coordinator's app scope). `launchBillingFlow` runs on the main thread.
  - **Time limits:** every call is time-bound. Connect 10 s, queries 10 s, consume 15 s, reaching the main thread 5 s, the resumed wake screen 5 s.
  - **Launch:** from the resumed `WakeActivity` only, with the product's `ProductDetails` and its one-time offer token, `obfuscatedAccountId` = install id and `obfuscatedProfileId` = session id. No resumed wake screen means `Failed(Error)`, and Play does not open.
  - **Updates:** a buffered channel, so an update sent before the coordinator collects is kept. Each sheet result is delivered once (`LaunchEchoFilter`).
  - **Product details:** fetched 20 ids per query and kept in memory, so the launch after the live price needs no second query.
  - **Billing country:** `getBillingConfigAsync` after each connection.
- **`PlayResponses`** is the one response-code table. It is unit-tested row by row.
  - OK → Launched / Purchases (pending ones included).
  - USER_CANCELED → Cancelled.
  - ITEM_ALREADY_OWNED → ItemAlreadyOwned.
  - NETWORK_ERROR, SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED → Offline.
  - Everything else → Error.
  - Consume: OK → Consumed, ITEM_NOT_OWNED → NotOwned (never Consumed, 4.10), anything else → Failed.
- **`PlayPurchaseMapping.kt`** sits at the path the 4.9 scan exempts. It maps `Purchase` to `PurchaseSnapshot`: `obfuscatedAccountId` → `accountId`, `obfuscatedProfileId` → `profileId`. `UNSPECIFIED_STATE` and unknown states are dropped, and blank ids become null.
- **`AndroidProductDetailsSource`** replaces `UnavailableProductDetailsSource` (4.3). Each product's first one-time offer gives `formattedPrice`, `priceAmountMicros` and `priceCurrencyCode` through `Money.parse`. Unfetched, missing, offer-less or bad-currency products are reported as unfetched; they never fail the whole refresh.
- **`AndroidDeviceUnlocker`** is the one `UnlockPort` (S1 option B, the PIN first). It calls `requestDismissKeyguard` from the resumed wake screen. `onDismissSucceeded` → Succeeded; cancel or error → Failed. With no resumed screen it fails, and a prompt with no answer for 2 minutes (wrong PIN) fails. The coordinator's resume rule (4.11) still settles a lost callback sooner.
- **For 4.13's ports:** `PlayLivePrices` gives Play's live price now, with an 8 s limit, and stores the answer in the price cache (4.3 deferral). `CachedDisplayPrices` gives the cache without expired prices. `PlayBillingCountry` gives the country from `getBillingConfigAsync`.
- **Debug only:** a hidden developer toggle binds `DebugFakeBillingFacade`, a pretend Play that is always connected, charges USD at the tier price and reports PURCHASED 1.5 s after a launch. It is turned on with `adb shell am broadcast -a com.yawnandpawn.app.debug.FAKE_BILLING --ez on true`, then an app restart. Release builds get `PlayBillingClientFacade` from their own `variantBillingFacade`, and `checkReleaseContent` now fails on `DebugFakeBilling*` or the action.

## Boundaries & Constraints

**Always:**
- **Alarm safety:**
  - Billing never sits on the wake path. "I'm up", the checks and the sound never wait for it.
  - `init()` returns at once.
  - Every Play wait is bounded, so a hung Play cannot hold the coordinator's mutex for ever.
  - A Play that never connects leaves only `paying` set. `PlayBillingWiringTest` covers it end to end.
- **Money safety:**
  - Only the coordinator launches and queries, and only `PurchaseLedger` consumes. The facade's call is `consumeAsync`, so the 4.10 scan still sees a single `.consume` caller.
  - Only the mapping file names a purchase's state.
  - Tokens never reach a log. `PlayPurchase`, `PurchaseSnapshot` and `PurchaseUpdate` hide them, and this is tested on the `Logger` fake. The adapter reports nothing to Crashlytics.
- **Builds without Play services:** a phone or emulator without the Play Store's billing service builds no client. Every call answers `BILLING_UNAVAILABLE` at once, and the backoff stops.
- New dependencies are in `config/dependency-allowlist.txt` in the same change, with licences, and in `docs/decisions/play-billing-dependencies.md` for the Data safety form.

**Never:**
- No change to the reconciler, the ledger, the coordinator or any schema.
- No UI. The confirm sheet is 4.13 and the outcome messages are 4.14.
- No Play call before the first unlock.

## Decisions (default taken, owner can change)

1. **Before the first unlock** the error is `DomainError.BillingUnavailable("before first unlock")`, not a new `BeforeFirstUnlock` error. 4.11 already documents `BillingUnavailable` as covering it, and a new error type would touch every exhaustive `when`.
2. **Connection failures in `launch`:** they map through the table (SERVICE_UNAVAILABLE → Offline). A connection or main-thread timeout is Error ("Payment didn't go through"), since a hung Play is not known to be offline.
3. **Time limits:** the values above. PIN prompt 2 minutes. Live price 8 s.
4. **ProductDetails at launch:** the in-memory copy from the latest query (the live price at the sheet, or the refresh) is used. It is fetched once when missing.
5. **Echo rule:** Play also sends a failed `launchBillingFlow`'s code to the listener. One bare copy of that code is dropped, whether it comes during the call or within 5 s after it. Purchases are never dropped.
6. **Wake screen:** `ResumedWakeScreen`, application lifecycle callbacks held weakly, one per process. `WakeActivity` itself is unchanged, and Play and the PIN prompt wait up to 5 s for it to resume. After the PIN, the screen resumes a moment after `onDismissSucceeded`.
7. **Debug toggle:** a flag file in device-protected storage, set by a debug broadcast and read at process start. Release builds have a different `variantBillingFacade` and no hook at all.
8. **Billing country:** read after each connection and kept in memory only, so it is null until the first connection after an app start (the tax note then hides).
9. **Removed:** `UnavailableBilling` and `UnavailableProductDetailsSource`, with their tests. Robolectric app tests bind a fake facade that answers like a phone without Play (`testAppModule`).

</intent-contract>

## Code Map

- **androidApp `android/billing/`:**
  - `BillingClientFacade.kt`: the seam and its plain values.
  - `PlayBillingClientFacade.kt`: PBL 9.1.
  - `AndroidBilling.kt`.
  - `PlayResponses.kt`.
  - `PlayPurchaseMapping.kt`.
  - `LaunchEchoFilter.kt`.
  - `AndroidDeviceUnlocker.kt`.
  - `PlayPriceAdapters.kt`: `AndroidProductDetailsSource`, `PlayLivePrices`, `PlayBillingCountry`, `CachedDisplayPrices`.
  - `ResumedWakeScreen.kt`.
  - `BillingModule.kt`.
- **Variant sources:**
  - `src/release/.../billing/VariantBillingFacade.kt`.
  - `src/debug/.../billing/VariantBillingFacade.kt`.
  - `src/debug/.../debug/billing/DebugFakeBilling.kt`.
  - `DebugHooksProvider` registers the toggle.
- **Wiring:**
  - `YawnAndPawnApp` includes `billingModule()` and registers `ResumedWakeScreen`.
  - `WorkModule` drops its product details stand-in.
  - `androidApp/build.gradle.kts` adds `libs.play.billing`.
- **Build and config:**
  - `build-logic` `ReleaseContent`: the fake billing markers.
  - `config/dependency-allowlist.txt`.
  - `docs/decisions/play-billing-dependencies.md`.
- **Tests:**
  - `AndroidBillingTest`: unlock gate, connection, joined setup, backoff, launch ids and thread, no wake screen, hung Play, the echo, updates, queries, consume, the token log check, product details.
  - `PlayMappingTest`: the table row by row, and the purchase mapping including Play's own `Purchase`.
  - `LaunchEchoFilterTest`, `AndroidDeviceUnlockerTest` (Robolectric keyguard shadow), `PlayPriceAdaptersTest`.
  - `PlayBillingWiringTest`: the Koin graph, and the end-to-end alarm safety with a Play that never connects.
  - `ReleaseContentTest`, `SessionWiringTest`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.
- `./gradlew :androidApp:bundleRelease` -- expected: the R8 release bundle builds (Play Billing ships its own consumer keep rules; no app rule needed).

## Rebase notes

- **Rebased after review** onto origin/main `79aa8b4`, which has 4.13 (PR #53, confirm sheet) and 4.16 (PR #52, purchase history). Conflicts:
  - `YawnAndPawnApp`: 4.13's `SheetEffectRunner` stays as the `EffectRunner`, and its `UnavailableBilling` binding goes.
  - `WakeApp` (test): both sides' parameters stay.
  - `deferred-work.md` and `sprint-status.yaml`: both sides kept.
- **4.13's ports are now bound to Play** in `confirmSheetModule`:
  - `PlayLivePrices` implements `LivePriceSource`;
  - `PlayBillingCountry` implements `BillingCountry`;
  - their `None` bindings are gone.
- **`DisplayPrices`:** 4.13 had already bound `CatalogDisplayPrices`, a real adapter over the 4.3 price cache that is the same as this story's `CachedDisplayPrices`. So `CachedDisplayPrices` and its test were deleted, and `CatalogDisplayPrices` stays (default taken, owner can change).
- **`UnlockPort`:** `AndroidDeviceUnlocker` in `billingModule` is the only binding. 4.13 added none.
- **4.13's intent id:** `ConfirmSheetHost` mints a new UUID for every Pay tap (the 4.8 deferral).
- **4.16 (purchase history)** shares no files with this story.

## Review (2 reviewers, fast mode)

Two reviewers read `fea363ac`: one for verification gaps, one for edge cases. The edge-case reviewer found no HIGH and confirmed the echo filter, the consume mapping, the updates, PENDING, the ids and the alarm path. Every item was fixed in `fix(4.12): review fixes`, with its test:

1. **(HIGH) `ResumedWakeScreen` had no test.** `ResumedWakeScreenTest` drives the application's lifecycle callbacks: another screen is never current, a wait ends when the wake screen resumes, and a paused or destroyed screen is no longer current.
2. **(Real bug) A setup that never answered stayed joinable for ever.** After `connect` with no answer, the client is ended (new facade `endConnection()`), the waiting callers are answered, and the next call builds a fresh client. A service dropped during the setup answers the waiting call at once.
3. **Backoff bounds:** with virtual time, tries land at 0/1/3/7/11/15 s, with no seventh by 100 s.
4. **No connection before the first unlock, in the app's own Koin graph.** A locked boot that rings, recovers and is asked directly makes 0 connections, and the unlock makes 1. Real bug found: `init()` called before the unlock marked billing started and then never connected. It now waits for the unlock.
5. **The never-connecting wiring test now proves its name:** Play answers OK at last, with the product known and a wake screen resumed, and no sheet opens for the ended session.
6. **The real facade is tested** (`PlayBillingClientFacadeTest`). Without the Play Store, every call answers `BILLING_UNAVAILABLE` at once. The launch request (`FlowRequest`, now plain values) sends the install id as `obfuscatedAccountId` and the session id as `obfuscatedProfileId`.
7. **"Main thread busy":** the launch is now posted to the main thread and waited for with a limit. A blocked main thread gives `Failed(Error)` at once, and the abandoned call never runs (it is claimed first). The old `withContext` version could not time out at all: it waited for the blocked thread.
8. **(Real bug) An `Error` thrown by `launchBillingFlow` left the echo filter launching for ever,** so every later update, a PURCHASED included, was held. Any throwable is now caught, the filter is always closed in `finally`, and a later purchase is delivered.
9. **PENDING → PURCHASED through Play's own `Purchase` JSON** (`purchaseState` 4, then 0): two updates in order, neither dropped as an echo.
10. **`DEVELOPER_ERROR` on a setup** (Play reconnecting by itself) is not permanent. The next call connects, and the backoff retries. Only `BILLING_UNAVAILABLE` and `FEATURE_NOT_SUPPORTED` stop it.
11. **(Alarm, money) Never behind the keyguard.** Right before `launchBillingFlow`, on the main thread, the keyguard is read again. If it is locked, nothing opens and the launch fails as `UnlockFailed` ("Phone still locked. No charge."). Tested with Robolectric's keyguard shadow.
12. **= 2.**
13. **The backoff keeps growing across drops** until a connection stays up 60 s (`stableConnection`), so a flapping Play Store is not rebound in a storm. At most 4 connections in 5 s of virtual time. The billing country is read only while unknown.

**Weak assertions:**
- The cancelled PIN prompt must answer within 100 ms, and `onDismissError` is driven too.
- The live-price limit test now fails without the 8 s limit: the billing query there waits 10 s.

**Minors:**
- A later product query that fails keeps the earlier queries' prices and reports the rest as unfetched.
- Single quantity is documented in `tools/play-catalog/README.md` and `docs/decisions/play-billing-dependencies.md`. The adapter ignores a purchase's quantity, so multi-quantity must stay off.

## Auto Run Result

Status: implemented in fast mode (one agent, unattended Epic 4 run), with no review pass. Branch `story/4-12-play-billing-adapter` on `origin/main` `8d6d7a8`.

**Verification:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`: BUILD SUCCESSFUL (32 min 21 s, on `b039c1f`).
- `:androidApp:bundleRelease`: R8 (`minifyReleaseWithR8`), `checkReleaseContent` and the bundle all built. Play Billing ships its own consumer keep rules, so `proguard-rules.pro` needs no change.
- Gate fixes along the way:
  - the ledger scan caught `timeouts.consume` as a second `.consume` caller (renamed `consumeCall`);
  - detekt `ReturnCount` and `NoDirectTimeAccess` (the debug fake now takes the wall clock from the adapter package).
- One gate run hit the 30-minute test-task cap after 121 of about 128 test classes, while other lanes loaded the PC. There was no watchdog output, so no test was hung.

**Residual risks:**
- Nothing here has run against real Play. The S1 timings (the sheet after the PIN, the echo window, the 2-minute PIN limit) are for the 4.18 device rows (deferred-work.md).
- **Billing country:** it is null until the first connection after an app start, so the tax note can be missing on a sheet opened in the first seconds after a cold start.
- **ProductDetails reuse:** the launch reuses the in-memory `ProductDetails` from the latest query. A price changed in Play Console since then still shows correctly on Play's own sheet.
- **Crashlytics mapping upload:** a release build with a local `google-services.json` runs `uploadCrashlyticsMappingFileRelease`, as every release build on this machine does.
