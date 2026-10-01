---
title: 'Story 1.19: Permissions for reliable alarms and crash reporting'
type: 'feature'
created: '2026-10-01'
status: 'in-review'
baseline_revision: '301cc43b1b251159a09af6284af8fd034e284e48'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Alarms can be silently blocked:
- On Android 13+ without `POST_NOTIFICATIONS` there is no notification and no full-screen intent.
- On API 34+ the full-screen intent can be revoked.
- On API 31–32 exact alarms can be denied.

Nothing asks for the notification permission, and nothing tells the user any of this. Crashes in the wake flow go to a no-op reporter.

**Approach:**
- **Probe:** a core `ReliabilityProbe` port, with an Android adapter and a fake, checks the three settings.
- **Permission:** Home shows the existing design-preview reliability banner while any check fails, and "Fix" opens the first failing setting. The editor asks for `POST_NOTIFICATIONS` once, on the first save.
- **Crashlytics:** Firebase Crashlytics sits behind the `CrashReporter` port. It is used only when `google-services.json` exists, and starts only after the user unlocks.

## Boundaries & Constraints

**Always:**
- **ReliabilityProbe** (core `reliability`): `check(): ReliabilityStatus(notificationsAllowed, fullScreenIntentAllowed, exactAlarmsAllowed)`. `firstFailing` checks in that order. `FakeReliabilityProbe` lives in `:testing`. The Android adapter uses:
  - `NotificationManagerCompat.areNotificationsEnabled()`;
  - `NotificationManager.canUseFullScreenIntent()` on API 34+ (true below);
  - `AlarmManager.canScheduleExactAlarms()` on API 31–32 only (true on 33+ with `USE_EXACT_ALARM`, and on 30 and below).
  - Robolectric tests run on API 26, 31, 32, 33, 34 and 36.
- **ReliabilitySettings** port: `open(item)` starts the deep link with `FLAG_ACTIVITY_NEW_TASK`, only from a user tap on Home:
  - Notifications → `ACTION_APP_NOTIFICATION_SETTINGS` with `EXTRA_APP_PACKAGE`.
  - Full-screen intent → `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, `package:` URI (API 34+).
  - Exact alarms → `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, `package:` URI (API 31+).
  - If the intent has no handler, it falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`.
- **Home:**
  - The probe is evaluated when the ViewModel starts and on every `ON_START` (`HomeIntent.Started`).
  - `reliabilityProblem = !status.allOk` shows the existing non-dismissible `BannerWarning`, "Alarms may not ring. Fix settings" · "Fix".
  - "Fix" opens `firstFailing`. The banner clears on the next `ON_START` once every item is OK.
  - Granting exact alarms already triggers `rescheduleAll()` through `SystemEventsReceiver` (Story 1.10, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`). It is unchanged.
- **NotificationPermission** port: `shouldRequest()` is true on API 33+ when the permission is not granted and it has never been asked; `request()` asks and marks it asked.
  - The Android adapter launches the system dialog through an `ActivityResultLauncher` registered by `MainActivity`, only while it is at least STARTED. With no activity it does nothing and does not mark it asked, so nothing is ever requested from the background.
  - The editor ViewModel calls it after a successful save. Every editor save is an enabled alarm.
- **Crashlytics** (BoM 34.19.0, `firebase-crashlytics`):
  - `FirebaseCrashReporter(sink, logger)` implements `CrashReporter`.
  - It sends `recordException` of a **sanitized** copy: the class names and stack traces are kept, and every message is replaced by its class name, for the throwable and every cause.
  - It sets no custom keys and writes no Crashlytics logs.
  - Before Firebase is initialised it only logs the exception type.
  - A unit test proves a label, file path and purchase token in exception messages never reach the sink.
- **Firebase start:**
  - `FirebaseInitProvider` is removed from the merged manifest (`tools:node="remove"`).
  - `FirebaseStartup` initialises only when the app is configured (`FirebaseOptions.fromResource` is not null) and the user is unlocked (`UserManager.isUserUnlocked`). Otherwise it waits for `ACTION_USER_UNLOCKED`, through a receiver registered at runtime.
  - The Koin `CrashReporter` is the Firebase reporter only when configured, else `NoOpCrashReporter`.
  - The manifest sets `firebase_analytics_collection_enabled` and `google_analytics_adid_collection_enabled` to false.
- **Gradle:**
  - The `com.google.gms.google-services` (4.5.0) and `com.google.firebase.crashlytics` (3.0.8) plugins are declared `apply false` at the root.
  - `:androidApp` applies both only when `androidApp/google-services.json` exists.
  - CI writes the file from the secret `GOOGLE_SERVICES_JSON_DEBUG` only when the secret is set, so the build without the file uses the no-op reporter and passes `qualityGate`.
  - The new Firebase runtime coordinates are added to `config/dependency-allowlist.txt`.
  - The permission allowlist gains only what Firebase needs: `ACCESS_NETWORK_STATE`, a normal permission that Firebase's upload scheduling requires.
- **Debug crash hook** (debug source set only):
  - A debug-only `DebugHooksProvider` registers an exported runtime receiver for `com.yawnandpawn.app.debug.CRASH`, which throws `DebugTestCrash`.
  - `adb shell am broadcast -a com.yawnandpawn.app.debug.CRASH` therefore works: implicit broadcasts don't reach manifest receivers.
- Strings are the existing EXPERIENCE.md key strings ("Alarms may not ring. Fix settings", "Fix"). `CopyRulesTest` passes. The pps-design Done checklist is ticked below.

**Never:**
- No permission request from the background, and none on every save (only once).
- No redesign of the banner. No DND, battery or OEM checklist (Epic 5).
- No Firebase Analytics dependency (consent is Epic 5). No custom keys, logs or messages with personal data.
- No `google-services.json` in the repo.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| All OK | API 33, granted, FSI ok | No banner | No error expected |
| Notifications off | `areNotificationsEnabled` false | Banner; Fix opens app notification settings | No error expected |
| FSI revoked | API 34+, `canUseFullScreenIntent` false | Banner; Fix opens the full-screen intent setting | No error expected |
| Exact denied | API 31–32, `canScheduleExactAlarms` false | Banner; Fix opens the exact alarm setting | No error expected |
| API ≤ 30 / 33+ exact | Any | Exact item true | No error expected |
| Fixed | Probe OK on the next ON_START | Banner clears | No error expected |
| First save | API 33+, not granted, never asked | Dialog requested once | No activity → nothing, not marked |
| Later saves | Asked before | No request | No error expected |
| Not configured | No `google-services.json` | NoOp reporter; Firebase never initialised | Logged |
| Locked | Configured, user locked | Init on `ACTION_USER_UNLOCKED` | No error expected |
| Report | Exception with a label or path in its message | Sink gets class names and stacks only | No error expected |

</intent-contract>

## Code Map

- `composeApp/.../ui/home/HomeContract.kt:46` `reliabilityProblem`, `HomeIntent.FixSettings` (line 79), `Resumed`. `HomeScreen.kt:227` renders `BannerWarning` (`home_reliability_banner`, `home_fix`). `HomeRoute.kt` has `LifecycleEventEffect(ON_RESUME)`. `HomeViewModel.kt` has `LocalState` and `render`.
- `composeApp/.../ui/editor/AlarmEditorViewModel.kt` `save()`, success → `close()`. `UiModule.kt` (the ViewModel parameters). Call sites: `HomeViewModelTest.kt:91,402`, `AlarmScreensSemanticsTest.kt:479`, the editor tests and `EditorRouteLifecycleTest`.
- `core/.../crash/CrashReporter.kt` (port KDoc). `androidApp/.../wake/NoOpCrashReporter.kt` and `WakeModule.kt` (the `CrashReporter` binding). `YawnAndPawnApp.kt` `onCreate`.
- `androidApp/src/main/AndroidManifest.xml` (provider removal, meta-data). `androidApp/src/debug/AndroidManifest.xml` (debug hook). `MainActivity.kt` (launcher).
- `gradle/libs.versions.toml` (firebase-bom and crashlytics are already there; add the plugins). Root `build.gradle.kts` plugins. `androidApp/build.gradle.kts`. `config/dependency-allowlist.txt` and `permission-allowlist.txt`. `.github/workflows/ci.yml`.
- `androidApp/src/test/.../ui/HomeSamples.kt` and `HomeScreenshotTest.kt` (the banner screenshots).

## Tasks & Acceptance

**Execution:**
- core `reliability/Reliability.kt` (status, items, the three ports) plus tests. Fakes in `:testing`.
- androidApp `reliability/` (`AndroidReliabilityProbe`, `AndroidReliabilitySettings`, `AndroidNotificationPermission`), `crash/` (`FirebaseCrashReporter`, `CrashSink`, `FirebaseStartup`, the module), `MainActivity`, the manifests, the debug hook, the Koin wiring, Gradle and CI.
- composeApp: Home (probe, Started, Fix) and editor (request after save).
- Tests:
  - the probe on 6 API levels;
  - the settings intents;
  - the notification permission;
  - the reporter sanitizing;
  - the startup (locked and unlocked);
  - the merged manifest (no `FirebaseInitProvider`, analytics flags);
  - the Home and editor ViewModels;
  - the debug hook;
  - the Home banner screenshots in Light, Dark and 200%.

**Acceptance Criteria:**
- Given no `google-services.json`, when `./gradlew qualityGate` runs, then it is BUILD SUCCESSFUL with both allowlists green, and the app binds `NoOpCrashReporter`.
- Given the Crashlytics console check, when the owner has the Firebase projects, then it is human-verify: **pending owner** (no Firebase project or `google-services.json` exists yet, 2026-10-01).

## Verification

**Commands:**
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

**Manual checks (human-verify, pending owner):**
- Debug build with `google-services.json`: run `adb shell am broadcast -a com.yawnandpawn.app.debug.CRASH`. The crash should show in the debug project's Crashlytics console within 10 minutes. Record the date here.

## pps-design Done checklist

- [x] Only `DESIGN.md` tokens are used. The existing `BannerWarning` is reused, with no new UI.
- [x] Light and Dark are checked with screenshots (Home with the banner).
- [x] Every colour pair is in the contrast table: `banner-warning` as in the design preview.
- [x] Touch targets are ≥ 48 dp ("Fix" is the existing `button-text`).
- [x] It works at 200% font scale (screenshot) and with TalkBack: the banner text and the "Fix" button. No outcome glyphs.
- [x] Reduced motion: there is no new motion.
- [x] Copy matches EXPERIENCE.md verbatim ("Alarms may not ring. Fix settings", "Fix"). There are no new strings.
- [x] State rows: the reliability banner shows while any item fails and clears when all are OK.
- [x] "I'm up" / snooze: not applicable.
- [x] Screenshot tests are updated (Roborazzi Home banner in Light, Dark and 200%). The preview already has `homeReliability`.

## Spec Change Log

- 2026-10-02 (implementation): two deviations forced by the test toolchain.
  - **API 36 coverage:** Robolectric 4.17 needs Java 21 for SDK 35+, and the toolchain is pinned to JDK 17 (`robolectric.properties` sdk=34). The API 36 probe rules therefore run through the adapter's injected SDK level on the SDK 34 sandbox. API 34 and 36 use the same calls.
  - **Full-screen intent seam:** Robolectric has no shadow for `NotificationManager.canUseFullScreenIntent()`, so the probe takes it as a seam (the production default is the platform call). The same gap makes Robolectric report the grant as off, so the test app (`TestYawnAndPawnApp`, `restartKoin`) binds a probe that grants everything, through a test-only `overrideModules` hook on `YawnAndPawnApp`. Otherwise the banner would show on every real-app Home screenshot.
  - **KEEP:** the probe's production binding stays `AndroidReliabilityProbe`.

## Review Triage Log

No review pass yet (fast mode). Review is for the PR.

## Auto Run Result

**Summary:**
- **Probe and banner:**
  - A core `reliability` package holds `ReliabilityStatus` (with `firstFailing`) and three ports: `ReliabilityProbe`, `ReliabilitySettings` and `NotificationPermission`. The fakes are in `:testing`.
  - The Android adapters are in `android/reliability/` and bound in `reliabilityModule()`.
  - Home checks the probe at start and on every `ON_START` (`HomeIntent.Started`), shows the existing banner while a setting is off (also when the alarm list failed to load), and "Fix" opens the first failing setting.
- **Notification permission:** the editor asks for `POST_NOTIFICATIONS` once, after a successful save. The system dialog is launched through `MainActivity`'s launcher while it is started, and never without a screen in front.
- **Crashlytics:**
  - The BoM 34.19.0 `firebase-crashlytics` dependency is added.
  - `FirebaseCrashReporter` sends sanitized exceptions only (class names and stacks, no messages, keys or logs).
  - `FirebaseStartup` starts Firebase only when configured and unlocked, else on `ACTION_USER_UNLOCKED`.
  - `FirebaseInitProvider` is removed and the Analytics flags are off.
  - The google-services 4.5.0 and Crashlytics 3.0.8 plugins apply only when `androidApp/google-services.json` exists. `ci.yml` and `release.yml` write it from the secrets when they are set.
  - The allowlists list the Firebase coordinates and `ACCESS_NETWORK_STATE`.
- **Debug crash hook:** `DebugHooksProvider` (debug source set) registers the `com.yawnandpawn.app.debug.CRASH` receiver.
- **Story 1.17 gap closed:** `release.yml` now installs ffmpeg before its quality gate (`checkSoundLoudness`).
- **Test flake fixed:** `AndroidSoundPreviewTest` waits for the app-start volume restore, as `AndroidAlarmPlayerTest` does since Story 1.15.

**Human-verify, pending owner (2026-10-02):** there are no Firebase projects or `google-services.json` yet, so the Crashlytics console check (`adb shell am broadcast -a com.yawnandpawn.app.debug.CRASH` → the crash shows in the debug project within 10 minutes) has not been run.

**Residual risks:**
- The Firebase Gradle plugins have never been applied with AGP 9.3.3. The first CI run with the `GOOGLE_SERVICES_JSON_DEBUG` secret is the first test.
- Crashlytics' own uncaught-exception handler sends fatal crash messages unsanitized. The wrapper only covers reports made through `CrashReporter`.
- `firebase-sessions` (part of Crashlytics) adds DataStore and sends session events to Firebase. This is to be declared in Data safety.
