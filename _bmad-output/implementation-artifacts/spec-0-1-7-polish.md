---
title: '0.1.7 polish: Home entrance, name field, rings line, Settings wording, About, dialogs, printed QR, battery Fix'
type: 'feature'
created: '2026-10-30'
status: 'done'
baseline_commit: '13eb25796b2b2a1bcb155f5b4dc409842d5b9f42'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Owner feedback on the 0.1.6 closed test (approved on a mockup 2026-10-30): Home cards pop in with no motion, the editor's name field does not look like a field, the "Rings tomorrow at ..." line appears only sometimes and pushes the layout, two Settings labels are long or unclear, there is no About page, the turn-off and delete dialogs are wordy with a 28 sp title, the printed QR is small (6 cm), and the Battery "Fix" opens the list of all apps.

**Approach:** UI-only polish in one PR (`feat/0-1-7-polish`), reusing existing components, motion and link plumbing. Owner decisions 2026-10-30 (all approved, no further sign-off): the eight items below.

1. Home list cards use Progress' staggered fade-and-rise entrance (instant with animator scale 0).
2. Editor "Alarm name" is a Material outlined field: label inside when empty and unfocused, on the top border when focused or filled; focused border `accent`, focused label `accent-text`.
3. The time card always shows "Rings today at {time}." / "Rings tomorrow at {time}." / "Rings on {weekday} at {time}.", so the layout never jumps (editor and onboarding step 4 share the card).
4. Settings "Share anonymous usage stats" -> "Share usage stats"; "Reliability checklist" -> "Make sure alarms ring" (row and screen title, docs/site, store listing draft, closed-test docs). Onboarding's own "Share anonymous usage stats?" step and "Make sure it rings" headline stay.
5. New About page (pushed over Settings from a new "About" row after "Make sure alarms ring", same card): app icon, "Yawn & Pawn", "Version {versionName}", rows Website (yawnandpawn.com), Contact support (support@yawnandpawn.com, opens an email), Privacy policy, Terms; back arrow like other sub-screens.
6. Turn-off dialog: title "Turn off the {time} alarm?", body "It rings in {n} hours." / "It rings in 1 hour." / "It rings in {n} minutes." / "It rings in 1 minute.", buttons "Turn off" (destructive) / "Keep it on". Delete: "Delete the {time} alarm?" with "Delete" / "Keep it". "This is logged." is dropped from both (logging unchanged). The shared `ConfirmDialog` title becomes `title` (20 sp) and its body a new 15 sp type token in `text-secondary`, for every app dialog.
7. Printed QR: the code (without quiet zone) is 14 cm square, centred, caption under it.
8. Battery "Fix": Google Play allows REQUEST_IGNORE_BATTERY_OPTIMIZATIONS only when Doze breaks the app's core function, and alarm clocks are not a listed case (`setAlarmClock` alarms fire in Doze), so it is not clearly allowed: Fix opens this app's App info page (ACTION_APPLICATION_DETAILS_SETTINGS, package uri), always. No new permission.

## Boundaries & Constraints

**Always:** tokens only (a new type token goes into DESIGN.md frontmatter first and `PpsTokens.kt` is regenerated with `generateTokens`); strings in resources, no em dashes; every new colour pair already in the contrast table or added; reduced motion = final state at once; 48 dp targets; TalkBack labels; update Roborazzi baselines of changed screens; keep the app's version name/code untouched.

**Never:** no new permission, no change to commitment-lock logic, no change to the skip dialog wording or wake-screen sheets, no quiet-time or snooze-length changes (that is PR B).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Rings today | one-time 7:00, now 6:00 | "Rings today at 7:00 AM." | N/A |
| Rings tomorrow | one-time 7:00, now 8:00 (or exactly 7:00) | "Rings tomorrow at 7:00 AM." | N/A |
| Rings weekday | repeat Mon only, now Fri | "Rings on Monday at 7:00 AM." | N/A |
| Turn off, hours | 7 h left | "It rings in 7 hours." | N/A |
| Turn off, one hour | 1 h left | "It rings in 1 hour." | N/A |
| Turn off, minutes | 25 min / 1 min left | "It rings in 25 minutes." / "It rings in 1 minute." | N/A |
| Contact support, no email app | no handler | the existing "No email app found. Write to:" note with "Copy address" | no crash |
| Link, no browser | no handler | "No browser found." snackbar | no crash |
| Battery Fix | any exemption state | App info of this app; if it does not resolve, system settings | logged when nothing opens |

</frozen-after-approval>

## Code Map

All UI paths are under `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/`; strings in `composeApp/src/commonMain/composeResources/values/strings.xml`.

- `progress/ProgressMotion.kt` -- `rememberEntered()`, `entranceProgress()`, `Modifier.entrance(entered, order)`, `ENTRANCE_MILLIS`, stagger 70 ms, rise 16 dp. Move these (unchanged) to `components/Motion.kt` (or a new `components/Entrance.kt`) and update imports in `progress/ProgressScreen.kt`, `progress/ProgressRing.kt`, `daydetail/DayDetailScreen.kt`; keep `DayChip` in progress.
- `home/HomeScreen.kt` `HomeList` -- LazyColumn items: hero (`HERO_KEY`, already has `Modifier.collapsingHero`), "notices", "next", then `items(state.alarms)` with `Modifier.animateItem()`. Add `val entered = rememberEntered()` and `.entrance(entered, order)` per item (hero 0, notices 1, next 2, alarm i -> 3 + i, capped so far items do not wait long). Items composed after the first frame already start at 1 (animateFloatAsState initial = target), so only the opening screenful animates.
- `components/PpsTextField.kt` -- unused Material `OutlinedTextField` wrapper (`text-field`). Make its container transparent (field sits on a glass card), focused border `accent`, focused label `accentText`, unfocused label/border `textSecondary`/`outline`, error as now; label style `body` when resting is Material's own animation (pass `label = { Text(label) }` without forcing a style, or `label` style; it must float). Use it for the editor name row in `editor/AlarmEditorScreen.kt` `NameSoundCard` (replace `TextFieldRow`, keep `errorText` = "Keep the label under 40 characters."), with card padding around it. Remove `TextFieldRow` from `components/Rows.kt` if nothing else uses it.
- `editor/AlarmEditorContract.kt` `EditorUiState.ringsTomorrowAt: LocalTime?` -> replace with an always-set value (e.g. `ringsAt: RingsAt?` = days ahead, day of week, time); `editor/AlarmEditorViewModel.kt` `withRings()` (line ~1117) computes it from `nextRing(...)`. `editor/EditorCards.kt` `TimeWheelCard` shows the `NoteInline` always (reserve the line when null with an invisible note, cleared from semantics). Onboarding passes it through: `onboarding/OnboardingContract.kt`, `OnboardingSteps.kt:141`, `OnboardingViewModel.kt:761`. Strings: keep `editor_rings_tomorrow`, add `editor_rings_today` "Rings today at %1$s.", `editor_rings_on_day` "Rings on %1$s at %2$s." (weekday via `format.dayName(day, DayNameStyle.Full)`).
- `settings/SettingsScreen.kt` `GeneralCards` -- usage stats switch and the reliability row; add `SettingsRow.About` + `SettingsIntent.AboutClicked` + `SettingsEffect.OpenAbout` (`settings/SettingsContract.kt`, `SettingsViewModel.kt`), `SettingsRoute(onOpenAbout)`, `BuiltSettingsRows` includes About. Strings: `settings_usage_stats` -> "Share usage stats", `settings_reliability` -> "Make sure alarms ring" (also the title in `reliability/ReliabilityScreen.kt:170`), new `settings_about` "About" (or reuse `you_about`).
- `nav/Route.kt`, `nav/ReliabilityNavigation.kt` (pattern `openReliability(from)`), `nav/PushedEntries.kt`, `nav/AppNavHost.kt` `SettingsTab` -- add `Route.About` pushed over Settings only when Settings is on top; Back closes it.
- New `about/` package: `AboutScreen` (`SubScreen` with back arrow like `reliability/ReliabilityScreen.kt` `Checklist`; centred icon 84 dp clipped `shapes.md`, "Yawn & Pawn" in `title`, "Version {v}" `caption`/`textSecondary` via existing `you_version`; one `GroupCard` of `NavRow`s with values "yawnandpawn.com" and the address, trailing `open_in_new` icon instead of the chevron: add `symbol_open_in_new.xml` (Material Symbols Rounded, like the other `symbol_*.xml`) and a trailing-icon parameter on `components/Rows.kt` `NavRow`), `AboutViewModel` (links like `you/YouViewModel.kt`: `ExternalLinks.openUrl`, `sendEmail(supportEmail, "", "")`, no-email note `payments/NoEmailAppNote` + `copyText`, "No browser found." snackbar `settings_no_browser`, double-tap guard), `AboutRoute`. Register the view model in `UiModule.kt`; fix `composeApp/src/commonTest/.../UiModuleTest.kt`.
- Version and website: add `AppLinks.websiteUrl` (`links/AppLinks` data class) from a new `index.url=https://yawnandpawn.com/` in `config/app-links.properties` (checkSitePages maps `index.url` to `docs/site/index.md`, permalink `/`, so it stays green), `BuildConfig.WEBSITE_URL` in `androidApp/build.gradle.kts` (next to `PRIVACY_URL`), wired in `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt:372`. Version: a small `AppInfo(versionName)` bound in `YawnAndPawnApp` from `BuildConfig.VERSION_NAME`. Fix every `AppLinks(` call in tests (`ProblemWithChargeViewModelTest`, `UiModuleTest`, `YouDeleteAllDataTest`, `YouViewModelTest`).
- Icon: new compose resource `composeResources/drawable/app_icon.xml`, one vector merging `androidApp/src/main/res/drawable/ic_launcher_background.xml` and `ic_launcher_foreground.xml` (aapt gradients), cropped to the adaptive icon's visible 72/108 centre (viewport about 341 units, translated by about -85). It is artwork, not UI tokens; contentDescription null (the name follows).
- `components/ConfirmDialog.kt` -- title `PpsTheme.typography.title`, body the new token, body colour `textSecondary`. DESIGN.md frontmatter (both `_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md`, the generator's input, and the `docs/DESIGN.md` copy): add `typography.dialog-body` 15sp/22sp/400; run `./gradlew generateTokens`; add the field to `theme/PpsTypography.kt` and `composeApp/src/commonTest/.../theme/PpsThemeTest.kt` (no tnum).
- `home/HomeScreen.kt` `DisableDialog` and `home/DeleteAlarmConfirm.kt` -- new strings: `disable_title` "Turn off the %1$s alarm?", `disable_rings_in_hours` "It rings in %1$d hours.", `disable_rings_in_hour_one` "It rings in 1 hour.", `disable_rings_in_minutes` "It rings in %1$d minutes.", `disable_rings_in_minute_one` "It rings in 1 minute." (repo uses `_one` variants, no `<plurals>`), `delete_alarm_title` "Delete the %1$s alarm?". Remove the old `disable_under_lock_*`. Fix KDoc mentions of the old copy.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/qr/QrPageLayout.kt` -- `SIDE_CM` 6 -> 14; `LINE_GAP` must exceed the largest quiet zone (version 1: 4/21 of 14 cm ~ 76 pt), e.g. 88 pt; caption size up to about 18 pt; update KDoc and `AndroidQrPrinter.kt` comments ("6 cm"), `androidApp/src/test/.../qr/PrintableQrTest.kt` (14 cm; line still on the page).
- `androidApp/src/main/kotlin/com/yawnandpawn/app/android/reliability/AndroidReliabilitySettings.kt` -- Battery always `appDetails()`; drop the `batteryExempt` seam, `platformBatteryExempt`, `READ_BATTERY`; the manifest `<queries>` entry for IGNORE_BATTERY_OPTIMIZATION_SETTINGS may go; update `androidApp/src/test/.../reliability/AndroidReliabilitySettingsTest.kt`. `OemIntents.kt` (manufacturer row) unchanged.
- Docs: `docs/site/support.md:28`, `docs/site/terms.md:50`, `docs/closed-test/store-listing-draft.md:57`, `docs/closed-test/feedback-form.md:98`; EXPERIENCE.md in both copies (Settings rows, Key strings for usage stats, checklist name, dialogs, About in IA, rings line, `text-field`), DESIGN.md `dialog-confirm` and `text-field` and `printable-qr` rows; PRD/epics only where they name these strings.
- Tests with old copy: `androidApp/src/test/.../MainActivityTest.kt` (lines ~323, 454-460, 496-589), `ui/AlarmScreensSemanticsTest.kt`, `ui/DefaultsSettingsSemanticsTest.kt`, `ui/EditorLockScreenshotTest.kt`, `ui/EditorLockSemanticsTest.kt`, `ui/OnboardingFlowTest.kt:310`, `ui/ReliabilityRouteTest.kt`, `ui/ReliabilityScreenshotTest.kt`, `ui/UsageStatsSettingsTest.kt`, `androidApp/src/testDebug/.../Round2TapThroughTest.kt`, `composeApp/src/commonTest/.../editor/AlarmEditorViewModelTest.kt` and `onboarding/OnboardingSetupStepsTest.kt` (ringsTomorrowAt), samples in `androidApp/src/test/.../ui/EditorSamples.kt`, `OnboardingSamples.kt`, debug previews `androidApp/src/debug/.../preview/PreviewSamples.kt`, `PreviewRound2.kt`, `composeApp/src/androidMain/.../editor/AlarmEditorPreview.kt`.

## Tasks & Acceptance

**Execution:**
- [ ] Motion move + Home entrance (files above).
- [ ] Name field (`PpsTextField`, editor).
- [ ] Rings line (contract, view model, `TimeWheelCard`, onboarding) + view-model tests for today / tomorrow / weekday.
- [ ] Settings wording + About row + route + About screen/view model/icon/links/version + `AboutViewModelTest` (website, privacy, terms open; email; no email app note; no browser snackbar; double tap ignored) + a semantics test that Settings > About opens it and shows the version.
- [ ] Dialog token + `ConfirmDialog` + disable/delete strings + tests for the four turn-off variants.
- [ ] Printed QR 14 cm + test.
- [ ] Battery Fix + test.
- [ ] Docs.
- [ ] Roborazzi: add About screenshots (light, dark, light font200) and re-record every changed baseline under `androidApp/src/test/screenshots` (`./gradlew :androidApp:recordRoborazziDebug -Proborazzi.test.verify=false --tests ...` per test class); review the new images.

**Acceptance Criteria:**
- Given Home with alarms, when it opens with animations on, then cards fade and rise in order; with animator scale 0 they show at once.
- Given the About page, when Website / Privacy policy / Terms / Contact support is tapped, then the configured URL opens / an email to support@yawnandpawn.com opens.
- Given any app dialog, then its title renders at 20 sp and its body at 15 sp in `text-secondary`.
- `./gradlew qualityGate` passes (including checkSitePages, checkTokens, detekt, ktlint/spotless, lint, unit and screenshot tests).

## Implementation Notes

## Spec Change Log

## Review Triage Log

| # | Layer | Finding | Verdict | Evidence / route |
|---|---|---|---|---|
| 1 | blind | Battery Fix to App info cannot clear the row on Android 8 to 11 (no optimisation switch there; row needs the exemption) | medium | Before API 31 the exemption is only in the optimisation list. Patch: API 31+ App info; below 31 keep the old exempt-aware choice (list while not exempt, App info once exempt) and its manifest query. |
| 2 | blind | Battery KDoc cites the wrong policy reason | low | The removed intent needs no permission; reason is UX. Patch the comment with item 1. |
| 3 | blind | Plural rules faked with `_one` strings | low | Repo convention (weekly summary `_one`), English only. Rejected. |
| 4 | blind+edge | "Rings on {weekday}" reads as today when the next ring is 7+ days away | medium | `RingsAt(7, WEDNESDAY)` on a Wednesday renders "Rings on Wednesday". Patch: "Rings next {weekday} at {time}." for daysAhead >= 7. |
| 5 | blind+edge | Placeholder height may differ from a wrapped weekday line at 200% | low | Placeholder shows only before the first state; rare. Rejected. |
| 6 | verif | No UI test of today/tomorrow/weekday lines | medium (gap) | Patch: semantics test for the three lines, next-week and null placeholder silence. |
| 7 | blind | No screenshot baselines in the diff | false | PNGs were excluded from the review diff; 130 changed and about_*.png added in the worktree. |
| 8 | blind+edge | 14 cm QR does not fit landscape A4, A5 or small paper | medium | Caption baseline off a landscape A4 page. Patch: shrink the side to fit the page (code + quiet zone + caption), 14 cm max; test A5 and landscape A4. |
| 9 | blind | Hidden You "About" row with empty version | low | Pre-existing hidden row; not reachable. Rejected. |
| 10 | blind | AboutViewModel duplicates You link handling | low | Drift risk only; fix is a refactor. Rejected. |
| 11 | blind | Dropping "This is logged." changes product meaning | false | Owner decision 2026-10-30 in the intent. |
| 12 | blind | Usage stats consent worded two ways | false | Owner decision: Settings label shortened, onboarding question kept. |
| 13 | blind | Two near-identical checklist names | false | Owner decision (onboarding headline kept). |
| 14 | blind | Contact support email is blank | low | Same as the You tab's Email support; rejected. |
| 15 | blind | Home entrance never runs on a real start | false | HomeList composes only once alarms are loaded (noList branch before), so its first frame has the cards. |
| 16 | blind | About lacks accessibility test and DESIGN entry | low | Rows are NavRows (48 dp, tested component); rejected. |
| 17 | blind | app_icon.xml can drift from the launcher layers | low | Rejected. |
| 18 | edge+verif | Label error lost its polite live region and exact error semantics | medium | `InlineError` was a polite live region; PpsTextField supporting text is plain. Patch: live region on the supporting text and `error(errorText)` on the field; tighten the test. |
| 19 | edge | Line goes stale when the editor stays open past the ring time | low | Same as the existing header countdown; rare. Rejected. |
| 20 | verif | Home entrance has no test | low (gap) | Visual polish; graphicsLayer alpha hard to assert under Robolectric. Deferred. |
| 21 | verif | Dialog styles checked only by screenshots | low (gap) | Baselines cover it. Rejected. |
| 22 | verif | Skip dialog keeps the old one-sentence shape | false | Skip wording out of scope by the intent. |
| 23 | verif | ProgressScreen repeats `rememberEntered() \|\| enteredBefore` | low | Direct deduplication. Patch: use `rememberEnteredOnce()`. |

## Design Notes

The About page reads the version from `BuildConfig.VERSION_NAME` (the owner passes it at build time; do not change versionName/versionCode). Contrast: focused field border `accent` on glass (3.24 Light, 8.21 Dark) and label `accent-text` on glass (5.21 Light, 8.21 Dark) are already in the table; dialog body `text-secondary` on `surface` (7.03 / 6.88) too. No new colour pair.

## Verification

**Environment (company PC, no admin):** worktree `C:/Users/BuiTua/Personal/yp-017`, branch `feat/0-1-7-polish`; `JAVA_HOME=C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1`; RAM is tight: run ONE Gradle build at a time, targeted tasks, `./gradlew --stop` when done; no adb, no phone; never edit `C:/Users/BuiTua/Personal/yawn-and-pawn`; do not commit, push or open a PR (the caller does).

**Commands:**
- `./gradlew :composeApp:testAndroidHostTest :androidApp:testDebugUnitTest --tests <changed classes>` -- pass, one Gradle build at a time on this PC
- `./gradlew qualityGate` -- pass (CI runs it too)
