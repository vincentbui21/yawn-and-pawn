# Epic 4 Context: Pay to snooze

<!-- Written 2026-10-08 from planning artifacts and the code on main at 75b1877 (Epics 1–3 merged). Regenerate if planning docs change. -->

## Goal

Snoozing costs real money through Google Play. The Nth snooze in a session costs B × N (USD tiers `snooze_usd_01` … `snooze_usd_50`, shown as Play's local price), with a $50 cap, a max-snoozes limit (1–5, default 5) and an 8-hour commitment lock on weakening changes. The confirm sheet shows the price first and asks for the PIN only on "Pay" (Spike S1 option B, owner 2026-10-05). Every failed payment says "No charge.". A snooze is granted only for a `PURCHASED` token linked to the active session. Stranded payments are never consumed (Google refunds them) or are reused with consent. Every charge appears in Purchase history. Waking up stays free, and nothing in the payment flow may ever stand between the user and "I'm up".

## Stories

- 4.1 Create the 50 snooze products with `tools/play-catalog` (auto + owner live run)
- 4.2 Money, MoneyFormatter and the FeeLadder in core
- 4.3 Cache Play prices for offline display (also the first `BackgroundWork`/WorkManager user)
- 4.4 Commitment lock and pending changes in core
- 4.5 Snooze settings: base fee and max snoozes
- 4.6 Fee ladder, lock notes and turn-off confirmation in the editor and on Home
- 4.7 `snoozeAvailability` with every reason
- 4.8 Purchase intents, install id and the `runtime.db` intent table
- 4.9 `PurchaseReconciler` for every recovery case
- 4.10 Grant ledger, purchase records and consume with retry
- 4.11 Billing orchestration: launch, recovery, `ITEM_ALREADY_OWNED` and stranded reuse
- 4.12 Play Billing 9.1 adapter replacing the fake
- 4.13 Snooze confirm sheet
- 4.14 Payment outcome messages
- 4.15 Snoozed screen, session line and snoozing from a check
- 4.16 Purchase history
- 4.17 "Problem with a charge?"
- 4.18 Epic 4 payment verification with license testers (human-verify; automation never marks it done)

## What already exists (extend it, don't rebuild it)

### Core (`core/src/commonMain/kotlin/com/yawnandpawn/app/core/session/`)

- **`SessionPolicies.kt`:**
  - `SnoozeOffer(productId, snoozeNumber)`, the `UnavailableReason` enum (all 8 reasons already listed), `SnoozeAvailability.Available/Unavailable` and `fun interface SnoozeAvailabilityPolicy.availability(session)`.
  - `NoBillingSnoozeAvailability` is the production policy, with the precedence TestMode > BeforeFirstUnlock (live `UserLockState`) > CatalogueNotLoaded.
  - `fun interface FeeLadder.offer(baseFeeTier, snoozeNumber): SnoozeOffer` and `FeeLadder.nextOffer(session)`.
  - **`TierFeeLadder` uses the wrong formula:** it computes `B + n − 1`, capped at 50, where the spec says B × N. It has no `PriceCapReached` result, and `snoozeProductId(tier)` builds the ids. Story 4.2 changes the interface to a sealed result and fixes the formula.
- **`PurchaseRules.kt`:** the AD-2 purchase rows already exist:
  - `SnoozeTapped` emits `ShowSnoozeConfirm`.
  - `PayConfirmed` sets `paying` and emits `PersistPurchaseIntent` + `LaunchBilling`. A second confirm while `paying` is set is ignored.
  - `ReuseOffered` (verdict `OfferReuse`) emits `ShowReuseSheet`.
  - `ReuseDeclined` sets `declinedReuseProduct`.
  - `PurchaseGranted` (verdict `Grant`) moves to Snoozed with `snoozesGranted++`, a restarted `CheckRun`, `ArmSlot(snoozeEnd)` and `Consume(token)`. Test mode never grants.
  - `PurchaseFailed`/`Cancelled` emit `ShowPurchaseOutcome`, and `PurchasePending` sets `paymentPending`.
- **`SessionEvent.kt`:**
  - `PurchaseToken` already redacts its `toString`, and the `PurchaseVerdict` enum mirrors the reconciler's results.
  - **`PurchaseFailed` has no kind yet:** it is a `data object`. Stories 4.11 and 4.14 need the kinds Offline, UnlockFailed and Error.
  - **No `UnlockRequested`/`UnlockFailed` events or `Unlocking` state exist yet.** See "Open questions" Q1.
- **`SessionState.kt`:** it has `paying`, `paymentPending` and `declinedReuseProduct`, but no `paid` field (4.7 adds it). States persist through `SessionJson` golden fixtures, so add fields with defaults and a decode test.
- **`SessionPorts.kt`:**
  - `Billing` has only `launch(intent)` and `init()`. `init()` is called on the first unlock by `androidApp/.../wake/UnlockSignals.kt`.
  - `PurchaseIntent(intentId, sessionId, productId, snoozeNumber)` has no price yet.
  - `PurchaseIntentStore` has `save`/`get`, and `ActiveSessionStore.commit(state)` takes one state with no extra writes (4.8 adds the `writes`).
- **`SessionConfig.kt`:**
  - `SessionConfig` already freezes `baseFeeTier`, `maxSnoozes`, `snoozeLengthMinutes` and `graceSeconds`.
  - `GlobalSettings` (base fee tier 1, max snoozes 5) is **only a data class with defaults: nothing stores it.** `WakeService.kt` (around line 400) resolves every session with `GlobalSettings()`. Story 4.4 or 4.5 must add a DataStore-backed global settings repository and read it at `AlarmFired` and in `resolveTest`. Otherwise the user's base fee never reaches a session.
  - `ConfigResolver.resolve(...)` has no pending-change input yet.

### Android and data

- **Billing binding:**
  - `androidApp/.../android/UnavailableBilling.kt` is bound in `YawnAndPawnApp.kt` (`single<Billing> { UnavailableBilling(get()) }`, line ~107).
  - **`WakeRuntime.kt` handles none of the billing effects** (`PersistPurchaseIntent`, `LaunchBilling`, `Consume`, `ShowSnoozeConfirm`, `ShowReuseSheet`, `ShowPurchaseOutcome`). It does have `reassertRingVolume()`, built and tested in Story 2.8 but never called. Story 4.11 must call it on every outcome that returns to ringing.
- **Fakes:** `testing/.../SessionEngineFakes.kt` has `FakeBilling`, `FakePurchaseIntentStore` and `FakeActiveSessionStore`.
- **Databases:**
  - `data/.../db/AppDatabase.kt` sets `SCHEMA_VERSION = 8` (schemas 1–8 are exported).
  - `data/.../db/RuntimeDatabase.kt` is **version 1** (the `active_session` table only).
- **Settings DataStore:** `data/.../settings/SettingsDataStore.kt` is device-protected and is **backed up**, with both `cloud-backup` and `device-transfer` in `androidApp/src/main/res/xml/data_extraction_rules.xml` and `backup_rules.xml`. `BackupRulesCoverageTest` fails on any file that no rule names.
- **Allowlists:**
  - `config/permission-allowlist.txt` already lists `com.android.vending.BILLING`, `INTERNET` and `ACCESS_NETWORK_STATE`.
  - `config/dependency-allowlist.txt` has **no** Billing and **no** WorkManager entries yet.
  - `gradle/libs.versions.toml` already declares `play-billing = 9.1.0` (`billing-ktx`) and `workmanager = 2.11.0`, but nothing uses them.
- **App id:** `androidApp/build.gradle.kts` sets `applicationId = "com.yawnandpawn.app"` with no debug suffix ("Play Billing test purchases need the real id"). The versionCode comes from the versionName (`versionCodeOf`). The spike took versionCode 101, so the next Play upload must be 0.1.2 or later.

### UI (design preview already in `:composeApp` `commonMain`: wire it, don't redraw it)

- **`ui/wake/WakeContract.kt`:**
  - UI `SnoozeOffer` (Available, Unavailable(reason), TestMode, LockedBeforeUnlock, StrandedRefund) and `SessionLine`.
  - `WakeMessage` (UnlockFailed, PaymentCancelled, PaymentError, PaymentOffline, PaymentPending).
  - `SnoozeSheet.Confirm/Unlocking/AlreadyPaid`.
  - `RingingUiState.sheet/message/sessionLine`.
  - `WakeComponents.kt` renders the sheet and the messages, and `RingingMapping.kt` maps the policy to the button.
- **`ui/format/Money.kt` + `Money.android.kt`:** a second, UI-only `Money` type with `formatMoney`. Story 4.2 must replace it with, or map it to, the AD-8 core `Money` and the `MoneyFormatter` port, so that there is one money type.
- **App screens:**
  - `ui/purchases/PurchaseHistoryScreen.kt` groups purchases into month cards, newest first. Story 4.16's text says a flat list (Q5).
  - `ui/payments/PaymentsScreens.kt` holds `PaymentsScreen` and `ProblemWithChargeScreen`.
  - Also built: `ui/settings/SettingsScreen.kt` (base fee and max snoozes sub-screens), `ui/you/YouScreen.kt`, `ui/home/DeleteAlarmConfirm.kt` and `components/Stepper.kt`.
  - **`components/Buttons.kt` already has `PpsOutlinedButton`.** Story 4.17 reuses it instead of building `button-outlined`.
- **Placeholders:** `ui/nav/AppNavHost.kt` shows the Settings and You tabs with `rows = emptySet()` (Epic 4 turns on the Snooze card and Money rows).
- **Reference states (`docs/design-preview/states.md`):**
  - Ringing: `ringing-after-snooze`, `ringing-offline`, `ringing-max-snoozes`, `ringing-stranded`.
  - Sheet: `sheet-confirm`, `sheet-last-snooze`, `sheet-unlocking`, `sheet-already-paid`.
  - Outcomes: `payment-unlock-failed`, `payment-cancelled`, `payment-error`, `payment-offline`, `payment-pending`.
  - Check and Success: `check-sheet`, `snoozed`, `success-after-snooze`, `success-pending`.
  - Purchases: `purchases-list`, `purchases-empty`.
  - Settings: `settings-base-fee*`, `settings-max-snoozes`.
  - Payments: `payments-problem*`.
  - Home and editor: `home-disable-dialog`, `editor-snooze`.
  - The previews live in `androidApp/src/debug/.../debug/preview`.

### Tools and docs

- **`tools/play-catalog/`:** holds only `.gitkeep`. Follow `tools/tokens` (an included JVM build with unit tests).
- **`docs/decisions/package-id.md` does not exist** (Story 4.1 points at it). Take the package from `applicationId`, or write the file in 4.1.
- **`config/snooze-products.txt`** (the shared 50-id list) and **`config/app-links.properties`** (the support address) do not exist yet.
- **`docs/spikes/S1.md`** has the findings, the decision (B) and the proposed AD-2 rows. `docs/ci-release.md` covers the service account and `PLAY_SERVICE_ACCOUNT_JSON`. That service account has release permissions only, not product management.

## What each story adds (delta on the base above)

| Story | Adds | Touches |
|---|---|---|
| 4.1 | JVM tool `tools/play-catalog` (dry-run, apply, idempotent, never deletes, "unmanaged" for `spike_s1_test`), `config/snooze-products.txt`, `tools/play-catalog/README.md`, `docs/decisions/play-catalog-run.md` (owner run) | tools only; tool-only deps (Play Developer API client `google-api-services-androidpublisher`, `google-auth-library`; Apache-2.0, not in the app classpath) |
| 4.2 | `core.billing.Money` (+`CurrencyMismatch`, `totalsByCurrency`), `MoneyFormatter` port + `AndroidMoneyFormatter` + fake, real B × N `FeeLadder` with `PriceCapReached` and `InvalidFee`, detekt rule against Double/Float money names | `SessionPolicies.kt` FeeLadder interface and callers, `ui/format/Money.kt`, `:detekt-rules` |
| 4.3 | `PriceCatalog` + `ProductDetailsSource` ports and fakes, `price_cache` DataStore (excluded from backup), `BackgroundWork` port + `AndroidBackgroundWork` (WorkManager, on-demand init after unlock), daily "price-refresh", refresh at session start | backup XMLs + tests, manifest (remove the WorkManager initializer), dependency allowlist |
| 4.4 | `core.config`: `LockWindow`, `classify`, `PendingChange` + repository, `SetBaseFee`/`SetMaxSnoozes`, the `SaveAlarm` lock path, `PromotePendingChanges`, `RecordCommitmentEvent`; **app.db v8 → v9** (`pending_change`, `commitment_event`); global pending changes in the settings DataStore; **the persisted global settings repository** (see above) | `ConfigResolver.resolve` signature, `SaveAlarm`, `rescheduleAll()`, `WakeService` config read |
| 4.5 | Settings "Snooze" card, Base fee and Max snoozes sub-screens (stepper, ladder preview, approximate USD, lock notes) | `AppNavHost` Settings tab rows, `SettingsScreen` |
| 4.6 | Fee ladder in the editor's Snooze sub-screen, pending-field notes, the turn-off-under-lock dialog, commitment events on disable/delete | editor, Home, `AlarmActions` (deferred item: move the `AlarmDeleted` log into `DeleteAlarm`) |
| 4.7 | `SessionState.paid: List<Money>`, `Connectivity` port + `AndroidConnectivity`, pure `snoozeAvailability(state, config, env)` replacing `NoBillingSnoozeAvailability`, every button variant on Ringing and the check footer, live env flows | `SessionPolicies.kt`, `RingingMapping`, `WakeActivity`, golden fixtures |
| 4.8 | `PurchaseIntent` with price, `PurchaseIntentStore.forSession/forProduct/purgeOlderThan`, `ActiveSessionStore.commit(state, writes)`, **runtime.db v1 → v2** (`purchase_intent`), `InstallIdProvider`; **recommended: also the S1 unlock rows (Q1) and the `DeviceUnlocker` port + fake**, so Lane 2 can build 4.13 without waiting for 4.12 | reducer, `RoomActiveSessionStore`, engine restore |
| 4.9 | Pure `PurchaseReconciler.decide` with the 14-row table and sequence tests; a scan test that it is the only reader of purchase state | new files in `core.billing` only |
| 4.10 | **runtime.db v2 → v3** (`grant_ledger`), **app.db v9 → v10** (`purchase_record`, token hash only), `PurchaseLedger` (the only writer), the consume path with crash replay, the "consume-retry" job, `markReused` | engine commit, `BackgroundWork` |
| 4.11 | Extended `Billing` port (`launch`, `queryPurchases`, `consume`, `purchaseUpdates`), `PurchaseCoordinator`, recovery triggers, stranded set → availability env, reuse flow, `PurchaseFailed(kind)`, `reassertRingVolume()` on every return | `WakeRuntime`, `WakeActivity`, `MainActivity` |
| 4.12 | `AndroidBilling` (PBL 9.1.0) behind `BillingClientFacade`, `AndroidProductDetailsSource`, the response-code table, `AndroidDeviceUnlocker` (`requestDismissKeyguard`), the Koin swap, a debug-only FakeBilling toggle (absent from release), a billing-config port for the tax note | **new dependencies** (see below), Koin, release-content check |
| 4.13 | Confirm sheet wired: 500 ms monotonic input guard, unlocking and already-paid states, tax note (`config/tax-exclusive-countries.txt`: US, CA) | `WakeActivity`, sheet composables |
| 4.14 | `PaymentOutcome` table (exhaustive `when`), wake snackbar rules, Offline re-enables in place | wake UI |
| 4.15 | Snoozed surface (3 s, then finish), the session line, snooze from the check footer, Success "{paid} paid this morning" and "pending not used" | wake UI, Success screen |
| 4.16 | Purchase history route from You › Money, row status mapping | nav, `PurchaseHistoryScreen`, `PurchaseRecordRepository` |
| 4.17 | "Problem with a charge?" screen: order-history link, support email picker and body builder (no token, install id, session id or label), `config/app-links.properties` | nav, `ProblemWithChargeScreen` |
| 4.18 | Owner checklist, 17 items | none |

## Dependency graph

```
4.1 (tool) ───────────────────────────────────────────────┐ owner live run → real prices (4.12 smoke, 4.18)
4.2 ──┬─> 4.3 ──┬─> 4.7 ──> 4.8 ──> 4.10 ──> 4.11 ──> 4.12
      │         │           │  ^       ^        │
      │         │           │  └─ 4.9 ─┘        ├─> 4.14 (needs PurchaseFailed kinds)
      │         │           ├─> 4.13 (needs 4.7 price, 4.8 PayConfirmed + unlock rows; full only after 4.11)
      │         │           └─> 4.15 (needs 4.7 paid; reducer grant rows exist)
      │         └─> 4.5 ──> 4.6
4.4 ──────────────┘ (4.5 and 4.6 need 4.4's pending changes and the settings repository)
4.10 ──> 4.16 ──> 4.17
everything ──> 4.18
```

- **Hard edges:**
  - 4.2 before 4.3, 4.7, 4.8 and every money UI.
  - 4.3 before 4.5, 4.6, 4.7 and 4.10 (`BackgroundWork`).
  - 4.4 before 4.5 and 4.6.
  - 4.8 before 4.10.
  - 4.9 and 4.10 before 4.11.
  - 4.11 before 4.12 and 4.14.
  - 4.10 before 4.16.
- **Soft edges (build on fakes):**
  - 4.9 needs nothing merged (pure).
  - 4.13 can start once 4.8 is merged, if 4.8 carries the unlock rows and the `DeviceUnlocker` port.
  - 4.15 can start once 4.7 is merged.

## Two-lane plan

**Before either lane:** merge the open `fix/epic-3-device-check-bugs` branch first. It changes the test alarm's checks (`ConfigResolver.resolveTest`), volume 0 % and the Math default, which overlap 4.4 and the wake UI. Then run the owner question batch (Q1–Q5).

| Order | Lane 1: core and billing pipeline | Lane 2: tool, settings and UI |
|---|---|---|
| 1 | 4.2 Money / FeeLadder | 4.1 play-catalog tool (no app code; can stack freely) |
| 2 | 4.3 Price cache + WorkManager | 4.9 Reconciler (pure core, new files only; stacks on main) |
| 3 | 4.7 snoozeAvailability (**waits for 4.2 + 4.3 merged**) | 4.4 Commitment lock + app.db v9 + settings repository (stacks on main) |
| 4 | 4.8 Intents + runtime v2 (+ unlock rows, `DeviceUnlocker` port) | 4.5 Snooze settings (**waits for 4.2, 4.3, 4.4 merged**) |
| 5 | 4.10 Ledger + runtime v3 + app.db v10 (**waits for 4.9 merged**) | 4.6 Editor ladder and turn-off dialog (stacks on 4.5) |
| 6 | 4.11 Orchestration | 4.15 Snoozed, session line (**waits for 4.7 merged**) |
| 7 | 4.12 Play Billing adapter (**waits for 4.11 merged**) | 4.13 Confirm sheet (**waits for 4.8 merged**) |
| 8 | Integration review, then owner smoke (Session C) | 4.14 Outcomes (**waits for 4.11 merged**) |
| 9 | spare: deferred fixes, review fixes | 4.16 History (**waits for 4.10 merged**), then 4.17 (stacks on 4.16) |

- **Stacking:**
  - These can stack, because their file sets don't overlap: 4.1; 4.9; 4.4 on main; 4.5 → 4.6; 4.16 → 4.17.
  - Wait for a merged, reviewed base (retro action 3: they share the reducer, `WakeActivity`, `RingingMapping` or `SessionJson`): 4.7, 4.8, 4.10, 4.11, 4.12 and the wake-UI stories 4.13, 4.14 and 4.15.
  - Keep one PR per stack, with two reviewers per story.
- **Schema order:**
  - **app.db:** v9 is 4.4 (`pending_change`, `commitment_event`; the epic says "7→8", which is one too low). v10 is 4.10 (`purchase_record`; the epic says "8→9").
  - If 4.10 is ready before 4.4, it takes v9 and 4.4 rebases to v10. The rule is the next version at merge time, with the exported schema and a migration test each time.
  - **runtime.db:** v2 is 4.8 (`purchase_intent`) and v3 is 4.10 (`grant_ledger`). Both are in Lane 1, so they come in order.
  - **DataStore:** 4.3 adds the `price_cache` file, excluded from backup. 4.4 adds keys to the settings DataStore (global settings, global pending changes).
  - **Install id (4.8):** keep it out of backup. Use its own excluded file, because the settings DataStore is backed up and device-transferred.
  - Update `BackupRulesCoverageTest` in each story.
- **New dependencies (allowlist entries go in the same change):**
  - **4.3: WorkManager 2.11.0** (`androidx.work:work-runtime(-ktx)`). Its transitive dependencies include androidx.room 2.x and sqlite (different coordinates from `androidx.room3`) and `androidx.startup`. The permissions it merges (WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE) are already allowlisted. Remove the default initializer.
  - **4.12: Play Billing 9.1.0:**
    - Coordinates: `billing` and `billing-ktx`, plus transitive `play-services-base`, `-basement`, `-tasks`, **`play-services-location` and `play-services-places-placereport`** (seen on the S1 branch's allowlist), and `kotlinx-coroutines-play-services`.
    - Check that the merged manifest adds no new permission.
    - Licences: Play Billing ships under Google's Android SDK / Play terms (proprietary; list it in the open-source notices with Epic 5/8). WorkManager and the tool's API clients are Apache-2.0.
  - **4.1:** tool-only dependencies (Play Developer API client, google-auth). They are not in the app's runtime classpath, so the app allowlist does not apply. Pin a client revision that has `monetization.onetimeproducts` and `convertRegionPrices`.
- **Integration reviews (retro action 1):**
  1. After 4.7 + 4.15 + 4.13, on `WakeActivity`/`RingingMapping`: every button variant, sheet and message on both Ringing and Check.
  2. When 4.12 lands on 4.11 + 4.13/4.14: run the full Payment outcomes table end to end with `FakeBilling` in Robolectric, and check that "I'm up" is reachable in every state.
  3. After 4.10, the schema merge: app.db v9/v10, `BackupRulesCoverageTest`, golden `SessionJson` fixtures.
  4. A final pass before the 4.18 build: grep for token logging, check that the release build has no FakeBilling toggle, and check the deferred-work items below.
- **CI:** prefer Robolectric with a fake `BillingClientFacade`. The ATD image has no Play Store, so there are no GMD billing tests.

## Deferred work assigned to Epic 4

- **4.7 / 4.11:**
  - Price through `FeeLadder(baseFeeTier, snoozesGranted + 1)` with a reducer-level test.
  - Validate `ReuseAccepted` against the offered product. The Epic 1 reducer accepts any `ReuseAccepted` outside test mode.
- **4.8 or 4.11 (sprint-change-proposal 2026-10-01: "a small story at the start of Epic 4"):** the S1 unlock rows (Unlocking sub-state, `UnlockSucceeded`/`UnlockFailed`), plus updating the AD-2 table and the FR-RNG-3 wording. This is Q1.
- **4.10:** consume retry. S1 saw `SERVICE_UNAVAILABLE` on consume.
- **4.11:**
  - Never block the session on a billing result. Offline, the Play sheet has no timeout (S1 N1u).
  - A decline arrives as `BILLING_UNAVAILABLE`.
  - Call `reassertRingVolume()` on every outcome that returns to ringing.
- **4.13 / 4.18:** the EU "Review and agree" screen appears before every purchase on the owner's Finnish account, which adds a tap.
- **4.18:**
  - Re-check S1 on a Pixel and a Samsung.
  - Re-check the slow-card anomaly (a purchase gone from the query after about 5 min). This is probably the license-tester acknowledgement window, which is shortened to minutes, so the stranded-reuse item (4.18 #8) must be run within minutes of the stranding.
- **4.6:** log `AlarmDeleted` inside `DeleteAlarm`, so the commitment-lock path can't skip it.
- **First wake-snooze story (4.7):** decide whether the Fallback check picker gets the `button-snooze` footer (Q2).

## Owner actions (grouped into sessions)

**Session A: desk, about 30 min, at the start of Epic 4 (blocks nothing in code; needed by the 4.1 live run)**

1. Answer Q1–Q5 below, plus the Epic 3 "default taken" batch (Word heading, numbered fallback Memory, stepper range, 200 % grace header; retro action 4).
2. Play Console → Settings → **Payments profile** is active, and One-time products is available for the app.
3. **Service account for `tools/play-catalog`:**
   - Confirm that it exists, with the Google Play Android Developer API enabled.
   - In Play Console → Users and permissions, grant it **"Manage store presence"** (create and edit in-app products and prices) and "View app information" for Yawn & Pawn only. The release permissions from `docs/ci-release.md` are not enough.
   - Keep the JSON key outside the repo. The tool reads only `PLAY_SERVICE_ACCOUNT_JSON` or `-Pcredentials=`.
4. **License testing:** the owner's account plus at least one second Google account, both `RESPOND_NORMALLY` (a Story 1.4 AC, so confirm it). Note that the owner's account is Finnish (EUR), which already covers 4.18 #15 (non-USD). A US account is needed for the tax-note device check (#2). Otherwise the Robolectric test covers it (waive).
5. Deactivate `spike_s1_test` (S1 follow-up; the tool will leave it alone).

**Session B: desk, about 15 min, as soon as 4.1 merges (needed hours before any real-price test; new products can take time to reach Billing)**

6. Run `./gradlew playCatalog -Pmode=dry-run`, then `-Pmode=apply`. Check the 50 active products in Monetize › One-time products, paste both outputs into `docs/decisions/play-catalog-run.md`, then run a second dry run and confirm "0 changes". (4.1 human-verify; **not deferrable**: 4.12's smoke and 4.18 need live products.)

**Session C: phone, about 45 min, right after 4.12 merges (retro action 5: plan the payment session early; deferrable to 4.18, but not recommended)**

7. Decide the build path:
   - Sideload the debug build (same `applicationId`, signed in as a license tester; verify that Play accepts test purchases from it). This keeps the fire-now hook.
   - Or upload **0.1.2 (versionCode ≥ 102)** to the internal track. This needs the GitHub release secrets in `docs/ci-release.md` and Play App Signing. A release build has no debug hook, so set real alarms 1–2 min ahead.
   - Story 4.18 says "debug build from the internal track", which Play rejects (debuggable).
8. Smoke the 4.18 items #1, #3, #5 and #6 on the Oppo:
   - Local price shown.
   - Locked: PIN, then the Play sheet.
   - A test purchase snoozes and re-rings.
   - Cancel and airplane mode say "No charge.".
   - Failures become bug stories while Lane 2 is still finishing.

**Session D: phone, 2–3 h, the final 4.18 session (deferrable items marked)**

9. The full 17-item checklist on the Oppo A96:
   - Test alarms at the lowest volume.
   - For #9, use a plain process kill, not Oppo's "Close app" or force-stop (retro action 3). Force-stop also cancels the session slot.
   - Run #8 within minutes of #7.
10. **Deferrable to v2 (owner's call, as in Epics 2–3):**
    - The Pixel, Samsung, Xiaomi and budget-device items (#1, #3, #12; S1 re-check).
    - The tax note on a US account (#2).
    - Both waits in #12 (lock at 23:40 and the next day) can be run with the debug clock or waived.

**Later (not Epic 4 code):** replace the placeholder support email in `config/app-links.properties` before launch.

## Risks

- **Alarm safety: payment must never block stopping the alarm.**
  - **I'm up stays reachable:** "I'm up" stays enabled in every Snooze variant. The sheet's "I'll get up", Back and swipe always close it with no intent written. `ImUpTapped` must work while `paying` or Unlocking is set.
  - **Sound and timers keep running:** while the Play sheet or the keyguard is up, `WakeActivity` is stopped, but the sound, slot heartbeat, grace and 30-min deadline keep running in `WakeService`.
  - **Lost callbacks:** a lost `requestDismissKeyguard` callback (activity recreated, wrong PIN gives no callback) must not leave the sheet stuck in "Unlock to pay". On resume with the keyguard still locked, fall back to Ringing ("Phone still locked. No charge.").
  - **Volume:** the volume keys can lower the alarm under Play's sheet (S1), so re-assert the volume on return.
- **Late or missing results:**
  - Offline, Play gives no result until the sheet closes, so nothing may wait on `launch()`, which must run outside the engine Mutex (see `SessionPorts.kt`).
  - `paying` is cleared by any result or by a restore, and a restore never relaunches billing.
- **Offline and stale prices:**
  - Snooze is unavailable offline. `NET_CAPABILITY_VALIDATED` can disagree with Play (captive portals), so a real launch may still fail as Offline.
  - With no cache, it shows "Prices not loaded yet". A cached price can be stale, so Play's sheet shows the real price, and the confirm sheet re-arms its 500 ms guard when the displayed price changes.
- **Pending purchases:**
  - A pending purchase never grants.
  - Approved mid-check: snooze wins and progress is discarded.
  - Approved after the session ends or while Snoozed: stranded, never consumed, auto-refunded (3 days in production, minutes for license testers).
  - Intents are kept 7 days to price a late completion.
- **Refunds and stranded tokens:**
  - Consuming a stranded token keeps money for nothing. The reconciler is the only reader of purchase state, and consume runs only from ledger rows (tests scan for other callers).
  - Self-requested refunds (48 h) are undetectable without a backend. History shows the charge, and refund-and-repeat abuse is accepted (PRD Q13).
- **Double grant or double charge across crashes:** the transition and ledger row commit in one `runtime.db` transaction. Restore replays from the ledger. `ITEM_ALREADY_OWNED` is handled by consume-then-retry once or by the reuse offer.
- **Privacy:**
  - Tokens are never logged, `app.db` holds only token hashes, and the email body is unit-tested.
  - The install id must not be restored onto another phone (backup rules).
  - Billing 9.1 pulls in the location and places libraries (no permission is declared); record them for the Epic 8 Data safety form (Q3).
- **Parallel lanes:**
  - Schema numbers can collide (app.db v9 and v10).
  - `ConfigResolver` changes (4.4) may conflict with the Epic 3 bugfix branch.
  - 4.2's FeeLadder interface change touches the Epic 1–3 tests that use `TierFeeLadder`.
- **Play policy:** the price is always shown before purchase, the free path is first and works offline, and there is no pre-selected Pay. The EU withdrawal consent adds a tap per purchase (no build change; PRD Q17 stays with the terms author).

## Open questions for the owner (only ones that change what gets built)

- **Q1. The unlock step in the state machine.**
  - **Recommended default:** add S1's rows to AD-2 and the reducer: `PayConfirmed` (locked) → Unlocking (`RequestKeyguardDismiss`); `UnlockSucceeded` → Paying (`LaunchBilling`); `UnlockFailed` → Ringing. Ship them in 4.8, with the `DeviceUnlocker` port and fake, and update `docs/architecture.md` AD-2. The sheet's "Unlock to pay" state then survives activity recreation and is table-tested.
  - **Alternative:** keep the unlock inside the billing adapter and report a cancel as `PurchaseFailed(UnlockFailed)`.
- **Q2. A Snooze footer on the Fallback check picker?**
  - **Recommended default:** no. Keep the approved baseline. "Back to check" is one tap to the footer, and the picker stays the accessible path.
- **Q3. Play Billing 9.1's transitive `play-services-location` and `places-placereport`.**
  - **Recommended default:** allowlist them, and record them for Data safety (no location permission, no location API calls).
  - **Alternative:** exclude them in Gradle (risk: runtime failures inside Billing).
- **Q4. PRD Q16: deleting and recreating an alarm bypasses the commitment lock.**
  - **Recommended default:** accept it as a nudge and build nothing extra. The delete inside the window is confirmed and logged (4.6).
- **Q5. Purchase history layout.**
  - **Recommended default:** follow the approved preview (month cards, newest first) rather than 4.16's flat list. The rows and statuses stay as specified.
