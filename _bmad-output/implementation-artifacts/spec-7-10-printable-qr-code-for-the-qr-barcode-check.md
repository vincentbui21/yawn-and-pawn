---
title: 'Story 7.10: Printable QR code for the QR/Barcode check'
type: 'feature'
created: '2026-10-27'
status: 'done'
baseline_revision: 'bb75ee1396ff5d73eb3668137a730ee135a5ce5a'
review_loop_iteration: 1
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-7-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-3-10-qr-barcode-register-and-scan.md'
warnings: []
deferred:
  - summary: >-
      Real PrintJob states at onFinish (dialog cancelled, Save as PDF cancelled or completed) are not verified by any test.
    evidence: |-
      SystemPrintStarter maps job.info.state (QUEUED/STARTED/BLOCKED/COMPLETED = printed); every test fakes the starter.
      Settle on a phone in Story 7.12: Re-register, Print, cancel the dialog, then the old code must still pass the check;
      Save as PDF must register the new code and the saved PDF must scan.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/qr/AndroidQrPrinter.kt
    severity: medium (unverified)
  - summary: >-
      QrPrintAdapter onLayout/onWrite branches (cancelled, failed, finished) have no automated test.
    evidence: |-
      The LayoutResultCallback/WriteResultCallback constructors are package-private to android.print, so a test needs a shim.
      writeQrPdf (the page itself) is device-tested. Settle on the phone in Story 7.12 (print preview shows the page, save works).
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/qr/AndroidQrPrinter.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** A user with no handy barcode cannot use the QR/Barcode check. FR-PWK-13 promises an app-made QR code to print. The approved round 3 screens ("Make a printable QR" row, the printable QR screen and the replace dialog) exist only in the design preview. The app hides them (`printable = false`), and the code drawn is fake.

**Approach:** Core makes the payload `pps:{UUID v4}` and its `RegisteredCode`. A new UI port `QrPrinter` turns a payload into a module matrix (ZXing core 3.5.3, error correction M, 4-module quiet zone) and sends a one-page PDF to Android `PrintManager`. The editor gains an `EditorPane.PrintableQr` reached from Check setup's row and from QR registration's row. "Print or save as PDF" prints first, then registers the code exactly like "Use this code". When a code is already set, the existing replace dialog asks first.

## Boundaries & Constraints

**Always:**
- Build to the approved round 3 preview layouts (`QrRegistrationScreen`, `PrintableQrScreen`, `CheckSetup` QR rows). The entry is the approved `NavRow` "Make a printable QR" ›, not a new outlined button. All strings already exist in resources and match EXPERIENCE.md verbatim, so no new string is added.
- The payload is generated once per opening of the printable screen. It is never stored (only its `RegisteredCode` fingerprint is, as for a scan), never logged, and has no personal or alarm data.
- Registration goes through the existing paths. In the editor that is `EditorIntent.CodeRegistered` into the form; in Re-register mode (Home, Story 3.13) it goes through `storeCode`. Replacing a code stays a non-weakening change, as today (`CommitmentRules` treats codes as equal).
- PDF: `PrintedPdfDocument` on the page size the user picks (A4 or Letter), with the QR exactly 6 cm square, centred, and "Scan this to stop your alarm." under it. It is written straight to the system's output descriptor, so the app keeps no file.
- ZXing core is added to `androidApp` and to `config/dependency-allowlist.txt` with a reviewed comment (Apache-2.0, pure Java, no network).

**Never:** no new permission, no new string, no storage of the raw payload, no schema change, no change to scan or wake-check matching, no House Hunt work.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First code | QR check with no code; Print tapped | print dialog opens; form code = `RegisteredCode.of(QrCode, payload)`; pane back to Check setup ("Code saved") | — |
| Code already set | chip has a code; Print tapped | replace dialog; "Replace" prints and registers; "Keep it" closes the dialog, nothing printed or changed | — |
| From QR registration | Make a printable QR on QR registration | printable pane; Back returns to QR registration | — |
| Re-register mode | opened from Home's Re-register, then printable, Replace | code stored at once (`storeCode`), editor closes as for a scan | storage failure handled by `storeCode` as today |
| No screen to print from | printer has no attached activity | nothing registered, failure logged without payload | stays on the screen |
| Encode failure | encoder throws | matrix null; Print disabled; failure logged | — |

</intent-contract>

## Code Map

- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/checks/qr/RegisteredCode.kt` -- `RegisteredCode.of(format, raw)` is the fingerprint used by scans; reuse it.
- `core/src/commonMain/kotlin/com/yawnandpawn/app/core/id/IdGenerator.kt` -- `IdGenerator` port (UUID v4, bound in `YawnAndPawnApp.kt:140`).
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/qr/QrRegistration.kt` -- `QrRegistrationScreen` (`printable` flag), `PrintableQrScreen`, `PrintableQrUiState`, `QrIntent`, and `FakeQrCode` (preview-only drawing, to be replaced by the real matrix).
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/qr/QrRegistrationModel.kt` -- `QrRegistrationModel.onIntent` ignores the printable intents; `QrRegistrationRoute` passes `printable = false`.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/editor/AlarmEditorContract.kt` -- `EditorPane` (with `depth`) and `EditorIntent.CodeRegistered`.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/editor/AlarmEditorViewModel.kt` -- `onCheckSetupIntent` (~l.440, `else -> Unit` for the printable intent), `onCodeIntent` (~l.376, `reRegistering`/`storeCode`), `requestBack` (~l.756).
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/editor/AlarmEditorScreen.kt` -- `EditorPaneContent` (~l.470) renders `ScanCode`.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/editor/EditorSubScreens.kt` -- `checkSetupState()` sets `printable = false` (~l.128); the pane title map (~l.153) and the pane list (~l.79).
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/onboarding/OnboardingViewModel.kt` (`OVERLAY_PANES`) and `OnboardingRoute.kt` (`overlayTitle`) -- onboarding step 5 shows editor panes as overlays.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/qr/QrModule.kt`, `AndroidCameraPermission.kt`, `MlKitFrameDecoder.kt` -- Koin bindings, the attach/detach pattern used by `MainActivity` (l.83-100), and ML Kit format mapping for the device test.
- `androidApp/src/debug/.../preview/PreviewSetupSamples.kt`, `TapThroughSetup.kt`, `PreviewRound3.kt` -- preview states for `PrintableQrUiState`; keep the approved preview images unchanged.
- `androidApp/src/test/kotlin/com/yawnandpawn/app/android/qr/QrCheckScreenTest.kt:100` asserts the row is absent (flip it). `androidApp/src/test/kotlin/com/yawnandpawn/app/ui/QrScreenshotTest.kt` holds the registration screenshots.
- `config/dependency-allowlist.txt` (CameraX/ML Kit block ~l.305) and `gradle/libs.versions.toml:118` (`zxing-core` already declared).

## Tasks & Acceptance

**Execution:**
- `core/.../checks/qr/PrintableCode.kt` -- add `PrintableCode.newPayload(ids)` = `"pps:" + ids.newId()` and `registered(payload)` = the `QrCode` `RegisteredCode` -- payload rules live in core; add tests in `core/src/commonTest` (prefix, UUID v4 shape, fingerprint equals a scan of the same text).
- `composeApp/.../ui/qr/QrPrinter.kt` -- new port `QrPrinter { encode(payload): QrMatrix?; print(matrix, line, jobName): Boolean }` and `QrMatrix(size, modules)` -- keeps ZXing and PrintManager out of common code.
- `composeApp/.../ui/qr/PrintableQr.kt` -- `PrintableQrModel` plus `PrintableQrRoute(codeSaved, onCodeChosen, onBack)`: one payload per opening, Print → replace dialog or print-then-register, Keep it → close dialog -- the real screen logic.
- `composeApp/.../ui/qr/QrRegistration.kt` -- `PrintableQrUiState.code: QrMatrix?` drawn on the sheet; Print disabled when null; the preview's fake pattern becomes a `QrMatrix` sample so preview images stay the same -- real code on screen.
- `composeApp/.../ui/qr/QrRegistrationModel.kt` -- the route shows the row (`printable = true`) and `PrintableClicked` calls a new `onPrintable` callback.
- Editor files and onboarding -- add `EditorPane.PrintableQr` (depth 3, after the panes it opens from); Check setup `PrintableQrClicked` and QR registration open it; Back returns to its opener; `checkSetupState()` shows the row; register through `CodeRegistered`; add the pane to onboarding `OVERLAY_PANES` and to the title map.
- `androidApp/.../qr/ZxingQrEncoder.kt`, `AndroidQrPrinter.kt`, `QrPageLayout.kt` -- ZXing encode (M, margin 4), PDF page and `PrintDocumentAdapter`, attach/detach from `MainActivity`, Koin binding in `qrModule()`.
- `androidApp/build.gradle.kts`, `config/dependency-allowlist.txt` -- add `libs.zxing.core` and the allowlist entry with its comment.
- Tests -- composeApp `commonTest`: editor/model transitions (first code, replace confirm/cancel, re-register, no printer, back targets) with a fake `QrPrinter`. androidApp unit: ZXing round-trip decode of the payload equals `PrintableCode.registered`, and the page layout is 6 cm and centred on A4 and Letter. androidApp Roborazzi: registration with the row, the printable screen and the replace dialog in Light, Dark and 200%, each with "QR code for your alarm" and targets ≥ 48 dp. androidTest: the rendered bitmap decodes with bundled ML Kit to the registered code.
- Preview baselines, `QrCheckScreenTest` -- update the preview samples and tap-through tests; flip the absent-row assertion.

- Review pass 1 tests -- several new tests:
  - The managed-device test writes the A4 PDF through `writeQrPdf` (with printer margins), renders page 0 with `PdfRenderer` and decodes it with ML Kit.
  - The ZXing unit test asserts the decoded error-correction level is M.
  - A Robolectric test renders `AlarmEditorScreen` at `EditorPane.ScanCode` and checks that the row sends `EditorIntent.PrintableQrClicked`.
  - The same kind of test renders `EditorPane.PrintableQr` with a saved code and checks that Print shows the replace dialog and prints nothing.
  - A Robolectric `MainActivity` test checks that `QrPrinter.print` returns true while the activity is started and false after it stops.
  - Model tests cover a cancel (nothing registered, and Print works again) and a double tap (one print).

**Acceptance Criteria:**
- Given the QR registration screen or QR Check setup in the app, when it renders, then "Make a printable QR" › is shown and opens the printable screen with a real, scannable code.
- Given a generated code, when it is decoded by ZXing (unit test) or ML Kit (managed-device test), then its `RegisteredCode` equals the one registered for the alarm.
- Given the pps-design Done checklist, when the story is done, then every item is ticked below, and `CopyRulesTest` plus `./gradlew qualityGate` pass.

## Spec Change Log

### 2026-10-27, review pass 1 (bad_spec)
- **Trigger:** three reviewers (blind, edge-case and verification-gap) found that a cancelled system print dialog still registered the new code. `print()` returned true as soon as the dialog opened. In Re-register mode the code was then stored at once, so the old sheet stopped working while no new sheet existed. Related findings: a null `PrintJob` was treated as success, runtime failures other than `IllegalStateException` escaped, and a double tap could print twice.
- **Amended:**
  - Design Notes: registration waits for a print job that was not cancelled (`onPrinted` / `onCancelled`); an in-flight flag; a broader catch; a testable `writeQrPdf`; 6 cm means the code without its quiet zone.
  - Tasks: the review pass 1 tests.
- **Known-bad state avoided:** an alarm whose registered QR code exists on no sheet.
- **KEEP:** everything else from attempt 1 worked and must survive.
  - Start by applying the saved attempt-1 patch with `git apply --binary C:/Users/BuiTua/AppData/Local/Temp/claude/c--Users-BuiTua-Personal-yawn-and-pawn/0310348c-37b3-48a8-9921-3618045ea016/scratchpad/7-10-attempt-1.patch`, run from the repo root. Then change only what the amended Design Notes and Tasks require.
  - Keep these parts as they are:
    - core `PrintableCode` and `QrMatrix`;
    - `PreviewQrMatrix`, so the approved preview images stay unchanged;
    - the editor pane `PrintableQr` with `printableFrom` and `isDeeperThan`;
    - the onboarding overlay wiring;
    - the ZXing encoder (M, margin 4);
    - `QrPageLayout`, with `LINE_GAP` 36 and the 6 cm counting modules only;
    - the `MainActivity` attach/detach;
    - the allowlist entry and the `CodeAnalyzerTest` exemption;
    - all recorded screenshot baselines and every existing test.
  - Also merge the two `editor_wake_check` branches in `EditorSubScreens.title()` into one.

## Review Triage Log

### 2026-10-27, review pass
- **Verdicts:** 34 findings: high 3, medium 11, low 14, false 6, maybe-false 0. Several are the same finding reported by more than one reviewer; each still gets its own row.
- **Findings:**
  - `[high]` `[bad_spec]` (blind) A cancelled print still registers the code, and Re-register stores it at once. Confirmed in `PrintableQrModel.printAndRegister` and `AlarmEditorViewModel.onCodeIntent`. Design Notes amended: registration now waits for the print job.
  - `[medium]` `[bad_spec]` (blind) `PrintManager.print` can return a null job, or throw something other than `IllegalStateException`. Real per the `PrintManager` source. Folded into the amended `print` contract.
  - `[low]` `[reject]` (blind) No feedback to the user when print returns false. This only happens with no activity attached, which cannot happen while the screen is visible, and fixing it would need a new string.
  - `[low]` `[reject]` (blind) A failed encode shows a blank sheet. ZXing never fails on a 40-character ASCII payload, and fixing it would need a new string.
  - `[low]` `[reject]` (blind) The replace dialog's wording does not match the draft-only behaviour. This is the same as the existing scan flow (the change is a draft until Save), and an everyday Save makes the wording true.
  - `[medium]` `[bad_spec]` (blind) A double tap prints twice. Real during the exit animation. The in-flight flag is now in the Design Notes.
  - `[medium]` `[bad_spec]` (blind) `QrPrintAdapter` is untested. Added `writeQrPdf` and a `PdfRenderer` device test to Tasks.
  - `[low]` `[reject]` (blind) The layout does not fit small paper. 6 cm plus the quiet zone fits every common sheet. A scaling guard would add complexity only for label printers.
  - `[low]` `[reject]` (blind) Module seams on screen and in the PDF. Cosmetic: scanners binarise across them, and the fix (merging runs of modules) adds code.
  - `[low]` `[reject]` (blind) `isDeeperThan` depends on enum order. This is documented in its KDoc, and the editor test asserts the slide direction.
  - `[low]` `[bad_spec]` (blind) `title()` has two branches returning the same string. Merged, as a KEEP instruction.
  - `[false]` `[reject]` (blind) The diff has no screenshot baselines. The PNGs were left out of the review diff on purpose; 8 new and 12 re-recorded baselines are in the tree.
  - `[low]` `[bad_spec]` (blind) Wiring test gaps: onboarding ScanCode to PrintableQr, and `onPrintable` passing through `AlarmEditorScreen`. The screen-level pass-through test is added to Tasks; onboarding uses the same editor screen.
  - `[false]` `[reject]` (blind) The wake QR check could offer "Make a printable QR". The wake check renders `CheckScreen`, not `QrRegistrationScreen`.
  - `[false]` `[reject]` (blind) No planning updates. The spec file exists, and sprint-status is updated at finalize.
  - `[high]` `[bad_spec]` (edge) The dialog is cancelled after `print()` already returned true. Same as the first row; same amendment.
  - `[medium]` `[bad_spec]` (edge) A null `PrintJob` returns true. Same amendment.
  - `[medium]` `[bad_spec]` (edge) A `RuntimeException` from `manager.print` escapes. Same amendment (a broader catch).
  - `[medium]` `[bad_spec]` (edge) The `PrintedPdfDocument` constructor sits outside the try. Amended: `onWrite` catches `RuntimeException`.
  - `[low]` `[reject]` (edge) Small paper gives a negative left/top. See the small-paper row above.
  - `[false]` `[reject]` (edge) `drawQrPage` gets a matrix without a quiet zone. Only `ZxingQrEncoder` matrices (margin 4) reach it; `PreviewQrMatrix` is never printed.
  - `[medium]` `[bad_spec]` (edge) Double tap. Same as the blind row.
  - `[low]` `[bad_spec]` (edge, claim) "Prints first, then registers" overstates what happens. Resolved by the amendment: registration now follows the job.
  - `[medium]` `[bad_spec]` (verification-gap) `onPrintable` passing through `AlarmEditorScreen` is untested. Added to Tasks.
  - `[medium]` `[bad_spec]` (verification-gap) The `codeSaved` expression in `AlarmEditorScreen` is untested. Added to Tasks.
  - `[medium]` `[bad_spec]` (verification-gap) The `MainActivity` attach is untested. Added to Tasks.
  - `[medium]` `[bad_spec]` (verification-gap) The `QrPrintAdapter` output is never exercised. Added to Tasks (`writeQrPdf` plus `PdfRenderer`).
  - `[high]` `[bad_spec]` (verification-gap, other) Re-register plus a cancelled print stores an unprinted code. Same as the first row.
  - `[low]` `[reject]` (intent) Registration is tied to printing, not to generation. This is the intended reading (A2), consistent with "exactly as a scanned code".
  - `[false]` `[reject]` (intent) The replace dialog's trigger. It follows the approved EXPERIENCE.md layout, row 51.
  - `[low]` `[reject]` (intent) No commitment-lock test for a printed replacement. Registration goes through the same `CodeRegistered`/`storeCode` path that the Story 3.13 and 4.4 tests already cover.
  - `[low]` `[reject]` (intent) Scan verification on screen. The AC's instrumented test is the bitmap decode, and a PDF render decode is now added too.
  - `[low]` `[bad_spec]` (intent) No test pins error correction M. Added to Tasks: assert the decoded EC level.
  - `[false]` `[reject]` (intent) Finish not done and qualityGate not evidenced. This is done at finalize and in CI, so it is not a code defect.

### 2026-10-27, review pass 2
- **Verdicts:** 33 findings: high 0, medium 5, low 16, false 6, maybe-false 6.
- **Findings:**
  - `[medium]` `[patch]` (blind) "Went to print" was a blacklist that accepted STATE_CREATED. Patched: `SystemPrintStarter` now accepts only QUEUED, STARTED, BLOCKED or COMPLETED.
  - `[maybe-false]` `[defer]` (blind) Real `PrintJob` states at `onFinish` (dialog cancelled, Save as PDF cancelled or completed) are never checked. Settled only by a real print dialog; added to the Story 7.12 device checklist.
  - `[low]` `[reject]` (blind) A queued job that later fails still replaces the old code. The user is not trapped: the wrong-code fallback link after 5 attempts (Story 3.11) still works, and the code can be printed again from the editor.
  - `[low]` `[reject]` (blind) The in-flight flag can stick if `onFinish` never comes. The framework always calls `onFinish`, and opening the screen again resets the flag; nothing gets registered, which is the safe outcome.
  - `[medium]` `[patch]` (blind) A late `onPrinted` is not tied to the screen. Patched: the ViewModel ignores `CodeRegistered` unless the pane is ScanCode or PrintableQr.
  - `[low]` `[reject]` (blind) The payload is kept in `remember`, not `rememberSaveable`. On an activity recreate the old model registers the code that was actually printed, which is correct; the app is portrait only.
  - `[low]` `[reject]` (blind) No feedback when Print fails. It would need a new string; the failure paths (finishing activity, spooler disabled) are rare.
  - `[maybe-false]` `[defer]` (blind) `QrPrintAdapter` `onLayout`/`onWrite` branches are untested. The callback constructors are package-private to `android.print`; a test needs an `android.print` shim. Checked on the phone in Story 7.12 instead (print and save as PDF).
  - `[low]` `[reject]` (blind) No rendered test of Check setup's row in the app path. The row is the same `CheckSetup` composable the approved preview baselines (`check_setup_qr_*`) already render with the row shown.
  - `[low]` `[patch]` (blind) `writeQrPdf` is public and weakens the no-storage guard. Patched: it is now `internal` with `@VisibleForTesting`.
  - `[low]` `[patch]` (blind) Two onboarding comments are stale. Patched.
  - `[false]` `[reject]` (blind) The callbacks' main-thread promise could race. `print` runs from a click on the main thread, and `onFinish` is posted to the main thread, so `whenFinished` is always set before `onFinish` can run.
  - `[low]` `[reject]` (blind) The PDF device test covers only A4 portrait. Letter is covered by the layout unit test; landscape uses the same centring maths.
  - `[maybe-false]` `[defer]` (edge) `PrintJob.getInfo` can return stale cached CREATED. The whitelist now treats CREATED as cancelled; a real device shows the remaining timing (Story 7.12).
  - `[low]` `[reject]` (edge) Small media clips the quiet zone. Same as the pass 1 rejection.
  - `[medium]` `[patch]` (edge) A late `onPrinted` after leaving the pane. Same patch as above.
  - `[false]` `[reject]` (edge) A matrix without its quiet zone. Same as the pass 1 refutation.
  - `[medium]` `[patch]` (edge, claim) A cancelled job counted as printed via the cached state. Same as the whitelist patch.
  - `[maybe-false]` `[defer]` (verification-gap) The adapter's `onLayout`/`onWrite` never run in a test. Same as the defer above.
  - `[medium]` `[patch]` (verification-gap) The printed PDF is checked for "it scans", not for 6 cm and centred. Patched: the device test asserts the ML Kit bounding box size and centre.
  - `[maybe-false]` `[defer]` (verification-gap) `SystemPrintStarter` is never run against a real `PrintJob`. Same as the device-checklist defer above.
  - `[low]` `[reject]` (verification-gap) The onboarding overlay title for PrintableQr is untested. The other overlay titles are untested in the same way; this is a one-line `when` branch.
  - `[low]` `[reject]` (intent) Registration on print rather than on generation. This was decided in review pass 1 (the Design Notes); no new evidence.
  - `[low]` `[reject]` (intent) The replace dialog guards the Print tap. This follows EXPERIENCE.md row 51.
  - `[false]` `[reject]` (intent) The on-screen Compose code is never decoded. The AC's instrumented test is the rendered bitmap; the screen and the PDF use the same matrix.
  - `[low]` `[reject]` (intent) No weakening test for a printed replacement. Same path as a scan (`CodeRegistered` / `storeCode`); see pass 1.
  - `[low]` `[reject]` (intent) The hint string is not asserted. It is the existing approved preview string, rendered in the screenshots.
  - `[false]` `[reject]` (intent) The PNGs are not in the diff. They were left out of the review diff on purpose and are in the tree.
  - `[low]` `[reject]` (intent) The preview draws a different code from the app. Deliberate, so the approved preview images stay unchanged.
  - `[false]` `[reject]` (intent) Finish obligations are not visible. They are done at finalize and in CI.
  - `[low]` `[reject]` (intent) Silent interpretive choices (6 cm without the quiet zone, the paper the user picks, Save as PDF counts, a new code on every opening). All are recorded in the Design Notes.
  - `[maybe-false]` `[defer]` (intent) No test exercises the real `PrintManager`. Same as the device-checklist defer.
  - `[false]` `[reject]` (intent) The owner-review list is not reflected. Every screen was in the approved preview; the real code drawing is listed in the PR for the owner to see.

## Design Notes

- The approved preview uses a `NavRow`, not the epic's `button-outlined`; the preview wins (owner-approved round 3).
- Registration waits for the print job (amended after review pass 1). `QrPrinter.print(matrix, line, jobName, onPrinted, onCancelled)` returns false when nothing could be shown: no activity is attached, `PrintManager.print` returned a null job, or it threw any `RuntimeException`. Otherwise it returns true. It then calls exactly one of `onPrinted` or `onCancelled`, on the main thread, from the adapter's `onFinish`: `onPrinted` when the `PrintJob` returned by `PrintManager.print` is neither cancelled nor failed (queued, started, blocked and completed all count; "Save as PDF" completes), otherwise `onCancelled`. A cancelled dialog registers nothing, so the old code and its sheet keep working. This matters most in Home's Re-register mode, where `CodeRegistered` stores the code at once. The printable screen stays while the dialog is open, then `onPrinted` registers through `onCodeChosen` exactly as before.
- `PrintableQrModel` sets an in-flight flag on a Print or Replace tap. The flag clears on a false return, on `onPrinted` and on `onCancelled`. While it is set, further Print and Replace taps are ignored.
- The payload is never kept, so the same code cannot be printed again later. Opening the screen again makes a new code, and the replace dialog says so.
- The 6 cm is the code itself: the dark modules, without the quiet zone. The white quiet zone is extra around it, and the line sits below the quiet zone.
- The PDF writing is a testable function `writeQrPdf(context, attributes, matrix, line, out: OutputStream)`, called by `QrPrintAdapter.onWrite`. All of `onWrite`, including the `PrintedPdfDocument` constructor, catches `RuntimeException` and `IOException` and reports `onWriteFailed`.

## pps-design Done checklist

- [x] Only DESIGN.md tokens (no raw hex, radii or font sizes). The printed PDF page (not a screen) is black on white paper with a 14 pt line.
- [x] Light and Dark checked with screenshots (`qr_printable_*`, `qr_printable_replace_*`, `qr_registration_*`)
- [x] Every colour pair is in the contrast table (sheet: `text` on `surface`, `inverse-text` on `inverse-surface`)
- [x] Touch targets ≥ 48 dp (asserted: the row, Print, Back, Replace, Keep it)
- [x] Works at 200% font scale and with TalkBack (QR image content description "QR code for your alarm", asserted)
- [x] Reduced-motion path works (no new motion; the existing pane slide)
- [x] Copy matches EXPERIENCE.md, strings in resources (no new string; the print job is named with `app_name`)
- [x] State rows handled (no code, code saved / replace, camera unavailable still offers the printable QR)
- [x] "I'm up" prominence (not applicable: no wake surface)
- [x] Previews and screenshot tests updated (preview images unchanged)

## Verification

**Commands:**
- `./gradlew :core:jvmTest :composeApp:testAndroidHostTest` (targeted) -- expected: pass
- `./gradlew :androidApp:testDebugUnitTest --tests "*Qr*"` plus a Roborazzi record for the new screenshots -- expected: pass
- `./gradlew qualityGate` (CI) -- expected: pass; the managed-device test runs in CI

## Auto Run Result

Status: done

**Summary:** "Make a printable QR" now works in the app.
- It opens from QR/Barcode Check setup and from QR registration, in the editor, in onboarding step 5 and in Home's Re-register.
- The printable screen shows a real code. Core makes the payload `pps:{UUID v4}`, and ZXing core 3.5.3 encodes it with error correction M and a 4-module quiet zone.
- "Print or save as PDF" opens the system print dialog with a one-page `PrintedPdfDocument`: the code 6 cm square and centred, with "Scan this to stop your alarm." under it. The page is written only to the print service's file.
- The code is registered the same way a scan is, but only once the print job went to print (queued, started, blocked or completed). Cancelling the dialog keeps the old code.
- If a code is already saved, the approved replace dialog asks first.

**Files changed:**
- **core:** `PrintableCode` (the payload and its `RegisteredCode`), with tests.
- **composeApp:**
  - `QrPrinter` port and `QrMatrix`.
  - `PrintableQrModel` / `PrintableQrRoute`: in-flight flag; registration on `onPrinted`.
  - `QrRegistration.kt`: real matrix drawing; `PreviewQrMatrix` keeps the preview images unchanged.
  - `QrRegistrationModel.kt`: `onPrintable`.
  - Editor: `EditorPane.PrintableQr`, `PrintableQrClicked`, `printableFrom`, `isDeeperThan`, and a guard that ignores `CodeRegistered` outside ScanCode / PrintableQr. `checkSetupState()` shows the row.
  - Onboarding: overlay panes and title.
- **androidApp:**
  - `ZxingQrEncoder`, plus `QrPageLayout` / `drawQrPage`.
  - `AndroidQrPrinter`: `PrintStarter` seam, `QrPrintAdapter`, internal `writeQrPdf`.
  - `MainActivity` attach/detach, the Koin binding, the `zxing-core` dependency and its allowlist entry.
- **Tests:**
  - `PrintableCodeTest`, `PrintableQrModelTest`, `AlarmEditorQrTest`, `AlarmEditorTestAlarmTest`, `OnboardingSetupStepsTest`.
  - `PrintableQrTest`, `PrintableQrScreenTest`, `PrintableQrAppTest`, `QrCheckScreenTest`, `CodeAnalyzerTest`.
  - `QrScreenshotTest`: 8 new baselines, and the 12 registration baselines re-recorded with the row.
  - `PrintableQrDeviceTest` (CI managed device): the bitmap and the PDF decode with ML Kit, 6 cm and centred.

**Review:**
- **Pass 1:** 34 findings. One bad_spec entry: a cancelled print still registered the code. That led to amended Design Notes and a re-derivation from the saved attempt-1 patch. The low and false findings were rejected, with reasons in the triage log.
- **Pass 2:** 33 findings.
  - 5 patches: a whitelist of print-job states, a guard against a late `CodeRegistered`, a bounding-box size and centre check, `writeQrPdf` made internal, and stale comments fixed.
  - 2 deferred to the Story 7.12 phone check, recorded in the frontmatter and in `deferred-work.md`: the real `PrintJob` states, and the `QrPrintAdapter` callbacks.
  - The rest were rejected.

**Follow-up review recommendation:** true. Pass 2 patched 3 medium entries. The open risk is that nobody has yet seen the real print spooler's `PrintJob` state when `onFinish` arrives. So only a phone can rule out a cancelled dialog registering the code (Story 7.12 item).

**Verification:**
- Passed locally:
  - `:core:jvmTest` (qr) and `:composeApp:testAndroidHostTest` (all).
  - `:androidApp:testDebugUnitTest` for `*Qr*`, `*Printable*`, `*Editor*`, `*Onboarding*`, `*MainActivity*` and `*CodeAnalyzer*`, including Roborazzi verify.
  - `:androidApp:compileDebugAndroidTestKotlin` and `:androidApp:checkDependencyAllowlist`.
  - spotless and detekt, run by the implementation agent.
- Not run locally: the full `qualityGate` and the managed-device test run in CI.

**Residual risks:**
- The PDF and print dialog behaviour on real printers, and "Save as PDF", are checked only on the phone in Story 7.12.
- The app's sheet draws the real code, while the approved preview draws a made-up one. The layout is identical.
