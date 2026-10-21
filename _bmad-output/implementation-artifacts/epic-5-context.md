# Epic 5 Context: First run, settings and trust

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

A new user is guided through setup in 8 onboarding steps: mission, alarm behaviour disclosure and consent, base fee, first alarm, checks, reliability checklist, anonymous-stats choice and a test alarm with the phone locked. The user ends setup with an alarm they can trust, and agrees to the rules before any alarm can be saved. Settings is completed: wake and snooze defaults, "Bright wake screen", theme override, usage stats and the reliability checklist. The You tab gets payments and refunds, privacy, terms, support and "Delete all data". A full reliability probe detects every phone setting that can stop the alarm (Do Not Disturb, battery, background restriction, manufacturer settings) and warns when one is revoked. Analytics stays off until the user says yes. The policy pages are published on GitHub Pages. The 14-day closed test, which Play requires before production access, starts at the end of this epic.

## Stories

- Story 5.1: Wake and snooze defaults and theme override in Settings
- Story 5.2: Bright wake screen
- Story 5.3: Full reliability probe: Do Not Disturb, battery and the locked-screen test
- Story 5.4: Reliability checklist screen and revoked-setting warnings
- Story 5.5: Manufacturer background guidance
- Story 5.6: Anonymous usage stats, off until the user says yes
- Story 5.7: Privacy policy, terms and support pages on GitHub Pages
- Story 5.8: "How payments & refunds work", privacy, terms and support in the You tab
- Story 5.9: Delete all data
- Story 5.10: Onboarding, part 1: mission, alarm behaviour disclosure and base fee
- Story 5.11: Onboarding, part 2: first alarm, checks, checklist and usage stats
- Story 5.12: Onboarding test alarm on a locked screen
- Story 5.13: Epic 5 device verification checklist (owner, human-verify)
- Story 5.14: Start the 14-day closed test (owner task)

## Requirements & Constraints

- **Order of first run:** mission, disclosure and consent, base fee, first alarm, checks, permissions and checklist, analytics choice, test alarm. The disclosure has no skip. No alarm can be saved before "I understand" is stored.
- **Disclosure text** is fixed and shown verbatim both in onboarding and under "How payments & refunds work". It says the alarm rings until the check is done or a snooze is paid, that the phone stays usable (calls, other apps, emergency calls), and lists the accepted escapes: force-stop, uninstall, power off, 30 minutes with no interaction.
- **Checklist items:** Notifications; Full-screen alarm; Exact alarms (Android 12 to 12L only); Do Not Disturb allows alarms; Battery optimization exemption; Manufacturer background guidance (Xiaomi, Samsung, Huawei, Oppo/Realme, Vivo, OnePlus); Camera (only when a camera check is used); Microphone (only when recording). The checklist is also in Settings and flags revoked items. OEM "total silence" modes are covered by guidance, not detected.
- **Test alarm:** it can be skipped only through "Skip for now". The checklist row stays unticked until a test completes with the phone locked. A test never charges.
- **Analytics:** asked once, default off, changeable in Settings. Only three anonymous events (`session_outcome`, `snooze_count`, `check_type`). No advertising ID, no user id, no extra user properties, no events before consent and no queueing of events for later. Crashlytics is always on and holds no personal content.
- **Privacy:** there is no account and no backend. Auto Backup covers alarms, settings, history and purchase records, and excludes media, the active session and pending purchase intents. Payments go through Google Play only, and the price is always shown first. Play target audience is 18+, and onboarding recommends Play purchase authentication.
- **Accessibility on every UI story:** TalkBack labels with role and state, targets ≥ 48 dp, 200% font scale with no clipping, Reduced motion honoured. Onboarding focus starts on each step's headline. Checklist rows read as title, reason, status.
- **Standing criteria on every UI story:** copy the `pps-design` Done checklist into the story file and tick every item. Every string is a Compose Multiplatform resource and matches the EXPERIENCE.md key strings verbatim (`CopyRulesTest`, no em dash, banned words). Strings EXPERIENCE.md does not define are marked and listed for the owner. Roborazzi screenshots in Light, Dark and at 200% font scale. `./gradlew qualityGate` passes.
- **Human-verify stories (5.7 live URLs, 5.13, 5.14, the 5.6 DebugView check):** automation never marks them done.

## Technical Decisions

- **Hexagonal core:** rules (`ReliabilityChecklist.evaluate`, `alarmBlocking`, `DeleteAllData`, telemetry gating, `DeviceMaker` mapping, settings use cases) are pure Kotlin in `:core` with no Android types. Platform reads live in `:androidApp` adapters (`AndroidReliabilityProbe`), and every port has a `Fake*` in `:testing`. Kover keeps core lines ≥ 90%.
- **Settings:** settings live only in the device-protected DataStore, never in `app.db`. They are edited only through core use cases (`SetDefaultGrace`, `SetThemeMode`, …), which reject out-of-range values with `DomainError.InvalidSetting`. New defaults apply only to alarms created afterwards, so they are not commitment-locked. `ConfigResolver` freezes `SessionConfig` (including `brightWakeScreen` and `testStartedLocked`) when the alarm fires, so changing a setting mid-session has no effect.
- **Errors are values:** ports return `Outcome<T, DomainError>`. New error cases are `DisclosureRequired` (on `SaveAlarm`) and `SessionActive` (on `DeleteAllData`, as defence in depth behind the session lock).
- **Permission allowlist is fixed.** Do not add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_NOTIFICATION_POLICY` or `WRITE_SETTINGS`. Brightness is a window-scoped `screenBrightness` override only, and is skipped when Extra dim is on. Remove the merged `AD_ID` permission with `tools:node="remove"`.
- **Dependency allowlist:** adding Firebase Analytics needs `config/dependency-allowlist.txt` updated in the same change. Firebase and Billing start only after first unlock. Analytics auto-collection stays disabled in the manifest and is turned on at runtime only when consent is true and the user is unlocked.
- **System intents:** every deep link is checked with `resolveActivity` first. It falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`, then `ACTION_SETTINGS`. OEM components resolve the same way and are never launched unless they resolve.
- **UI:** one ViewModel per screen with `StateFlow<UiState>`, `onIntent` and a `Channel<UiEffect>`. Navigation 3 uses a sealed `@Serializable Route`. Tabs are Alarms, Progress, Settings, You. The wake flow stays in `WakeActivity`, outside the nav graph. Theme comes from generated tokens via `PpsTheme(mode)`; raw colours and sizes are banned. Dynamic colour is off, and wake screens always use Sunrise.
- **System alarms:** all scheduling and cancelling goes through `AlarmScheduler`, with one request code per occurrence plus one session slot. Granting exact alarms triggers `rescheduleAll()`. Deferred work goes through `BackgroundWork`; Delete all data cancels it all except consume-retry. Granted `grant_ledger` rows survive Delete all data until consumed.
- **Delete all data must survive a crash:** it writes a `deleteInProgress` marker first. The next app start finishes the deletion before showing any screen.
- **Story 5.9 must decide** whether "Delete all data" logs each alarm deletion. If it does, the logging belongs inside `DeleteAlarm`, so no path can skip it.
- **Story 5.9 must also clear the `settings_fallback` shared preferences** (`SharedPreferencesSettingsCache`, device-protected, not backed up). Since Story 5.1 this last-known settings snapshot holds the new-alarm defaults and the theme as well as the fees, and it gives the first frame (and the window background) its theme.
- **Published URLs** live in `config/app-links.properties` and are exposed as `AppLinks`. Only `docs/site/` is published, by `.github/workflows/pages.yml`. `checkSitePages` is a `qualityGate` dependency.

## UX & Interaction Patterns

- **Onboarding layout (design preview round 3):** one job per step on the gradient. `progress-dots` (8, not tappable, TalkBack "Step 3 of 8") sit on a small glass capsule at the top, with a back arrow from step 2. Cards scroll, and the step's actions sit in their own bottom area. Back goes to the previous step and leaves the app from step 1. The current step survives process death.
- **Settings (grouped cards, rows open sub-screens, app behaviour only):**
  - Snooze: Base fee, Max snoozes, Default snooze length.
  - Wake: Default quiet time slider (15 to 30 s), Vibrate during quiet time, Bright wake screen with its caption.
  - Appearance: System / Light / Dark `segmented-control`.
  - Usage stats switch.
  - Reliability checklist.
  - Switches and segmented controls apply at once, with no Save button.
- **You tab:** "Money" (Purchase history, How payments & refunds work), "Privacy and your data" (Privacy policy, "Delete all data" in `error` colour), "Help" (Support, Terms, About). Settings and Delete all data cannot be reached during a session.
- **Reliability checklist:** all rows sit in one `card-group`. Each `checklist-row` is 64 dp with "OK" or "Fix", and statuses are re-checked on every `ON_START`/`ON_RESUME`. "Ring a test alarm" stays at the bottom. The Home/Settings `banner-warning` "Alarms may not ring. Fix settings" is not dismissible and clears itself. It is raised only by Notifications, Full-screen, Exact alarms, DND or Battery (flow F7).
- **Usage stats choice:** two equal `button-outlined` buttons, "Share" and "No thanks", with neither pre-selected nor focused.
- **Copy mismatches between epics and EXPERIENCE.md** (follow the epic's criteria and flag the mismatch for the owner):
  - Camera row reason: "Needed for your QR/Barcode check." in the epic, "…and House Hunt checks." in EXPERIENCE.md.
  - The skipped-test Home note: shown "until a locked test passes" in the epic, "until dismissed" in EXPERIENCE.md.
  - No check selected in onboarding: the epic blocks "Continue" with "Pick at least one check."; EXPERIENCE.md disables "Continue".
- **Open copy item:** Samsung, Huawei, Oppo/Realme, Vivo and OnePlus manufacturer steps are still unwritten. Oppo/ColorOS steps are required, because the owner's Oppo A96 is the test phone. Each step is ≤ 25 words, and every maker's steps are sourced in `docs/oem-guidance.md`.

## Cross-Story Dependencies

- **Builds on:**
  - Epic 1: `ReliabilityProbe` and the banner (1.19), the test alarm (1.18), `GlobalSettings`/`ConfigResolver`, `PpsTheme`, Crashlytics.
  - Epic 2: the session lock.
  - Epic 3: the check picker, `check-type-card`, QR registration.
  - Epic 4: base fee `stepper` and ladder, `PriceCatalog`, the Snooze sub-screens, "Problem with a charge?", Purchase history, `BackgroundWork`.
  - Reuse these; do not redefine them.
- **Within the epic:**
  - 5.3 feeds 5.4, which 5.11 step 6 embeds as the `ChecklistCard`.
  - 5.5 replaces the Manufacturer row's Fix target and turns on `makerCovered`. Until then the row is Hidden in production.
  - 5.1 defaults feed 5.11's first alarm.
  - 5.2's note is shown in 5.12.
  - 5.6 consent backs 5.11 step 7.
  - 5.7 URLs feed 5.8 and the Play listing in 5.14.
  - 5.8's purchase-authentication tip is reused in 5.10.
  - 5.9 ends in onboarding (5.10+), or the empty Home before that exists.
  - 5.12 sets `lockedTestPassedAt` using 5.3.
- **Closed test (5.14)** needs Stories 4.18 and 5.13 passed or explicitly waived.

## Notes from implemented stories

- **Story 5.13 checklist line (from the 5.3 review, fix 13):** on the Oppo A96, check the DND row against a ColorOS schedule rule (Bedtime/Sleep mode) that has its own "alarms" setting. Turn alarms off in that rule only, leave manual DND allowing alarms, and record whether the checklist still shows DND as OK while the rule would block the alarm. The app reads only the default DND policy; a rule's own policy is invisible without `ACCESS_NOTIFICATION_POLICY`, which stays off the allowlist.
- **Story 5.12 open question (from the 5.3 review, item 16):** a phone with no screen lock never shows the keyguard, so its test can never tick the locked-screen row. A test rung with the screen on and unlocked counts as unlocked too. The owner decides in 5.12 whether such a phone passes with any completed test or is told to set a screen lock.
- **5.6 → 5.13 checklist (DebugView human-verify):** with the debug project, DebugView shows only the three custom events (`session_outcome`, `snooze_count`, `check_type`) plus the automatic events approved for the Data safety form, nothing before a yes and nothing after a no. With consent on, Crashlytics also logs `app_exception` to Analytics for a reported crash: note it for the Data safety form (Epic 8).
- **Epic 4 device follow-ups → 5.13 checklist (PR #73):** on the Oppo with a license tester and the slow test card:
  - Note the time from the approval (Play's notification, or the "pending" message clearing) to the next Pay at that price.
  - Note which shows first: the app's "You already paid …" reuse sheet, or Play's "You already own this item.".
  - Repeat a few times. If Play's dialog still shows first, raise `PurchaseCoordinator.PRE_LAUNCH_REFRESH` (now 1 s) to the value the timings suggest.
  - Also check that a re-ring shows the time it rings, and that the clock turns over at the minute.
- **5.11 → 5.12 hook:** onboarding completes after step 7 until 5.12 raises `OnboardingViewModel.LAST_BUILT` to `TestAlarm`; `afterUsageStats()` then opens step 8. The first alarm's id is stored as `OnboardingProgress.alarmId` (the alarm the test rings). Back from step 8 must skip step 7 for a user already asked about usage stats. Steps 4 and 5 are a child `AlarmEditorViewModel` (`OnboardingFirstAlarm`); step 6 is a child of 5.4's `ReliabilityViewModel` (`OnboardingChecklist`), its "Test alarm" row left out for step 8.
