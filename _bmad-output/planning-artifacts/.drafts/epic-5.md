## Epic 5: First run, settings and trust

A new user is guided through the mission, the alarm behaviour disclosure and consent, the base fee, a first alarm, checks, the full reliability checklist (with Do Not Disturb detection and manufacturer guidance), an anonymous-stats choice and a test alarm on a locked screen. Settings is complete: wake and snooze defaults, "Bright wake screen", theme override, usage stats, reliability checklist, "How payments & refunds work", privacy policy, terms, support and "Delete all data". Firebase Analytics stays off until the user consents (AD-15). The privacy policy, terms and support pages are published on GitHub Pages from `docs/`. **The 14-day closed test starts at the end of this epic.**

This epic builds on Epic 1 (`ReliabilityProbe`, the Story 1.19 `banner-warning`, Crashlytics and the Firebase init-after-unlock rule, `GlobalSettings`/`ConfigResolver`, `PpsTheme(mode)`, the Story 1.18 test alarm, the Story 1.4 Play Console record and license testers), Epic 2 (session lock: Settings and "Delete all data" are unreachable during a session), Epic 3 (check picker, `check-type-card` with "Try it", QR registration, Success screen basic) and Epic 4 (base fee `stepper`, ladder preview, commitment lock, Settings shell and `nav-bar`, `PriceCatalog`, "Problem with a charge?", Purchase history, `BackgroundWork`). It does not redefine them.

Every UI story carries the two standing acceptance criteria from Epic 1: (1) the `pps-design` Done checklist is copied into the story file with every item ticked; (2) all new user-facing strings live in Compose Multiplatform resources, match `EXPERIENCE.md > Voice and Tone > Key strings` verbatim where a key string exists, and pass `CopyRulesTest` (FR-MSG-4). A string that EXPERIENCE.md does not define is marked `[ASSUMPTION: add to EXPERIENCE.md Key strings]` in the story and listed for the owner.

### Story 5.1: Wake and snooze defaults and theme override in Settings

As a user,
I want to set the default grace window, vibrate-in-grace and snooze length for new alarms, and choose System, Light or Dark for the app,
So that new alarms start the way I like and the app is comfortable to use at night.
**Refs:** FR-SET-1 (remaining), FR-MSG-4, NFR-9, AD-6, AD-10, AD-11, AD-16, UX-DR2, UX-DR38, UX-DR40, UX-DR41, UX-DR51, UX-DR62, UX-DR64, UX-DR66, UX-DR67 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `GlobalSettings` in the device-protected settings DataStore
**When** this story extends it
**Then** it holds `defaultGraceSeconds` (15–30, default 20), `defaultVibrateInGrace` (default off) [ASSUMPTION: owner to confirm default], `defaultSnoozeLengthMinutes` (5, 9, 10 or 15, default 9) and `themeMode` (System, Light, Dark; default System), edited only through core use cases `SetDefaultGrace`, `SetDefaultVibrateInGrace`, `SetDefaultSnoozeLength`, `SetThemeMode` that reject out-of-range values with `DomainError.InvalidSetting`
**And** these defaults apply only to alarms created after the change (the Alarm editor reads them for a new alarm), never alter existing alarms, and are therefore not commitment-locked fields [ASSUMPTION: owner to confirm defaults are outside the commitment lock]

**Given** Settings (Epic 4 shell)
**When** it renders
**Then** the Snooze section gains "Default snooze length" as a `segmented-control` 5 / 9 / 10 / 15 min; a "Wake" section shows "Default grace window" as a `slider` 15–30 s in 1 s steps with the value announced, and "Vibrate in grace window" as a `switch`; an "Appearance" section shows a `segmented-control` "System" / "Light" / "Dark" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** switches and segmented controls apply immediately (no Save)

**Given** the theme override
**When** the user picks Light or Dark
**Then** every app screen re-renders at once with `PpsTheme(mode = Light | Dark)`, System follows `isSystemInDarkTheme()` and updates when the system setting changes, the choice survives restart, and wake screens always stay Sunrise regardless of the choice (test)

**Given** the new Settings sections
**When** Roborazzi and semantic tests run
**Then** screenshots exist for the Wake, Snooze and Appearance sections in Light and Dark and at 200% font scale, and for Home rendered under each theme mode, with targets ≥ 48 dp
**And** ViewModel tests cover bounds, immediate apply and that a new alarm opened in the editor uses the new defaults while an existing alarm keeps its values
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.2: Bright wake screen

As a user,
I want the alarm screen to turn fully bright when it rings, and go back to normal when I'm done,
So that the light helps me wake up.
**Refs:** UX-DR10, UX-DR2, UX-DR62, UX-DR78, UX-DR87, FR-MSG-4, AD-5, AD-16, NFR-13 · **Priority:** Should · **Verify:** auto, plus (human-verify) in Story 5.13

**Acceptance Criteria:**

**Given** `GlobalSettings.brightWakeScreen` (default on)
**When** a session starts
**Then** `ConfigResolver` copies it into the frozen `SessionConfig` (`brightWakeScreen`), so changing the setting during a session has no effect until the next session (AD-16) (test)
**And** the Settings "Wake" section has a `switch` "Bright wake screen" (default on) with the caption "Raises screen brightness on alarm screens." [ASSUMPTION: add to EXPERIENCE.md Key strings]; the onboarding key string "Your alarm screen turns bright to help you wake. Change it in Settings." is used in Story 5.12

**Given** `config.brightWakeScreen` is true
**When** `WakeActivity` shows any wake screen (Ringing, Snooze confirm, Check, Fallback check picker, Success)
**Then** it sets its window `screenBrightness` to `BRIGHTNESS_OVERRIDE_FULL` (window-only, no `WRITE_SETTINGS` permission, permission allowlist unchanged)
**And** brightness returns to the system level when the user taps "Done" on Success, on the Snoozed surface, and whenever `WakeActivity` stops or finishes (Home, Recents, crash), because the override is window-scoped (Robolectric test on window attributes for each case)
**And** when the system Extra dim setting is active (`Settings.Secure` `reduce_bright_colors_activated` = 1, API 31+) the override is not applied [ASSUMPTION: owner to confirm that "respects Extra dim" means no override while Extra dim is on]
**And** with the setting off, the window brightness is never touched (test)
**And** no copy anywhere makes a blue-light or health claim (reviewed in the `pps-design` checklist)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.3: Full reliability probe: Do Not Disturb, battery and the locked-screen test

As a user,
I want the app to detect every phone setting that can stop my alarm,
So that I find out tonight, not tomorrow morning.
**Refs:** FR-ONB-2, FR-ONB-3, FR-ONB-4, FR-SET-2, NFR-1, NFR-11, NFR-13, AD-4, AD-5, AD-12 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** the Story 1.19 `ReliabilityProbe` port
**When** it is extended
**Then** it adds `dndAllowsAlarms`, `batteryOptimizationIgnored`, `backgroundRestricted`, `standbyBucketRestricted` and `cameraGranted`, implemented in `AndroidReliabilityProbe` and `FakeReliabilityProbe`
**And** `dndAllowsAlarms` is false when `NotificationManager.getCurrentInterruptionFilter()` is `INTERRUPTION_FILTER_NONE`, or when (API 28+) the notification policy's `priorityCategories` lacks `PRIORITY_CATEGORY_ALARMS`, so a scheduled night-time DND that would block alarms is caught even while DND is off; OEM "total silence" modes the API doesn't expose are not detected (covered by manufacturer guidance, Story 5.5)
**And** `batteryOptimizationIgnored` uses `PowerManager.isIgnoringBatteryOptimizations`, `backgroundRestricted` uses `ActivityManager.isBackgroundRestricted()` (API 28+), `standbyBucketRestricted` is `UsageStatsManager.getAppStandbyBucket() == STANDBY_BUCKET_RESTRICTED` (API 30+), and no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` or `ACCESS_NOTIFICATION_POLICY` permission is added (permission allowlist unchanged)
**And** Robolectric tests cover each value on API 26, 28, 30, 31, 33, 34 and 36

**Given** the locked-screen test result
**When** a test session reaches `Completed`
**Then** if the keyguard was locked when `TestAlarmFired` was handled (recorded in `SessionConfig.testStartedLocked` from `KeyguardManager.isKeyguardLocked`), `lockedTestPassedAt` is stored in the settings DataStore; a test completed unlocked does not set it (test)

**Given** pure `ReliabilityChecklist.evaluate(probe, alarms, device, stored)` in core
**When** it runs
**Then** it returns rows in this order with status OK, NeedsFix, Revoked or Hidden: Notifications; Full-screen alarm; Exact alarms (Hidden except API 31–32); Do Not Disturb; Battery (fails when optimization is not ignored, background is restricted or the bucket is restricted); Manufacturer settings (Hidden unless the maker is covered, Story 5.5); Camera (Hidden unless an enabled alarm uses QR/Barcode, or House Hunt later); Microphone (Hidden unless an alarm uses a recording, none until Epic 7); Locked-screen test
**And** a row that was OK at the last evaluation (stored set in DataStore) and now fails is Revoked, otherwise NeedsFix
**And** `alarmBlocking(rows)` is true when any of Notifications, Full-screen alarm, Exact alarms, Do Not Disturb or Battery is not OK; Manufacturer, Camera, Microphone and Locked-screen test never raise the Home banner [ASSUMPTION: owner to confirm which rows raise the banner]
**And** table tests cover every row state and the Revoked transition, and Kover keeps core ≥ 90%
**And** `./gradlew qualityGate` passes

### Story 5.4: Reliability checklist screen and revoked-setting warnings

As a user,
I want one screen that shows each setting my alarm needs, why, and a Fix button that takes me there,
So that I can make my alarm reliable in a minute and notice when a phone update breaks it.
**Refs:** FR-ONB-2, FR-ONB-3, FR-SET-2, FR-ALM-12, FR-MSG-4, NFR-9, AD-5, AD-11, UX-DR33, UX-DR50, UX-DR51, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR92 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 5.13

**Acceptance Criteria:**

**Given** Settings
**When** the user taps "Reliability checklist" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**Then** a pushed screen shows each non-hidden row from Story 5.3 as a `checklist-row` (64 dp): leading icon, title (`body`), reason (`caption`), and trailing `check_circle` in `success` with "OK" or a `button-outlined` "Fix"
**And** row titles and reasons are [ASSUMPTION: add to EXPERIENCE.md Key strings]: "Notifications" · "Needed to show your alarm."; "Full-screen alarm" · "Shows the alarm over the lock screen."; "Exact alarms" · "Lets the alarm ring on time."; "Do Not Disturb" · "Do Not Disturb must allow alarms."; "Battery" · "Stops Android from pausing your alarm."; "Manufacturer settings" · "Your phone may stop apps in the background."; "Camera" · "Needed for your QR/Barcode check."; "Microphone" · "Needed to record messages."; "Test alarm" · "Ring a test with your phone locked."
**And** a Revoked row's reason reads "Alarm may not ring: {setting} turned back on" or "Alarm may not ring: {setting} turned off" as fits, with exactly "Alarm may not ring: battery optimization turned back on" for Battery (PRD FR-ONB-3) [ASSUMPTION: add the other revoked variants to EXPERIENCE.md Key strings]

**Given** a row with "Fix"
**When** it is tapped
**Then** it deep-links to: app notification settings; `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` (API 34+); `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31–32); `Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS`; `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (or app details when background is restricted); the Story 5.5 guidance screen; the camera runtime permission request (app details if permanently denied); the Story 1.18 test alarm for "Test alarm"
**And** every intent is checked with `resolveActivity` first and falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`, then `ACTION_SETTINGS` (Robolectric test per row with and without a resolver)
**And** statuses are re-evaluated on every `ON_START`/`ON_RESUME`, so a fixed row turns "OK" on return without a tap

**Given** all rows are OK
**When** the screen renders
**Then** every row shows "OK" and the `button-outlined` "Ring a test alarm" stays at the bottom, scheduling a locked-screen test through Story 1.18 with the soonest enabled alarm's settings (or defaults)

**Given** the Story 1.19 `banner-warning` on Home
**When** `alarmBlocking` is true
**Then** it shows "Alarms may not ring. Fix settings" on Home and on Settings, its "Fix" now opens the Reliability checklist (F7), it is not dismissible, and it clears itself once `alarmBlocking` is false (re-evaluated on every app foreground)
**And** granting exact alarms still triggers `rescheduleAll()` (Story 1.10)

**Given** the checklist states
**When** Roborazzi and semantic tests run
**Then** screenshots exist for all OK, several NeedsFix, a Revoked battery row, camera row visible, and API 31 with the exact-alarm row, in Light and Dark and at 200% font scale, and TalkBack reads each row as title, reason and status
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.5: Manufacturer background guidance

As a user with a Xiaomi, Samsung, Huawei, Oppo/Realme, Vivo or OnePlus phone,
I want short steps for my phone's own background settings, with a button that opens them,
So that my phone's battery tools don't kill my alarm.
**Refs:** FR-ONB-2, FR-ONB-3, FR-MSG-4, NFR-1, NFR-9, AD-5, UX-DR50, UX-DR64, UX-DR66 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 5.13

**Acceptance Criteria:**

**Given** `Build.MANUFACTURER` and `Build.BRAND` (case-insensitive)
**When** `DeviceMaker` is resolved
**Then** it maps xiaomi, redmi, poco → Xiaomi; samsung → Samsung; huawei, honor → Huawei; oppo, realme → OppoRealme; vivo, iqoo → Vivo; oneplus → OnePlus; anything else → Other (Manufacturer row Hidden) (unit-tested per value)

**Given** a covered maker
**When** the user taps "Fix" on the Manufacturer settings row
**Then** a pushed guidance screen shows 2 to 4 numbered steps for that maker (for example Xiaomi: allow Autostart, set Battery saver to "No restrictions", lock the app in Recents), each step ≤ 25 words [ASSUMPTION: add each maker's steps to EXPERIENCE.md Key strings], sourced from `docs/oem-guidance.md`, which lists per maker the steps, the source (dontkillmyapp.com page and OEM help pages), the Android/skin versions checked and a review date
**And** an "Open settings" `button-filled` [ASSUMPTION: add to EXPERIENCE.md Key strings] tries that maker's known settings components from `OemIntents` in order, launching the first one that `resolveActivity` finds, falling back to app details; it never uses a component that is not resolvable (Robolectric test per maker with and without resolvable components)
**And** a `button-outlined` "I've done this" [ASSUMPTION: add to EXPERIENCE.md Key strings] marks the row OK, storing the maker and `Build.FINGERPRINT`

**Given** a stored confirmation
**When** `Build.FINGERPRINT` changes (system update)
**Then** the Manufacturer row becomes Revoked with "Alarm may not ring: check your phone's background settings again" [ASSUMPTION: add to EXPERIENCE.md Key strings] (test)
**And** Roborazzi screenshots cover each maker's guidance screen in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.6: Anonymous usage stats, off until the user says yes

As a user,
I want usage statistics sent only if I turn them on, and only three anonymous numbers,
So that my mornings stay private unless I choose to help.
**Refs:** FR-ONB-6, FR-SET-6, FR-MSG-4, NFR-4, NFR-15, AD-13, AD-15, UX-DR41, UX-DR51, UX-DR84 · **Priority:** Must · **Verify:** auto, plus (human-verify) DebugView check

**Acceptance Criteria:**

**Given** `core`
**When** telemetry is added
**Then** a `Telemetry` port takes a sealed `TelemetryEvent` with exactly `SessionOutcome(outcome)`, `SnoozeCount(count)` and `CheckTypeUsed(type)`, whose parameters are enums or integers only (a unit test asserts no `String` field other than enum names), and `FakeTelemetry` records events
**And** a `ConsentStore` (settings DataStore) holds `analyticsConsent` (default false) and `analyticsAsked` (default false), and `ConsentGatedTelemetry` drops every event while consent is false, never queueing it for later (test: 3 events before consent, consent on, 1 event → only 1 delivered)
**And** after `Recorded` for a non-test session, one `SessionOutcome`, one `SnoozeCount` and one `CheckTypeUsed` per check type used are logged; test sessions log nothing [ASSUMPTION: owner to confirm test sessions are excluded]

**Given** Firebase Analytics (BoM 34.19.0)
**When** `FirebaseTelemetry` is integrated
**Then** the manifest keeps `firebase_analytics_collection_enabled` = false (Story 1.19) and adds `google_analytics_adid_collection_enabled` = false, `google_analytics_ssaid_collection_enabled` = false, `google_analytics_default_allow_ad_storage` = false, `google_analytics_default_allow_ad_user_data` = false, `google_analytics_default_allow_ad_personalization_signals` = false and `google_analytics_automatic_screen_reporting_enabled` = false
**And** the merged `com.google.android.gms.permission.AD_ID` permission is removed with `tools:node="remove"` so the permission allowlist still passes, and the Analytics coordinates are added to `config/dependency-allowlist.txt` in the same change
**And** `setAnalyticsCollectionEnabled(true)` and analytics-storage consent granted are called only when consent is true and the user is unlocked; turning consent off calls `setAnalyticsCollectionEnabled(false)` and `resetAnalyticsData()`; no user id or user property is ever set (Robolectric test on a facade)
**And** Firebase automatic events (`first_open`, `session_start`, `app_remove`) are left on while consented because PRD CM-2 uses `app_remove` [ASSUMPTION: owner to confirm against NFR-15 "no other events"]

**Given** Settings
**When** the "Usage stats" section renders
**Then** it has a `switch` "Share anonymous usage stats" [ASSUMPTION: add to EXPERIENCE.md Key strings], default off, applying immediately
**And** Roborazzi screenshots cover the section in Light and Dark and at 200% font scale

**Given** the owner's Firebase debug project
**When** a debug build with consent on completes a session
**Then** the three events appear in Firebase DebugView with no other custom events, and with consent off nothing appears (human-verify, recorded with date)
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.7: Privacy policy, terms and support pages on GitHub Pages

As the owner,
I want the privacy policy, terms and support page published from the repo,
So that Play Console, the app and testers link to one honest, up-to-date source.
**Refs:** FR-SET-3, NFR-4, NFR-5, NFR-14, NFR-15, AD-15; PRD §13 launch blockers, Q17 · **Priority:** Must · **Verify:** auto, plus (human-verify) owner review and live URLs

**Acceptance Criteria:**

**Given** `docs/site/`
**When** the pages are written
**Then** it contains `index.md`, `privacy.md`, `terms.md`, `support.md` and `payments.md` in plain Markdown with a minimal Jekyll config, and only `docs/site/` is published (spike notes and decisions in `docs/` stay unpublished) by `.github/workflows/pages.yml` using the official Pages actions on pushes to `main` that touch `docs/site/`
**And** `privacy.md` covers: no account and no backend; what stays on the phone (alarms, history, purchase records, photos, recordings); Android Auto Backup of alarms, settings, history and purchase records to the user's Google account and what is excluded (photos, recordings, custom sounds, the active session, pending purchase intents); Firebase Crashlytics always on with no personal content; Firebase Analytics only if the user opts in, limited to three anonymous events, no advertising ID; Google Play Billing processes payments, the app keeps order ID, price and time locally and sends only random ids; camera images processed on the device and never uploaded; 18+ audience; how to delete data ("Delete all data" or uninstall); contact; effective date
**And** `terms.md` covers fees (B × N, cap, max snoozes, commitment lock), that Google handles payments and refunds, pending and unused payments, no guarantee that an alarm rings on every device, and a clearly marked placeholder for EU/UK right-of-withdrawal wording (Q17) [ASSUMPTION: owner or a legal reviewer finalises terms and Q17]
**And** `support.md` gives the support email and short answers for refunds, pending payments, unused payments and alarm reliability; `payments.md` mirrors the in-app "How payments & refunds work" text

**Given** `./gradlew checkSitePages` (a `qualityGate` dependency)
**When** it runs
**Then** it fails if a page is missing, if `privacy.md` lacks any required heading (Data on your phone, Backup, Crash reports, Usage stats, Payments, Camera and microphone, Deleting your data, Contact), if any page contains "TODO" outside the marked Q17 placeholder, or if the copy rules (no em dash, banned words) are broken
**And** the published URLs live in `config/app-links.properties` (`privacyUrl`, `termsUrl`, `supportUrl`, `supportEmail`) and are exposed to the app as `AppLinks` [ASSUMPTION: GitHub Pages base URL and support email, owner to provide]

**Given** the owner
**When** the pages are live
**Then** the owner reads every page, confirms it matches the app's behaviour, and records the live URLs and date in the story file (human-verify); automation never marks this story done
**And** `./gradlew qualityGate` passes

### Story 5.8: "How payments & refunds work", privacy, terms and support in Settings

As a user,
I want to read how fees, pending payments and refunds work, and reach the privacy policy, terms and support from Settings,
So that I trust the app before I ever pay.
**Refs:** FR-SET-3, FR-ONB-5, FR-SET-5, FR-MSG-4, NFR-5, NFR-9, AD-11, UX-DR51, UX-DR60, UX-DR64, UX-DR66, UX-DR67, UX-DR84 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** Settings
**When** it renders
**Then** it has rows "Payments & refunds", "Privacy policy", "Terms" and "Support" [ASSUMPTION: add to EXPERIENCE.md Key strings]; Privacy policy, Terms and Support open `AppLinks` URLs with `ACTION_VIEW` (snackbar "No browser found." when nothing handles it, reusing Story 4.17's string), and Support also offers the support email

**Given** the Payments & refunds screen
**When** it renders
**Then** it shows, in ≤ 25-word paragraphs: how the fee rises (base fee × snooze number, local price from Google Play, max snoozes, $50 cap), that the fee is locked for the morning and lowering it waits until after the next alarm, that a pending payment only starts a snooze if it clears during the session, that unused payments are refunded automatically by Google, and that Google lets you request a refund within 48 hours [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** it shows the alarm behaviour disclosure verbatim: "Your alarm keeps ringing until you finish your check or pay to snooze. Your phone stays usable: calls, other apps and emergency calls all work. Other ways to stop it: force-stop or uninstall the app, turn off the phone, or leave it 30 minutes." (FR-ONB-5)
**And** it recommends Play purchase authentication: "Turn on purchase authentication in Google Play so every snooze needs your fingerprint or password." [ASSUMPTION: add to EXPERIENCE.md Key strings] (NFR-5)
**And** it links to "Problem with a charge?" (Story 4.17) and "Purchase history" (Story 4.16)

**Given** these screens
**When** Roborazzi and semantic tests run
**Then** screenshots exist for Settings (full list) and Payments & refunds in Light and Dark and at 200% font scale, headings are TalkBack headings, targets are ≥ 48 dp, and a Robolectric test asserts each link intent
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.9: Delete all data

As a user,
I want to delete everything the app stores about me in one step,
So that I can start over or leave without a trace on the phone.
**Refs:** FR-SET-4, FR-SES-3, FR-MSG-4, NFR-4, NFR-14, NFR-15, AD-4, AD-6, AD-7, AD-12, UX-DR55, UX-DR61 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** Settings
**When** the user taps "Delete all data" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**Then** a `dialog-confirm` opens with "Delete all data?" and "Alarms, history, purchase records and settings are removed from this phone. This can't be undone." with "Delete" in `error` colour and "Keep it" as the default dismiss [ASSUMPTION: add to EXPERIENCE.md Key strings]

**Given** core use case `DeleteAllData`
**When** it runs while a session is active (including Snoozed)
**Then** it returns `DomainError.SessionActive` and changes nothing (the Settings row is also unreachable under the Epic 2 session lock; this guard is defence in depth, unit-tested)

**Given** no active session and the user confirms
**When** `DeleteAllData` runs
**Then** it writes a `deleteInProgress` marker, cancels every scheduled alarm request code and the test slot through `AlarmScheduler`, cancels all `BackgroundWork` jobs except consume-retry, deletes all rows in `app.db` (alarms, check configs, pending changes, commitment events, session history, purchase records), deletes `purchase_intent` rows and the active session in `runtime.db`, deletes media folders in credential-protected storage (if present), clears the settings DataStore, price cache and missed-note dismissals, regenerates the install id, sets analytics consent false and calls `resetAnalyticsData()`, calls Crashlytics `deleteUnsentReports()`, then removes the marker
**And** `grant_ledger` rows with status granted are kept until consumed, so a paid and granted snooze is never left unconsumed and refunded as if unused [ASSUMPTION: owner to confirm]
**And** if the process dies midway, the next app start sees the marker and finishes the deletion before showing any screen (test with a fake that throws after each step)
**And** afterwards the app opens onboarding (Story 5.10 onward; until then, the empty Home)

**Given** tests
**When** they run
**Then** a `:data` integration test fills both databases and the DataStores, runs the deletion and asserts every table and file is empty except granted ledger rows, and a Robolectric test asserts no alarm remains in `ShadowAlarmManager`
**And** Roborazzi screenshots cover the dialog in Light and Dark and at 200% font scale
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.10: Onboarding, part 1: mission, alarm behaviour disclosure and base fee

As a new user,
I want to understand on the first screens that waking up is free, how the alarm behaves, and what a snooze will cost me,
So that I agree to the rules before I set my first alarm.
**Refs:** FR-ONB-1, FR-ONB-5, FR-MSG-1, FR-SET-1, FR-MSG-4, NFR-3, NFR-5, NFR-9, NFR-13, AD-11, AD-16, UX-DR25, UX-DR35, UX-DR39, UX-DR45, UX-DR60, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR86 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** `onboardingCompleted` false in the settings DataStore (first launch, or after "Delete all data")
**When** the app opens
**Then** the Onboarding route shows instead of Home, with `progress-dots` for 8 steps (mission, disclosure, base fee, first alarm, checks, reliability checklist, usage stats, test alarm), Back going to the previous step (and leaving the app from step 1), dots not tappable
**And** the current step is saved, so a process death resumes at the same step
**And** a user restored from backup with `onboardingCompleted` true goes straight to Home (the reliability banner covers settings that differ on the new phone)

**Given** step 1, mission
**When** it renders
**Then** it shows "This app makes money only when you snooze. We hope you never pay us." as the headline content, "Snooze costs money. Waking up is free." below it, and one `button-filled` "Let's set it up" (FR-MSG-1)

**Given** step 2, disclosure
**When** it renders
**Then** it shows exactly "Your alarm keeps ringing until you finish your check or pay to snooze. Your phone stays usable: calls, other apps and emergency calls all work. Other ways to stop it: force-stop or uninstall the app, turn off the phone, or leave it 30 minutes." and one `button-filled` "I understand"; there is no skip
**And** "I understand" stores `disclosureAcceptedAt`, and `SaveAlarm` returns `DomainError.DisclosureRequired` while it is unset, so no alarm can be saved before consent (unit test; FR-ONB-5)

**Given** step 3, base fee
**When** it renders
**Then** it reuses the Epic 4 base fee `stepper` and ladder preview "Snooze 1: {price1} · 2: {price2} · 3: {price3}" with local Play prices, the note "You can raise it anytime. Lowering it waits until after your next alarm.", and the purchase-authentication tip from Story 5.8, with `button-filled` "Continue" [ASSUMPTION: add to EXPERIENCE.md Key strings]
**And** offline or with no cached prices it shows USD amounts with "Approximate. Your local price shows when you're online." and "Continue" still works; `PriceCatalog.refresh()` is attempted when the step opens
**And** the fee is saved through `SetBaseFee` (no alarm exists yet, so no commitment lock applies)

**Given** steps 1 to 3
**When** Roborazzi and semantic tests run
**Then** screenshots exist for each step (base fee loaded and approximate) in Light and Dark and at 200% font scale, TalkBack focus starts on each step's headline, and targets are ≥ 48 dp
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.11: Onboarding, part 2: first alarm, checks, checklist and usage stats

As a new user,
I want to set my first alarm and its check, fix my phone's settings and decide about usage stats in a few taps,
So that I finish setup with an alarm I can trust.
**Refs:** FR-ONB-1, FR-ONB-2, FR-ONB-6, FR-ALM-1, FR-PWK-2, FR-PWK-12, FR-MSG-4, NFR-9, NFR-15, AD-11, AD-15, UX-DR29, UX-DR36, UX-DR42, UX-DR45, UX-DR50, UX-DR64, UX-DR66, UX-DR67, UX-DR80, UX-DR84, UX-DR86 · **Priority:** Must · **Verify:** auto

**Acceptance Criteria:**

**Given** step 4, first alarm
**When** it renders
**Then** it shows the `time-picker` (keyboard first) and `chip-day`s with a headline "When should it ring?" [ASSUMPTION: add to EXPERIENCE.md Key strings]; other alarm fields use the Story 5.1 defaults; a passed one-time time shows "Rings tomorrow at {time}."

**Given** step 5, checks
**When** it renders
**Then** it reuses the Epic 3 `check-type-card`s with "Try it", mode Random selected by default, headline "How will you prove you're up?" [ASSUMPTION: add to EXPERIENCE.md Key strings]; choosing QR/Barcode runs the Epic 3 registration (camera permission asked there, with its reason)
**And** "Continue" with no check selected is blocked with "Pick at least one check."
**And** on "Continue" the alarm is saved with `SaveAlarm` (enabled), the Story 1.19 notification permission request runs once on API 33+, and going back to step 4 or 5 edits the same alarm instead of creating a second one (test)

**Given** step 6, reliability checklist
**When** it renders
**Then** it embeds the Story 5.4 checklist (rows, Fix deep-links, re-check on return) with headline "Make sure it rings" [ASSUMPTION: add to EXPERIENCE.md Key strings]; the "Test alarm" row is left for step 8 and "Continue" is never blocked by failing rows

**Given** step 7, usage stats
**When** it renders
**Then** it shows "Share anonymous usage stats? Off unless you turn it on." with two equal buttons "Share" and "No thanks", neither pre-selected or focused
**And** either tap sets `analyticsAsked` true and consent accordingly (Story 5.6), the question is never asked again, and the Settings toggle reflects the choice

**Given** steps 4 to 7
**When** Roborazzi and semantic tests run
**Then** screenshots exist for each step (checks with QR selected, checklist with two Fix rows) in Light and Dark and at 200% font scale; a ViewModel test drives the whole flow with fakes and asserts exactly one alarm saved and consent false after "No thanks"
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.12: Onboarding test alarm on a locked screen

As a new user,
I want to ring a test alarm with my phone locked before tonight,
So that I see with my own eyes it rings over the lock screen, for free.
**Refs:** FR-ONB-1, FR-ONB-4, FR-ALM-8, FR-MSG-4, UX-DR10, UX-DR14, UX-DR35, UX-DR45, UX-DR64, UX-DR81, UX-DR84, UX-DR86 · **Priority:** Must · **Verify:** auto, plus (human-verify) in Story 5.13

**Acceptance Criteria:**

**Given** step 8, test alarm
**When** it renders
**Then** it shows "Lock your phone. We'll ring in 10 seconds." after the user taps `button-filled` "Ring a test alarm", the note "Your alarm screen turns bright to help you wake. Change it in Settings." (when Bright wake screen is on), and a `button-text` "Skip for now"
**And** "Ring a test alarm" schedules a test through Story 1.18 using the first alarm's settings (check, sound, grace), and the test ring shows "Test · no charge" and can never charge

**Given** the test completes while the phone was locked when it fired
**When** the user returns to the app
**Then** `lockedTestPassedAt` is set (Story 5.3), the checklist "Test alarm" row is OK, `onboardingCompleted` is set and Home opens

**Given** the test completes while the phone was not locked
**When** the user returns
**Then** step 8 stays with "Your phone wasn't locked. Try again with it locked." [ASSUMPTION: add to EXPERIENCE.md Key strings] and the row stays unticked

**Given** the user taps "Skip for now"
**When** onboarding ends
**Then** `onboardingCompleted` is set, Home shows a `note-inline` "Ring a test alarm with your phone locked to check it works." [ASSUMPTION: add to EXPERIENCE.md Key strings] until a locked-screen test passes (UX-DR81), and the checklist row stays unticked (FR-ONB-4)
**And** skipping is only possible through "Skip for now" (Back returns to step 7)

**Given** the test step
**When** Roborazzi and instrumented tests run
**Then** screenshots cover step 8 (before tap, waiting, not-locked retry) and Home with the skip note in Light and Dark and at 200% font scale, and a Gradle Managed Device test with the debug keyguard locked completes the test session and asserts `lockedTestPassedAt` is set
**And** the `pps-design` Done checklist is ticked in the story file, all strings are resources, key strings match EXPERIENCE.md verbatim and `CopyRulesTest` passes (FR-MSG-4)
**And** `./gradlew qualityGate` passes

### Story 5.13: Epic 5 device verification checklist

As the owner,
I want to confirm first run, settings and the reliability checklist on real phones,
So that closed testers get an app that sets itself up correctly on their devices.
**Refs:** FR-ONB-1–6, FR-SET-1–4, FR-SET-6, FR-MSG-1, FR-MSG-4, UX-DR10, NFR-4, NFR-9, NFR-15 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the latest `main` debug build freshly installed on each device of the matrix (Pixel, Samsung, Xiaomi, budget device)
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version and date:
1. First launch runs mission → disclosure → base fee → first alarm → checks → checklist → usage stats → test alarm, in that order, and resumes at the same step after swiping the app away.
2. The disclosure has no skip and "I understand" is required; the base fee shows local prices online and "Approximate…" in airplane mode.
3. Every checklist "Fix" opens the right system screen and the row turns "OK" on return: notifications, full-screen alarm (API 34+), exact alarms (Android 12 device or emulator), Do Not Disturb set to block alarms, battery optimization.
4. With a DND schedule that blocks alarms (DND currently off), the Do Not Disturb row shows Fix and the Home banner appears.
5. Turning battery optimization back on after it was OK shows "Alarms may not ring. Fix settings" on Home and "Alarm may not ring: battery optimization turned back on" in the checklist; fixing clears both.
6. On the Xiaomi and Samsung, the Manufacturer settings row appears, "Open settings" opens the right OEM screen, and "I've done this" marks it OK; on the Pixel the row is hidden.
7. The onboarding test alarm rings over the locked screen with "Test · no charge", the screen goes fully bright, brightness returns to normal on "Done", and the Test alarm row turns OK; an unlocked test shows the retry message; "Skip for now" leaves the Home note.
8. With Extra dim on (API 31+), the wake screen does not force full brightness; with "Bright wake screen" off, brightness is untouched.
9. Theme System / Light / Dark switches every app screen immediately; wake screens stay Sunrise.
10. New default grace window and snooze length apply to a new alarm but not to existing ones.
11. Usage stats "No thanks" sends nothing (Firebase DebugView empty after a session); "Share" sends only the three events.
12. Privacy policy, Terms and Support open the live GitHub Pages; Payments & refunds shows the disclosure text and links to "Problem with a charge?".
13. "Delete all data" is unreachable during a session; when idle it removes all alarms (none ring afterwards), history and settings, and the app restarts onboarding.
14. TalkBack reads each onboarding step headline first and every checklist row as title, reason and status; at 200% font size nothing clips.
15. All copy seen matches EXPERIENCE.md (FR-MSG-4).

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

### Story 5.14: Start the 14-day closed test (owner task)

As the owner,
I want at least 12 testers running the app on the closed track for 14 days, able to test payments for free and send feedback,
So that the production-access requirement is met and real-world bugs surface before launch.
**Refs:** NFR-1, NFR-5, NFR-11, AD-7, AD-14; PRD §11 SM-1, §13 release plan and launch blockers · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** Stories 4.18 and 5.13 have passed (or failures are explicitly waived)
**When** the owner prepares the closed test
**Then** a Google Group (or email list) of at least 12, ideally 15 or more, testers exists, every tester's Google account is also added under Setup > License testing with "RESPOND_NORMALLY" so snooze purchases are test purchases that charge nothing, and testers are told this in writing
**And** `docs/closed-test.md` (committed) gives testers: the opt-in link, what to test each morning (real alarms, snooze payments, reliability checklist, a locked test alarm), the device matrix gaps to fill, how to report (feedback form link and email), and a note that alarms may fail in testing so they keep a second alarm

**Given** Play Console requirements for a closed track
**When** the owner sets up the track
**Then** the App content items Play requires before publishing to closed testing are complete (privacy policy URL from Story 5.7, ads declaration, content rating questionnaire, target audience 18+, Data safety draft, and any exact alarm, full-screen intent or foreground-service declarations Play asks for); anything pulled forward from Epic 8 is noted in the story file
**And** a release tag `vX.Y.Z` from `main` builds the signed AAB through the Story 1.2 release workflow, the build is promoted from internal to the closed track, and the tester group is attached

**Given** the feedback channel
**When** testers report issues
**Then** a feedback form (for example a Google Form: device, Android version, what happened, time) and the support email are live, Play's private tester feedback is enabled, and the owner triages reports at least twice a week into bug stories referencing this story

**Given** the test starts
**When** at least 12 testers have opted in
**Then** the story file records the start date, the planned end date (start + 14 days), the opted-in count on day 1, and a weekly count until the end; the continuous 14 days with ≥ 12 opted-in testers are tracked into Epic 8 (production access)
**And** this story is done when the test has started with ≥ 12 opted-in testers; automation never marks it done
