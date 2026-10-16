---
title: 'Story 5.8: "How payments & refunds work", privacy, terms and support in the You tab'
type: 'feature'
created: '2026-10-16'
status: 'ready-for-dev'
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
- [ ] Cap copy: the string, EXPERIENCE.md, `payments.md` comment, the mirror check with placeholders (and its test),
  `mirroredKeys`, and deferred-work.
- [ ] You: rows, state, intents, ViewModel, route, wiring.
- [ ] Payments route and nav rules, with their tests.
- [ ] Screenshots, semantics and real-app link tests. Keep the preview catalogue compiling.

**Acceptance Criteria:**
- Given the You tab, when it renders, then Money, Privacy and your data, and Help show the rows in the matrix. Each
  link row starts `ACTION_VIEW` on its `AppLinks` URL, and "Email support" starts an email to the support address
  (Robolectric intent asserts).
- Given Payments, when it renders, then it shows the six payment paragraphs (the new cap sentence), the disclosure
  verbatim, the purchase authentication tip, and links to "Problem with a charge?" and Purchase history.
- Given screenshots and semantics, then You (full list) and Payments exist in Light, Dark and 200 %. Headings are
  headings, and targets are ≥ 48 dp.

## pps-design Done checklist

- [ ] Only tokens from `DESIGN.md` used.
- [ ] Light, Dark (and Sunrise where relevant) checked with previews / screenshots.
- [ ] Every colour pair used is in the `DESIGN.md` contrast table.
- [ ] Touch targets ≥ 48 dp; wake actions ≥ 64 dp.
- [ ] Works at 200% font scale and with TalkBack; outcome glyphs present.
- [ ] Reduced-motion path works.
- [ ] Copy matches `EXPERIENCE.md > Voice and Tone` (no em dashes, no filler, strings in resources).
- [ ] Every state row in `EXPERIENCE.md > State Patterns` for this surface is handled.
- [ ] "I'm up" is the most prominent wake action; snooze is visible, plain and priced.
- [ ] Compose `@Preview`s for each state (light/dark/Sunrise, empty/error) exist; screenshot tests updated.

## Spec Change Log

## Review Triage Log

## Verification

**Commands (targeted, `--no-daemon --max-workers=2`):**
- `spotlessCheck detekt`
- `:build-logic:test --tests '*SitePagesTest'` (or the included build's test task) and `checkSitePages`
- `:composeApp:testAndroidHostTest --tests 'com.yawnandpawn.app.ui.you.*' --tests '*TabNavigationTest' --tests '*UiModuleTest' --tests '*CopyRulesTest'`
- `:androidApp:testDebugUnitTest` for the new You tests (Roborazzi record, then verify), plus `MainActivityTest`,
  `PurchaseHistory*`, `ProblemWithCharge*` and any test that renders the You tab or the preview catalogue's You and
  Payments items.
