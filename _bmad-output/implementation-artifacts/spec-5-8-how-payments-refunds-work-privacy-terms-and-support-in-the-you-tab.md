---
title: 'Story 5.8: "How payments & refunds work", privacy, terms and support in the You tab'
type: 'feature'
created: '2026-10-16'
status: 'done'
baseline_revision: 'c03bdee0c739baeee81294fa3888cff6b6bed1cb'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-7-privacy-policy-terms-and-support-pages-on-github-pages.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The You tab shows only "Purchase history" (Story 4.16). The approved rows "How payments & refunds work",
"Privacy policy", "Terms" and "Support" are hidden, so a user cannot read the payment rules or reach the policy pages
(FR-SET-3, FR-SET-5, FR-ONB-5, NFR-5). The approved `PaymentsScreen` (design preview round 2) is not wired. Its cap
sentence is also wrong: `payments_cap_body` says "one morning never costs more than {cap}", but the $50 cap is per
snooze (deferred from Story 5.7).

**Approach:**
- **You tab:**
  - A `YouViewModel` drives the approved `YouScreen` with the rows Purchase history, How payments & refunds work,
    Privacy policy, Support, Email support (new) and Terms.
  - Privacy, Terms and Support open `AppLinks` URLs through `ExternalLinks.openUrl` (`ACTION_VIEW`). When nothing
    handles the link, the snackbar "No browser found." shows for 4 s (Story 4.17's string and timing).
  - "Email support" opens an email to `AppLinks.supportEmail`. When no app handles it, Story 4.17's "No email app
    found. Write to:" note shows the address with "Copy address".
- **Payments screen:** "How payments & refunds work" pushes a new `Route.Payments` showing the approved `PaymentsScreen`.
  Its links push Purchase history and "Problem with a charge?" over it, and Back returns.
- **Cap copy:** `payments_cap_body` becomes "You can snooze up to your max snoozes per session. A single snooze never
  costs more than %1$s.", with the cap `Money(50 × 1_000_000, "USD")` formatted by `formatMoney`. EXPERIENCE.md and
  `payments.md` match it. `checkSitePages` now mirrors the key too: a `%N$s`/`%N$d` placeholder matches any
  non-space text.

## Boundaries & Constraints

**Always:**
- Use the approved `YouScreen`, `PaymentsScreen`, `NavRow`, `GroupCardOf` and `NoEmailAppNote`. Tokens only.
- Use existing strings, except the changed cap string. Key strings are verbatim, and `CopyRulesTest` passes.
- Links open only on the user's tap. The session lock still replaces every route.
- Card titles and the screen title are TalkBack headings. Every target is ≥ 48 dp.

**Never:**
- No "Delete all data" (Story 5.9) and no "About" row (no story asks for one). Both stay hidden.
- No new screen besides the Payments route. No changes to Problem with a charge or Purchase history behaviour.
- No new dependency or permission.

## DEFAULTS TAKEN (owner can change)

1. **Cap copy (owner sign-off):** "You can snooze up to your max snoozes per session. A single snooze never costs
   more than {cap}." This matches the published `payments.md`. The cap shows as the formatted USD $50, because the
   cap is defined in USD tiers. The fee paragraph above it already says Google Play shows local prices.
2. **"Support also offers the support email":** a new "Email support" row (the existing Story 4.17 string) sits under
   "Support" in the Help card. "Support" opens the support page, which also shows the address. The email has no
   subject or body, because no approved copy exists for them. A screen change for owner review.
3. **About and Delete all data stay hidden:** About has no story, and Delete all data is Story 5.9.
4. **Navigation depth:** Payments may push Purchase history or "Problem with a charge?". Purchase history pushed from
   Payments may push "Problem with a charge?". A double tap never stacks the same screen twice.
5. **EXPERIENCE.md:** the "Payments & refunds" surface row's entry point changes from "Settings" to "You", matching
   the approved IA and the sprint change proposal.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected |
|---|---|---|
| You renders | production | Money: Purchase history, How payments & refunds work · Privacy and your data: Privacy policy · Help: Support, Email support, Terms |
| Privacy / Terms / Support | tap, browser present | `openUrl(privacyUrl / termsUrl / supportUrl)`; no snackbar |
| No browser | `openUrl` false | "No browser found." for 4 s, then gone; a second tap restarts it |
| Email support | tap, email app present | `sendEmail(supportEmail, "", "")` |
| No email app | `sendEmail` false | the note with the address and "Copy address"; Copy puts the address on the clipboard |
| Payments | tap the row | the Payments screen pushed; Back returns to You |
| From Payments | "Problem with a charge?" / "Purchase history" | that screen pushed over Payments; Back returns to Payments |
| Double tap | any push twice | one screen |
| Session starts | any of these open | "Alarm in progress" only |
| Cap | USD $50 | "…A single snooze never costs more than $50.00." (en-US) |
| Site mirror | `payments.md` says "$50." | `checkSitePages` passes with `payments_cap_body` mirrored; a page without the sentence fails |

</intent-contract>

## Code Map

- `composeApp/.../ui/you/YouScreen.kt`:
  - Add the `YouRow.EmailSupport` row under Support in the Help card (label `problem_email_support`).
  - Add `YouUiState.noEmailAppAddress` to show `NoEmailAppNote` under the cards.
  - Add `YouIntent.EmailSupportClicked` and `CopyAddressClicked`.
  - Make the card titles headings, if `GroupCardOf` does not already.
- `composeApp/.../ui/you/YouViewModel.kt` (new) and `YouRoute.kt` (new):
  - Links go through `ExternalLinks` and `AppLinks`, the snackbar job follows `ProblemWithChargeViewModel`, and the
    copy confirmation follows its Copy address handling.
  - Navigation callbacks: Purchase history and Payments.
  - Export `BuiltYouRows`.
- `composeApp/.../ui/payments/PaymentsScreens.kt`: unchanged layout. `NoEmailAppNote` lives near
  `ProblemWithChargeMain` and stays internal to the module.
- `composeApp/.../ui/payments/PaymentsRoute.kt` (new): the `PaymentsScreen` with `PRICE_CAP`
  (`Money(FeeRules.PRICE_CAP_TIER × 1_000_000, "USD")`). Back → `onBack`, the links → callbacks.
- `composeApp/.../ui/nav/Route.kt`, `TabNavigation.kt` (`tab`, `openPayments`, the push rules), `AppNavHost.kt` (the
  `YouRoute` and Payments entries; drop the private `YouTab`/`YouTabRows`); `TabNavigationTest.kt`.
- `composeApp/.../ui/UiModule.kt`: the `YouViewModel` binding.
- `composeApp/src/commonMain/composeResources/values/strings.xml`: `payments_cap_body`.
- `build-logic/.../SitePages.kt` (`mirrorViolations` placeholder matching), `SitePagesTest.kt`; root
  `build.gradle.kts` (`payments_cap_body` into `mirroredKeys`, comment); `docs/site/payments.md` (the Liquid comment
  only).
- `_bmad-output/planning-artifacts/ux-designs/.../EXPERIENCE.md`: the cap long-form line, and the Payments & refunds
  surface row ("You").
- `_bmad-output/implementation-artifacts/deferred-work.md`: the cap item → resolved.
- `androidApp/src/debug/.../preview/*`: keep compiling (the `YouIntent` / `YouRow` additions; the preview reducer
  handles the new intents as no-ops or the no-browser toggle).
- Tests:
  - `composeApp/src/commonTest/.../you/YouViewModelTest.kt` (fake `ExternalLinks`).
  - In `androidApp/src/test/.../ui/`: `YouScreenshotTest.kt` (You full list, Payments top and scrolled to the links;
    Light, Dark, Light 200 %), `YouSemanticsTest.kt` (headings, 48 dp), `YouRouteTest.kt` (real app: each link's
    intent via Robolectric `shadowOf(app).nextStartedActivity`, no-browser snackbar, Payments push and Back, the
    links from Payments).

## Tasks & Acceptance

**Execution:**
- [x] Cap copy: the string, EXPERIENCE.md, `payments.md` comment, the mirror check with placeholders (and its test),
  `mirroredKeys`, and deferred-work.
- [x] You: rows, state, intents, ViewModel, route, wiring.
- [x] Payments route and nav rules, with their tests.
- [x] Screenshots, semantics and real-app link tests. Keep the preview catalogue compiling.

**Acceptance Criteria:**
- Given the You tab, when it renders, then Money, Privacy and your data, and Help show the rows in the matrix. Each
  link row starts `ACTION_VIEW` on its `AppLinks` URL, and "Email support" starts an email to the support address
  (Robolectric intent asserts).
- Given Payments, when it renders, then it shows the six payment paragraphs (the new cap sentence), the disclosure
  verbatim, the purchase authentication tip, and links to "Problem with a charge?" and Purchase history.
- Given screenshots and semantics, then You (full list) and Payments exist in Light, Dark and 200 %. Headings are
  headings, and targets are ≥ 48 dp.

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md` used. No new UI code paints anything: You and Payments reuse `NavRow`,
  `GroupCardOf`, `TextCard`, `NoteInline`, `NoEmailAppNote` and `AppSnackbar`; detekt (NoRawColor and the rest) passes.
- [x] Light, Dark (and Sunrise where relevant) checked with previews / screenshots. `YouScreenshotTest` (You, You with
  the no-email-app note, Payments top and scrolled to its links, each Light, Dark and Light 200 %), plus the preview
  catalogue's You and Payments items. No wake screen, so no Sunrise.
- [x] Every colour pair used is in the `DESIGN.md` contrast table. Only existing pairs (text and text-secondary on
  glass, accent-text on the text button, inverse-text on inverse-surface).
- [x] Touch targets ≥ 48 dp; wake actions ≥ 64 dp. `YouSemanticsTest` checks every You row, "Copy address", both
  Payments links and the Back arrow; the preview semantics floor passes for the You and Payments items. No wake actions.
- [x] Works at 200% font scale and with TalkBack; outcome glyphs present. 200 % screenshots wrap with no clipping;
  screen and card titles are headings, rows are buttons, the note and both snackbars are polite live regions. No
  outcomes on these screens.
- [x] Reduced-motion path works. Only the existing 250 ms sub-screen slide of Navigation 3 (instant with reduced
  motion, unchanged); nothing new animates.
- [x] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources). Only existing
  strings plus the changed `payments_cap_body` (EXPERIENCE.md long-form line changed with it); `CopyRulesTest` passes.
- [x] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled. No browser (4 s snackbar), no
  email app (the note with "Copy address"), session active (the lock replaces You and Payments, `YouRouteTest`).
- [x] "I'm up" is the most prominent wake action; snooze is visible, plain and priced. Not applicable: no wake screen.
- [x] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated. New preview
  item `you_no_email_app`; the You, Payments and nav bar preview baselines were re-recorded for the Email support row
  and the cap sentence.

## Spec Change Log

### Implementation notes (2026-10-18)

- **Cap:** `payments_cap_body` is "You can snooze up to your max snoozes per session. A single snooze never costs more
  than %1$s." and `PRICE_CAP` (`ui/payments/PaymentsRoute.kt`) is `Money(FeeRules.PRICE_CAP_TIER * Money.MICROS_PER_UNIT,
  "USD")`, shown as "$50.00" in en-US. The preview catalogue's `priceCap` (was the old B × 15 "morning" figure) now uses
  `PRICE_CAP` too. `SitePagesRules.mirrorViolations` turns each `%N$s`/`%N$d` into `\S+` and quotes the rest; a new
  `SitePagesTest` case covers another amount, a missing sentence, other words around the value, regex characters and
  `%2$d`. `payments_cap_body` is in `mirroredKeys`.
- **You:** `YouRow.EmailSupport` (label `problem_email_support`) sits under Support. `YouUiState` gains
  `noEmailAppAddress` and `addressCopied` (the "Address copied." snackbar, only where the phone gives no confirmation,
  as in Story 4.17). The note is cleared when an email opens and when the user taps Purchase history or Payments; it
  also goes when the You entry leaves the back stack (another tab). `YouViewModel` runs one 4 s snackbar at a time;
  a second tap restarts it.
- **Navigation:** `Route.Payments`. `openPayments` pushes it over a tab; `openPurchaseHistory` now also works over
  Payments, and `openProblemWithCharge` over Payments or Purchase history. To keep detekt's size limits without a
  suppression, the three push rules moved to `ui/nav/YouNavigation.kt` (`topTab` became internal) and the You,
  Purchase history, Payments and "Problem with a charge?" entries to `ui/nav/YouEntries.kt`.
- **NoEmailAppNote** stays where Story 4.17 put it (`SupportChargePicker.kt`, next to `ProblemWithChargeFlow`), internal
  to the module; `YouScreen` uses it as is.
- **Preview catalogue:** the reducer toggles the no-email-app note on "Email support" and ignores "Copy address"; a new
  `you_no_email_app` item uses the reserved `support@example.com`. `docs/design-preview/states.md` lists it
  (`PreviewMenuTest`).
- **EXPERIENCE.md:** only the cap long-form line and the Payments & refunds surface row ("You") changed. The You IA row
  still lists "Help" as Support, Terms, About; it does not name the new Email support row (owner review, DEFAULT 2).

## Review Triage Log

### 2026-10-18 — Review pass (fast mode: one combined self-review; the coordinator runs the independent review)
- verdicts: 4 findings — high 0, medium 0, low 3, false 1, maybe-false 0
- findings:
  - `[low]` `[reject]` The EXPERIENCE.md You IA row still lists Help as Support, Terms and About, not the new "Email support" row. The spec limits this story to two EXPERIENCE.md edits, and the row is an owner-review default (DEFAULT 2). Raised for owner sign-off instead.
  - `[low]` `[reject]` The 4 s snackbar check in `YouRouteTest` advances Robolectric's clock by hand, so it may be timing-sensitive on CI. `YouViewModelTest` covers the same timing with a virtual clock. To watch on CI.
  - `[low]` `[reject]` `NoEmailAppNote` stays in `SupportChargePicker.kt` instead of moving next to `ProblemWithChargeMain`, as the Code Map said. It is internal to the module either way, and moving it would only churn Story 4.17's file.
  - `[false]` A double tap on Payments' links could stack screens. Disproved: `openPurchaseHistory` needs a tab or Payments on top, and `openProblemWithCharge` needs Purchase history or Payments on top, so the second tap is ignored. `TabNavigationTest` covers it.

## Verification

**Commands (targeted, `--no-daemon --max-workers=2`):**
- `spotlessCheck detekt`
- `:build-logic:test --tests '*SitePagesTest'` (or the included build's test task) and `checkSitePages`
- `:composeApp:testAndroidHostTest --tests 'com.yawnandpawn.app.ui.you.*' --tests '*TabNavigationTest' --tests '*UiModuleTest' --tests '*CopyRulesTest'`
- `:androidApp:testDebugUnitTest` for the new You tests (Roborazzi record, then verify), plus `MainActivityTest`,
  `PurchaseHistory*`, `ProblemWithCharge*` and any test that renders the You tab or the preview catalogue's You and
  Payments items.

**Results (2026-10-18), all with `--no-daemon --max-workers=2`, all passed:**
- `spotlessCheck`, `detekt` (all modules) and `checkSitePages`.
- `:build-logic:test --tests '*SitePagesTest'` (15 tests).
- `:composeApp:testAndroidHostTest`: `YouViewModelTest` (8), `TabNavigationTest` (14), `SessionLockTest` (5),
  `UiModuleTest` (4), `CopyRulesTest` (14).
- `:androidApp:recordRoborazziDebug` for `YouScreenshotTest`, `NavBarScreenshotTest` and the preview cases `you_*`,
  `payments_light`, `payments_dark` and `payments_light_font200`; then `:androidApp:testDebugUnitTest` verifying
  `YouScreenshotTest` (12), `YouSemanticsTest` (6), `YouRouteTest` (8), `MainActivityTest` (20), `PurchaseHistory*`,
  `ProblemWithCharge*`, `AndroidExternalLinksTest`, `NavBarScreenshotTest` (4), `Round2TapThroughTest`,
  `TapThroughTest`, `PreviewMenuTest` (8) and the preview screenshot and semantics cases for `you_*` and `payments*`.
- The full `qualityGate` was not run (left to CI).

## Review (fast mode)

### 2026-10-19: independent review, 8 findings, all fixed

1. **[medium] Push rules checked only the top type, not the caller.** A tap on two different rows during the 250 ms
   slide gave `[You, Payments, PurchaseHistory]`.
   - Fix: `openPurchaseHistory`, `openPayments` and `openProblemWithCharge` (`ui/nav/YouNavigation.kt`) take
     `from: NavKey` and push only when `lastOrNull() == from` (and `from` is an allowed source). The You, Payments and
     Purchase history entries (`YouEntries.kt`) pass their own route. The double-tap and session-lock guarantees stay.
   - Test: `TabNavigationTest` updated to the new calls, plus "a second, different row tapped on the screen below
     during the slide pushes nothing" (You: Payments then Purchase history, and the reverse; Payments: Purchase history
     then "Problem with a charge?"), and pushes `from` the lock are ignored.
2. **[medium] "A single snooze never costs more than $50.00." is not true locally** (an EU price includes VAT, e.g.
   €59.99).
   - Fix: a new `PaymentsViewModel` (`ui/payments`) reads the `PriceCatalog` port (`observe()` +
     `displayablePriceFor(snooze_usd_50, clock.now())`) and exposes Play's own formatted price as `cap`, or null when
     not cached or expired. A read failure is logged (`load cap price`) and counts as unknown. Bound in `UiModule`, used
     by `PaymentsRoute` (`koinViewModel`). `PaymentsScreen` takes `cap: String?`: with a price, `payments_cap_body`
     with it; without, `payments_cap_tier_body` with `formatMoney(PRICE_CAP)` ("$50.00") followed by
     `payments_local_price_body`. EXPERIENCE.md's long-form list gives both variants; `docs/site/payments.md` states the
     tier wording and its Liquid comment explains it; root `build.gradle.kts` mirrors the tier strings, not
     `payments_cap_body`.
   - Tests: `PaymentsViewModelTest` (4: a cached "€59.99" is the cap, stale too; none / other tiers only / expired is
     null; a later price replaces null; an unreadable cache is logged and null). `YouSemanticsTest`: "€59.99" reads
     "…never costs more than €59.99."; no price reads the tier sentence then "Google Play sets the local price,
     including any tax.". `YouRouteTest` (fresh install, no cached price) shows the tier sentence. `UiModuleTest`
     builds the ViewModel.
3. **[low] `%1$s` matched any text, so the page's cap number was unchecked.**
   - Fix: `mirroredKeys` (build-logic `SitePages`) is now a `MapProperty<String, List<String>>`: key → the values its
     placeholders show on the page, in `%N$` order, configured in root `build.gradle.kts`
     (`payments_cap_tier_body` → `["$50.00"]`, the rest `[]`). The mirror check fills the placeholders and looks for the
     exact sentence. A placeholder with no value, or a value with no placeholder, is a violation naming the key.
   - Test: `SitePagesTest` (16, all pass): "$5." and another currency fail naming `payments_cap_body`; a new case for a
     missing value, an extra value and a value on a string without placeholders; `%2$d` and regex characters still
     work; the TestKit fixture uses the map.
4. **[low] The no-email-app note was never scrolled into view.**
   - Fix: `YouScreen` puts a `BringIntoViewRequester` on the note and calls it in `LaunchedEffect(address)` once the
     note is measured, with a rect that extends below it by the nav capsule clearance (`LocalNavBarClearance` +
     `space6`, the column's own bottom padding), so "Copy address" is never left under the capsule.
   - Test: `YouRouteTest` at `w360dp-h640dp`, 200 % font: after "Email support" with no email app, "Copy address" is
     displayed (no scroll by the test) and ends above the nav bar's tabs. Baseline `you_no_email_app_light_font200`
     re-recorded (the screen now scrolls to the note).
5. **[low] A double tap on a link opened two browser tasks; `copyText` claimed success without a clipboard.**
   - Fix: `YouViewModel` takes the `MonotonicClock` port and ignores a repeat of the link (or the support email) that
     opened less than `REPEAT_MILLIS` (1 s) ago; a link that failed to open never counts. `AndroidExternalLinks.copyText`
     returns false (and logs "copy address" / "no clipboard") when the clipboard service is null, so neither You nor
     "Problem with a charge?" shows "Address copied." (both show it only on true). `ExternalLinks.copyText` doc says so.
   - Tests: `YouViewModelTest` on the virtual clock (double tap on a link and on Email support opens once, the same link
     again at 1 s, another link at once; a failed open is not a double tap; a failed copy shows no confirmation).
     `AndroidExternalLinksTest`: no clipboard (SDK 32) returns false and logs no text. The existing
     `ProblemWithChargeViewModelTest` covers false → no confirmation.
6. **[low] Weak intent asserts.** `YouRouteTest` now asserts the email `dataString` starts with
   `mailto:${BuildConfig.SUPPORT_EMAIL}`, and `CATEGORY_BROWSABLE` and `FLAG_ACTIVITY_NEW_TASK` on each VIEW intent
   (the adapter already set both).
7. **[low] Note lifetime across tabs.** New `YouRouteTest` case: show the note, go to Settings, back to You, the note
   is gone.
8. **[low] Flaky timing margins.** `YouRouteTest` keeps only "No browser found. appears, then is gone 5 s later"; the
   exact 4 s and the restart stay in `YouViewModelTest` (virtual clock).

**Defaults taken (owner can change):**
- Item 2 copy, per the coordinator's default: with a cached Play price, "…A single snooze never costs more than
  {Play price}."; without, "…never costs more than the $50.00 price tier." then "Google Play sets the local price,
  including any tax.". The tier text was given as one 30-word string; it is split into `payments_cap_tier_body` (21
  words) and `payments_local_price_body` (9 words) because `CopyRulesTest` and EXPERIENCE.md limit a body to 25 words.
  The words are unchanged. The site states the tier wording.
- Item 2: the preview catalogue shows the "price known" variant with "$50.00" (a US Play price), so its Payments
  baselines are unchanged; `YouScreenshotTest` shows the tier variant (baselines re-recorded).
- Item 5: the repeat window is 1 s and covers the three links and Email support; only the link that opened last counts.

**For the owner:** in the US and Canada (`config/tax-exclusive-countries.txt`) Play prices exclude tax, so "never
costs more than $50.00" with Play's US price leaves sales tax out. `docs/site/terms.md` still says "A single snooze
never costs more than $50." (USD tiers); neither was in this review's scope.

**Verification (2026-10-19, all `--no-daemon --max-workers=2`, all passed):**
- `spotlessApply`, `spotlessCheck`, `detekt`, `checkSitePages` (11 mirrored strings), `:build-logic:test --tests
  'com.yawnandpawn.app.buildlogic.SitePagesTest'` (16).
- `:composeApp:testAndroidHostTest`: `ui.you.*` (`YouViewModelTest` 11), `ui.payments.*` (`PaymentsViewModelTest` 4,
  `ProblemWithChargeViewModelTest` 16), `ui.nav.*` (`TabNavigationTest` 15, `SessionLockTest` 5), `UiModuleTest` (4),
  `CopyRulesTest` (14).
- `:androidApp:recordRoborazziDebug -Proborazzi.test.verify=false` for `YouScreenshotTest.Payments*` and "You with no
  email app at 200 percent": re-recorded `payments_top_light`, `payments_top_dark`, `payments_top_light_font200`,
  `payments_links_light`, `payments_links_dark` (the tier paragraphs) and `you_no_email_app_light_font200` (scrolled to
  the note); `payments_links_light_font200` came out unchanged.
- `:androidApp:testDebugUnitTest` verify: `YouScreenshotTest` (12), `YouSemanticsTest` (7), `YouRouteTest` (10),
  `AndroidExternalLinksTest` (8), `MainActivityTest` (20), `ProblemWithChargeScreenshotTest` (15),
  `ProblemWithChargeSemanticsTest` (5), `PurchaseHistoryScreenshotTest` (15), `PurchaseHistorySemanticsTest` (7),
  `MoneyLayoutFontScaleTest` (3), `NavBarScreenshotTest` (4), `Round2TapThroughTest` (6), `TapThroughTest` (5),
  `PreviewMenuTest` (8).
- Not run locally: `PreviewScreenshotTest` / `PreviewSemanticsTest` (Gradle's `--tests` cannot pick single cases of the
  parameterized runner, and the whole class does not fit the 10-minute foreground limit). Their You and Payments cases
  should not change: the preview Payments text is the same ("$50.00" through `payments_cap_body`), and the preview
  no-email-app note is fully on screen at 411 x 891 dp, so the scroll is a no-op. Left to CI.

## Auto Run Result

Status: done (fast mode). The coordinator runs the independent review and CI.

- **Built:**
  - The You tab runs on `YouViewModel` with the rows Purchase history, How payments & refunds work, Privacy policy,
    Support, Email support and Terms.
    - The links open `AppLinks` URLs, with "No browser found." for 4 s when nothing handles them.
    - Email support writes to the support address, with the no-email-app note and "Copy address" when no app takes it.
  - `Route.Payments` shows the approved `PaymentsScreen`, and its links push Problem with a charge? and Purchase
    history.
  - The cap sentence is fixed: "A single snooze never costs more than {cap}". It is in sync with EXPERIENCE.md and
    `payments.md`, and `checkSitePages` now mirrors it, with placeholders matched.
- **Files:**
  - `ui/you/` (screen, ViewModel, route), `ui/payments/PaymentsRoute.kt`.
  - `ui/nav/` (`Route`, `TabNavigation`, `YouNavigation`, `YouEntries`, `AppNavHost`), `UiModule`, `strings.xml`.
  - `SitePages.kt` and its test, `build.gradle.kts`, `docs/site/payments.md`, EXPERIENCE.md, `deferred-work.md`.
  - The preview catalogue, `docs/design-preview/states.md`, tests and baselines.
- **Review:** 4 findings, 0 patches, 3 rejected (reasons in the triage log), 1 false.
- **Follow-up review:** false. Nothing medium or high was patched.
- **Verification:** see `## Verification`. All targeted runs passed. The full `qualityGate` was not run.
- **Residual risks:** the timing of the `YouRouteTest` snackbar check on CI, and the owner's sign-off on the cap copy
  and the "Email support" row.
