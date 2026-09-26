---
title: 'Story 1.3: Generated design tokens and PpsTheme'
type: 'feature'
created: '2026-09-26'
status: 'done'
baseline_commit: 'df11c2b2d0f6803d35ecca519c0c05f876334a6c'
route: 'dispatch'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-1-2-ci-pipeline-and-dependency-and-permission-allowlists.md'
  - '{project-root}/.claude/skills/pps-design/SKILL.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** There is no theme yet: the placeholder screen uses stock Material defaults, and nothing stops later stories from hand-typing colours, sizes or off-voice copy.

**Approach:** Implement Story 1.3 in `epics.md`: generate `PpsTokens.kt` from the DESIGN.md frontmatter (with a `checkTokens` diff check in `qualityGate`), build `PpsTheme` (Light/Dark/Sunrise, Geist, Material Symbols Rounded), add detekt rules against raw colours/radii/sp, a contrast test against DESIGN.md's verified table, `CopyRulesTest`, and a debug-only theme showcase with screenshots. `App()` switches from stock `MaterialTheme` to `PpsTheme`.

## Boundaries & Constraints

**Always:**
- Generated file path is `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/theme/PpsTokens.kt` (package `com.yawnandpawn.app.ui.theme`). The story's `com/payper/snooze/...` path predates the package decision of 2026-09-26; names `PpsTokens`/`PpsTheme` stay.
- Token groups generated: all `colors` (Light no suffix, `-dark`, `-sunrise`, `sunrise-gradient-top`), the 8 `typography` styles with exact size/line-height/weight, `rounded` (`sm` 8, `md` 16, `lg` 28 dp, `full`), and every dp `spacing` token (non-dp values such as `thumb-zone` are skipped). `components` is out of scope. Colours must be `#RRGGBB` or generation fails.
- `generateTokens` writes the committed file; `checkTokens` regenerates into `build/` and fails on any diff; `checkTokens` is a `qualityGate` dependency.
- `PpsTheme(mode = System | Light | Dark, wake = false)`: one `MaterialTheme`; dynamic colour never used; `System` follows `isSystemInDarkTheme()`; `wake = true` always Sunrise. Token access for non-Material roles (e.g. `accent-text`, `snoozed`, spacing, targets) through a `PpsTheme` accessor object backed by CompositionLocals.
- Geist bundled from its official OFL release with the licence file committed; system sans fallback. Tabular figures via `fontFeatureSettings = "tnum"` for `clock-xl`, `display`, `title`, `button-wake`; if Geist lacks `tnum`, `clock-xl` uses Geist Mono. Finding recorded in `docs/decisions/geist-tnum.md`. `clock-xl` font scale capped at 1.3×.
- Icons only from Material Symbols Rounded (weight 400, fill 0; fill 1 variants for selected/outcome use) as vector resources in `:composeApp`; this story adds the four outcome-marker glyphs (check circle filled, schedule, cancel, radio button unchecked).
- detekt rules (in `:detekt-rules`, active for all modules, exempt inside package `...ui.theme`): `Color(0x…)`, `RoundedCornerShape(<number>.dp)`, `<number>.sp`; messages point to `PpsTheme`; one violating + one compliant snippet test each.
- Contrast test: recomputes WCAG ratio for every row of DESIGN.md "Verified contrast" from the generated tokens, matches the recorded ratio ±0.02; text rows ≥ 4.5, graphic rows ≥ 3.0; rows marked "fails" must stay below 3.0 (documented forbidden pairs) and "Info" rows are ratio-checked only. No `bg`, `bg-dark`, `bg-sunrise` token is `#000000`/`#FFFFFF` ("background token" = the `bg*` tokens; white `surface` is allowed by DESIGN.md).
- `CopyRulesTest` over every Compose Multiplatform string resource, exactly the story's rules (em dash, banned words any case, `$ € £ ¥`, "backup" adjacent to "check", emoji except key `success_zero_snooze`, `_headline`/`_title` ≤ 8 words, `_body` ≤ 25 words), each rule proven by a failing fixture.
- Theme showcase is debug-only (lives in `androidApp/src/debug`, never in release), renders every colour role, type style and shape; Roborazzi screenshots in Light, Dark, Sunrise and Light at 200% font scale, using the shared `screenshotOptions`. Existing `empty_screen.png` is re-recorded because `App()` now uses `PpsTheme`.
- pps-design Done checklist copied into Implementation Notes with each item ticked or marked N/A with reason.

**Never:**
- No hand-edits to `PpsTokens.kt`; no new tokens beyond DESIGN.md; no change to DESIGN.md values.
- No new runtime network dependency (fonts and icons are bundled files); any new runtime artifact goes through `config/dependency-allowlist.txt`.
- No screens beyond the showcase; no navigation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Generate | DESIGN.md frontmatter | `PpsTokens.kt` with every listed token | N/A |
| Bad colour | fixture colour `#FFF` or `orange` | generator fails naming the key | message names key + value |
| Token drift | DESIGN.md changed, file not regenerated | `checkTokens` fails | tells to run `generateTokens` |
| Wake theme | `PpsTheme(wake = true)` in system dark | Sunrise colours | N/A |
| System mode | `PpsTheme(mode = System)` light/dark | Light / Dark set | N/A |
| Raw colour | `Color(0xFF112233)` outside `ui.theme` | detekt fails pointing to PpsTheme | rule id reported |
| Raw radius / sp | `RoundedCornerShape(12.dp)` / `14.sp` outside theme | detekt fails | rule id reported |
| Inside theme | same code in `ui.theme` | no finding | N/A |
| Contrast drift | token changed so a row's ratio moves > 0.02 | contrast test fails naming the row | N/A |
| Copy violation | string with em dash, `$`, "Seamless", "backup check", emoji, 9-word `_headline`, 26-word `_body` | `CopyRulesTest` fails naming key and rule | N/A |

</frozen-after-approval>

## Code Map

- `_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/DESIGN.md` -- frontmatter lines 1–~380 (colors, typography, rounded, spacing, components); "Verified contrast" table at ~line 415 (`| Theme | Pair | Kind | Ratio |`, pair labels may carry a parenthetical, some ratios are bold "**x, fails: …**").
- `EXPERIENCE.md` Voice and Tone (~line 71) -- source of future strings; only `app_name` exists today.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/App.kt` -- switch to `PpsTheme`.
- `composeApp/build.gradle.kts`, `composeApp/src/commonMain/composeResources/` -- fonts/drawables go under `composeResources/font` and `composeResources/drawable`.
- `config/detekt-rules/` -- existing `NoPrintlnInCore` + `YawnAndPawnRuleSetProvider` pattern; `config/detekt/detekt.yml` for activation/excludes.
- `build.gradle.kts` -- `qualityGate` list + "later checks" comment (add `checkTokens`).
- `build-logic/` -- `CoreDependencyRules` flags unknown Gradle subprojects, so the generator must not be a new subproject: put it in `tools/tokens` as an included build (like `build-logic`) exposing a plugin/tasks, or in `build-logic` with sources referencing `tools/tokens`; YAML parsing library is a build-time dependency only.
- `androidApp/src/test/.../ScreenshotOptions.kt` + `MainActivityTest.kt` -- shared 0.1% threshold; screenshots in `androidApp/src/test/screenshots/`.
- `config/dependency-allowlist.txt` -- update if Compose resources/fonts add runtime artifacts.

## Tasks & Acceptance

**Execution:**
- [x] `tools/tokens/` -- generator (frontmatter parse → Kotlin source), `generateTokens` + `checkTokens` tasks, fixture tests (each group parsed; bad colour rejected).
- [x] `composeApp/.../ui/theme/PpsTokens.kt` -- generated and committed.
- [x] `composeApp/.../ui/theme/` -- `PpsTheme`, colour-scheme mapping, typography with Geist + tnum, shapes, spacing accessors, font-scale cap for `clock-xl`; host tests for mode/wake selection.
- [x] `composeApp/src/commonMain/composeResources/font/`, `drawable/`, `docs/licenses/geist-OFL.txt`, `docs/decisions/geist-tnum.md` -- bundled fonts, four Material Symbols Rounded vectors, licence, tnum decision.
- [x] `config/detekt-rules/` + `detekt.yml` -- three raw-value rules with tests; theme package exempt.
- [x] `composeApp/src/commonTest/.../ContrastTest.kt` (or host test) -- reads the DESIGN.md table, recomputes from `PpsTokens`.
- [x] `composeApp/src/commonTest/.../CopyRulesTest.kt` -- scans all `composeResources/values*/strings.xml`; rule logic unit-tested with fixtures.
- [x] `composeApp/.../ui/App.kt` -- use `PpsTheme`; re-record `empty_screen.png`.
- [x] `androidApp/src/debug/` -- `ThemeShowcase` composable (+ optional debug activity); `androidApp/src/test/.../ThemeShowcaseScreenshotTest.kt` with four baselines.
- [x] `build.gradle.kts` -- `checkTokens` in `qualityGate`, comment updated.

**Acceptance Criteria:**
- Given the branch, when `./gradlew qualityGate` runs, then it passes including `checkTokens`, the new detekt rules, contrast test, `CopyRulesTest` and the four showcase screenshots.
- Given a release build, when inspected, then no showcase code or activity is included.

## Implementation Notes

- `./gradlew qualityGate` passes locally (BUILD SUCCESSFUL, about 6 min). It now runs `checkTokens`, `:tokens:test` (included build), the three new detekt rules on every module, `ContrastTest`, `CopyRulesTest`, `GeistFontTest`, `PpsThemeTest`, `PpsThemeSelectionTest` and the four showcase screenshots. `generateTokens` followed by a hash compare of `PpsTokens.kt` shows no diff (the generated file is Spotless/ktlint-clean as written). `checkDependencyAllowlist` passes unchanged: fonts and icons are Compose resources, no new runtime artifact.
- **Generator:** `tools/tokens` is an included build (`rootProject.name = "tokens"`, plugin `yawnandpawn.design-tokens`, applied in the root build file with a `designTokens {}` block). Pure `DesignTokenParser` (SnakeYAML 2.6, build-time only, catalog entry `snakeyaml`) and `TokenSourceWriter`; the tasks only read and write files. Input is `_bmad-output/.../DESIGN.md` (`docs/DESIGN.md` is an identical copy and is not read). `checkTokens` writes `build/tokens/PpsTokens.kt` and compares line by line (CRLF ignored), naming the first differing line and telling to run `generateTokens`. The committed file is an `@InputFiles` input, so a hand edit re-runs the check. Tests: 11 parser (each group; `#FFF`, `orange` and alpha colours rejected naming key and value; bad sp/px), 6 writer, 5 TestKit (generate + check passes, DESIGN.md drift, hand edit, missing file, bad colour). Manual probe: one hex hand-edited in `PpsTokens.kt` failed `checkTokens` naming line 27 (reverted).
- **PpsTokens.kt:** `PpsTokens.Light/Dark/Sunrise` (suffix dropped, `sunrise-gradient-top` in Sunrise), `colorsByName` (DESIGN.md key to colour, used by the contrast test), `Type` (`PpsTypeToken` size, line height, weight), `Rounded` (`CornerSize`; `9999px` = 50%), `Spacing` (dp only; `thumb-zone` skipped). The generated header carries `@file:Suppress("MagicNumber")`.
- **Theme** (`composeApp/.../ui/theme/`): `PpsTheme(mode = System | Light | Dark, wake = false)` with a pure `resolveColorSet`; one `MaterialTheme`, no dynamic colour. `object PpsTheme` exposes `colors`, `colorSet`, `typography`, `shapes`, `spacing` through static CompositionLocals. `PpsColors.toColorScheme()` fills all 48 Material 3 roles from tokens (choices for undefined roles documented in KDoc; a test asserts every role is a DESIGN.md token). DESIGN.md has no Sunrise `snoozed`/`missed`/`inverse-accent`: those three roles reuse the Light tokens (documented on `SunrisePpsColors`). Each M3 typography slot reuses one ramp style; shapes map extraSmall/small = sm, medium = md, large/extraLarge = lg.
- **clock-xl cap:** uses the density's own sp-to-dp conversion (binary search), not `size * 1.3 / fontScale`, so it only ever shrinks. Finding (corrected after review): Compose applies its own non-linear font scaling on every API level, not only Android 14+. `ClockCapTest` (androidApp testDebug, `@Config(sdk = [33], fontScale = 2f)`) shows the system density renders body 16 sp at 28 dp and clock-xl 88 sp at about 89 dp, already below the 1.3x cap (114.4 dp), so on real devices up to 2.0x the cap does not change the clock. The naive linear formula would have shrunk it to about 62 dp. The same test composes `PpsTheme` under a linear `LocalDensity` at 2.0x and asserts clock-xl renders at 114.4 dp (body 32 dp), proving the wiring. The `commonTest` cap tests only cover the helper (linear density; plus a 'never above 1.3x nor above uncapped' check for whatever conversion the density uses). The 200% screenshot therefore shows the uncapped (non-linear) clock.
- **Fonts/icons:** Geist v1.7.2 static TTFs at 300/400/500/600 (`font/geist_*.ttf`), OFL in `docs/licenses/geist-OFL.txt`. Geist has `tnum` (`docs/decisions/geist-tnum.md`, enforced by `GeistFontTest`), so no Geist Mono. Four Material Symbols Rounded vectors (weight 400, from fonts.gstatic.com): `symbol_check_circle_fill1`, `symbol_schedule_fill1`, `symbol_cancel_fill1`, `symbol_radio_button_unchecked` (fill 0). Each XML header names the glyph, variant and Apache 2.0 licence.
- **detekt:** `NoRawColor`, `NoRawCornerRadius` (any `<number>.dp` argument, named ones too), `NoRawSp` (also `13.5f.sp`, `(-2).sp`) in `:detekt-rules`, active in `detekt.yml`, exempt when the package ends in `ui.theme` (or is below one). 3 tests each (violating, compliant, inside theme). Manual probe: `Color(0xFF000000)`, `RoundedCornerShape(12.dp)` and `14.sp` added to `App.kt` failed `:composeApp:detekt` with `[NoRawColor]`, `[NoRawCornerRadius]`, `[NoRawSp]` (reverted). `detekt.yml` now treats `testDebug`, `testRelease` and `*Test` source sets as tests for `FunctionNaming`, `MagicNumber` and `TooManyFunctions`, because detekt's defaults do not know `androidHostTest`/`testDebug`.
- **Contrast test:** parses all 80 rows (count asserted against the raw table lines), resolves `name` / `name-dark` / `name-sunrise` (`sunrise-*` unchanged), checks the WCAG ratio within 0.02, text >= 4.5, graphic >= 3.0, "fails" rows < 3.0, info rows ratio only, and that `bg`/`bg-dark`/`bg-sunrise` are not pure black or white. Fixture tests prove that drift, weak text, weak graphic, a documented failure that now passes and an unknown token each fail naming the row. All 80 recorded ratios matched the tokens.
- **CopyRulesTest:** scans every `composeResources/values*/*.xml` (`string`, `plurals` and `string-array` items). `%1$s` placeholders are not treated as currency. "backup" next to "check" is caught in either order (also "back-up"). Emoji = code points in U+1F000..1FAFF, 2300..23FF, 2600..27BF, 2B00..2BFF, FE0F. One unit test per rule, plus a fixture `strings.xml` whose expected violation list names each key and rule.
- **Deviation (location):** `ContrastTest`, `CopyRulesTest`, `GeistFontTest` and helpers live in `composeApp/src/androidHostTest` (not `commonTest`) because they read files with `java.io`. Paths come from system properties set in `composeApp/build.gradle.kts` (declared as test inputs; the DESIGN.md path is repeated there from the root `designTokens` block). `PpsThemeTest` (pure) is in `commonTest`.
- **Deviation (location):** the showcase screenshot test and `PpsThemeSelectionTest` live in `androidApp/src/testDebug` (not `src/test`) because `ThemeShowcase` exists only in the debug variant, so `src/test` would not compile for `testReleaseUnitTest`. They reuse `screenshotOptions` from `src/test`. The debug `ThemeShowcaseActivity` (exported, debug manifest only, `adb shell am start -n com.yawnandpawn.app/.debug.ThemeShowcaseActivity --es mode Dark`) hosts the showcase. Tests launch it with intent extras and `createEmptyComposeRule()`, because the compose test rule refuses `setContent` on an activity that already has content.
- **Release check:** `:androidApp:bundleRelease` succeeds. No `ThemeShowcase*` class exists in any release intermediate, and the release AAB manifest has no showcase activity (the debug build has them under `com.yawnandpawn.app.debug`).
- **Screenshots:** `empty_screen.png` re-recorded (Geist on Light `bg`). New baselines `theme_showcase_{light,dark,sunrise,light_font200}.png` at `w411dp-h1500dp-mdpi` (font200: `+h2400dp`, `fontScale = 2.0`), recorded on Windows. Not yet verified on Linux CI: if CI fails only on anti-aliasing noise above the 0.1% threshold, re-record these four from the CI artifact (see Design Notes).
- `App()` takes an optional `themeMode` (default System); `AppPreview.kt` has Light and Dark previews.

- **Review fixes (pass 1):**
  - `PpsTheme` CompositionLocals have no defaults any more (`error("PpsTheme not provided ...")`); all readers (App, showcase, tests) are inside `PpsTheme`.
  - detekt: exemption is only `com.yawnandpawn.app.ui.theme` and sub-packages (look-alike `...feature.ui.theme` is reported, tested). `NoRawColor` also reports `Color(...)` with only literal arguments (`Color(255, 0, 0)`, `Color(0.2f, 0.1f, 0.1f)`) and `Color.Red/Black/White/...` (Transparent and Unspecified allowed; imports ignored). `NoRawCornerRadius` covers `RoundedCornerShape`, `AbsoluteRoundedCornerShape`, `CutCornerShape`, `AbsoluteCutCornerShape` and `CornerSize` with any literal or `<number>.dp/.px` argument (percent included). `NoRawSp` also reports `<number>.em` and `TextUnit(<literal>, ...)`. One violating and one compliant test per form. `DetektConfigTest` loads `config/detekt/detekt.yml` and asserts `yawn-and-pawn.active` and `<rule>.active` are true for every rule the provider registers.
  - Contrast: parsing fails on a table row with an unknown theme; the row-count check counts only the Verified contrast section; documented "fails" rows must stay below their own kind's limit (text 4.5, graphic 3.0). New test: the Sunrise fallbacks `snoozed`/`missed` pass 4.5 on `bg-sunrise` and `surface-sunrise`, and `inverse-accent` passes 4.5 on `inverse-surface-sunrise` (its only use, snackbar action).
  - `PpsThemeTest` checks each Material scheme against its own set's tokens (Sunrise plus the three documented Light fallbacks).
  - Copy rules: `success_zero_snooze` may hold at most one emoji; U+203C, U+2049 and U+200D count as emoji (FE0F/200D are not counted as extra emoji in the allowed key); "back up check" and "check back up" are caught; banned words match only the words and inflections (elevate/elevates/elevated/elevating, seamless(ly), unleash(es/ed/ing), supercharge(s/d/r/ing)), so "elevator"/"elevation" pass; `
`/`	` escapes separate words; `androidApp/src/main/res/values*/strings*.xml` is scanned too (system property `yawnandpawn.androidRes`).
  - Generator: duplicate YAML keys fail (`LoaderOptions.isAllowDuplicateKeys = false`); a UTF-8 BOM is stripped; spacing values in px, sp or bare numbers fail, only non-numeric prose (`thumb-zone`) is skipped. Tested.

### pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used (no raw hex, no new radii, no new font sizes). Enforced by the new detekt rules outside `ui.theme`. The showcase uses `1.dp` only as a swatch border width.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots. Showcase screenshots in all three sets; `App` previews for Light and Dark.
- [x] Every colour pair used is in the `DESIGN.md` contrast table. `App` uses text/bg; the showcase uses text, text-secondary and outline on bg. The Material mapping only pairs roles whose pairing is in the table (accent-text/surface, text/surface-variant, error/surface, ...). Swatches themselves are data display.
- N/A Touch targets ≥ 48 dp; wake actions ≥ 64 dp. This story adds no interactive controls; the targets are exposed as `PpsTheme.spacing.targetMin/targetWake/targetWakeHero`.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present. The 200% screenshot reflows without clipping, the `clock-xl` cap is implemented, and the four outcome glyphs are bundled (with content descriptions in the showcase). TalkBack order is N/A: no product screens yet.
- N/A Reduced-motion path works. No animation in this story.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources). `CopyRulesTest` passes; the only user string is still `app_name`. Showcase labels are DESIGN.md token names in a debug-only tool, not user copy.
- N/A Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled. No product surface in this story.
- N/A "I'm up" is the most prominent wake action; snooze is visible, plain and priced. No wake screen yet.
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated (Roborazzi). `App` Light/Dark previews; the showcase is covered by four Roborazzi baselines; `empty_screen.png` re-recorded.

- Final gate fixes (orchestrator): split `NoRawColor.visitDotQualifiedExpression` and `CopyRules.violations` to satisfy detekt ComplexCondition / CyclomaticComplexMethod after the review fixes; behaviour unchanged.

## Spec Change Log

## Review Triage Log

Pass 1 (blind-hunter; edge-case-hunter and verification-gap re-run after a rate-limit interruption):

| # | Finding | Verdict | Evidence | Route |
|---|---|---|---|---|
| 1 | clock-xl cap never exercised through `PpsTheme` under linear scaling; "non-linear" test uses linear Density | medium | Robolectric SDK 34 is non-linear (cap no-op); commonTest passes even with the naive formula | patch |
| 2 | detekt.yml activation of the rules untested | medium | Rule tests use `Config.empty`; validation excludes `yawn-and-pawn.*` | patch |
| 3 | Theme-package exemption matches any `*.ui.theme` package | medium | `isInThemePackage()` substring match | patch |
| 4 | Raw-value rules bypassable (`Color(255,0,0)`, `Color.Red`, `RoundedCornerShape(12)`, `CornerSize`, `CutCornerShape`, `em`, `TextUnit`) | medium | Rules match only `0x` literal / `.dp` arg / `.sp` | patch |
| 5 | Sunrise Light fallbacks (snoozed/missed/inverse-accent) never contrast-checked | medium | Table has no Sunrise rows for them; showcase tints markers with them on `bg-sunrise` | patch |
| 6 | Documented failing rows judged with graphic limit regardless of kind; row scan not section-scoped; unknown theme rows skipped | low | `ContrastTable` logic | patch |
| 7 | "Every role is a token" checks union of sets | low | A Dark role mapped to a Light token would pass | patch |
| 8 | Copy rules: unlimited emoji in `success_zero_snooze`, "back up check" spaced, "elevator" false positive, `\n` joins words, androidApp strings unscanned | medium | UX-DR82 "at most one"; regexes as cited; scan root is composeResources only | patch |
| 9 | YAML duplicate keys, BOM, silently dropped `48px` spacing | low | SnakeYAML default keeps last key; `startsWith("---")`; non-dp values skipped | patch |
| 10 | CompositionLocals silently default to Light outside `PpsTheme` | low | `staticCompositionLocalOf { LightPpsColors }` | patch |
| 11 | Disabled token pair never reaches Material components | medium | M3 `ColorScheme` has no disabled roles; stock controls use onSurface alpha. Belongs to the stories that build the wake/disabled components with explicit token colours | defer |
| 12 | No automated check that release excludes the showcase | medium (unverified regression path) | Needs APK/manifest inspection tooling; a regression requires deliberately moving files out of `src/debug` | defer |
| 13 | Showcase baselines recorded on Windows unverified on Linux | maybe-false | Settled by this PR's CI run before merge (spec Design Notes plan) | reject (handled in merge gate) |
| 14 | Emoji ranges flag plain dingbats (✓ ★) | low | No such copy exists; icons are vector resources | reject |
| 15 | Token names colliding with Kotlin keywords / empty names / unwrapped render exception / EOF message / `relativeTo` on another drive | low | Unrealistic for DESIGN.md keys; fixes add guards | reject |
| 16 | Showcase intent extra case-sensitive; GeistFontTest robustness on non-TTF files | low | Debug tooling; fonts dir is controlled | reject |

## Design Notes

- Colour-scheme mapping to Material 3 roles (suggested): `background`←`bg`, `surface`←`surface`, `surfaceVariant`←`surface-variant`, `onBackground`/`onSurface`←`text`, `onSurfaceVariant`←`text-secondary`, `primary`←`accent`, `onPrimary`←`on-accent`, `outline`←`outline`, `outlineVariant`←`outline-subtle`, `error`←`error`, `inverseSurface`/`inverseOnSurface`/`inversePrimary`←inverse tokens. Roles DESIGN.md doesn't define keep a documented token-derived choice, never a raw hex.
- Screenshot risk: the showcase is text-heavy, so Linux vs Windows anti-aliasing may exceed the 0.1% threshold. If CI fails only on rendering noise, prefer recording the showcase baselines from the CI (Linux) artifact and keeping the local Windows run passing within the threshold; record what was done.
- Environment (company PC): JDK 17 at `C:/Users/BuiTua/AppData/Local/Programs/jdk17/jdk-17.0.20.1+1` (export `JAVA_HOME` before `./gradlew`).

## Verification

**Commands:**
- `./gradlew generateTokens` then `git diff --exit-code composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/theme/PpsTokens.kt` -- expected: no diff.
- `./gradlew qualityGate` -- expected: BUILD SUCCESSFUL.
- `./gradlew :androidApp:bundleRelease` then inspect that no `ThemeShowcase` class is in the release output -- expected: absent.
- Temporarily change one colour in a copy of the frontmatter fixture / add `Color(0xFF000000)` in `ui/App.kt` -- expected: `checkTokens` / detekt fail (revert).
