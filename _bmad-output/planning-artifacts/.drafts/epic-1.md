## Epic 1: Set an alarm that rings

The user can create alarms that ring on time over the lock screen (Doze, silent, Do Not Disturb, reboot, time and time-zone changes), choose and preview built-in sounds, run a test alarm, and end the alarm with "I'm up". Under the hood this epic lays the foundation every later epic plugs into: the KMP project and quality gate, generated design tokens, the Play Console record, the two spikes, platform-free time and occurrence math, the complete AD-2 session state machine with write-ahead persistence, the single history writer, and the wake runtime.

Every UI story in this epic (and in every later epic) carries two standing acceptance criteria, repeated in the story so the build loop can check them: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass the automated copy-rules test from Story 1.3 (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `[ASSUMPTION: add to EXPERIENCE.md Key strings]` in the story and listed for the owner.

### Story 1.1: Scaffold the KMP project with a quality gate

As the developer (owner or build loop),
I want an AGP 9 Kotlin Multiplatform project with the architecture's modules, pinned versions and one `qualityGate` task,
So that every later story builds on the same structure and has one command that says "done".
**Refs:** NFR-6, NFR-11, NFR-12, AD-1, AD-13, AD-14 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** an empty repository (planning files only)
**When** the scaffold is created
**Then** `settings.gradle.kts` includes exactly `:core`, `:data`, `:composeApp`, `:androidApp`, `:testing` (plus the build-only `:detekt-rules` module under `config/detekt-rules/`), and the folders `tools/tokens/`, `tools/play-catalog/`, `config/`, `docs/`, `data/schemas/` exist per the Architecture Structural Seed
**And** `:core` and `:testing` are KMP modules with only `commonMain`/`commonTest` sources and a `jvm()` target (iOS targets deferred), `:data` and `:composeApp` are KMP modules with `commonMain` and `androidMain`, and `:androidApp` is the Android application module using the AGP 9 KMP-compatible plugins
**And** the project dependency graph is exactly androidApp → composeApp, data, core; composeApp → core; data → core; testing → core; test-only androidApp/composeApp/data → testing

**Given** `gradle/libs.versions.toml`
**When** it is read
**Then** it pins every Architecture Stack version exactly (Kotlin 2.4.20, Compose Multiplatform 1.12.1, AGP 9.3.3, KSP 2.3.11, Room KMP 3.0.3, DataStore 1.2.1, kotlinx-coroutines 1.11.0, kotlinx-datetime 0.8.0, kotlinx-serialization 1.11.0, Navigation 3 1.1.2, Koin 4.2.2, Play Billing 9.1.0, CameraX 1.6.2, ML Kit barcode 17.3.0, MediaPipe tasks-vision 1.0.0, WorkManager 2.11.0, Firebase BoM 34.19.0, Kover 0.9.8, detekt 2.0.0-alpha.5, Spotless 8.8.0, Roborazzi 1.74.0, Turbine 1.2.1), the Gradle wrapper is 9.7.1 and the JVM toolchain is 17
**And** `:androidApp` has `applicationId` and namespace `com.payper.snooze` [ASSUMPTION: final id pending PRD Q8, confirmed in Story 1.4], compileSdk 37, targetSdk 36, minSdk 26, no `applicationIdSuffix` on `debug`, R8 on `release`, `versionName` 0.1.0 and `versionCode` computed as major*10000 + minor*100 + patch (a unit test asserts 0.1.0 → 100 and 1.2.3 → 10203)

**Given** the debug APK is installed on an emulator
**When** the app is launched
**Then** a single `MainActivity` shows the `App()` composable from `:composeApp` with the app name from a Compose Multiplatform string resource, and Koin is started in the `Application` with one `val xxxModule = module { }` per module (`:core` has no Koin dependency)
**And** a Robolectric test in `:androidApp` launches `MainActivity` and finds the app-name text, and one Roborazzi screenshot of the empty screen is recorded and verified (confirms OQ-3; if Roborazzi does not work with AGP 9 host tests, the fallback Compose Preview screenshot testing is set up instead and the decision is written to `docs/decisions/oq-3-screenshots.md`)

**Given** the `verifyCoreDependencies` Gradle check (AD-1)
**When** `:core` declares any dependency other than Kotlin stdlib, kotlinx-coroutines, kotlinx-datetime or kotlinx-serialization, or any `:core` source file imports `android.`, `androidx.`, `org.koin.`, Compose or Room packages, or `:composeApp` depends on `:data`
**Then** the build fails with a message naming the offending dependency, import or edge
**And** the check's logic is unit-tested with a passing and a failing fixture

**Given** the `qualityGate` task
**When** `./gradlew qualityGate` runs
**Then** it runs, in one invocation: `spotlessCheck` (ktlint), `detekt` (config in `config/detekt/detekt.yml`, custom rules from `:detekt-rules` with a first rule banning `println` in `:core`), `:core:allTests`, `:data:allTests`, `:composeApp` host tests, `:androidApp:testDebugUnitTest` (incl. Roborazzi verify), `koverVerify` (`:core` ≥ 90% lines), `:androidApp:lintDebug`, `:androidApp:assembleDebug` and `verifyCoreDependencies`
**And** later stories register their checks (token diff, allowlists, loudness script) as dependencies of `qualityGate`, documented in a comment in the root build file
**And** `./gradlew qualityGate` passes

### Story 1.2: CI pipeline and dependency and permission allowlists

As the owner,
I want every push checked in CI and every new dependency or Android permission to fail the build until reviewed,
So that the build loop cannot silently add network SDKs or device-hostage permissions.
**Refs:** NFR-4, NFR-11, NFR-13, AD-5, AD-14, AD-15 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `.github/workflows/ci.yml`
**When** a commit is pushed or a pull request is opened
**Then** CI checks out the repo, sets up Temurin JDK 17 and Gradle caching, runs `./gradlew qualityGate`, then runs instrumented tests on a Gradle Managed Device (ATD image, API 34) with one smoke test that launches `MainActivity`
**And** the workflow fails if either step fails, and the job summary names the failing task

**Given** `config/dependency-allowlist.txt` listing every `group:artifact` in the resolved `:androidApp` debug and release runtime classpaths
**When** `./gradlew checkDependencyAllowlist` runs and a resolved runtime dependency is not in the file
**Then** the task fails and prints each unlisted coordinate
**And** removing a line from the allowlist in a test fixture makes the task fail (automated test), and the task is a dependency of `qualityGate`

**Given** `config/permission-allowlist.txt` containing exactly `INTERNET`, `com.android.vending.BILLING`, `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` (with `maxSdkVersion="32"`), `USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `VIBRATE`, `CAMERA`, `RECORD_AUDIO`, plus reviewed library-merged permissions
**When** `./gradlew checkPermissionAllowlist` parses the merged debug and release manifests
**Then** the task fails on any `uses-permission` not in the list, on `SCHEDULE_EXACT_ALARM` without `maxSdkVersion="32"`, and on any component protected by `BIND_ACCESSIBILITY_SERVICE` or `BIND_DEVICE_ADMIN` or using lock-task mode
**And** unit tests with fixture manifests prove that `READ_PHONE_STATE` and `SYSTEM_ALERT_WINDOW` each fail the check, and the task is a dependency of `qualityGate`

**Given** `.github/workflows/release.yml`
**When** a tag `vX.Y.Z` is pushed and the upload-key and Play service-account secrets exist
**Then** CI builds a signed release AAB with `versionName` X.Y.Z and uploads it to the Play internal track
**And** when the secrets are absent the upload job is skipped with a notice, never failed, and no keystore, `*.jks`, service-account JSON or `google-services.json` is tracked by git (`.gitignore` entries plus a CI step that fails if such files are committed)
**And** `./gradlew qualityGate` passes

### Story 1.3: Generated design tokens and PpsTheme

As a user,
I want every screen to use the same calm colours, type and shapes, with a bright Sunrise look on wake screens,
So that the app is readable at night and unmistakable when the alarm rings.
**Refs:** FR-MSG-4, NFR-9, NFR-10, AD-10, AD-11, UX-DR1, UX-DR2, UX-DR3, UX-DR4, UX-DR5, UX-DR6, UX-DR7, UX-DR8, UX-DR9, UX-DR11, UX-DR82, UX-DR83, UX-DR85 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the DESIGN.md YAML frontmatter
**When** `./gradlew generateTokens` (the `tools/tokens` generator) runs
**Then** it writes `composeApp/src/commonMain/kotlin/com/payper/snooze/ui/theme/PpsTokens.kt` with every colour token for Light (no suffix), Dark (`-dark`) and Sunrise (`-sunrise`, plus `sunrise-gradient-top`), the 8-style typography ramp (`clock-xl` 88/92 w300, `display` 48/52 w500, `headline` 28/34 w600, `title` 20/26 w600, `button-wake` 20/24 w500, `body` 16/24 w400, `label` 14/20 w500, `caption` 12/16 w400), rounded tokens `sm` 8 dp / `md` 16 dp / `lg` 28 dp / `full`, and spacing tokens (4 dp base, screen margin 20 dp, section gap 24 dp, card padding 16 dp, `target-min` 48 dp, `target-wake` 64 dp, `target-wake-hero` 72 dp)
**And** the generated file is committed, and `checkTokens` (a `qualityGate` dependency) regenerates into `build/` and fails on any diff
**And** a generator unit test with a fixture frontmatter asserts parsing of each token group and rejects a colour value that is not `#RRGGBB`

**Given** `PpsTheme`
**When** a composable is wrapped in `PpsTheme(mode = System | Light | Dark)` or `PpsTheme(wake = true)`
**Then** one Material 3 `MaterialTheme` is provided with the Light, Dark or Sunrise colour set, dynamic colour is never used, `System` follows `isSystemInDarkTheme()`, and `wake = true` always uses Sunrise regardless of system setting
**And** Geist (OFL, licence file committed) is bundled with system sans fallback; `clock-xl`, prices, countdowns and list times use tabular figures (`fontFeatureSettings = "tnum"`), or Geist Mono for `clock-xl` if Geist lacks `tnum`, with the finding recorded in `docs/decisions/geist-tnum.md`
**And** icons come only from Material Symbols Rounded (weight 400, fill 0; fill 1 for selected nav items and outcome markers) as vector resources

**Given** detekt with the custom `:detekt-rules`
**When** code outside the `ui.theme` package contains `Color(0x…)`, `RoundedCornerShape(<number>.dp)` or a raw `<number>.sp` literal
**Then** detekt fails with a rule message pointing to `PpsTheme`
**And** each rule has a unit test with one violating and one compliant snippet

**Given** the DESIGN.md "Verified contrast" table
**When** the contrast test runs
**Then** it recomputes the WCAG ratio for every listed pair from the generated tokens, asserts each matches the recorded ratio within ±0.02, asserts text pairs ≥ 4.5 and graphic pairs ≥ 3.0, and asserts no background token is `#000000` or `#FFFFFF`

**Given** all Compose Multiplatform string resources (FR-MSG-4 copy rules)
**When** the `CopyRulesTest` runs
**Then** it fails on any string containing an em dash (U+2014), the words Elevate, Seamless, Unleash or Supercharge (any case), a hard-coded currency symbol (`$`, `€`, `£`, `¥`), the word "backup" next to "check", or any emoji outside the key `success_zero_snooze`
**And** it fails when a key ending `_headline` or `_title` has more than 8 words or a key ending `_body` has more than 25 words
**And** a theme showcase screen (debug only) renders every colour role, type style and shape, with Roborazzi screenshots in Light, Dark and Sunrise and at 200% font scale
**And** `./gradlew qualityGate` passes

### Story 1.4: Google Play Console setup (owner task)

As the owner,
I want a verified Play developer account, an app record with the final package id, a payments profile, a signed internal build and license testers,
So that billing spikes, closed testing and releases are not blocked by account setup later.
**Refs:** NFR-5, NFR-6, AD-7, AD-14; PRD §13 release plan · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the owner's Google Play developer account
**When** the owner completes Play Console onboarding
**Then** identity verification (and D-U-N-S if registering as an organization) is complete and the developer contact email is set
**And** the story file records whether the account is a new personal account (which triggers the ≥ 12 testers × 14 days closed-test rule planned in Epic 5)

**Given** the package id is permanent after the first upload
**When** the owner decides PRD Q8 (app name)
**Then** the decision is recorded in `docs/decisions/package-id.md`: either `com.payper.snooze` is accepted or a new id is chosen, in which case a follow-up story renames `applicationId` and namespace before any upload
**And** the app record is created in Play Console with that package id, default language English (United States), type App, Free, with in-app products

**Given** the app record
**When** the owner sets up monetization
**Then** a payments (merchant) profile is created and linked, and Monetize > Products > One-time products is available for the app

**Given** an upload key created on the owner's machine
**When** the owner enrols in Play App Signing and uploads the signed 0.1.0 AAB built from `main`
**Then** the build is rolled out to the internal testing track, the owner's account is on the internal testers list, and installing through the opt-in link on a real phone launches the app
**And** the keystore and passwords are stored only in the owner's password manager and GitHub Actions secrets (`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`), never in the repo

**Given** Setup > License testing
**When** the owner adds license testers
**Then** the owner's account and at least one separate test Google account are license testers with response "RESPOND_NORMALLY"
**And** a Google Cloud service account with Play Developer API access (for `tools/play-catalog` and the release workflow) exists and its JSON key is stored only as the GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`

**Given** the checklist above
**When** the owner finishes
**Then** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` still passes on `main` after the decision docs are committed

### Story 1.5: Spike S1: pay for a snooze over the lock screen

As the owner,
I want a throwaway prototype that shows the Google Play purchase sheet from a ringing lock-screen activity,
So that the snooze-payment unlock mechanics are decided before the session state machine is written.
**Refs:** FR-RNG-3, FR-RNG-5, AD-2, AD-7, OQ-1; PRD §10 S1 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the internal-track app record and license testers from Story 1.4
**When** the spike starts
**Then** the prototype lives only on branch `spike/s1-billing-lockscreen` and is never merged to `main`
**And** one consumable test product `spike_s1_test` at the lowest USD tier is created by hand in Play Console for the spike only (the production catalogue is created by `tools/play-catalog` in Epic 4), with pending purchases enabled, and it is deactivated after the spike

**Given** the prototype: an activity using `setShowWhenLocked`/`setTurnScreenOn` that plays a looping sound on `USAGE_ALARM`, with a "Pay" button that calls `requestDismissKeyguard` when locked and then `launchBillingFlow` with `obfuscatedProfileId` = a random UUID
**When** the owner runs it on at least a Pixel and a Samsung device with a license tester account, locked and unlocked, at least 5 times per device
**Then** `docs/spikes/S1.md` answers, with device, Android version, date and evidence (screen recordings or logs) per run: (1) whether the Play sheet can appear over the lock screen without unlocking, (2) what happens with `requestDismissKeyguard` when the user unlocks, cancels or fails, (3) whether the alarm sound keeps playing at full volume on the alarm stream under the Play sheet, (4) median and max time from "Pay" to purchase result, (5) whether `obfuscatedProfileId` is present on the purchase update, on `queryPurchasesAsync`, on a pending purchase (license-tester slow card) that completes later, and on a promo-code purchase (OQ-1), (6) behaviour with no connection

**Given** the findings
**When** the owner writes the Decision section of `docs/spikes/S1.md`
**Then** it states the unlock mechanics for FR-RNG-3 (for example: confirm sheet → *unlocking* state "Unlock to pay {price}" → keyguard → Play sheet) and whether AD-2 needs `UnlockRequested` / `UnlockFailed` events, listing each new transition-table row as From / Event / Guard / To / One-shot effects
**And** any finding that contradicts the Architecture Spine or PRD (for example, the sheet cannot be shown while locked, or the profile id is missing on some path) is raised through `bmad-correct-course` before Story 1.11 starts

**Given** the spike checklist
**When** it is complete
**Then** the story file records pass/fail per question, device, Android version and date; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/spikes/S1.md` committed

### Story 1.6: Time ports, deadlines and alarm occurrence math in core

As a user,
I want my alarm to ring at the wall-clock time I set, even across daylight-saving changes and time-zone moves,
So that I never wake an hour early or late.
**Refs:** FR-ALM-5, FR-ALM-9, NFR-11, NFR-12, AD-1, AD-3 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core`
**When** the time ports are added
**Then** `core` exposes `Clock` (wall, `kotlin.time.Clock`), `MonotonicClock` (elapsed millis since boot), `BootCounter` (boot count) and `TimeZoneProvider` (current `TimeZone`), and `:testing` provides `FakeClock`, `FakeMonotonicClock`, `FakeBootCounter` and `FakeTimeZoneProvider` with `advanceBy(duration)`, `set(...)` and `reboot()` helpers
**And** `:androidApp` provides `AndroidMonotonicClock` (`SystemClock.elapsedRealtime`), `AndroidBootCounter` (`Settings.Global.BOOT_COUNT`) and `AndroidTimeZoneProvider`, wired in Koin

**Given** a detekt rule in `:detekt-rules`
**When** `Clock.System`, `System.currentTimeMillis()`, `SystemClock` or `TimeZone.currentSystemDefault()` is used outside `com.payper.snooze.android.*` adapters
**Then** detekt fails (rule unit-tested with a violating and a compliant snippet)

**Given** `Deadline(wallMillis, elapsedMillis, bootCount)`
**When** `isDue(now)` is evaluated with the current wall time, elapsed time and boot count
**Then** on the same boot it compares elapsed time only (a wall-clock jump of ±2 h does not change the result), and on a different boot it compares wall time
**And** `remaining(now)` follows the same rule and never returns a negative duration

**Given** an alarm rule (`LocalTime`, repeat days as a set of `DayOfWeek`, empty = one-time) and `now` in a `TimeZone`
**When** `nextOccurrence(rule, now, zone)` is computed
**Then** it returns the first instant strictly after `now` whose local date's weekday is in the repeat set (any day for one-time) and whose local time equals the rule time, and a one-time alarm whose time has already passed today returns tomorrow
**And** in a DST gap (for example 02:30 on 2027-03-28 in Europe/Berlin) it returns the instant shifted forward by the gap length (03:30 local) (confirmed by owner)
**And** in a DST overlap (for example 02:30 on 2027-10-31 in Europe/Berlin) it returns only the earlier instance, and the next call after that instant returns the following repeat day, never the second 02:30
**And** after a time-zone change (for example Europe/Berlin → America/New_York) the same rule recomputes to the rule time in the new zone
**And** a repeat alarm set for Monday–Friday evaluated on Friday after the time returns Monday
**And** `durationUntil(nextOccurrence, now)` feeds the Home countdown in Story 1.9 (same function, one source)
**And** tests are table-driven, named as backticked sentences, and cover at least 3 zones including one with a 30-minute DST shift (Australia/Lord_Howe)
**And** `./gradlew qualityGate` passes

### Story 1.7: Store alarms in app.db

As a user,
I want my alarms saved on the phone in storage that is readable right after a reboot,
So that they are never lost and can ring before I unlock.
**Refs:** FR-ALM-1, FR-ALM-2, NFR-4, NFR-14, AD-6, AD-12 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:core`
**When** the alarm domain is added
**Then** `Alarm` holds `id` (UUID v4 string), `time` (`LocalTime`), `repeatDays` (set of `DayOfWeek`, empty = one-time), `label` (optional, ≤ 40 characters), `enabled`, `soundRef` (default: the default built-in sound), `volumePercent` (default 80), `gradualVolume` (default true), `rampStartPercent` (default 20), `vibration` (default on), `snoozeLengthMinutes` (one of 5, 9, 10, 15; default 9), `graceSeconds` (15–30, default 20), `requestCode` (stable unique int per alarm), `createdAt`, `updatedAt`
**And** check configuration, pending changes and motivation recording are not stored yet (they arrive with their own tables in Epics 3, 4 and 7)
**And** an `AlarmRepository` port (`observeAll(): Flow`, `get`, `upsert`, `delete`) returns `Outcome<T, DomainError>`, with `FakeAlarmRepository` in `:testing`
**And** use cases `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm`, `DuplicateAlarm` validate input (label length, snooze length in {5, 9, 10, 15}, grace in 15–30) and return `DomainError.InvalidAlarm(field)` instead of throwing

**Given** `:data`
**When** `RoomAlarmRepository` is implemented
**Then** `app.db` (Room KMP, schema version 1, table `alarm` only) is created at a path built from `createDeviceProtectedStorageContext()`, never the credential-protected context, and the exported schema is committed under `data/schemas/`
**And** if Room 3.0.3 cannot be used with KMP and AGP 9 (OQ-2), Room 2.8.5 is used and the decision is recorded in `docs/decisions/oq-2-room.md`
**And** `:data` tests (in-memory or temporary file database) cover insert, update, delete, observe ordering by time of day, and a unique `requestCode` constraint

**Given** Android Auto Backup (NFR-14)
**When** backup rules are added
**Then** `dataExtractionRules` (API 31+) and `fullBackupContent` (API ≤ 30) include `app.db` in the device-protected domain and exclude everything else created so far
**And** a Robolectric test parses both XML files and asserts the include and exclude entries
**And** `./gradlew qualityGate` passes

### Story 1.8: Create and edit an alarm

As a user,
I want to set an alarm's time, repeat days, label, snooze length, volume, whether it gradually increases (and from what level) and vibration,
So that each alarm rings the way I need.
**Refs:** FR-ALM-1, FR-ALM-2, FR-MSG-4, NFR-9, NFR-10, AD-11, UX-DR25, UX-DR28, UX-DR35, UX-DR36, UX-DR38, UX-DR40, UX-DR41, UX-DR42, UX-DR43, UX-DR55, UX-DR64, UX-DR66, UX-DR67, UX-DR80 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the app root
**When** the app opens with no alarms
**Then** Navigation 3 shows the Alarms route (sealed `@Serializable Route : NavKey` registered with `subclassesOfSealed`) with the empty state "No alarms yet." and a `button-filled` "Add your first alarm", plus the `fab` ("+", content description "Add alarm" (EXPERIENCE.md Key strings)), both opening the Alarm editor with defaults

**Given** the Alarm editor (one ViewModel with `StateFlow<UiState>`, `onIntent(Intent)` and `Channel<UiEffect>`)
**When** it opens for a new alarm
**Then** it shows, in order: `time-picker` (keyboard input first, 12/24 h per system setting, digits in `display` with tabular figures), repeat `chip-day`s M T W T F S S (TalkBack reads full day names, selected chips use accent fill), a label `text-field`, snooze length `segmented-control` 5 / 9 / 10 / 15 min with 9 selected, a Sound row showing the default sound's name, a volume `slider`, a "Gradually increase volume" `switch` (default on) and, only while it is on, a starting-volume `slider` (5% steps, value announced on change), and a vibration `switch`, with a bottom `button-filled` "Save"
**And** the fee ladder next to snooze length, checks, grace window and motivation sections are not shown yet (added in Epics 3, 4 and 7)

**Given** a one-time alarm (no days selected) whose time has already passed today
**When** the time is chosen
**Then** a `note-inline` shows "Rings tomorrow at {time}." with the time formatted per system 12/24 h setting

**Given** valid input
**When** the user taps "Save"
**Then** `SaveAlarm` stores the alarm (enabled) and the editor closes to the Alarms route
**And** an invalid label (over 40 characters) blocks Save with supporting text on the field in `error` colour (EXPERIENCE.md Key strings)

**Given** an existing alarm opened for editing
**When** the user changes a field and presses Back or the `top-app-bar` back arrow
**Then** a `dialog-confirm` "Discard changes?" appears with actions "Discard" and "Keep editing" (EXPERIENCE.md Key strings), "Keep editing" is the default dismiss, and with no changes Back closes immediately

**Given** the editor screens
**When** Roborazzi and semantic tests run
**Then** screenshots exist for new alarm, edit alarm, one-time-tomorrow note and discard dialog in Light and Dark and at 200% font scale with nothing clipped, every touch target is ≥ 48 dp, and every control has a TalkBack label with role and state
**And** ViewModel tests with `FakeAlarmRepository` and `FakeClock` cover save, edit, validation and discard
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.9: Alarm list on Home with the next-alarm countdown

As a user,
I want to see all my alarms, turn them on or off, duplicate or delete them, and see when the next one rings,
So that I trust what will happen tomorrow morning.
**Refs:** FR-ALM-1, FR-ALM-7, FR-MSG-4, NFR-9, AD-3, AD-11, UX-DR31, UX-DR32, UX-DR35, UX-DR41, UX-DR55, UX-DR56, UX-DR64, UX-DR66, UX-DR67, UX-DR76, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** saved alarms
**When** Home is shown
**Then** each alarm is a `card-alarm` (`surface`, `rounded.md`) sorted by time of day, with the time in `title` (tabular figures), repeat days and label in `caption` (repeat summary "Every day", "Once" or locale short day names like "Mon, Wed, Fri" (EXPERIENCE.md Key strings)) and a `switch` on the right
**And** tapping a card opens the Alarm editor for that alarm, and the `fab` stays bottom-right 20 dp from the edges

**Given** at least one enabled alarm
**When** Home is shown
**Then** above the list it shows "Rings in {hours} h {minutes} min" for the soonest enabled alarm, computed with the Story 1.6 `nextOccurrence`/`durationUntil` functions (the same ones the scheduler uses) and rounded up to the next whole minute
**And** under one hour it shows "Rings in {minutes} min" and at 24 h or more "Rings in {days} d {hours} h" (EXPERIENCE.md Key strings)
**And** the countdown refreshes every minute, on resume, and when the time or time zone changes; with no enabled alarms the line is hidden
**And** unit tests with `FakeClock` cover 7 h 12 min, 59 s → "1 min", exactly 24 h, a DST-gap day and a time-zone change

**Given** an alarm card
**When** the user toggles its `switch`
**Then** `SetAlarmEnabled` applies immediately and the countdown updates (the commitment-lock confirmation for turning off within 8 h is added in Epic 4)

**Given** an alarm card
**When** the user long-presses it, or opens the editor overflow menu (for TalkBack)
**Then** a menu offers "Duplicate" and "Delete"; Duplicate creates a copy with a new id and `requestCode` and opens it in the editor
**And** Delete opens `dialog-confirm` "Delete your {time} alarm? This is logged." with "Delete" (in `error` colour) and "Keep it" as the default dismiss; confirming deletes the alarm and writes an `AlarmDeleted(alarmId, at)` entry through the `Logger` port

**Given** the Home screen states (empty, one alarm, many alarms, all disabled)
**When** Roborazzi and semantic tests run
**Then** screenshots exist for each state in Light and Dark and at 200% font scale, targets are ≥ 48 dp, and long-press actions are also reachable through the editor menu
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.10: Schedule alarms exactly and keep them across reboot and clock changes

As a user,
I want every enabled alarm registered with Android as an exact alarm clock and re-registered after reboots, app updates and clock changes,
So that it rings on time in Doze, silent mode and Do Not Disturb.
**Refs:** FR-ALM-3, FR-ALM-5, FR-ALM-12, NFR-1, NFR-8, AD-3, AD-4 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the `AlarmScheduler` port in `:core` (`schedule(alarmId, requestCode, triggerAtWallMillis)`, `cancel(requestCode)`, `armSessionSlot(Deadline)`, `cancelSessionSlot()`, `scheduleTest(triggerAtWallMillis)`) with `FakeAlarmScheduler` in `:testing`
**When** `AndroidAlarmScheduler` schedules an alarm
**Then** it calls only `AlarmManager.setAlarmClock()` with an `AlarmClockInfo` whose trigger time equals the scheduled epoch millis and whose show intent opens `MainActivity`, and an operation `PendingIntent` (immutable) to `AlarmFiredReceiver` carrying `alarmId` and the scheduled epoch millis, using the alarm's `requestCode`
**And** the session slot uses one reserved request code and the test alarm another, both distinct from every alarm `requestCode` (unit-tested)
**And** a Robolectric test with `ShadowAlarmManager` asserts `setAlarmClock` was called with trigger time == `nextOccurrence(...)` epoch ms (FR-ALM-3) and that no `set`, `setExact` or `setAndAllowWhileIdle` call exists (a detekt or lint rule bans them)

**Given** `core.rescheduleAll()`
**When** it runs
**Then** it reads every alarm from `AlarmRepository`, schedules the next occurrence of each enabled alarm and cancels the request code of each disabled alarm, and is idempotent (running it twice produces the same scheduler calls, verified with `FakeAlarmScheduler`)
**And** `SaveAlarm`, `SetAlarmEnabled`, `DeleteAlarm` and `DuplicateAlarm` sync the affected alarm with the scheduler after the repository write succeeds

**Given** the manifest
**When** the receivers are added
**Then** `SystemEventsReceiver` handles `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` by calling `rescheduleAll()` inside `goAsync()`, `rescheduleAll()` also runs on app start, and all receivers are `directBootAware` and never start a foreground service (`LOCKED_BOOT_COMPLETED` is added in Epic 2)
**And** `AlarmFiredReceiver` hands `AlarmFired(alarmId, scheduledAt)` to an `AlarmFiredHandler` whose Epic 1 default schedules the next occurrence of a repeating alarm and disables a one-time alarm (Story 1.14 binds it to the wake runtime)
**And** `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"` and `RECEIVE_BOOT_COMPLETED` are declared and pass the permission allowlist
**And** on API 31–32, when `canScheduleExactAlarms()` is false, the adapter returns `DomainError.ExactAlarmNotPermitted` without falling back to an inexact alarm, and the error is logged (the user-facing prompt is Story 1.19)

**Given** Robolectric tests for each broadcast
**When** a boot, time-set, time-zone change or package-replaced broadcast is delivered with `FakeClock` and `FakeTimeZoneProvider` set to the new state
**Then** each enabled alarm is re-armed at the recomputed wall time, including a DST-gap day and a Berlin → New York zone change
**And** `./gradlew qualityGate` passes

### Story 1.11: The complete wake-session state machine in core

As a user,
I want one set of rules to decide whether my alarm rings, is quiet, is snoozed or is done,
So that no screen or service can disagree about what happens next.
**Refs:** FR-ALM-8, FR-ALM-9, FR-SES-7, FR-SES-8, FR-SES-10, NFR-11, NFR-12, AD-2, AD-3, AD-7, AD-16 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `core.session`
**When** the model is added
**Then** `SessionState` is a sealed type with `Idle`, `Ringing`, `Grace`, `Loud`, `Snoozed`, `Completed`, `Missed`, each active state holding `sessionId`, frozen `SessionConfig`, `ringIndex`, `snoozesGranted`, `CheckRun` (plan, seeds, step, failedAttempts, fallbackUsed), `paying: PurchaseIntentId?`, `noGraceThisRing`, `paused`, `beforeFirstUnlock`, and `Deadline`s for grace end, interaction timeout and snooze end, all `@Serializable`
**And** `SessionConfig` holds alarmId, label, scheduledAt, testMode, baseFeeTier, maxSnoozes, snoozeLengthMinutes, graceSeconds, vibrateInGrace, volumePercent, gradualVolume, rampStartPercent, soundRef, vibration and check plan, and a pure `ConfigResolver.resolve(alarm, globalSettings, testMode)` produces it (pending changes arrive in Epic 4); `GlobalSettings` defaults are base fee tier 1, max snoozes 5, grace 20 s, snooze 9 min
**And** events are the AD-2 set: `AlarmFired`, `TestAlarmFired`, `SlotFired`, `ProcessRestored`, `ImUpTapped`, `GraceElapsed`, `CheckAnswerSubmitted`, `FallbackRequested`, `SnoozeTapped`, `PayConfirmed`, `ReuseOffered`, `ReuseAccepted`, `ReuseDeclined`, `PurchaseGranted`, `PurchaseFailed`, `PurchaseCancelled`, `PurchasePending`, `NoInteractionTimeout`, `UserInteracted`, `CallStarted`, `CallEnded`, `OverlapAlarmFired`, `UserUnlocked`, `Recorded`, plus `UnlockRequested` / `UnlockFailed` and their rows if `docs/spikes/S1.md` (Story 1.5) decided them

**Given** the pure reducer `reduce(state, event, now): Transition(state, oneShotEffects)` and the idempotent `entryEffects(state)`
**When** each of the 27 rows of the AD-2 transition table is exercised
**Then** one parameterised test per row asserts the target state and the exact one-shot effects (for example Idle + `AlarmFired` with alarm enabled → `Ringing(ringIndex = 1)` with effects freeze config, create `CheckRun`, start wake runtime, arm slot +60 s, record session start; `Snoozed` + `OverlapAlarmFired` → `Ringing(ringIndex + 1, noGraceThisRing = true)`, record merged, reschedule that alarm)
**And** a table-coverage test asserts every row has a test, and that every (state, event) pair without a row returns the same state with only a `LogIgnored(event)` effect and never throws
**And** entry effects per state are asserted (Ringing/Loud: sound at set volume, slot armed, wake UI shown; Grace: muted, vibration only if `vibrateInGrace`; Snoozed: sound off, slot armed at snooze end; Completed/Missed: history write requested)

**Given** guards that later epics make real
**When** the reducer evaluates them
**Then** they come from constructor-injected pure policies with fakes in `:testing`: `SnoozeAvailabilityPolicy` (returns `Available(price)` / `Unavailable(reason)`; the Epic 1 production policy returns `Unavailable(TestMode)` for test sessions and `Unavailable(CatalogueNotLoaded)` otherwise), `CheckValidator` (`FakeCheck` programmable valid / invalid / last step; the Epic 1 production plan is one `Placeholder` step), and `FallbackPolicy` (Epic 1 production: not allowed)
**And** the next price is always `FeeLadder(config.baseFeeTier, snoozesGranted + 1)` behind a `FeeLadder` interface (the real ladder is Epic 4), and grace keeps counting while `paying` is set

**Given** the timeout rules (FR-ALM-9)
**When** pure `dueEvents(state, now)` is evaluated
**Then** it emits `GraceElapsed` when the grace deadline is due and `NoInteractionTimeout` when 30 minutes have passed since the last user event in Ringing or Loud, measured with the monotonic clock on the same boot
**And** every user event resets the interaction deadline, each new ring (after snooze or merge) starts a fresh 30-minute deadline, time spent `Snoozed` never counts, time spent `paused` (between `CallStarted` and `CallEnded`) is excluded, and `ProcessRestored` gives the restored ring a fresh 30-minute deadline, clears `paying`, and turns an overdue `Snoozed` into `Ringing(ringIndex + 1)` immediately
**And** tests use `FakeClock`/`FakeMonotonicClock` to cover: 29:59 no timeout; 30:00 → `Missed`; a tap at 20:00 moves the timeout to 50:00; a 10-minute call moves it by 10 minutes; a wall-clock jump of +2 h changes nothing; a snooze of 9 minutes is not counted
**And** Kover shows `core.session` ≥ 90% line coverage
**And** `./gradlew qualityGate` passes

### Story 1.12: SessionEngine with write-ahead persistence

As a user,
I want the alarm's state saved before anything happens,
So that a crash or kill never loses where my morning was.
**Refs:** FR-SES-1 (foundation), NFR-2, AD-2, AD-6, AD-12, AD-13 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `SessionEngine` in `:core` (single instance, serialized by a `Mutex`)
**When** `dispatch(event)` is called
**Then** it reads time from the ports, calls `reduce`, commits the new state to the `ActiveSessionStore` port in one transaction, and only after the commit succeeds runs the one-shot effects and then the entry effects through the `EffectRunner` port
**And** if the commit fails, no effect runs, the error is logged, and the previous state is kept (test with a failing fake store)
**And** `state: StateFlow<SessionState>` exposes the committed state, and concurrent dispatches from 100 coroutines produce a strictly serial transition log (test)
**And** `dueEvents` are evaluated on every dispatch and by `tick()` (called by the wake runtime), and resulting events are dispatched in order

**Given** `:data`
**When** `RoomActiveSessionStore` is implemented
**Then** `runtime.db` (Room KMP, version 1, table `active_session` with `session_id`, `state_json`, `updated_at`) is created in device-protected storage, its schema exported to `data/schemas/`, and `state_json` round-trips every `SessionState` variant with kotlinx-serialization (tests)
**And** the backup rules from Story 1.7 are updated so `runtime.db` is explicitly excluded (Robolectric XML test updated)
**And** `purchase_intent` and `grant_ledger` tables are not created yet (Epic 4)

**Given** a persisted active session
**When** `SessionEngine.restore()` runs at process start
**Then** it loads the state, dispatches `ProcessRestored`, and runs only entry effects; no one-shot effect is ever replayed (test: commit `PayConfirmed` then "crash" before effects, restore, assert billing launch is not called and `paying` is null)

**Given** `:testing`
**When** fakes are added
**Then** `FakeActiveSessionStore`, `FakeEffectRunner` (records effects), `FakeBilling` (`launch(intent)` returns programmable `PurchaseGranted` / `Failed` / `Cancelled` / `Pending`) and `FakePurchaseIntentStore` exist, and an engine test runs a full morning with fakes: `AlarmFired` → `SnoozeTapped` → `PayConfirmed` → `PurchaseGranted` → `SlotFired` at snooze end → `ImUpTapped` → valid last answer → `Completed` → `Recorded` → `Idle` with `active_session` cleared
**And** Koin in `:androidApp` wires `SessionEngine` with the Room store and the Epic 1 production policies; the production `Billing` binding is `UnavailableBilling` until Epic 4
**And** `./gradlew qualityGate` passes

### Story 1.13: Record every session in history

As a user,
I want every morning written down once, with when it rang, when I got up and how it ended,
So that my progress can be shown later and is never counted twice.
**Refs:** FR-PRG-1, NFR-14, AD-6, AD-18 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `:data`
**When** the history table is added
**Then** `app.db` migrates from version 1 to 2 adding `session_history` (`session_id` primary key, `alarm_id`, `scheduled_at`, `first_ring_at`, `ended_at` nullable, `snooze_count`, `check_types` (list), `time_to_complete_ms` nullable, `fallback_used`, `direct_boot`, `outcome` nullable: OnTime / Snoozed / Missed / Skipped / Test), with the exported v2 schema and a Room migration test from v1 with existing alarms preserved
**And** amounts paid per snooze are not stored in this table; they come from purchase records keyed to `session_id` in Epic 4 (AD-7, AD-8)

**Given** `SessionRecorder` in `:core` (the only writer of session history, via a `SessionHistoryRepository` port with `FakeSessionHistoryRepository`)
**When** the engine executes "record session start" at `AlarmFired` or `TestAlarmFired`
**Then** one row is upserted with `first_ring_at`, `scheduled_at`, `alarm_id`, `direct_boot` and a null outcome

**Given** a session reaching `Completed` or `Missed`
**When** the history-write entry effect runs
**Then** the row is upserted with `ended_at`, `snooze_count`, `check_types`, `time_to_complete_ms` (first ring to completion), `fallback_used` and outcome: OnTime when completed with 0 snoozes, Snoozed when completed with ≥ 1, Missed after the timeout, Test when `config.testMode`
**And** the engine then dispatches `Recorded`, which moves to `Idle` and clears `runtime.db`
**And** replaying the same write (process death between the write and `Recorded`) leaves exactly one row with identical values (test)
**And** no other class writes `session_history` (a unit test scans `:core` and `:data` for writers of the DAO other than `RoomSessionHistoryRepository` used by `SessionRecorder`)
**And** `./gradlew qualityGate` passes

### Story 1.14: Ring the alarm: WakeService, AlarmPlayer and the ongoing notification

As a user,
I want the alarm sound to start on the alarm stream at my chosen volume, ramping up, with a full-screen notification,
So that it wakes me whether the phone is locked, silent or in Do Not Disturb.
**Refs:** FR-ALM-3, FR-ALM-4, FR-ALM-6, NFR-1, NFR-2, NFR-7, NFR-8, AD-2, AD-4, AD-5, AD-12, UX-DR24 · **Priority:** Must · **Verify:** auto (device timing is human-verify in Stories 1.20 and 1.21)

**Acceptance Criteria:**

**Given** an enabled alarm whose occurrence fires
**When** `AlarmFiredReceiver` receives it
**Then** it calls `startForegroundService(WakeService)`; `WakeService` calls `startForeground` within the platform limit with foreground-service type `mediaPlayback` [ASSUMPTION pending Spike S2], and dispatches `AlarmFired` to `SessionEngine` (or `OverlapAlarmFired` when a session is already active)
**And** the `AlarmFiredHandler` still schedules the next occurrence of a repeating alarm and disables a one-time alarm; a disabled or deleted alarm that fires does not ring and is logged
**And** `WakeService`, `WakeActivity` and the receivers are `directBootAware`, and `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `USE_FULL_SCREEN_INTENT`, `WAKE_LOCK` and `VIBRATE` pass the permission allowlist

**Given** the "sound playing" entry effect
**When** `AndroidAlarmPlayer` (the only player, owned by `WakeService`) starts
**Then** it plays the alarm's sound looping with `AudioAttributes` usage `USAGE_ALARM` (independent of media and ringer volume), sets the alarm stream to the alarm's `volumePercent` at the start of the ring and restores the user's previous alarm-stream volume when the session ends (confirmed by owner)
**And** when `gradualVolume` is true, player gain ramps linearly from `rampStartPercent` to full over 30 s using a pure `rampGain(elapsed, start, duration)` function in core (unit-tested at 0 s, 15 s, 30 s, 45 s and with start = 100%); when false, the first audible frame is already at the set volume (unit-tested)
**And** vibration runs with alarm usage when `vibration` is on and not at all when off
**And** the default built-in sound is bundled (OGG, licence recorded in `docs/sounds/LICENSES.md`), and when the chosen sound cannot be opened or errors during playback the player switches to it (never silent; full sound-library fallback in Story 1.17)

**Given** the "wake UI shown" entry effect
**When** the notification is posted
**Then** it uses channel "Alarms" (EXPERIENCE.md Key strings) with importance high, category `alarm`, ongoing, monochrome sunrise small icon with accent tint, title = alarm time, text "{time} alarm · Tap to return to your alarm", a full-screen intent and content intent to `WakeActivity`, and no action that stops the sound

**Given** `WakeActivity` (skeleton in this story, full UI in Story 1.15)
**When** it starts
**Then** it calls `setShowWhenLocked(true)`/`setTurnScreenOn(true)` on API 27+ and sets `FLAG_SHOW_WHEN_LOCKED`/`FLAG_TURN_SCREEN_ON` on API 26, Back does nothing, it renders from `SessionEngine.state` without any loading state, and it forwards taps only as session events
**And** the app never starts an activity from the background (only the full-screen intent and notification tap open `WakeActivity`)

**Given** an uncaught exception inside the wake flow
**When** it reaches the `WakeService` boundary
**Then** it is reported through the `CrashReporter` port (fake until Story 1.19), the player switches to the default sound, and `ProcessRestored` is dispatched (AD-12, NFR-2)

**Given** the session reaches `Completed`, `Missed` or `Idle`
**When** the entry effects run
**Then** the sound and vibration stop, the notification is removed, the slot is cancelled and `WakeService` stops itself, leaving no running service (NFR-8)
**And** Robolectric tests cover: receiver → service start → engine `Ringing`; player attributes `USAGE_ALARM`; ramp values; notification fields; stop on completion; missing sound → default
**And** `./gradlew qualityGate` passes

### Story 1.15: Ringing screen over the lock screen with "I'm up"

As a user,
I want a bright, simple ringing screen with the time and one big "I'm up" button,
So that I can stop the alarm half-asleep.
**Refs:** FR-ALM-4, FR-RNG-1 (visual shell), FR-MSG-4, NFR-7, NFR-9, AD-2, AD-5, AD-11, UX-DR2, UX-DR9, UX-DR12, UX-DR13, UX-DR14, UX-DR64, UX-DR66, UX-DR67, UX-DR72, UX-DR74, UX-DR78 · **Priority:** Must · **Verify:** auto, plus (human-verify) timing in Story 1.21

**Acceptance Criteria:**

**Given** a session in `Ringing`
**When** `WakeActivity` renders
**Then** it uses `PpsTheme(wake = true)` (Sunrise), with the optional `sunrise-gradient-top` → `bg-sunrise` gradient only in the top 40% behind the label, clock (`clock-xl`, tabular figures, capped at 1.3× font scale) and date, and a flat `bg-sunrise` thumb zone
**And** `button-wake-primary` "I'm up" is full width, 72 dp, `rounded.full`, `accent-sunrise` fill with `on-accent-sunrise` label in `button-wake`, the largest element on screen, always enabled, and in the bottom 40% of the screen
**And** 16 dp below it the snooze control renders the `SnoozeAvailabilityPolicy` result: `button-snooze-disabled` (64 dp, `disabled-container-sunrise` fill, `disabled-content-sunrise` label, leading `block` icon) reading "Snooze unavailable: prices not loaded yet" in a normal Epic 1 session and "Test · no charge" in a test session, with TalkBack "Snooze unavailable, {reason}"; the enabled "Snooze · {price}" variant renders for `Available(price)` in a preview and screenshot (tapping it arrives in Epic 4)
**And** the screen has no loading state and renders from the in-memory `SessionEngine.state` (a test asserts no suspend repository call happens before the first frame)

**Given** the ringing screen
**When** the user taps "I'm up"
**Then** `ImUpTapped` is dispatched (plus `UserInteracted`), the session enters Grace (muted), the `Placeholder` check step submits its answer automatically, the session reaches `Completed` → `Recorded` → `Idle`, the sound stops, the notification is removed and `WakeActivity` finishes, with history outcome OnTime (Epic 3 replaces the placeholder with real checks)
**And** any other tap on the screen dispatches `UserInteracted`, and Back does nothing while Home and Recents still work

**Given** accessibility and layout rules
**When** semantic and Roborazzi tests run
**Then** TalkBack initial focus is the clock (read as the full time, for example "6:15 AM"), then "I'm up"; every wake action is ≥ 64 dp ("I'm up" 72 dp); at 200% font scale nothing clips and both actions stay on screen without scrolling; with animator duration scale 0 every transition is instant
**And** screenshots exist for first ring with and without label, snooze unavailable, test alarm, and enabled-snooze preview, in Sunrise at 100% and 200% font scale
**And** an instrumented test on the Gradle Managed Device fires a debug-scheduled alarm and asserts `WakeActivity` is resumed and "I'm up" is displayed within 1,000 ms of the receiver running (NFR-7 on emulator; device timing is checked in Story 1.21 (human-verify))
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.16: Stop a forgotten alarm after 30 minutes

As a user (and the people sleeping near me),
I want an alarm nobody touches to stop after 30 minutes and be logged as missed,
So that it doesn't ring all day when I'm away from my phone.
**Refs:** FR-ALM-9, FR-PRG-1, FR-MSG-4, AD-2, AD-3, AD-18, UX-DR35, UX-DR80, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** an active ringing session
**When** `WakeService` runs
**Then** it calls `SessionEngine.tick()` when the nearest deadline (interaction timeout or grace end) is due, scheduling the next tick from `Deadline.remaining` with the monotonic clock, and also on every `SlotFired`
**And** when `NoInteractionTimeout` fires the session moves to `Missed`, the sound and vibration stop, the notification is removed, `WakeActivity` finishes, and history records outcome Missed via `SessionRecorder`

**Given** a Missed session in history that the user has not dismissed
**When** the user next opens Home
**Then** a `note-inline` shows "Your {time} alarm stopped after 30 minutes. Logged as missed." until the user dismisses it (dismissal stored per session id in DataStore, device-protected, created here with `PreferenceDataStoreFactory.createWithPath`)

**Given** Robolectric tests with fake clocks driving `WakeService`
**When** 30 minutes pass with no interaction
**Then** the service stops and the Missed row exists; with a `UserInteracted` at minute 20 the stop happens at minute 50; a wall-clock change of −1 h during the ring does not shorten or extend the timeout
**And** Roborazzi screenshots cover Home with the missed note in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.17: Built-in sound library with preview and a never-silent fallback

As a user,
I want to pick from at least ten loud alarm sounds or my phone's ringtones and hear them first,
So that I wake to a sound that works for me, and never to silence.
**Refs:** FR-SND-1, FR-SND-2, FR-SND-5, FR-MSG-4, NFR-2, NFR-9, AD-5, AD-14, UX-DR53, UX-DR43, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the app bundle
**When** the sound library is added
**Then** at least 10 royalty-free alarm sounds (OGG) are bundled, each listed in `docs/sounds/LICENSES.md` with source URL, author and licence (CC0 or equivalent), and a core `SoundCatalog` lists them with a `SoundRef.BuiltIn(id)` and a resource name; exactly one is marked default
**And** system alarm ringtones are listed through `RingtoneManager` (`TYPE_ALARM`) as `SoundRef.System(uri)`

**Given** the loudness rule (FR-SND-1)
**When** `./gradlew checkSoundLoudness` runs (a `qualityGate` dependency)
**Then** it measures every bundled sound with ffmpeg `ebur128` and fails, naming the file, if the peak is below −3 dBFS or the integrated loudness is below −14 LUFS; if ffmpeg is missing it fails with an install hint, and CI installs ffmpeg
**And** a fixture test proves a quiet file fails the check and a compliant file passes

**Given** the Alarm editor Sound row
**When** the user taps it
**Then** the Sound picker (pushed screen, `top-app-bar`) lists built-in sounds then system ringtones as `sound-row`s (56 dp, radio selection, name in `body`, source caption "Built-in" or "System" (EXPERIENCE.md Key strings), 48 dp preview button)
**And** selecting a row and returning updates the editor's Sound row; the choice is saved with the alarm on "Save"

**Given** a `sound-row` preview button
**When** the user taps it
**Then** the sound plays once through a preview player with `USAGE_ALARM` at the alarm's volume, a second preview stops the first, and preview stops when the user leaves the screen or the app goes to the background
**And** preview is announced by TalkBack with role and state ("Play preview" / "Stop preview" (EXPERIENCE.md Key strings))

**Given** a chosen sound that is missing or broken (system ringtone URI no longer resolves, file unreadable, decoder error at prepare or during playback)
**When** the alarm rings
**Then** `AlarmPlayer` plays the default built-in sound within the same ring, logs the fallback without file paths, and never leaves the alarm silent (NFR-2)
**And** the Sound picker and editor show "File missing. Default sound will play." for that choice
**And** Robolectric tests cover: missing URI → default; `MediaPlayer` error callback mid-ring → default; default resource always resolves
**And** Roborazzi screenshots cover the picker (list, selected, missing-file row) in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.18: Test alarm and debug fire-now hook

As a user,
I want to ring a test of my alarm that can never charge me,
So that I know it works on my phone before tomorrow morning.
**Refs:** FR-ALM-8, FR-MSG-4, NFR-11, AD-2, AD-14, UX-DR14, UX-DR27, UX-DR56, UX-DR78, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the Alarm editor
**When** the bottom bar is shown
**Then** it contains a `button-text` "Test alarm" next to "Save"

**Given** the user taps "Test alarm"
**When** no session is active
**Then** a snackbar shows "Lock your phone. We'll ring in 10 seconds." and a test ring is scheduled through `AlarmScheduler.scheduleTest` 10 s ahead using the editor's current (even unsaved) values
**And** when it fires, `TestAlarmFired` starts a session with `config.testMode = true`: the full Epic 1 flow runs (sound with ramp, vibration, full-screen ringing screen, "I'm up", placeholder check), the snooze control reads "Test · no charge" and is not tappable, no billing call is possible (test asserts `FakeBilling.launch` is never called in test mode), and history records outcome Test

**Given** a session is already active
**When** a test alarm fires
**Then** the `TestAlarmFired` event is ignored and logged per the AD-2 rule, and the running session is unchanged

**Given** the debug build only
**When** `adb shell am broadcast -a com.payper.snooze.debug.FIRE --ei seconds N [--es alarmId ID] [--ez test true|false]` is sent
**Then** `DebugFireReceiver` schedules that alarm (or a synthetic one with defaults) to fire in N seconds through `AlarmScheduler`, as a real or test session
**And** the receiver lives only in the `debug` source set; a test inspects the merged release manifest and release classes and fails if `DebugFireReceiver` or the `debug.FIRE` action is present
**And** Roborazzi screenshots cover the editor bottom bar and the test snackbar in Light and Dark and at 200% font scale, and the Sunrise ringing screen in test mode
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.19: Permissions for reliable alarms and crash reporting

As a user,
I want the app to ask for exactly the permissions an alarm needs and tell me plainly when one is missing,
So that my alarm isn't silently blocked, and crashes get fixed.
**Refs:** FR-ALM-4, FR-ALM-12, FR-MSG-4, NFR-4, NFR-13, NFR-15, AD-4, AD-5, AD-15, UX-DR33, UX-DR50 (partial), UX-DR84 · **Priority:** Must · **Verify:** auto, plus (human-verify) Crashlytics console check

**Acceptance Criteria:**

**Given** a `ReliabilityProbe` port (`notificationsAllowed`, `fullScreenIntentAllowed`, `exactAlarmsAllowed`) with an Android adapter and `FakeReliabilityProbe`
**When** it is evaluated on API 26–36
**Then** notifications use `NotificationManagerCompat.areNotificationsEnabled()`, full-screen intent uses `NotificationManager.canUseFullScreenIntent()` on API 34+ (always true below), and exact alarms use `AlarmManager.canScheduleExactAlarms()` on API 31–32 only (always true on 33+ with `USE_EXACT_ALARM`, and on ≤ 30)
**And** Robolectric tests cover API 26, 31, 32, 33, 34 and 36

**Given** API 33+ and notifications not yet granted
**When** the user saves their first enabled alarm
**Then** the app requests `POST_NOTIFICATIONS` once from that screen (never from the background)

**Given** any probe item is false
**When** Home is in the foreground (re-evaluated on every `ON_START`)
**Then** a non-dismissible `banner-warning` (`surface-variant`, leading `error` icon) shows "Alarms may not ring. Fix settings" with a `button-text` "Fix" that deep-links to the first failing setting: app notification settings, `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (API 34+), or `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31–32)
**And** the banner clears itself once every item is OK, and granting exact alarms triggers `rescheduleAll()` through the Story 1.10 receiver
**And** the full reliability checklist (DND, battery optimization, OEM guidance) comes in Epic 5

**Given** Firebase Crashlytics (BoM 34.19.0)
**When** it is integrated
**Then** a `CrashReporter` port has `FirebaseCrashReporter` and `FakeCrashReporter`; `FirebaseInitProvider` is removed from the merged manifest and Firebase initialises only when the user is unlocked (`UserManager.isUserUnlocked`, else on `ACTION_USER_UNLOCKED`) (AD-15)
**And** the manifest sets `firebase_analytics_collection_enabled` = false and `google_analytics_adid_collection_enabled` = false (Analytics consent comes in Epic 5), and no Crashlytics custom key or log contains personal data, purchase tokens, labels or file paths (unit test on the reporter wrapper)
**And** the google-services and Crashlytics Gradle plugins apply only when `google-services.json` exists (from CI secrets `GOOGLE_SERVICES_JSON_DEBUG` / `GOOGLE_SERVICES_JSON_RELEASE`); without it the build uses the no-op reporter and `qualityGate` still passes
**And** new Firebase coordinates are added to `config/dependency-allowlist.txt` in the same change, and the permission allowlist still passes

**Given** the owner has created the Firebase debug and prod projects for the final package id and stored the config secrets
**When** a debug build with the file triggers the debug-only test crash (`adb shell am broadcast -a com.payper.snooze.debug.CRASH`)
**Then** the crash appears in the Crashlytics console of the debug project within 10 minutes (human-verify, recorded in the story file with date)
**And** Roborazzi screenshots cover Home with the banner in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 1.20: Spike S2: alarm reliability on the device matrix

As the owner,
I want overnight evidence that alarms ring on real phones in hostile conditions,
So that the foreground-service type and the Epic 2 escape protections are chosen from facts.
**Refs:** FR-ALM-3, FR-ALM-4, FR-ALM-5, FR-SES-2, FR-SES-4, NFR-1, NFR-7, NFR-13, AD-4, AD-5; PRD §10 S2, Q18 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the Epic 1 debug build from `main` and, for the Epic 2 behaviours, a prototype on branch `spike/s2-reliability` (backup alarm re-armed every 60 s while ringing, notification return path) that is never merged
**When** the owner tests the device matrix (a Pixel, a Samsung, a Xiaomi and one budget device), recording device, Android version and date per run
**Then** `docs/spikes/S2.md` records for each device: ring latency from scheduled time (target ≤ 2 s, NFR-1) and ringing-screen latency (target ≤ 1 s, NFR-7) measured from screen recordings, with screen off and locked, in Doze (`adb shell dumpsys deviceidle force-idle` and one real overnight run), battery saver on, Do Not Disturb on (default "alarms allowed"), silent mode, and headphones connected
**And** it records what happens after an overnight reboot before first unlock (expected to fail until Epic 2), after "Stop" in the OEM Task Manager, after swiping the app from Recents, and during an incoming call, with and without the prototype backup alarm
**And** it records whether tapping the ongoing notification returns to the ringing screen within 1 s without any background activity start (FR-SES-4), and whether a heartbeat `setAlarmClock` every 60 s changes the system next-alarm indicator visibly (Q18)

**Given** the findings
**When** the owner writes the Decision section
**Then** it states the foreground-service type (`mediaPlayback` or `systemExempted`) with the reason (Android 14–16 start restrictions, Play declaration impact), and any change from `mediaPlayback` becomes a new story before Epic 2 starts
**And** every failed target becomes a bug story or an Epic 2 story note, and contradictions with the Architecture Spine go through `bmad-correct-course`
**And** the story file records pass/fail per scenario and device; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/spikes/S2.md` committed

### Story 1.21: Epic 1 device verification checklist

As the owner,
I want to confirm on real phones everything in Epic 1 that tests can't prove,
So that Epic 2 builds on an alarm I know rings.
**Refs:** FR-ALM-1–9, FR-ALM-12, FR-SND-1, FR-SND-2, FR-SND-5, FR-PRG-1, FR-MSG-4, NFR-1, NFR-2, NFR-7, NFR-9, NFR-11 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build installed on each device of the matrix (Pixel, Samsung, Xiaomi, budget device)
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. An alarm set 2 minutes ahead rings within 2 s of the scheduled time with the screen off and locked (NFR-1).
2. The ringing screen is visible within 1 s of the sound starting (NFR-7), measured on a screen recording.
3. It rings in forced Doze, with battery saver on, with Do Not Disturb on, and with the ringer on silent and media volume at 0, on the alarm stream.
4. With "Gradually increase volume" on, the volume ramps from the starting volume to the set volume within 30 s; with it off, the alarm starts at the set volume; vibration on and off are respected.
5. The ringing screen appears over the lock screen when locked and when the screen is off; when the phone is unlocked and in use, the heads-up notification appears and tapping it opens the ringing screen.
6. "I'm up" stops the sound, removes the notification and closes the screen; Home then shows the next "Rings in …" countdown.
7. Create, edit, duplicate, disable, enable and delete alarms; repeat days skip unselected days (checked by setting the date manually); a one-time alarm set for a passed time rings tomorrow and shows "Rings tomorrow at {time}."
8. After a reboot (unlock, then wait) the alarm still rings on time; after installing a newer debug build over the old one (`adb install -r`) it still rings.
9. After changing the clock by +1 h, and after changing the time zone, the alarm rings at the set local time.
10. With the zone set to Europe/Berlin and the date set manually to a DST spring-forward day, a 02:30 alarm rings at 03:30; on the fall-back day a 02:30 alarm rings once.
11. A test alarm rings 10 s after "Test alarm" with the phone locked and shows "Test · no charge" on a disabled snooze control.
12. Leaving an alarm untouched for 30 minutes stops it, and Home shows "Your {time} alarm stopped after 30 minutes. Logged as missed."
13. Every built-in sound previews on the alarm stream and stops when leaving the picker; a sound forced missing via the debug hook plays the default sound.
14. On API 33+, denying notifications shows "Alarms may not ring. Fix settings" and "Fix" opens the right setting; on API 34+ revoking full-screen intent does the same; on an Android 12 device or emulator revoking exact alarms does the same, and the banner clears on return.
15. With TalkBack on, the ringing screen focuses the clock first, then "I'm up", and the disabled snooze reads its reason; at 200% font size both actions stay on screen.
16. All copy seen during the checklist matches EXPERIENCE.md (no em dashes, no filler, "No charge." wording where relevant) (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done
