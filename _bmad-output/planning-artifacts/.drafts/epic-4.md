## Epic 4: Pay to snooze

Snoozing costs real money through Google Play. The Nth snooze in a session costs B × N (USD tiers shown as Play's local price), with a $50 per-snooze cap, a max-snoozes limit and a commitment lock that delays weakening changes made within 8 h of an alarm. The confirm sheet is honest and slow to mis-tap, every payment outcome ends in plain copy ("No charge." whenever nothing was charged), a purchase is granted only when it is `PURCHASED` and linked to the current session, stranded payments are never consumed (Google refunds them) or are reused with consent, and every charge appears in purchase history. Under the hood: `tools/play-catalog` creates the 50 products first; `FeeLadder`, `snoozeAvailability`, `PurchaseReconciler` and the grant ledger are pure core with table-driven tests; the Play Billing 9.1 adapter then replaces the Epic 1 `FakeBilling`/`UnavailableBilling` binding; the wake UI follows the EXPERIENCE.md payment outcomes table.

This epic builds on Epic 1 (`SessionState`, the AD-2 events, `SessionEngine`, `SnoozeAvailabilityPolicy`, `FeeLadder` interface, `FakeBilling`, `FakePurchaseIntentStore`, `SessionConfig`/`ConfigResolver`/`GlobalSettings`, the Play Console record and license testers from Story 1.4, and the unlock decision in `docs/spikes/S1.md` from Story 1.5), Epic 2 (session slot, `UserUnlocked`, `beforeFirstUnlock`, session lock UI) and Epic 3 (real checks via the AD-9 plugin contract, `CheckRun`, the check footer with `button-snooze`, grace window, fallback check, the basic Success screen). It does not redefine them.

Every UI story carries the two standing acceptance criteria from Epic 1: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass `CopyRulesTest` (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `[ASSUMPTION: add to EXPERIENCE.md Key strings]` in the story and listed for the owner.

### Story 4.1: Create the 50 snooze products with tools/play-catalog

As the owner,
I want one reviewed script that creates and updates the 50 consumable snooze products in Play Console,
So that prices are never typed by hand and the catalogue always matches the fee ladder.
**Refs:** PRD §6.3 (products), NFR-5, AD-7, AD-14 · **Priority:** Must · **Verify:** auto, plus (human-verify) first live run

**Acceptance Criteria:**

**Given** `tools/play-catalog`, a JVM tool wired the same way as `tools/tokens` (Story 1.3)
**When** `./gradlew playCatalog -Pmode=dry-run` runs
**Then** it builds the desired catalogue in memory: exactly 50 one-time products `snooze_usd_01` … `snooze_usd_50`, product `NN` priced at NN.00 USD as the base price, regional prices produced by the Play Developer API `convertRegionPrices` for every region Play offers, status active, one default buy purchase option usable by Play Billing Library 9 (legacy-compatible), and pending purchases allowed as PBL 8+ requires for one-time products
**And** it lists the app's existing one-time products through the Play Developer API (`monetization.onetimeproducts`, package name from `docs/decisions/package-id.md`), prints a plan of create / update / unchanged per product id, and makes no write call (asserted against a fake API client)
**And** listing title "Snooze" and description "One snooze for your alarm." are set per product [ASSUMPTION: add to EXPERIENCE.md Key strings]

**Given** `./gradlew playCatalog -Pmode=apply`
**When** it runs against the fake API client with a partially existing catalogue (10 products, 2 with a wrong price, 1 inactive)
**Then** it creates the 40 missing products, patches the 2 wrong prices and reactivates the inactive one, and a second run immediately after reports "0 changes" and issues no write call (idempotent)
**And** products outside the `snooze_usd_NN` pattern (for example `spike_s1_test` from Story 1.5) are reported as "unmanaged" and never changed or deleted
**And** the tool never deletes a product, and exits non-zero with the API error message on any failed call without retrying writes blindly

**Given** credentials
**When** the tool starts
**Then** it reads the service-account JSON only from the `PLAY_SERVICE_ACCOUNT_JSON` environment variable or a path given with `-Pcredentials=`, never from the repo, and fails with a clear message when neither is present (unit-tested)
**And** unit tests cover: id list and USD prices for all 50, plan diff (create, price change, reactivate, unchanged, unmanaged), dry-run makes no writes, apply is idempotent, missing credentials

**Given** the owner's Play Console app record (Story 1.4)
**When** the owner runs `apply` once for real (human-verify)
**Then** Monetize > Products > One-time products shows 50 active products `snooze_usd_01` … `snooze_usd_50` with local prices, the dry-run output and apply output are pasted into `docs/decisions/play-catalog-run.md` with date, and a second dry run shows "0 changes"
**And** any catalogue change after this goes only through this tool in a reviewed commit (documented in `tools/play-catalog/README.md`)
**And** `./gradlew qualityGate` passes

### Story 4.2: Money, MoneyFormatter and the FeeLadder in core

As a user,
I want each snooze to cost exactly base fee × snooze number, never more than $50, always shown in my currency,
So that the price is predictable and rises fairly.
**Refs:** FR-RNG-6, FR-RNG-7, NFR-10, NFR-11, AD-7, AD-8; PRD §6.2 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.billing`
**When** `Money` is added
**Then** `Money(micros: Long, currency: String)` (ISO 4217 code, validated as 3 upper-case letters) is the only money type, `plus` throws nothing and returns `DomainError.CurrencyMismatch` for different currencies, and `totalsByCurrency(List<Money>)` returns one `Money` per currency in first-seen order
**And** a detekt rule in `:detekt-rules` fails on `Double` or `Float` properties or parameters whose name contains `price`, `amount`, `fee` or `paid` in `:core` and `:data` (rule unit-tested with a violating and a compliant snippet)

**Given** the `MoneyFormatter` port (format a `Money` for display) with `FakeMoneyFormatter` in `:testing`
**When** `AndroidMoneyFormatter` formats with the device locale
**Then** it uses `NumberFormat.getCurrencyInstance(locale)` with the currency's own fraction digits, and Robolectric tests assert USD 1_000_000 micros in en-US → "$1.00", EUR 1_000_000 in de-DE → "1,00 €", JPY 150_000_000 in ja-JP → "￥150", VND 25_000_000_000 in vi-VN → "25.000 ₫", and a mixed list renders one string per currency joined with " + " [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** UI code never formats money in any other way (the Epic 1 `CopyRulesTest` still rejects hard-coded currency symbols in resources)

**Given** the real `FeeLadder` replacing the Epic 1 placeholder behind the same interface
**When** `FeeLadder.productFor(baseFeeTier, snoozeNumber)` is called
**Then** it returns `snooze_usd_NN` with NN = baseFeeTier × snoozeNumber zero-padded to 2 digits when 1 ≤ NN ≤ 50, and `PriceCapReached` when NN > 50 (the $50 cap)
**And** it rejects baseFeeTier outside 1–10 or snoozeNumber < 1 with `DomainError.InvalidFee`
**And** a table test asserts `productFor` exists for every reachable combination (B 1–10 × N 1–5, 31 distinct products) and that every returned id is in the 50-id list used by `tools/play-catalog` (the list lives in one shared file, `config/snooze-products.txt`, read by both tests)
**And** examples are asserted: B = 1 → 01, 02, 03, 04, 05; B = 3 → 03, 06, 09, 12, 15; B = 10, N = 5 → 50; B = 10, N = 6 → `PriceCapReached`
**And** Kover shows `core.billing` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 4.3: Cache Play prices for offline display

As a user,
I want to see the snooze price in my currency even when I'm offline,
So that the fee picker and ringing screen never show a blank or wrong price.
**Refs:** FR-RNG-1, FR-RNG-7, NFR-3, NFR-8, AD-7, AD-8, AD-15, AD-17; PRD §6.2 currency rule · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a `PriceCatalog` port in core (`observe(): Flow<PriceCatalogSnapshot>`, `refresh(): Outcome<Unit, DomainError>`) and `FakePriceCatalog` in `:testing`
**When** a snapshot is read
**Then** each entry holds `productId`, Play's `formattedPrice` string, `Money(priceAmountMicros, priceCurrencyCode)` and `fetchedAt`, and `snapshot.priceFor(productId)` returns the entry or `null`
**And** `refresh()` delegates to a `ProductDetailsSource` port (`FakeProductDetailsSource` programmable success / partial / failure; the Play implementation arrives in Story 4.12), stores the full result atomically, keeps the previous snapshot on failure, and ignores products Play reports as unfetched

**Given** `:data`
**When** the cache store is implemented
**Then** it is a separate device-protected DataStore file `price_cache` (created with `PreferenceDataStoreFactory.createWithPath`) holding the serialized snapshot, and the backup rules exclude it (a device restored in another country must not show old currency) with the Robolectric backup-XML test updated
**And** tests cover round-trip, partial refresh keeps missing entries from the previous snapshot, and a corrupt file resets to empty without crashing

**Given** the `BackgroundWork` port (AD-17) with `FakeBackgroundWork`, added here as its first user
**When** `AndroidBackgroundWork` (WorkManager 2.11) is added
**Then** it enqueues unique work by name, WorkManager is initialised on demand only after user unlock (default initializer removed from the merged manifest, custom `Configuration.Provider`), and WorkManager coordinates are added to `config/dependency-allowlist.txt` in the same change
**And** a unique "price-refresh" job with a network constraint runs `PriceCatalog.refresh()` on app start and once a day; nothing on the wake path waits for it

**Given** a session starts (`AlarmFired`) while the device is online and unlocked
**When** the wake runtime starts
**Then** it triggers one non-blocking `PriceCatalog.refresh()` (PRD: refreshed at each session start), and the wake UI renders from the cached snapshot without waiting (test asserts first frame before refresh completes)
**And** `./gradlew qualityGate` passes

### Story 4.4: Commitment lock and pending changes in core

As a user,
I want weakening changes I make late at night to wait until after my next alarm, while making things harder applies at once,
So that my sleepy self can't quietly undo the plan my daytime self made.
**Refs:** FR-SET-1, FR-ALM-2, NFR-11, AD-6, AD-16; PRD §6.2 commitment lock, Q4, Q15, Q16 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.config`
**When** `LockWindow` is evaluated
**Then** an enabled alarm is "in the lock window" when 0 < (its next occurrence − now) ≤ 8 h, computed with the Story 1.6 `nextOccurrence` on instants (not local times), and tests cover 7 h 59 min 59 s (inside), exactly 8 h 00 min 00 s (inside), 8 h 00 min 01 s (outside), a DST-gap night where the local-time difference and the instant difference disagree (instant wins), and a disabled alarm (never locks)
**And** for a global setting the window applies when any enabled alarm is in its window, and `effectiveAfterOccurrence` is the latest such occurrence at save time (`alarmId`, `scheduledAt`) [ASSUMPTION: owner to confirm "after that alarm" means after the last alarm inside the 8 h window]; for an alarm setting only that alarm counts

**Given** the weakening rules
**When** `classify(field, oldEffective, new)` runs
**Then** lower base fee, higher max snoozes and longer grace window are Weakening; the opposite directions are Strengthening; equal is NoChange
**And** a check-plan change is Strengthening only when the new plan uses mode All, contains every old check type, and each type's difficulty and count are ≥ the old ones; any other change (fewer types, Random mode, lower difficulty or count, a type swapped) is Weakening [ASSUMPTION: owner to confirm the check-plan rule]
**And** snooze length, sound, volume, label and repeat days are not locked fields (PRD lists only fee, checks, grace and snoozes)

**Given** a `PendingChange(field, value, effectiveAfterOccurrence)` store behind a `PendingChangeRepository` port (`FakePendingChangeRepository` in `:testing`)
**When** use cases `SetBaseFee`, `SetMaxSnoozes`, and the alarm-field path of `SaveAlarm` save a change
**Then** a Strengthening change or any change outside the lock window applies immediately and clears a pending change for that field; a Weakening change inside the window is stored as a `PendingChange` and the use case returns `Saved(pendingUntil = occurrence)`
**And** a second change on a field with a pending change is classified against the current effective value (for example effective $3, pending $1, new $2 → still Weakening, pending replaced by $2; new $5 → Strengthening, applied now, pending cleared) (tests)
**And** `SetMaxSnoozes` accepts 1–5 and `SetBaseFee` accepts tiers 1–10, returning `DomainError.InvalidSetting` otherwise

**Given** `ConfigResolver.resolve(alarm, globalSettings, pendingChanges, occurrence, testMode)` (extending the Epic 1 signature)
**When** a session is resolved at `AlarmFired`
**Then** a pending change is ignored for the occurrence it waits for and for any earlier one, and applied for every later occurrence, so the frozen `SessionConfig` never changes mid-session
**And** `PromotePendingChanges` writes a pending value into the live setting and deletes it once now > `effectiveAfterOccurrence.scheduledAt` and no active session exists for that occurrence; it runs on app start, on `Recorded` and in `rescheduleAll()`, so a pending change whose alarm was turned off still takes effect after that time (tests)

**Given** `:data`
**When** storage is added
**Then** alarm pending changes live in a new `app.db` table `pending_change` (`alarm_id`, `field`, `value_json`, `effective_after_alarm_id`, `effective_after_scheduled_at`), added by a migration from the previous `app.db` version with the exported schema and a migration test preserving existing rows, and global pending changes live in the settings DataStore (AD-6: no settings copy in `app.db`)
**And** a new `app.db` table `commitment_event` (`id`, `alarm_id`, `occurrence_at`, `action` Disabled/Deleted, `at`) is written by use case `RecordCommitmentEvent` (read later by Day detail in Epic 6; Q15 stays open for how it shows)
**And** Kover shows `core.config` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 4.5: Snooze settings: base fee and max snoozes

As a user,
I want to set my base fee from $1 to $10 in my local currency and how many snoozes a morning allows,
So that snoozing costs what I decided it should.
**Refs:** FR-SET-1, FR-MSG-4, NFR-3, NFR-9, NFR-10, AD-8, AD-11, AD-16, UX-DR35, UX-DR39, UX-DR44, UX-DR51, UX-DR59, UX-DR61, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR91 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the app root
**When** this story lands
**Then** the `nav-bar` (UX-DR44) shows Alarms, Progress and Settings (created here if no earlier story added it; Progress shows only "Your first morning shows up here." until Epic 6), it is hidden during the session lock (Epic 2), and Settings is a new `Route` with a "Snooze" section [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** Settings rows are `settings-row`s (56 dp) and are unreachable while a session is active (existing Epic 2 lock, re-asserted by a test)

**Given** the Snooze section
**When** it renders with a loaded price cache
**Then** a "Base fee" `stepper` [ASSUMPTION: add to EXPERIENCE.md Key strings] shows the Play `formattedPrice` of `snooze_usd_0B` for the current tier B in `display` with tabular figures, the − button is disabled at tier 1 and + at tier 10, long-press repeats, and a ladder preview line shows "Snooze 1: {price1} · 2: {price2} · 3: {price3}" for the first three snoozes (or fewer if max snoozes < 3) [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** the `note-inline` "You can raise it anytime. Lowering it waits until after your next alarm." is always shown under the stepper
**And** a "Max snoozes per session" `stepper` [ASSUMPTION: add to EXPERIENCE.md Key strings] runs 1–5 (default 5)

**Given** no cached price for a tier (never online)
**When** the section renders
**Then** the stepper and ladder show USD amounts formatted by `MoneyFormatter` from `Money(B × 1_000_000, "USD")` with the `note-inline` "Approximate. Your local price shows when you're online.", and the screen stays fully usable offline

**Given** an enabled alarm at 7:30 tomorrow and now 23:40
**When** the user lowers the base fee or raises max snoozes
**Then** the new value is shown as saved, the `note-inline` "Saved. Takes effect after tomorrow's {time} alarm." appears with {time} formatted per the system 12/24 h setting, and when the waited-for occurrence is later today the note reads "Saved. Takes effect after today's {time} alarm." [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** raising the fee or lowering max snoozes applies at once with no note, and a change with no alarm inside 8 h applies at once
**And** (UX note) the F6 flow's times (23:10 → 7:30 is 8 h 20 min, outside the window) are not used in tests; tests use 23:40

**Given** the Snooze section screens
**When** Roborazzi and semantic tests run
**Then** screenshots exist for loaded prices, approximate prices, lock note after lowering, and max snoozes at 1 and 5 in Light and Dark and at 200% font scale, targets are ≥ 48 dp, and the stepper announces its value with the localized price on change
**And** ViewModel tests with `FakePriceCatalog`, `FakePendingChangeRepository` and `FakeClock` cover immediate, pending, offline and bounds
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.6: Fee ladder, lock notes and turn-off confirmation in the editor and on Home

As a user,
I want to see what each snooze will cost next to the snooze length, and to be asked before I turn off or weaken an alarm late at night,
So that I know the price before the morning and don't weaken my plan by accident.
**Refs:** FR-SET-1, FR-ALM-1, FR-ALM-2, FR-MSG-4, NFR-9, AD-16, UX-DR31, UX-DR35, UX-DR55, UX-DR56, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR91 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the Alarm editor snooze length `segmented-control`
**When** it renders
**Then** under it a fee ladder line shows "Snooze 1: {price1} · 2: {price2} · 3: {price3}" using the effective base fee (pending changes applied for the alarm's next-but-one occurrence are not shown; the ladder shows what the next morning will charge), the cached Play prices, and up to max snoozes entries, or USD approximations with "Approximate. Your local price shows when you're online." when the cache is empty

**Given** an alarm whose next occurrence is inside the lock window
**When** the user saves a longer grace window or a weaker check plan (Story 4.4 rules)
**Then** `SaveAlarm` stores the other fields immediately and the weakened fields as `PendingChange`s, the editor closes, Home shows a `snackbar` "Saved. Takes effect after tomorrow's {time} alarm." (or the "today's" variant), and when the editor is reopened each pending field shows the same `note-inline` under it with the pending value selected
**And** strengthening changes (shorter grace, harder checks) save with no note and apply to the next occurrence

**Given** an enabled alarm inside the lock window on Home
**When** the user toggles its `switch` off
**Then** a `dialog-confirm` "Turn off your {time} alarm? It rings in {hours} h. This is logged." opens with "Turn off" and "Keep it on" (the default dismiss), {hours} being whole hours rounded down, and under 1 h the body reads "Turn off your {time} alarm? It rings in {minutes} min. This is logged." [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** "Turn off" disables the alarm (cancelling its schedule) and calls `RecordCommitmentEvent(Disabled)`; "Keep it on", Back or tapping outside leaves it enabled; outside the lock window the switch turns off with no dialog (Story 1.9 behaviour)

**Given** a delete inside the lock window
**When** the user confirms the existing "Delete your {time} alarm? This is logged." dialog (Story 1.9)
**Then** `RecordCommitmentEvent(Deleted)` is written in addition to the Story 1.9 log entry, and outside the window no commitment event is written

**Given** the editor and Home states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for the fee ladder (loaded, approximate, max snoozes 2), pending-field note, and both turn-off dialog variants in Light and Dark and at 200% font scale, with targets ≥ 48 dp
**And** ViewModel tests with `FakeClock` cover dialog at 7 h 59 min, no dialog at 8 h 01 min, "Keep it on" default, commitment events written only inside the window
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.7: snoozeAvailability with every reason

As a user,
I want the Snooze button to say exactly why it can't be used right now,
So that I'm never confused and always know "I'm up" is the way out.
**Refs:** FR-RNG-1, FR-RNG-7, FR-RNG-9, FR-ALM-8, FR-ALM-11, NFR-3, NFR-9, NFR-11, AD-2, AD-7, UX-DR13, UX-DR14, UX-DR64, UX-DR78, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the AD-2 session model
**When** this story extends it
**Then** `SessionState` gains three `@Serializable` fields with defaults: `paymentPending: Boolean = false` (set by `PurchasePending`, cleared by a later `PurchaseGranted`), `declinedReuseProduct: String? = null`, and `paid: List<Money> = emptyList()` (appended by `PurchaseGranted`/`ReuseAccepted` for wake-screen display only; history totals always come from purchase records), and a test decodes a `state_json` written before this story into the new model
**And** the AD-2 table is amended with two rows, each with a parameterised test and added to the table-coverage test: `ReuseOffered` sets `paying = null`, and `Ringing, Grace, Loud | ReuseDeclined | | same, declinedReuseProduct = expected product | none` [ASSUMPTION: AD-2 table amendment, raised through `bmad-correct-course` and accepted before this story starts]

**Given** a `Connectivity` port (`observeOnline(): Flow<Boolean>`) with `AndroidConnectivity` (`ConnectivityManager` default network callback with `NET_CAPABILITY_VALIDATED`) and `FakeConnectivity`
**When** the real production `SnoozeAvailabilityPolicy` replaces the Epic 1 one
**Then** the pure function `snoozeAvailability(state, config, env)` with `env = (online, priceSnapshot, strandedProducts, userUnlocked)` returns `Available(productId, formattedPrice, money)` or `Unavailable(reason)` checked in this order: `TestMode`, `BeforeFirstUnlock`, `MaxSnoozesReached` (snoozesGranted ≥ config.maxSnoozes), `PriceCapReached` (`FeeLadder` says so), `PaymentPending`, `EarlierPaymentRefunding(price)` (declinedReuseProduct = expected product and that product is still in `strandedProducts`), `Offline`, `CatalogueNotLoaded` (no cached price for the expected product)
**And** a table test has one row per reason plus overlaps (for example test mode and offline → TestMode; max snoozes and offline → MaxSnoozesReached; offline with no cache → Offline) and the Available case, and `PriceCapReached` is tested with a directly built config (B = 10, max 6) because production limits cannot reach it
**And** a stranded token for the expected product that the user has not declined keeps Snooze Available (the reuse offer happens on "Pay", Story 4.11)

**Given** the ringing screen and the Epic 3 check footer
**When** they render `button-snooze` from the policy
**Then** reasons map to exactly: "Test · no charge"; "Unlock your phone to snooze" (lock icon); "Snooze unavailable: max snoozes reached"; "Snooze unavailable: price cap reached"; "Snooze unavailable: payment pending"; "An earlier {price} payment is being refunded"; "Snooze unavailable: offline"; "Snooze unavailable: prices not loaded yet"; and Available renders "Snooze · {price}" with Play's `formattedPrice`
**And** TalkBack reads "Snooze unavailable, {reason}" for every disabled variant, and "I'm up" stays enabled and visible in every variant (FR-RNG-9)
**And** the wake UI combines `SessionEngine.state` with the env flows, so the button changes in place without leaving the screen when connectivity returns, the cache loads, the phone is unlocked (Epic 2 `UserUnlocked`) or a stranded token clears (test with `FakeConnectivity` toggling)
**And** Roborazzi screenshots cover every variant on Ringing and on the check footer in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.8: Purchase intents, install id and the runtime.db intent table

As a user,
I want every payment attempt saved with its session and price before Google Play opens,
So that a crash in the middle of paying can never lose or double my snooze.
**Refs:** FR-RNG-3, FR-SES-1, NFR-4, NFR-14, AD-2, AD-6, AD-7, AD-8, AD-12; PRD §6.3 linking · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.billing`
**When** `PurchaseIntent` is added
**Then** it holds `intentId` (UUID v4), `sessionId`, `productId`, `snoozeNumber` (= snoozesGranted + 1), `price: Money`, `formattedPrice` and `createdAt`, taken from the `Available` result the confirm sheet showed
**And** the `PurchaseIntentStore` port (Epic 1 `FakePurchaseIntentStore` extended) supports `get(intentId)`, `forSession(sessionId)`, `forProduct(sessionId, productId)` and `purgeOlderThan(instant)`

**Given** `SessionEngine` handling `PayConfirmed` while Available
**When** the transition is committed
**Then** the new state (`paying = intentId`) and the `purchase_intent` row are written in one `runtime.db` transaction through `ActiveSessionStore.commit(state, writes)`, and only after the commit does the "launch billing" one-shot effect run (test: failing commit → no intent row, no launch, previous state kept)
**And** a crash after commit and before launch, then `ProcessRestored`, leaves the intent row, clears `paying`, and never launches billing (extends the Story 1.12 test)
**And** `PayConfirmed` while Unavailable is ignored and logged, and a second `PayConfirmed` while `paying` is set is ignored (no second intent, no second launch)

**Given** `:data`
**When** `runtime.db` migrates to its next version
**Then** it adds `purchase_intent` (`intent_id` PK, `session_id`, `product_id`, `snooze_number`, `price_micros`, `currency`, `formatted_price`, `created_at`) with the exported schema and a migration test keeping an existing `active_session` row
**And** intents older than 7 days are purged on app start (long enough to price a pending purchase that completes after the session) [ASSUMPTION: owner to confirm 7 days]
**And** `runtime.db` stays excluded from backup (NFR-14: pending purchase intents never restored; Robolectric XML test still passes)

**Given** the install id
**When** it is first needed
**Then** `InstallIdProvider` returns a random UUID v4 stored in the device-protected settings DataStore, stable across restarts, never derived from any device or account identifier, and never logged (test on the `Logger` fake)
**And** `./gradlew qualityGate` passes

### Story 4.9: PurchaseReconciler for every recovery case

As a user,
I want every Google Play purchase checked against one set of rules,
So that I get exactly one snooze per charge, and a charge that gave me nothing is refunded instead of kept.
**Refs:** FR-RNG-4, FR-RNG-10, FR-PRG-4, NFR-11, AD-7; PRD §6.3 grant rule and recovery table · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.billing`
**When** the pure `PurchaseReconciler.decide(input)` is added
**Then** `input` holds a `PurchaseSnapshot` (token, productId, state Purchased/Pending, profileId nullable, orderId nullable, purchaseTime), the active session summary (sessionId, state kind, expected next productId, testMode) or none, the grant-ledger lookup for the token, the purchase-record lookup by token hash, and the context (`Update`, `Recovery`, `PreLaunch(productId)`, `AlreadyOwned(productId)`)
**And** it returns exactly one of `Grant`, `ConsumeOnly(retryLaunch: Boolean)`, `LeaveForAutoRefund`, `OfferReuse`, `Ignore(reason)`

**Given** a table-driven test named from PRD §6.3
**When** each case runs
**Then** these rows pass, one test each:
1. `PURCHASED`, profileId = active session, session in Ringing, Grace or Loud, not in ledger, product = expected next → `Grant`
2. `PURCHASED`, profileId = active session, product ≠ expected next → `LeaveForAutoRefund`
3. `PURCHASED`, profileId = an ended session → `LeaveForAutoRefund`
4. `PURCHASED`, profileId = another session id → `LeaveForAutoRefund`
5. `PURCHASED`, profileId = active session that is currently `Snoozed` (purchase while snoozed) → `LeaveForAutoRefund`
6. `PURCHASED`, profileId missing (promo code, OQ-1) → `LeaveForAutoRefund`
7. `PURCHASED`, any profileId, ledger status granted (not consumed) → `ConsumeOnly(retryLaunch = false)` (crash between grant and consume)
8. `PENDING`, any → `Ignore(Pending)`
9. duplicate delivery: token already in the ledger as granted, delivered again with profileId = active session → `ConsumeOnly`, never a second `Grant`
10. duplicate delivery after consume: token's purchase record status consumed or reused → `Ignore(AlreadyHandled)`
11. `PreLaunch(P)` or `AlreadyOwned(P)`: owned `PURCHASED` token for P, not in ledger, record absent or stranded, active session not Snoozed and not test mode, P = expected next → `OfferReuse`
12. `AlreadyOwned(P)`: token for P in ledger as granted → `ConsumeOnly(retryLaunch = true)`
13. active session in test mode, any `PURCHASED` token for it → `LeaveForAutoRefund` (never grant in test mode)
14. no active session, `PURCHASED` not in ledger → `LeaveForAutoRefund`
**And** sequence tests pass: pending then `PURCHASED` for the active session in Grace or Loud (mid-check) → `Grant`; pending then `PURCHASED` after the session ended → `LeaveForAutoRefund`; lost callback found by a `Recovery` query for the active session → `Grant`; the same token decided twice across a simulated restart → one `Grant` total
**And** the reconciler is the only place these rules live (a unit test scans `:core` and `:androidApp` for other readers of `Purchase.purchaseState` outside the adapter mapping)
**And** Kover shows `core.billing` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 4.10: Grant ledger, purchase records and consume with retry

As a user,
I want a granted snooze to be recorded and my payment consumed exactly once, even if the app dies in between,
So that I'm never charged twice and never lose a snooze I paid for.
**Refs:** FR-RNG-4, FR-RNG-6, FR-PRG-1, FR-PRG-4, FR-SES-1, NFR-2, NFR-4, NFR-14, AD-2, AD-6, AD-7, AD-8, AD-17, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:data`
**When** storage is added
**Then** `runtime.db` migrates to its next version adding `grant_ledger` (`token` PK, `session_id`, `product_id`, `status` granted/consumed, `created_at`), and `app.db` migrates to its next version adding `purchase_record` (`token_hash` PK = SHA-256 hex of the token, `order_id` nullable, `product_id`, `session_id` nullable, `alarm_id` nullable, `snooze_number` nullable, `price_micros`, `currency`, `purchased_at`, `status` granted/consumed/stranded/reused, `updated_at`), both with exported schemas and migration tests
**And** raw purchase tokens are stored only in `runtime.db` (not backed up); `app.db` (backed up) holds only the token hash [ASSUMPTION: token hash as key instead of the raw token, owner to confirm against AD-7 wording]

**Given** `SessionEngine` committing `PurchaseGranted(token, productId, money)` (reconciler said `Grant`)
**When** the transition commits
**Then** the Snoozed state and a `grant_ledger` row (status granted) are written in the same `runtime.db` transaction, and only then the effects run in this order: `PurchaseLedger.upsert(record, status granted)` → `Billing.consume(token)` → on success mark record consumed → delete the ledger row
**And** `PurchaseLedger` (core, via a `PurchaseRecordRepository` port with fake) is the only writer of `purchase_record` (a unit test scans `:core` and `:data` for other writers of the DAO), and upserts are idempotent by token hash
**And** the record's `session_id`, `alarm_id`, `snooze_number` and price come from the matching `PurchaseIntent`, or from the price snapshot when no intent exists

**Given** crash tests with fakes that throw at each step
**When** the process "dies" after the commit, after the record upsert, or after consume but before marking consumed, and restores
**Then** restore replays from the ledger: the record is upserted (no duplicate), consume is called again (Play treats a repeat consume of a consumed token as already consumed, mapped to success), the record ends consumed, the ledger row is gone, `snoozesGranted` was incremented exactly once and the session stays Snoozed
**And** `ConsumeOnly` decisions from the reconciler go through the same consume path

**Given** consume fails (offline, service error)
**When** the failure is returned
**Then** the ledger row stays granted, the record stays granted, and a unique "consume-retry" job is enqueued through `BackgroundWork` with a network constraint and exponential backoff (30 s initial), and retry also runs on app start and on resume, until success (test with `FakeBilling` failing twice then succeeding)
**And** the grant never waits for consume: the snooze starts at commit (test asserts Snoozed before consume completes)

**Given** a `LeaveForAutoRefund` decision
**When** it is handled
**Then** `PurchaseLedger` upserts a record with status stranded (never consumed, no ledger row), and a later reuse (Story 4.11) changes the same record to status reused with the current session, alarm and snooze number
**And** `./gradlew qualityGate` passes

### Story 4.11: Billing orchestration: launch, recovery, ITEM_ALREADY_OWNED and stranded reuse

As a user,
I want the app to recover lost payment results and offer an earlier unused payment instead of charging me again,
So that a bad connection or a crash never costs me an extra charge.
**Refs:** FR-RNG-3, FR-RNG-4, FR-RNG-10, FR-PRG-4, NFR-11, AD-2, AD-7, AD-12; PRD §6.3 recovery, stranded reuse, `ITEM_ALREADY_OWNED` · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the `Billing` port (Epic 1) extended to `launch(intent, installId): Outcome<LaunchResult>`, `queryPurchases(): Outcome<List<PurchaseSnapshot>>`, `consume(token)`, `purchaseUpdates: Flow<PurchaseUpdate>` and `FakeBilling` programmable for each (including `ItemAlreadyOwned`, `Pending`, lost callback, duplicate delivery)
**When** `PurchaseCoordinator` (core) executes the "launch billing" effect for intent I with product P
**Then** it first consumes any granted-but-unconsumed ledger token for P, then queries owned purchases; an owned token for P that the reconciler marks `OfferReuse` dispatches `ReuseOffered(token)` instead of launching; otherwise it launches with `obfuscatedProfileId = sessionId` and `obfuscatedAccountId = installId`
**And** a launch result of `ItemAlreadyOwned` re-queries and applies the reconciler with context `AlreadyOwned(P)`: granted token → consume then retry the launch once (a second `ItemAlreadyOwned` becomes `PurchaseFailed(Error)`); stranded token → `ReuseOffered(token)`

**Given** purchase updates and launch results
**When** they arrive
**Then** each purchase goes through `PurchaseReconciler` and the coordinator dispatches: `Grant` → `PurchaseGranted`; `ConsumeOnly` → consume path; `LeaveForAutoRefund` → stranded record; a pending purchase for the active session → `PurchasePending`; user cancel → `PurchaseCancelled`; errors → `PurchaseFailed(kind)` with kind Offline, UnlockFailed or Error
**And** a `PurchaseGranted` that arrives while the session is in Grace or Loud (pending cleared mid-check) moves to Snoozed and discards check progress per AD-2 (engine test)

**Given** recovery triggers
**When** the app starts, `MainActivity` resumes, or `WakeActivity` opens
**Then** the coordinator runs `queryPurchases()` and reconciles every result with context `Recovery`, never before first unlock (AD-15), and the set of stranded product ids feeds `snoozeAvailability`'s env
**And** tests cover: lost callback → granted on `WakeActivity` open; stranded for an ended session → record stranded, not consumed; crash between grant and consume → consumed on start; duplicate delivery → one grant

**Given** the reuse flow
**When** `ReuseAccepted(token)` is dispatched
**Then** it commits Snoozed and a ledger row for the stranded token in one transaction exactly like `PurchaseGranted`, the record changes from stranded to reused with the current session, and the token is consumed through the Story 4.10 path
**And** `ReuseDeclined` sets `declinedReuseProduct` so Snooze shows "An earlier {price} payment is being refunded" until a later recovery no longer finds that token
**And** an engine test runs PRD UJ4 end to end with fakes: payment error → session completes → token becomes `PURCHASED` later → recovery marks it stranded → next morning `Pay` → `ReuseOffered` → `ReuseAccepted` → Snoozed, with exactly one record (status reused) and one consume call
**And** `./gradlew qualityGate` passes

### Story 4.12: Play Billing 9.1 adapter replacing the fake

As a user,
I want Snooze to use real Google Play payments, unlocking my phone first when Play needs it,
So that I can actually pay for a snooze from the ringing screen.
**Refs:** FR-RNG-3, FR-RNG-4, FR-RNG-5, NFR-5, NFR-6, AD-5, AD-7, AD-12, AD-13, AD-15; PRD §10 S1 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 4.18

**Acceptance Criteria:**

**Given** Play Billing Library 9.1.0
**When** `AndroidBilling` is added behind a thin `BillingClientFacade` (so Robolectric tests use a fake facade)
**Then** the client is built with the purchases-updated listener, `enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())` and automatic service reconnection, it connects only after user unlock (before unlock every call returns `DomainError.BeforeFirstUnlock`), and Billing coordinates are added to `config/dependency-allowlist.txt` in the same change
**And** Koin binds `AndroidBilling` as the production `Billing` and `AndroidProductDetailsSource` as the production `ProductDetailsSource`, replacing `UnavailableBilling`; the debug build keeps a hidden developer toggle to bind `FakeBilling` for emulator tests only (absent from release, checked by the Story 1.18 release-manifest test pattern)

**Given** product details
**When** `AndroidProductDetailsSource` runs
**Then** it queries all 50 ids with `queryProductDetailsAsync` (type INAPP), maps each product's one-time purchase offer to `formattedPrice`, `priceAmountMicros` and `priceCurrencyCode`, and reports unfetched products without failing the whole refresh

**Given** a launch request from `PurchaseCoordinator`
**When** it runs
**Then** it calls `launchBillingFlow` from the resumed `WakeActivity` with the product's `ProductDetails`, `setObfuscatedAccountId(installId)` and `setObfuscatedProfileId(sessionId)`, and returns `PurchaseFailed(Error)` without launching if `WakeActivity` is not resumed
**And** response codes map to domain results in one table (unit-tested row by row): OK → purchases to the coordinator; USER_CANCELED → Cancelled; ITEM_ALREADY_OWNED → ItemAlreadyOwned; NETWORK_ERROR, SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED → Offline; BILLING_UNAVAILABLE, ERROR, DEVELOPER_ERROR, ITEM_UNAVAILABLE, FEATURE_NOT_SUPPORTED, ITEM_NOT_OWNED → Error; purchase state PENDING → Pending
**And** `queryPurchasesAsync(INAPP)` and `consumeAsync` map to the port, a consume of an already consumed token (ITEM_NOT_OWNED) maps to success, and no purchase token appears in any log or Crashlytics key (test on the `Logger` and `CrashReporter` fakes)

**Given** the unlock mechanics decided in `docs/spikes/S1.md` (Story 1.5)
**When** "Pay" is confirmed while the keyguard is locked
**Then** a `DeviceUnlocker` port (`isLocked()`, `requestUnlock(): Outcome<Unit, UnlockError>`) with `FakeDeviceUnlocker` is implemented by `AndroidDeviceUnlocker` using `KeyguardManager.requestDismissKeyguard(WakeActivity, callback)`; success continues to the launch; cancel or error dispatches `UnlockFailed` (or `PurchaseFailed(UnlockFailed)` if S1 added no event), and the Play sheet is launched only after `onDismissSucceeded`
**And** if S1 found the Play sheet works over the lock screen without unlocking, the adapter skips the unlock step, and the story file records which branch was built with a link to the S1 Decision section
**And** the alarm sound is not paused, lowered or muted by any billing or unlock call (test asserts no `AlarmPlayer` effect is emitted by the adapter)
**And** `./gradlew qualityGate` passes

### Story 4.13: Snooze confirm sheet

As a user,
I want a clear confirmation before I pay, with the easy "I'll get up" under my thumb and taps ignored for a moment,
So that I never pay by accident while half asleep.
**Refs:** FR-RNG-2, FR-RNG-3, FR-RNG-5, FR-RNG-9, FR-RNG-10, FR-PWK-10, FR-MSG-4, NFR-5, NFR-9, NFR-13, AD-2, AD-11, UX-DR15, UX-DR16, UX-DR64, UX-DR66, UX-DR67, UX-DR72, UX-DR73, UX-DR74, UX-DR77, UX-DR78, UX-DR84, UX-DR89 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 4.18

**Acceptance Criteria:**

**Given** a session in Ringing, Grace or Loud with Snooze Available
**When** the user taps `button-snooze` on the ringing screen or the check footer
**Then** `SnoozeTapped` is dispatched and `sheet-snooze-confirm` opens over the current wake screen (`surface-sunrise`, top corners `rounded.lg`, 24 dp padding) showing in order: "Snooze for {minutes} min?" (`headline`, {minutes} = frozen config snooze length), the price in `display` and `text-sunrise`, "This one costs {price}. The next one costs {nextPrice}." (`body`), "Is {minutes} more minutes worth {price}? You've got this." (`body`, `text-secondary-sunrise`), then the tax note when applicable, then two stacked full-width 64 dp buttons: outlined "Pay {price} and snooze" above and filled "I'll get up" at the bottom
**And** when the next snooze would exceed max snoozes or the cap, the body shows only "This one costs {price}." [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** the tax note "Google Play shows the final total, including any tax." is shown when the Play billing country (from `getBillingConfigAsync`, cached) is in `config/tax-exclusive-countries.txt` (initially US and CA) [ASSUMPTION: owner to confirm the country list]

**Given** the anti-double-tap guard
**When** the sheet opens, or its state or displayed price changes
**Then** all input is ignored for 500 ms measured with the monotonic clock (not animation end), including with animator duration scale 0, and neither button is pre-selected or focused (TalkBack focus starts on the title)
**And** a test taps "Pay" at 499 ms (ignored) and 500 ms (accepted), and repeats after a state change

**Given** the sheet
**When** the user taps "I'll get up", swipes down or presses Back
**Then** the sheet closes with no charge and no intent written, the screen underneath shows "I'm up" (or the current check) again, and no payment message is shown
**And** the alarm keeps ringing while the sheet is open; if the sheet was opened during a muted grace window, the mute ends when the countdown ends and the sheet stays open with the alarm at full volume behind it (AD-2 grace keeps counting)

**Given** "Pay {price} and snooze" is tapped
**When** the phone is locked and the S1 branch requires unlocking
**Then** the sheet switches to the *unlocking* state: lock icon, "Unlock to pay {price}", one outlined 64 dp "Cancel"; "Cancel" returns to the ringing (or check) screen with "Phone still locked. No charge."; a successful unlock opens the Play sheet
**And** when unlocked, `PayConfirmed` opens the Play sheet directly

**Given** the coordinator dispatches `ReuseOffered`
**When** the sheet is showing
**Then** it switches to the *already paid* state: "You already paid {price} earlier that wasn't used. Use it for this snooze?" with outlined "Use it" above and filled "Not now" at the bottom, the 500 ms guard applies again, "Use it" dispatches `ReuseAccepted` and "Not now", swipe or Back dispatches `ReuseDeclined`

**Given** the sheet states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for confirm (with and without tax note, last-snooze body), unlocking and already paid, over Ringing and over a check, in Sunrise at 100% and 200% font scale, with every button ≥ 64 dp and both buttons on screen without scrolling at 200%
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.14: Payment outcome messages

As a user,
I want a short plain message after every failed payment that tells me I wasn't charged,
So that I never wonder whether I paid.
**Refs:** FR-RNG-4, FR-RNG-5, FR-RNG-7, FR-RNG-9, FR-MSG-4, NFR-3, NFR-9, AD-11, UX-DR56, UX-DR64, UX-DR79, UX-DR84, UX-DR89 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a `PaymentOutcome` mapping table in `:composeApp` keyed by the domain result (from Story 4.12's response-code table)
**When** the mapping test runs
**Then** each row maps to exactly one string resource, return surface and availability, matching the EXPERIENCE.md Payment outcomes table:
- UnlockFailed → "Phone still locked. No charge." → back to Ringing or Check → Snooze still offered
- Cancelled → "Payment cancelled. No charge." → Snooze still offered
- Error → "Payment didn't go through. No charge." → Snooze still offered
- Offline → "No connection. No charge." → "Snooze unavailable: offline" until connectivity returns
- Pending → "Payment not confirmed yet. If it goes through before you finish, your snooze starts. Otherwise, finish the check to stop the alarm." → "Snooze unavailable: payment pending" for this session
- Purchased, Already paid "Use it" → no message → Snoozed
- Already paid "Not now" → no message → "An earlier {price} payment is being refunded"
**And** a test asserts every `LaunchResult`/`PurchaseFailed` kind has a row (no unmapped result compiles, via an exhaustive `when`)

**Given** an outcome with a message
**When** it is shown on a wake screen
**Then** it is a `snackbar` with wake rules: no action, visible at least 10 s or until the next tap, announced politely by TalkBack, never covering "I'm up" or the check input, and the alarm is at full set volume (or muted only while a grace countdown still runs)
**And** "I'm up" (on Ringing) or the check (on Check) is usable immediately while the snackbar shows, so the free path stays one tap away (FR-RNG-9)
**And** an Offline outcome while `Connectivity` later reports online re-enables Snooze in place without a new message

**Given** outcome states
**When** Roborazzi tests run
**Then** screenshots exist for each message on Ringing and on a check in Sunrise at 100% and 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.15: Snoozed screen, session line and snoozing from a check

As a user,
I want a calm "Snoozed" note after paying, and on the next ring to see how many snoozes I've used and what I've paid,
So that I know exactly where my morning stands without feeling judged.
**Refs:** FR-RNG-1, FR-RNG-6, FR-RNG-8, FR-PWK-10, FR-SES-10, FR-MSG-3, FR-MSG-4, NFR-9, AD-2, AD-8, UX-DR13, UX-DR64, UX-DR66, UX-DR72, UX-DR78, UX-DR84, UX-DR88, UX-DR89 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** a session enters Snoozed after `PurchaseGranted` or `ReuseAccepted`
**When** `WakeActivity` renders
**Then** the Snoozed surface (Sunrise) shows exactly "Snoozed. Next ring at {time}." with {time} = snooze end formatted per the system 12/24 h setting, for 3 s, then `WakeActivity` finishes and clears keep-screen-on so the system turns the screen off (the app never forces the screen off)
**And** the sound and vibration have stopped, the session slot is armed at snooze end (Epic 2), and no celebration, animation or payment wording appears

**Given** the re-ring after a snooze
**When** the ringing screen renders
**Then** under the date it shows "Snooze {n} of {max} · {paid} paid this morning" with n = snoozesGranted, max = frozen config max snoozes and {paid} = `SessionState.paid` formatted per currency by `MoneyFormatter`, and `button-snooze` shows the next price B × (n + 1) or the matching unavailable reason
**And** the first ring shows no session line
**And** a merged overlapping alarm during a snooze (Epic 2) re-rings early with the same session line and no fee

**Given** a session in Grace or Loud on a check (Epic 3)
**When** the user snoozes from the check footer and the purchase is granted
**Then** the check progress is discarded, the Snoozed surface shows, and the re-ring starts a fresh `CheckRun` (new seeds) and a new grace window (FR-PWK-10, PRD §6.4 "snooze wins")
**And** when a pending payment clears to `PURCHASED` while the user is mid-check, the same happens without any tap

**Given** the session completes
**When** the Epic 3 Success screen renders
**Then** after at least one paid snooze it shows "You're up. That's what counts." plus "{paid} paid this morning" in `text-secondary-sunrise`, with no animation
**And** when the session had a pending payment that was never granted it adds "Your pending payment wasn't used. Google refunds it automatically."

**Given** these states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for Snoozed, re-ring with session line (USD and EUR, n = 1 and 4 of 5), check footer during a paid session, Success after snooze and Success with pending not used, in Sunrise at 100% and 200% font scale; TalkBack reads the session line after the clock and before "I'm up"
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.16: Purchase history

As a user,
I want a list of every snooze I paid for, with the date, alarm, snooze number and price,
So that I can check any charge, and see when a payment wasn't used and was refunded.
**Refs:** FR-PRG-4, FR-RNG-10, FR-MSG-4, NFR-9, NFR-10, AD-8, AD-11, AD-18, UX-DR52, UX-DR58, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the Progress placeholder (Story 4.5)
**When** it renders
**Then** it shows a `settings-row` "Purchase history" [ASSUMPTION: add to EXPERIENCE.md Key strings] opening the Purchase history route (`top-app-bar` titled "Purchase history"); Epic 6 keeps this link when it fills Progress

**Given** purchase records
**When** Purchase history renders
**Then** each record is a `purchase-row` (64 dp) newest first: date and alarm (label, or alarm time if no label) in `body`, "Snooze {n}" in `caption`, and the price formatted by `MoneyFormatter` from micros and currency, right-aligned in `text` (never accent, green or red), tabular figures
**And** consumed and reused records read as normal paid snoozes; stranded records read "Not used, refunded automatically by Google" instead of the snooze number; granted records not yet consumed show as normal paid snoozes
**And** a record with no alarm or snooze number (stranded purchase with missing profileId) shows the date and "Not used, refunded automatically by Google" only
**And** rows are read-only; TalkBack reads each row as one item (date, alarm, snooze number or status, price)

**Given** no records
**When** the screen renders
**Then** it shows "No snoozes paid. Keep it that way."
**And** loading shows a `skeleton` only after 300 ms

**Given** records in two currencies (a trip abroad)
**When** the screen renders
**Then** each row keeps its own currency and nothing sums across currencies on this screen
**And** Roborazzi screenshots cover empty, mixed statuses and two currencies in Light and Dark and at 200% font scale, and a ViewModel test with `FakePurchaseRecordRepository` covers ordering and status mapping
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.17: "Problem with a charge?"

As a user,
I want a clear way to ask for a refund or contact support about a charge,
So that I'm never stuck with a charge I don't understand.
**Refs:** FR-SET-5, FR-PRG-4, FR-MSG-4, NFR-4, NFR-5, NFR-9, AD-11, UX-DR27, UX-DR51, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Must · **Verify:** auto, plus (human-verify) links in Story 4.18

**Acceptance Criteria:**

**Given** Purchase history and the Settings Snooze section
**When** they render
**Then** each has a `button-text` / `settings-row` "Problem with a charge?" opening a pushed screen of the same title

**Given** the "Problem with a charge?" screen
**When** it renders
**Then** it explains in ≤ 25-word paragraphs that Google lets you request a refund yourself within 48 hours of a charge, that payments that were not used are refunded automatically by Google, and that a pending payment only charges if it goes through [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** a `button-filled` "Open Google Play order history" [ASSUMPTION: add to EXPERIENCE.md Key strings] opens `https://play.google.com/store/account/orderhistory` with `ACTION_VIEW`, and if no app can handle it a snackbar says "No browser found." [ASSUMPTION: add to EXPERIENCE.md Key strings]

**Given** at least one purchase record
**When** the user taps `button-outlined` "Email support" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**Then** a picker lists the 10 most recent records (date, price, status) with the newest selected, and confirming opens `ACTION_SENDTO` `mailto:` to the support address from `config/app-links.properties` [ASSUMPTION: support email address, owner to provide] with subject "Charge question" and a body containing the order ID, purchase date and time (ISO 8601 with offset), price and status [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** the email body never contains the purchase token, install id, session id or alarm label (unit test on the body builder), and with no records the button opens the same email without order details

**Given** the screen
**When** Roborazzi and semantic tests run
**Then** screenshots exist with and without records in Light and Dark and at 200% font scale, targets ≥ 48 dp, and a Robolectric test asserts both intents' action, data and extras
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 4.18: Epic 4 payment verification with license testers

As the owner,
I want to confirm real Google Play payments from a ringing phone with license testers,
So that nobody is ever charged without a snooze or snoozes without being charged.
**Refs:** FR-RNG-1–10, FR-PWK-10, FR-PRG-4, FR-SET-1, FR-SET-5, NFR-3, NFR-5, NFR-13 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build installed from the internal track on a Pixel and a Samsung (plus the Xiaomi and budget device for items 1, 3 and 12), signed in with a license tester account (Story 1.4), with the 50 products live (Story 4.1)
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. With base fee $1, the ringing screen shows "Snooze · {local price}" matching Play Console's price for `snooze_usd_01`; with the phone offline for the whole ring (after one earlier online launch), Snooze reads "Snooze unavailable: offline" and turns into "Snooze · {local price}" in place when connectivity returns; after clearing app data and staying offline it reads "Snooze unavailable: offline".
2. The confirm sheet shows title, price, next price, nudge and (US account) the tax note; taps in the first 500 ms do nothing; "I'll get up", swipe down and Back close it with no charge.
3. Locked phone: "Pay" shows "Unlock to pay {price}"; unlocking opens the Play sheet over the ringing flow; cancelling the unlock shows "Phone still locked. No charge."
4. The alarm keeps ringing at full volume on the alarm stream under the Play sheet; snoozing during a grace window keeps the mute only until the countdown ends.
5. A successful test purchase shows "Snoozed. Next ring at {time}.", the screen turns off, and it re-rings at that time with "Snooze 1 of 5 · {price} paid this morning" and the next price at 2× B.
6. Cancelling the Play sheet shows "Payment cancelled. No charge."; airplane mode during "Pay" shows "No connection. No charge." and Snooze becomes "Snooze unavailable: offline" until connectivity returns.
7. "Slow test card, approves after a few minutes": "Payment not confirmed yet…" appears, Snooze reads "Snooze unavailable: payment pending"; if it approves during the check the snooze starts by itself; if the check is finished first, Success shows "Your pending payment wasn't used. Google refunds it automatically." and history later shows "Not used, refunded automatically by Google".
8. A stranded purchase (from item 7, not yet refunded) makes the next "Pay" at that price show the already-paid state; "Use it" snoozes with no new charge in Play order history; "Not now" shows "An earlier {price} payment is being refunded".
9. Force-stopping the app right after a purchase completes (before the Snoozed screen) and reopening: the snooze is granted once and the purchase is consumed (buying the same product again works).
10. Snooze from the check footer grants the snooze, discards check progress, and the re-ring starts a fresh check and grace window.
11. After max snoozes (set to 2), Snooze reads "Snooze unavailable: max snoozes reached"; a test alarm shows "Test · no charge" and can't open the sheet.
12. Lowering the base fee at 23:40 before a 7:30 alarm shows "Saved. Takes effect after tomorrow's 7:30 alarm." and the 7:30 session still charges the old fee; the next day's session charges the new fee; turning the alarm off within 8 h asks "Turn off your … alarm?" with "Keep it on" as default.
13. Purchase history lists every test purchase with date, alarm, snooze number and local price; stranded purchases read "Not used, refunded automatically by Google".
14. "Problem with a charge?" opens Google Play order history and a pre-filled support email with the order ID and time.
15. With a second license tester on a device set to a non-USD country (for example Germany), prices, session line and history use that currency's Play formatting.
16. TalkBack reads the sheet title first, neither button is pre-focused, and disabled Snooze reads its reason; at 200% font size both sheet buttons stay on screen.
17. All copy seen matches EXPERIENCE.md and every failed payment says "No charge." (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done
