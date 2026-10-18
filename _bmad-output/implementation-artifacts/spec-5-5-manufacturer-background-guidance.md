---
title: 'Story 5.5: Manufacturer background guidance'
type: 'feature'
created: '2026-10-16'
status: 'done'
baseline_revision: 'e35b0e2c71d601ed29504ea3844168c90b934eef'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
warnings: ['oversized']
deferred:
  - 'Story 5.4: the checklist screen, whose Manufacturer row "Fix" calls openManufacturerGuidance() (no production caller before it); the Settings "Reliability checklist" row stays hidden until then.'
  - 'Story 5.13: check the Oppo/ColorOS steps and the "Open settings" target on the Oppo A96; the other makers stay unverified on a device.'
  - summary: >-
      The community-collected OEM component names may be missing or unexported on current OS versions.
    evidence: |-
      Each is resolved before launch and a throwing launch falls through to the app details, so the cost is a missed
      shortcut. Settling it needs a device per maker; Story 5.13 covers the Oppo A96 only.
    location: >-
      androidApp/src/main/kotlin/com/yawnandpawn/app/android/reliability/OemIntents.kt
    severity: medium (unverified)
---

<intent-contract>

## Intent

**Problem:** Xiaomi, Samsung, Huawei, Oppo/Realme, Vivo and OnePlus phones have their own battery tools that stop background apps, and the Manufacturer row of the reliability checklist (Story 5.3) is always Hidden: no maker is recognised and there is no guidance to follow.

**Approach:** a pure `DeviceMaker` mapping in core turns the Manufacturer row on for covered makers; a pushed guidance screen (`Route.ManufacturerGuidance`) shows that maker's 2 to 4 numbered steps, "Open settings" (tries the maker's known settings components from `OemIntents`, only resolvable ones, else app details) and "I've done this" (stores the maker and `Build.FINGERPRINT`, so a system update turns the row Revoked). Every maker's steps and sources go in `docs/oem-guidance.md`.

## Boundaries & Constraints

**Always:** core stays pure (AD-1): no Android types in `DeviceMaker`, ports return `Outcome`; strings are Compose resources; EXPERIENCE.md strings verbatim ("Manufacturer settings", "Open settings", "I've done this", the Xiaomi steps, the revoked line); new strings are owner drafts listed below; tokens only, `SubScreen` + `GroupCard` like the other sub-screens; every component intent is checked with `resolveActivity` before it is launched, and a launch that throws falls through to the next.

**Never:** no new permission (`<queries>` package entries are not permissions) and no new dependency; no change to Home's banner or `alarmBlocking` (Manufacturer never blocks); no checklist screen (Story 5.4); never launch an unresolvable component; no adb or device run.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Maker mapping | `MANUFACTURER`/`BRAND` (any case, trimmed): xiaomi, redmi, poco; samsung; huawei, honor; oppo, realme; vivo, iqoo; oneplus | Xiaomi; Samsung; Huawei; OppoRealme; Vivo; OnePlus. Manufacturer is checked first, then brand | anything else → Other, row Hidden |
| Open settings | covered maker, first resolvable component | that component starts (new task) | not resolvable or throws → next; none → app details → `ACTION_SETTINGS`; logged, never crashes |
| I've done this | covered maker | `MakerConfirmation(maker, fingerprint)` stored, Manufacturer added to the rows seen OK, screen closes | write failure logged, screen stays, tap again |
| System update | stored confirmation, new `FINGERPRINT` | Manufacturer row Revoked ("Alarm may not ring: check your phone's background settings again") | — |
| Other maker opens the screen | Other | no steps, buttons still work (app details) | — |

</intent-contract>

## Code Map

- `core/.../core/reliability/ReliabilityChecklist.kt:62` -- `DeviceInfo(sdkInt, maker, fingerprint, makerCovered)`; the Manufacturer row is `confirmed.takeIf { device.makerCovered }` (l.148). `ReliabilityRecords.confirmMaker`/`saveLastOk`/`read` exist (data adapter `data/.../settings/DataStoreReliabilityRecords.kt`, fake `testing/.../ReliabilityFakes.kt`).
- `androidApp/.../android/reliability/AndroidReliability.kt:247` -- `AndroidDeviceInfoSource` hard-codes `makerCovered = false`; `AndroidReliabilitySettings` (l.145) is the start/fallback pattern (`FLAG_ACTIVITY_NEW_TASK`, catches `ActivityNotFoundException`/`SecurityException`, logs).
- `androidApp/.../android/reliability/ReliabilityModule.kt` -- Koin bindings of the reliability ports.
- `androidApp/src/main/AndroidManifest.xml` -- no `<queries>` yet; API 30+ `resolveActivity` cannot see other apps' components without it.
- `composeApp/.../ui/reliability/ReliabilityScreen.kt:248` -- the design-preview pane `ManufacturerSteps` (Xiaomi only, outlined + text buttons), used only by debug previews (`androidApp/src/debug/.../preview/TapThrough.kt`, `PreviewRound2Reducer.kt`, `PreviewProgressSamples.kt`; goldens `androidApp/src/test/screenshots/preview/reliability_manufacturer_{light,dark}.png`).
- `composeApp/.../ui/nav/Route.kt`, `TabNavigation.kt`, `AppNavHost.kt:105` -- routes, push helpers (`openProblemWithCharge` pattern), entries.
- Template for a pushed screen with a port: `composeApp/.../ui/payments/ProblemWithCharge{Route,ViewModel,Contract}.kt`, test `composeApp/src/commonTest/.../ui/payments/ProblemWithChargeViewModelTest.kt`, screenshots `androidApp/src/test/.../ui/ProblemWithChargeScreenshotTest.kt` (`withScreen`, `screenshotOptions`, `@Config(fontScale = 2.0f)`).
- `composeApp/src/commonMain/composeResources/values/strings.xml:371-391` -- reliability strings; `CopyRulesTest` checks em dash, banned words, emoji and word counts.
- `composeApp/.../ui/UiModule.kt` + `composeApp/src/commonTest/.../ui/UiModuleTest.kt` -- ViewModel bindings and their resolution test.
- `androidApp/src/test/.../reliability/ReliabilitySettingsTest.kt:66` -- the Robolectric resolver pattern (`checkActivities(true)`, `addResolveInfoForIntent`, `nextStartedActivity`).

## Tasks & Acceptance

**Execution:**
- `core/.../reliability/DeviceMaker.kt` (new) -- `enum DeviceMaker` + `covered` + `of(manufacturer, brand)`; `fun interface ManufacturerSettings { fun open(maker: DeviceMaker) }`; `ConfirmMakerGuidance(device, records, logger)` (confirm, then add Manufacturer to `lastOk`; a failed `lastOk` write is logged, the confirmation still counts).
- `core/.../reliability/ReliabilityChecklist.kt` -- `DeviceInfo.makerCovered` becomes `deviceMaker: DeviceMaker = Other` with `makerCovered` derived; update the 5.3 tests that set `makerCovered`.
- `androidApp/.../reliability/OemIntents.kt` (new) -- per maker, the ordered `ComponentName`s (Design Notes) and `packages`; `AndroidManufacturerSettings` (resolve, start, fall through, app details, `ACTION_SETTINGS`).
- `androidApp/.../reliability/AndroidReliability.kt`, `ReliabilityModule.kt` -- `AndroidDeviceInfoSource` reads `Build.BRAND` and maps; bind `ManufacturerSettings` and `ConfirmMakerGuidance`.
- `androidApp/src/main/AndroidManifest.xml` -- `<queries>` with one `<package>` per `OemIntents` package.
- `composeApp/.../ui/reliability/ManufacturerGuidance.kt` (new) -- state, intents, `DeviceMaker.guidanceSteps()`, stateless `ManufacturerGuidanceScreen(maker, onBack, onOpenSettings, onDone)` (`SubScreen`, numbered steps in a `GroupCard`, `PpsFilledButton` "Open settings", `PpsOutlinedButton` "I've done this"), `ManufacturerGuidanceViewModel`, `ManufacturerGuidanceRoute` (closes when done).
- `composeApp/.../ui/reliability/ReliabilityScreen.kt` -- the preview pane renders `ManufacturerGuidanceScreen`; `manufacturerSteps` becomes `manufacturer: DeviceMaker = Xiaomi`.
- `composeApp/.../ui/nav/{Route,TabNavigation,AppNavHost}.kt`, `ui/UiModule.kt` -- `Route.ManufacturerGuidance`, `openManufacturerGuidance()` (only over a tab on top), its entry, the ViewModel binding.
- `composeApp/.../composeResources/values/strings.xml` -- new step strings (owner drafts below); the shared Recents step replaces `reliability_xiaomi_step_3` with the same text.
- `testing/.../ReliabilityFakes.kt` -- `FakeManufacturerSettings`.
- `docs/oem-guidance.md` (new) -- per maker: steps, sources, versions, components, review date. `docs/EXPERIENCE.md` and the spine EXPERIENCE.md Long-form line point at the drafts.
- Tests: `DeviceMakerTest`, `ConfirmMakerGuidanceTest`, the fingerprint Revoked case in `ReliabilityChecklistTest`; `ManufacturerSettingsTest` (Robolectric, each maker with and without a resolvable component, a throwing one, the manifest `<queries>` cover every package); `AndroidDeviceInfoSource` mapping; `ManufacturerGuidanceViewModelTest`, nav test, `UiModuleTest`; `ManufacturerGuidanceScreenshotTest` (6 makers × Light, Dark, 200%) and re-recorded preview goldens.

**Acceptance Criteria:**
- Given each alias in any case, when `DeviceMaker.of` runs, then it maps as the matrix says, and Other hides the Manufacturer row.
- Given a covered maker, when the guidance screen opens, then it shows that maker's steps numbered 1 to n (2 to 4, each ≤ 25 words), a filled "Open settings" and an outlined "I've done this"; TalkBack reads each step as one item with its number.
- Given a covered maker and no resolvable component, when "Open settings" is tapped, then the app details open; with a resolvable one, that component opens.
- Given "I've done this" succeeded, when the checklist is evaluated, then the Manufacturer row is OK; after a fingerprint change it is Revoked.
- Given the targeted tests, `spotlessCheck` and `detekt`, they pass.

## Spec Change Log

## Review Triage Log

### 2026-10-18 — Review pass (fast mode: one combined self-review of the diff; the coordinator runs the independent review)
- verdicts: 7 findings — high 0, medium 0, low 3, false 2, maybe-false 2
- findings:
  - `[low]` `patch` detekt `TooManyFunctions` on `TabNavigation.kt` (12 > 11) — `openManufacturerGuidance()` moved to its own `ui/nav/ReliabilityNavigation.kt`, same rule (only over a tab).
  - `[low]` `patch` detekt `LongMethod` on the extended `UiModuleTest` case — the maker guidance resolution is its own test.
  - `[low]` `patch` two test lines over 140 characters (`ConfirmMakerGuidanceTest`, `ManufacturerGuidanceViewModelTest`) — split.
  - `[false]` `reject` "I've done this" on a phone mapped Other stores a confirmation — the row stays Hidden for Other (`confirmed.takeIf { device.makerCovered }`), and the screen is reachable only from that row once Story 5.4 exists.
  - `[false]` `reject` A screen with `<queries>` packages counts as a new permission — `<queries>` is package visibility only; `config/permission-allowlist.txt` is unchanged.
  - `[maybe-false]` `defer` The community component names may not exist or be exported on current OS versions — each is resolved first and a throwing launch falls through to app details, so the cost is only a missed shortcut; settling it needs a device per maker (Story 5.13 covers the Oppo A96).
  - `[maybe-false]` `reject` The Oppo steps assume ColorOS 12-14 labels ("Allow background activity", "Allow auto launch") — sourced from realme/ColorOS guides, not an English Oppo page; Story 5.13 checks them on the A96, and they are owner drafts.

## Design Notes

**Owner sign-off: new strings (drafts, not in EXPERIENCE.md; sourced in `docs/oem-guidance.md`):**
- Shared: "Lock the app in Recents so it stays open." (EXPERIENCE.md Xiaomi step 3, reused for every maker) · "In the app's Battery usage, turn on “Allow background activity”." (Oppo/Realme and OnePlus)
- Samsung: "In Battery, open Background usage limits and add Yawn & Pawn to “Never sleeping apps”." · "Turn off “Put unused apps to sleep”." · Recents
- Huawei: "In App launch, set Yawn & Pawn to “Manage manually”." · "Turn on Auto-launch, Secondary launch and Run in background." · Recents
- Oppo/Realme: Battery usage step · "Turn on “Allow auto launch” on the same screen." · Recents
- Vivo: "In Battery, allow “High background power consumption” for Yawn & Pawn." · "Turn on Autostart for Yawn & Pawn." · Recents
- OnePlus: Battery usage step · "In Battery optimization, turn off “Advanced optimization” if your phone has it." · Recents

**Owner design review:** the guidance screen (`ManufacturerGuidanceScreen`, screenshots `androidApp/src/test/screenshots/manufacturer_guidance_*.png`). It follows the epic (filled "Open settings", outlined "I've done this"); the round-2 preview pane had outlined + text buttons, and now uses the same composable.

**DEFAULT TAKEN (owner can change):**
- Manufacturer first, then brand, exact alias match (a Redmi reports `MANUFACTURER` Xiaomi; an Honor phone after the split reports Honor and gets the Huawei steps).
- "I've done this" also adds Manufacturer to the rows seen OK, so a system update before the checklist is next opened still reads Revoked, not NeedsFix.
- Fallback order: maker components → app details → `ACTION_SETTINGS`. Oppo/Realme and OnePlus steps start from the app details screen (Battery usage), which is also where the fallback lands.
- `openManufacturerGuidance()` pushes over a tab only until Story 5.4 pushes it over the checklist.

**Components** (community-collected names, e.g. the MIT AutoStarter library; unverified on devices except Story 5.13's Oppo): Xiaomi `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`, `com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity`; Samsung `com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity`, `…/com.samsung.android.sm.ui.battery.BatteryActivity`; Huawei `com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity`, `.optimize.process.ProtectActivity`, `.appcontrol.activity.StartupAppControlActivity`; Oppo/Realme `com.coloros.safecenter/.permission.startup.StartupAppListActivity`, `com.coloros.safecenter/.startupapp.StartupAppListActivity`, `com.oppo.safe/.permission.startup.StartupAppListActivity`; Vivo `com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`, `com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager`, `com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity`; OnePlus `com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity`.

**pps-design Done checklist** (tick each item when it holds; an item that does not apply says why):
- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes). `PpsTheme` colours, typography and spacing only; the step row is the round-2 pane's layout.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots. Light and Dark for all six makers; Sunrise does not apply (not a wake screen).
- [x] Every colour pair used is in the `DESIGN.md` contrast table. `text` and `text-secondary` on glass, and the existing filled/outlined button pairs; no new pair.
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp. Both buttons and each step row are at least `targetMin` (48 dp); no wake action here.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present. 200% screenshots per maker; each step row merges its number and text (semantics test); no outcome is shown on this screen.
- [x] Reduced-motion path works. No animation of its own; the push uses the shared screen transition, instant with reduced motion.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources). Verbatim keys reused; new steps are owner drafts in resources, checked by `CopyRulesTest` (≤ 25 words each).
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled. Covered maker (steps), Other (no steps, buttons still work), confirm failure (screen stays).
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced. Does not apply: not a wake screen.
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Paparazzi or Roborazzi). `ManufacturerGuidanceScreenshotTest` (6 makers × Light, Dark, 200%), recorded. The design-preview `preview/reliability_manufacturer_{light,dark}` goldens (`PreviewEditorScreenshotTest`) are re-recorded: the pane now shows the real guidance screen.

## Verification

**Environment:** company PC without admin rights, other builds run at the same time. Set `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`; run Gradle only with `--no-daemon --max-workers=2` and only the targeted tasks below (no full `qualityGate`). No phone, no adb, no push. The repo is public: no secrets or personal data. Do not commit (the coordinator's run commits).

**Commands** (all with `--no-daemon --max-workers=2`):
- `:core:jvmTest --tests "*reliability*"` -- pass
- `:composeApp:testAndroidHostTest` or `:composeApp:jvmTest` for `*ManufacturerGuidance*`, `*UiModuleTest*`, `*TabNavigation*`, `*CopyRules*` -- pass
- `:androidApp:recordRoborazziDebug -Proborazzi.test.verify=false --tests "*ManufacturerGuidanceScreenshot*" --tests "*PreviewScreenshotTest*"`, then `:androidApp:testDebugUnitTest` for those plus `*ManufacturerSettings*`, `*ReliabilityProbeTest*`, `*ReliabilitySettings*` -- pass
- `spotlessCheck detekt` -- pass

## Auto Run Result

Status: done (fast mode; not pushed; the follow-up below finished the verification). The first local verification round could not finish because the PC ran out of memory while other lanes were building (the JVM `hs_err` crash logs were deleted).

**Summary:**
- `DeviceMaker` maps `Build.MANUFACTURER`, then `Build.BRAND`, to six covered makers; any other maker hides the row. It turns the Manufacturer row on for covered makers.
- The pushed `Route.ManufacturerGuidance` screen shows that maker's 3 numbered steps and two buttons:
  - a filled "Open settings": the maker's `OemIntents` screens, each resolved first, then the app details, then the system settings;
  - an outlined "I've done this": stores the maker and the fingerprint, marks the row seen OK and closes. A system update then reads Revoked.
- `docs/oem-guidance.md` lists the steps, sources, components, versions and the review date.

**Files:**
- Core:
  - `core/.../reliability/DeviceMaker.kt` (new): `DeviceMaker`, the `ManufacturerSettings` port and `ConfirmMakerGuidance`.
  - `ReliabilityChecklist.kt`: `DeviceInfo.deviceMaker`, with `makerCovered` derived from it.
- androidApp:
  - `androidApp/.../reliability/OemIntents.kt` (new): `OemIntents` and `AndroidManufacturerSettings`.
  - `AndroidReliability.kt`: the device info maps the maker.
  - `ReliabilityModule.kt`: the bindings.
  - `AndroidManifest.xml`: `<queries>` for the 9 packages.
- composeApp:
  - `composeApp/.../reliability/ManufacturerGuidance.kt` (new): the steps, screen, ViewModel and route.
  - `ReliabilityScreen.kt`: the preview pane reuses the screen.
  - `nav/Route.kt`, `nav/ReliabilityNavigation.kt` (new), `nav/TabNavigation.kt`, `nav/AppNavHost.kt`, `UiModule.kt`: the route, the push helper, the nav entry and the ViewModel binding.
  - `strings.xml`: the owner-draft steps.
- Testing: `testing/.../ReliabilityFakes.kt` gets `FakeManufacturerSettings`.
- Docs: `docs/oem-guidance.md` (new). Both EXPERIENCE.md copies now point at the drafts.
- Tests:
  - Core: `DeviceMakerTest`, `ConfirmMakerGuidanceTest`, `ReliabilityChecklistTest`.
  - Robolectric: `ManufacturerSettingsTest`, `ReliabilityProbeTest`.
  - composeApp and testing: `ManufacturerGuidanceViewModelTest`, `TabNavigationTest`, `UiModuleTest`, `CopyRulesTest`, `ReliabilityFakesTest`.
  - Screenshots: `ManufacturerGuidanceScreenshotTest` and 18 goldens, `androidApp/src/test/screenshots/manufacturer_guidance_*.png`.

**Review:** fast-mode self-review with 7 findings.
- 3 low patched: detekt and line length.
- 2 false and 1 maybe-false rejected.
- 1 maybe-false deferred: the OEM component names.
- Follow-up review recommended: false. No high or medium finding was patched.

**Verification** (JDK 17, `--no-daemon --max-workers=2`):
- Passed before the final detekt patches:
  - `:core:jvmTest --tests "*reliability*"`: `DeviceMakerTest` 4, `ConfirmMakerGuidanceTest` 4, `ReliabilityChecklistTest` 14.
  - `:testing:allTests`.
  - `:composeApp:testAndroidHostTest`: `CopyRulesTest` 15, `UiModuleTest` 3, `TabNavigationTest` 12, `ManufacturerGuidanceViewModelTest` 5.
  - `:androidApp:recordRoborazziDebug` for `ManufacturerGuidanceScreenshotTest`: 18 goldens recorded and inspected.
- `:androidApp:testDebugUnitTest` crashed partway (out of memory):
  - passed: `ManufacturerSettingsTest` 8/8, `ReliabilitySettingsTest` 7/7, `Round2TapThroughTest` 6/6;
  - ran only partly: `ReliabilityProbeTest` and the screenshot verify.
- `spotlessApply` ran. The first `detekt` run found only the 4 issues the review patched.
- **Not re-run after the patches, because the machine was out of memory:**
  - `spotlessCheck` and `detekt`;
  - the tests touched by the patches: the moved `openManufacturerGuidance()` and the split `UiModuleTest`;
  - `:androidApp:testDebugUnitTest` in verify mode for the screenshots and `ReliabilityProbeTest`.
  - CI or the coordinator runs these.

**Follow-up (2026-10-18), after CI on PR #69:**
- CI ran 2177 tests. 2 failed: `PreviewEditorScreenshotTest[reliability_manufacturer_light]` and `[..._dark]`. This was expected, because the design-preview pane now uses the real guidance screen.
- Re-recorded those two baselines and checked them: they show the Xiaomi guidance screen.
- Then, with `--no-daemon --max-workers=2`, all of these pass:
  - `:androidApp:testDebugUnitTest --tests '*PreviewEditorScreenshotTest*'` in verify mode: 152/152.
  - `spotlessCheck` and `detekt`: 0 issues in core, composeApp and androidApp.
  - `:composeApp:testAndroidHostTest`: `TabNavigationTest` 12/12 and `UiModuleTest` 4/4.
- The other checks that crashed locally (`ReliabilityProbeTest`, `ManufacturerGuidanceScreenshotTest`) passed on CI.

**Residual risks:**
- The non-Xiaomi steps are owner drafts.
- The OEM components are unverified on a device. Story 5.13 checks the Oppo A96.
- No production caller opens the screen until Story 5.4 adds the checklist.
