---
title: 'Story 3.10: QR/Barcode: register a code and scan it to stop the alarm'
type: 'feature'
created: '2026-10-06'
status: 'done'
baseline_revision: 'cef47fa'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-3-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-1-check-plugin-contract-and-the-math-generator-in-core.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-2-solve-math-to-stop-the-alarm.md'
warnings:
  - 'Built on main (cef47fa) without Stories 3.5-3.9, which land first. Their touch points are thin and marked `// 3.5 hook`, `// 3.6 hook` and `// 3.9 hook`; see Rebase notes.'
deferred: []
---

<intent-contract>

## Intent

**Problem:** FR-PWK-7: the user wants to register a barcode far from the bed and have to scan it to stop the alarm. There is no QR/Barcode type in core, no camera stack and no scanner.

**Approach:**
- Core gets the `QrBarcode` plugin type. Its puzzle is the entry's registered code (`CheckEntry.code`). A scan is submitted as `CheckAnswer.Code` and the type decides.
- The registered code is stored as its format plus a SHA-256 fingerprint of the trimmed raw value, never the raw value. The fingerprint is computed in core (`RegisteredCode.of`), so the raw content of a code never reaches the engine, `runtime.db`, `app.db`, the backup or the logs.
- `:composeApp` gets a `CodeScanner` port with `FakeCodeScanner`, the 3-consecutive-frames filter, the 2 s repeat gate, the registration state holder and the wake QR mapping.
- `:androidApp` gets the CameraX + bundled ML Kit scanner, the camera permission port, and the wake QR check in `WakeActivity` (through `WakeCheck`).

## Boundaries & Constraints

**Always:**
- The UI never decides correctness. A stable scan becomes `CheckAnswer.Code(format, fingerprint)`, and only `CheckType.QrBarcode.validate` compares it with the registered code.
- Frames are analysed on the device only. The analyser closes every frame and never writes an image to storage. A scan test checks the scanner sources.
- `CAMERA` is never asked at app start or on the wake screen. It is asked only when QR/Barcode is selected (the picker gate, a 3.5 hook) or when registration is opened.
- The approved composables are reused, and the preview baselines do not change. The camera feed is drawn through `LocalViewfinderFeed`, which previews and screenshots do not provide, so they keep the placeholder surface.
- A session stored before this story decodes the same, and the `SessionJson` golden fixtures do not change: `CheckEntry.code` is never encoded when null.
- An entry that can never be passed (QR/Barcode without a code) is resolved to Math · Medium · 3 at ring time, so the alarm can always be stopped.

**Never:**
- No 5 s watchdog, mid-scan failure, release on pause, Direct Boot note or TalkBack torch state (all Story 3.11).
- No fallback picker (Story 3.9) and no printable QR (Epic 7).
- No androidTest that needs a camera, and no `adb`.
- No new AD-2 rows.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Register | permission granted, the same code in 3 frames | the guide shows a check, with "Use this code" and "Scan again" | — |
| Use it | "Use this code" | `RegisteredCode(format, sha256(trimmed))` is handed to the setup draft (3.5 hook) | a blank value is never offered |
| Scan again | "Scan again" | back to scanning; the same code must be seen in 3 new frames | — |
| Register without permission | permission missing or denied | "Camera isn't available." with "Fix", which opens the app's system settings | "don't ask again" is handled the same way |
| Wake match | Grace or Loud on a QR entry, the registered code is scanned | `CheckAnswerSubmitted(Code)` gives `Correct`, which completes the entry (Success) | — |
| Wrong code | another code | "That's a different code. Scan your registered one.", error haptic, failed attempts +1 | the same code within 2 s is not submitted again |
| Same format, padded | " 123 " registered as "123" | `Correct` (the value is trimmed) | — |
| Other format | the same value as EAN-13 vs UPC-A | `Wrong` (epic: the same format) | Risk, see the device checklist in 3.14 |
| No camera at wake | permission missing, or CameraX cannot bind | "Camera isn't available. Pick a fallback check." immediately; the link comes with 3.9 | no frame is analysed |
| Unpassable entry | a QR entry with no code reaches a ring | resolved to Math · Medium · 3 | — |
| Before first unlock | a QR entry | `directBootSafe = false`, so the existing substitution gives Math · Medium · 3 | — |

</intent-contract>

## Code Map

- `core/.../checks/qr/RegisteredCode.kt` (new): `CodeFormat`, `RegisteredCode` (format and fingerprint) and `RegisteredCode.of`. `qr/Sha256.kt` (new) is a pure SHA-256.
- `core/.../checks/CheckType.kt`: `QrBarcode`, plus `puzzle(entry, seed)` and `isReady(entry)` with defaults. `CheckPlan.kt`: `CheckEntry.code`, `CheckEntry.puzzle(seed)` and `isReady`. `Puzzle.Code`, `CheckAnswer.Code`. `PlanResolver`: an entry that is not ready becomes the default entry.
- Call sites move from `entry.type.generate(seed, …)` to `entry.puzzle(seed)`: `CheckRun`, `PluginCheckValidator`, `CheckMapping`, `WakeService.solution`, `DebugCheckAnswer` and `:testing` `rightAnswer`.
- `composeApp/.../ui/qr/CodeScanner.kt` (new): `ScanResult`, `ScanEvent`, `CodeScanner`, `ConsecutiveFrames`, `RepeatGate`, `LocalViewfinderFeed`. `ui/qr/CameraPermission.kt` (new): the port and `selectCameraCheck`. `ui/qr/QrRegistrationModel.kt` (new): the registration state holder and its `QrRegistrationRoute`. `ui/wake/QrCheckMapping.kt` (new): `qrCheckUiState`.
- `composeApp/.../components/ViewfinderColors.kt`: `ViewfinderPlaceholder` draws `LocalViewfinderFeed` under the overlay when one is provided.
- `androidApp/.../qr/` (new): `CameraXCodeScanner`, `CodeAnalyzer`, `MlKitFrameDecoder`, `AndroidCameraPermission` and `QrModule`. `MainActivity` attaches the permission launcher. `WakeCheck`/`WakeActivity` add the QR branch. The manifest adds `CAMERA` and `uses-feature camera required=false`.
- `data/.../checks/CheckCodeColumns.kt` (new): the `code_format`/`code_value` mapping. `AppDatabaseMigrations.kt`: `MIGRATION_7_8` (placeholder, not registered; see Rebase notes).
- Tests: `:testing` gets `aRegisteredCode` and a QR `rightAnswer`/`wrongAnswer`. The `:androidApp` host tests get `FakeCodeScanner` and `FakeCameraPermission`, because `:testing` is JVM-only. Also `QrCheckScreenTest`, `CodeAnalyzerTest`, `AndroidCameraPermissionTest`, `QrScreenshotTest`, `QrScanningTest` and `QrCheckMappingTest` (composeApp), and `CheckCodeMigrationTest` (data).

## Tasks & Acceptance

**Execution:**
- [x] Core type, fingerprint, entry code, resolver safety; `CheckTypeTest`, `QrBarcodeCheckTest` (including the SHA-256 vectors), `DirectBootTest`, `PlanResolverTest`, and a `CheckPluginSessionTest` QR case.
- [x] Scanner port, frame filter, repeat gate, registration model, QR mapping; tests in `composeApp` commonTest.
- [x] CameraX + ML Kit scanner, analyser wrapper, permission port, Koin, manifest, allowlist.
- [x] Wake QR check in `WakeCheck`/`WakeActivity`; `QrCheckScreenTest` (Robolectric with `FakeCodeScanner`).
- [x] Data column mapping and the placeholder migration with tests.
- [x] Roborazzi: wake QR (scanning, wrong, camera unavailable) in Sunrise and registration (scanning, detected, camera unavailable) in Light and Dark, at 100% and 200%.

**Acceptance Criteria:**
- Given the build, when CameraX 1.6.2 and bundled ML Kit barcode-scanning 17.3.0 are added, then the allowlist lists every resolved artifact with its reason, the merged manifests pass the permission allowlist, and the bundled model is used.
- Given `CodeScanner` with `FakeCodeScanner`, when a code is detected, then `ScanResult(format, rawValue)` is emitted only after 3 consecutive frames with the same value.
- Given the core `QrBarcode` type, then `usesCamera = true`, `directBootSafe = false`, it has no difficulty, and its count is fixed at 1. Only the same format and the same trimmed value pass; anything else is `Wrong`.
- Given registration with the permission granted, then the viewfinder starts with the guide and torch, a detection pauses with "Use this code" and "Scan again", and "Use this code" hands the code to the draft.
- Given a ring on a QR entry, then the viewfinder starts with "Scan your code". A match completes the entry. A wrong code shows the wrong-code line with an error haptic and is not submitted twice within 2 s. Without permission or a camera, "Camera isn't available. Pick a fallback check." shows immediately.
- Given `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon`, then BUILD SUCCESSFUL, the Kover gates pass (core/checks ≥ 90%), and `git status --porcelain androidApp/src/test/screenshots/preview` is empty.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used.
- [x] Light, Dark (registration) and Sunrise (wake) in screenshots at 100% and 200%.
- [x] Every colour pair is in the contrast table (approved `check-qr*` and `qr-*` states, no new pair).
- [x] Touch targets ≥ 48 dp (torch 48 dp); wake snooze 64 dp unchanged.
- [x] 200% font and TalkBack: the viewfinder reads "Camera viewfinder. Point at your code.", the header is a heading, and the wrong code and camera-unavailable lines are polite live regions.
- [x] Reduced motion: no new animation.
- [x] Copy verbatim from EXPERIENCE.md Key strings (no new strings).
- [x] State rows: scanning, detected, wrong, camera unavailable. Watchdog and Direct Boot are 3.11.
- [x] "I'm up" and snooze unchanged.
- [x] Previews exist (`check-qr*`, `qr-*`, unchanged); new Roborazzi baselines added.

## Design Notes

**Defaults taken in fast mode (the owner can change any of them):**
- **Fingerprint, not the raw value.** The epic's `code_value` holds the SHA-256 of the trimmed value, and `code_format` holds the `CodeFormat` stored name. Matching fingerprints is matching trimmed values, so the AC is unchanged. The raw content, which may be a Wi-Fi password or a personal URL, never reaches `app.db`, its backup, `runtime.db`, the engine or the logs. `ScanResult.toString` leaves the value out. The scanner turns the value into the fingerprint before anything is sent (`CheckAnswer.Code(RegisteredCode)`), so a test session can still be answered from its own code (`WakeService.solution`), which a hash compared in core alone would not allow.
- **Pure SHA-256 in `:core`.** `:core` may use only Kotlin and KotlinX, so the hash is FIPS 180-4 in plain Kotlin, tested with the NIST vectors (including one million "a").
- **The ports live in `:composeApp` `commonMain`, and CameraX and ML Kit live in `:androidApp`.** The epic says `CodeScanner` is in `:composeApp` `androidMain`, and the Architecture puts CameraX and ML Kit in `:androidApp`. The port (`CodeScanner`, `CameraPermission`) sits in `commonMain`, so the registration route and the wake mapping stay common. The implementation sits in `:androidApp`, so the camera dependencies stay out of the UI module. `FakeCodeScanner` is in the `:androidApp` host tests, because `:testing` is JVM-only and cannot see Compose.
- **3 frames, then a 2 s gate.** `ConsecutiveFrames` reports a code after 3 frames in a row and then starts its streak again. `RepeatGate` submits the same code at most once per 2 s (monotonic clock), so a wrong code held up counts one failed attempt per 2 s. A different code passes the gate at once.
- **Camera feed slot.** `LocalViewfinderFeed` lets the scanning screen draw the live `PreviewView` inside the approved `ViewfinderPlaceholder`. Nothing else in the composables changed, and previews and screenshots keep the placeholder surface.
- **Registration asks once.** If the permission is missing when registration opens, it asks once. After a denial, "Camera isn't available." with "Fix" stays, and each resume (back from the settings) only reads the permission again.
- **Unpassable entries.** `PlanResolver` turns a QR entry without a code into Math · Medium · 3. `SaveAlarm` should never let one through (a 3.5 hook), but a damaged row must not leave an alarm that cannot be stopped.
- **Format match is exact (epic).** ML Kit can read the same printed code as EAN-13 or as UPC-A, depending on the frame. This is a risk for the device checklist (3.14). Normalising UPC-A to EAN-13 is a possible follow-up.
- **`CheckType.all` is a getter.** `CheckType` now has default methods, so on the JVM the first type to initialise also initialises the interface. A stored list would then hold that type as null, which the tests caught.

**Data safety (AD-15, for Epic 8):** ML Kit's `com.google.mlkit:common` logs anonymous usage and performance events through Google's datatransport stack (`com.google.android.datatransport:*`, already on the allowlist for Crashlytics). No image or code content is sent. Bundled ML Kit also adds `MlKitInitProvider` and `MlKitComponentDiscoveryService` to the merged manifest.

**Licences:** CameraX, camera-video, viewfinder-core, media3 container/muxer, concurrent-futures, exifinterface, tracing, Guava, failureaccess, Dagger, jakarta.inject, auto-value annotations, j2objc annotations and atomicfu are Apache-2.0. jsr305 is BSD-3-Clause and checker-qual is MIT. ML Kit (`barcode-scanning`, `common`, `vision-*`, `play-services-mlkit-barcode-scanning`, `odml:image`) and `play-services-base` come under the ML Kit / Google APIs Terms of Service, which are proprietary and free to use.

## Rebase notes

Stories 3.5 to 3.9 land before this story. When rebasing:
- **`app.db` (3.5 v6, 3.9 v7):** `MIGRATION_7_8` in `AppDatabaseMigrations.kt` is a placeholder and not registered. Give it the next free version (v8 if 3.5 = v6 and 3.9 = v7). Add it to `APP_DATABASE_MIGRATIONS`, bump `AppDatabase.SCHEMA_VERSION`, and add nullable `code_format` and `code_value` to 3.5's `check_config` entity, mapped through `CheckCodeColumns.columnsOf` / `registeredCodeOf` into `CheckEntry.code`. Export the schema and add the step to `AppDatabaseFactoryTest`. Update `CheckCodeMigrationTest`, whose "not registered" assertion must flip.
- **`SaveAlarm` (3.5):** reject a plan with an entry that is not `isReady` (QR without a code). The editor shows "Scan a code to use this check." (`Res.string.qr_no_code`). The Check setup value is "Code saved" (`qr_code_saved`) once there is a code.
- **Picker and registry (3.5, `// 3.5 hook`):** add `CheckType.QrBarcode` to `PICKABLE_TYPES` and to `CheckRegistry`, where the wake composable is `CheckScreen`'s `QrCheck` through `qrCheckUiState`. The picker's QR toggle calls `CameraPermission.allowsCameraCheck()`: if it returns false, the card stays unselected and `CheckPickerUiState.cameraUnavailable` shows "Camera isn't available." with "Fix" (`CameraPermission.openSettings`). Map the UI `CheckType.QrBarcode` to the core one.
- **Register flow entry (3.5, `// 3.5 hook`):** Check setup's "Your code" (`CheckSetupIntent.ScanCodeClicked`) pushes `QrRegistrationRoute(onCodeChosen, onBack, resumed)`. `onCodeChosen` writes `CheckEntry.code` in the setup draft and pops the screen. Pass a counter that grows on each `ON_RESUME` as `resumed`, so the permission is read again after "Fix".
- **"Try it" (3.6, `// 3.6 hook`):** the QR preview provides `LocalViewfinderFeed` with `scanner.Feed(...)` and compares `RegisteredCode` with the draft's code in the preview, sending no engine event.
- **Generators (3.7, 3.8):** a new caller should use `entry.puzzle(seed)`, not `entry.type.generate(seed, entry.difficulty, entry.count)`. New types need nothing more: `puzzle`/`isReady` have defaults. Add a `Puzzle.Code` branch to any new exhaustive `when` on `Puzzle`.
- **Fallback (3.9, `// 3.9 hook`):** `qrCheckUiState` leaves `showFallbackLink` false. Feed `cameraAvailable == false` (from `WakeCheck`) into 3.9's `CameraFallbackPolicy` as `FallbackRequested(type, CameraUnavailable)`, so the link shows at once on the camera-unavailable message. `CheckType.QrBarcode.usesCamera` is true, so the policy already treats it as a camera check.
- **WakeCheck/WakeActivity:** 3.7 and 3.8 add their own `…CheckUiState` to `WakeCheck.screen`. Keep the `?: qrCheckUiState(...)` link in that chain, and the `LocalViewfinderFeed` provider around `WakeContent`.

## Verification

**Commands:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` -- expected: BUILD SUCCESSFUL.
- `git status --porcelain androidApp/src/test/screenshots/preview` -- expected: empty.

## Auto Run Result

Status: implemented in fast mode (one agent), waiting for review. The branch is `story/3-10-qr-barcode-register-and-scan`, on `main` (`cef47fa`), without Stories 3.5 to 3.9 (see Rebase notes).

**Verification:**
- `./gradlew qualityGate :androidApp:assembleDebugAndroidTest --no-daemon` gives BUILD SUCCESSFUL (22 min), with the Kover gates passing (core, session, checks).
- `git status --porcelain androidApp/src/test/screenshots/preview` is empty.
- There are 18 new Roborazzi baselines (`wake_check_qr_*` in Sunrise, `qr_registration_*` in Light and Dark, each at 100% and 200%).
- No androidTest uses the camera. Real scanning is human-verify in Story 3.14.

**Residual risks:**
- **Real camera unproven on the host:** the CameraX binding, the ML Kit model, the torch and real-world lighting are only verified on a device (3.14). The no-frame watchdog, mid-scan errors and release on pause come in 3.11.
- **EAN-13 vs UPC-A:** the same printed code may be read in either format. With the epic's exact format match, a code read the other way is a wrong code.
- **ML Kit usage logging** goes through datatransport (Data safety form, Epic 8). ML Kit's init provider also runs at app start.
- **APK size:** the bundled model and CameraX add native libraries (`libbarhopper_v3.so` and others).
- **Placeholder migration:** `MIGRATION_7_8` is not registered until 3.5 and 3.9 are in. The real `check_config` migration test lands with the rebase.
- **The printable QR row** is hidden in the app (`printable = false`) until Epic 7.
