---
title: 'Story 4.17: "Problem with a charge?"'
type: 'feature'
created: '2026-10-09'
status: 'review'
baseline_revision: '0c3665c1'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-4-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/epics.md (Story 4.17)'
  - '{project-root}/_bmad-output/planning-artifacts/ux-designs/ux-pay-per-snooze-2026-09-26/EXPERIENCE.md (Problem with a charge? key strings and long-form copy, Links no handler, App screens states)'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-4-16-purchase-history.md (showProblemWithCharge, price_source)'
  - '{project-root}/androidApp/src/debug/kotlin/com/yawnandpawn/app/debug/preview (payments_problem, payments_problem_no_browser)'
deferred:
  - 'Epic 5 ("How payments & refunds work", Privacy, Terms, Support): the You row "How payments & refunds work" and its link to this screen; reuse ExternalLinks/AppLinks and the "No browser found." snackbar.'
  - 'Before launch (owner): replace the placeholder support address in config/app-links.properties.'
---

<intent-contract>

## Intent

**Problem:** a user who doesn't understand a charge has nowhere to go (FR-SET-5). The approved "Problem with a charge?" screen exists only in the design preview, and Purchase history hides its row.

**Approach:**
- **Screen:** the approved preview screen, unchanged: three paragraphs, then a `card-group` with "Open Google Play order history" and "Email support". It is pushed from Purchase history's "Problem with a charge?" row (`Route.ProblemWithCharge`, `showProblemWithCharge = true`). Back returns to Purchase history.
- **Order history:** `ACTION_VIEW` on `https://play.google.com/store/account/orderhistory`. With no app to open it, the snackbar "No browser found." shows for 4 s.
- **Email support:**
  - With records, it opens a picker sub-screen (250 ms slide): "Which charge?" lists the 10 most recent charges (date and time, price, status), with the newest selected. `button-filled` "Email support" sits in its own bottom area.
  - Confirming opens `ACTION_SENDTO mailto:` to `support.email` with subject "Charge question". The body has one line each: "Order ID: …", "Date: …" (ISO 8601 with offset), "Price: …", "Status: …".
  - With no records, or when they can't be read, the button opens the same email straight away with an empty body.
  - With no email app, the snackbar "No email app found. Write to {email}." shows and the picker stays open.
- **Config:** `config/app-links.properties` (`support.email`, `play.orderHistory`) is read by the build into `BuildConfig`, so a change is one edit.
- **Ports:** `ExternalLinks` (composeApp) is implemented by `AndroidExternalLinks` (in `android.screen`, starts on the user's tap only). `AppLinks` comes from BuildConfig. The email text (`supportEmailWriter`) is resolved from string resources in composition and handed to the ViewModel with each intent, so opening the email never waits on a suspend resource read (a Robolectric end-to-end run hung on `getString` from `viewModelScope`).

## Boundaries & Constraints

- **The email holds only what the user can see:** Play's order id (on their Google receipt), the purchase time, the price and the status. `ChargeOption` has no token, token hash (only a picker key, never written), install id, session id, alarm or label. Unit and Robolectric tests assert this.
- **The amount comes only from the intent** (`PriceSource.isAmountPaid`). An unknown amount gets no price line and no picker price.
- **Read-only:** nothing writes `purchase_record`. Failures are logged without the address or the email's text.
- **Preview baselines stay unchanged:** `ProblemWithChargeScreen(onIntent, noBrowser)` keeps its signature and look.

## Decisions (fast mode: default taken, owner can change)

1. **Build to the approved preview:** the two actions stay `settings-row`s in a card, not the epic's `button-filled`/`button-outlined`. So `button-outlined` gets no new screenshots here. The existing `PpsOutlinedButton` is unchanged.
2. **Entry only from Purchase history.** The You tab's Money card gets no extra row (it isn't in the approved You preview). Epic 5's "How payments & refunds work" links here.
3. **The picker is a sub-screen pane** of the same nav entry (progressive disclosure, rule 15), not a dialog. Back and the arrow close it. It closes after the email app opens.
4. **Status line in the email:** Purchase history's own words ("Snooze 2", "Not used, refunded automatically by Google"). There is no line when the status isn't known, and the same goes for the order id and the price.
5. **No email app:** a snackbar naming the support address, so the user can still write.
6. **The intent carries subject and body twice,** in the `mailto:` URI and in the extras, because some email apps read only one. Check this on the phone in 4.18 #14.
7. **Picker rows show date and time:** two snoozes of one morning share a date.
8. **New strings (EXPERIENCE.md Key strings), need owner sign-off:** "Which charge?" and "No email app found. Write to {email}.". Both are added to EXPERIENCE.md, which also gets three new state rows.

## I/O & Edge-Case Matrix

| Input | Result |
|---|---|
| Open order history, a browser or Play present | `ACTION_VIEW` https page, NEW_TASK |
| Open order history, no handler | "No browser found." 4 s, logged |
| Email support, 12 records | Picker of 10 newest, newest selected |
| Pick another, confirm | Email about that charge; picker closes |
| Email support, no records / read fails | Email with subject only, empty body |
| Confirm, no email app | Picker stays, "No email app found. Write to {email}." 4 s |
| Record stranded, no order id, estimate price | Body "Date: …" and "Status: Not used, refunded automatically by Google" only |
| Picked charge disappears | Selection falls back to newest |

</intent-contract>

## pps-design Done checklist

- [x] Only tokens from `DESIGN.md`: existing `TextCard`, `GroupCard`, `NavRow`, `RadioRow`, `PpsFilledButton`, `AppSnackbar`, `PpsTopAppBar`. No raw hex, no new radii or font sizes.
- [x] Light and Dark checked with screenshots (`problem_with_charge_*_light/_dark`), plus Light at 200%. It is not a wake surface, so there is no Sunrise.
- [x] Every colour pair is already in the contrast table: `text` / `text-secondary` on glass, `accent-text` radio on glass, `on-accent` on `accent`, `inverse-text` on `inverse-surface`.
- [x] Targets: rows are 56 dp, the actions and "Email support" are ≥ 48 dp (asserted). There are no wake actions.
- [x] Works at 200% (screenshots; the picker's button stays on screen, asserted) and with TalkBack: the reading order is asserted, each picker row is one radio button with its selected state, and the snackbars are polite live regions. There are no outcome glyphs on this surface.
- [x] Reduced motion: the picker uses the shared sub-screen slide, which is instant at animator scale 0.
- [x] Copy from EXPERIENCE.md verbatim: title, three paragraphs, both actions, "Charge question", the four body lines, "No browser found.", "Snooze {n}" and the stranded status. The two new strings are listed above. All strings are in resources, and `CopyRulesTest` passes.
- [x] State rows: no browser, no email app, and no records or a failed read are handled and added to EXPERIENCE.md.
- [x] Wake rules: not a wake screen.
- [x] Previews: `payments_problem*` are unchanged (same composable). New Roborazzi baselines are `problem_with_charge_*` (with and without charges, picker, no browser, no email app).

## Review (fast mode)

One review pass, nothing HIGH (privacy and money-truth checks clean). Fixed in `fix(4.17): review fixes`, each with a test:

- **M1, the address fallback was a 4 s snackbar that broke mid-word at 200%:** with no email app a glass note now stays (until the pane is left or an email opens): "No email app found. Write to:", the address on its own line with break opportunities only after "@" and "." (TalkBack reads the plain address), and `button-text` "Copy address" (clipboard through `ExternalLinks.copyText`; "Address copied." only before Android 13, which confirms itself). It sits under the actions on the screen and above "Email support" in the picker, never over a row. Tests: screenshots `problem_with_charge_no_email_app_*` (incl. 200%) and the picker ones re-recorded; semantics assert no overlap (main at 200%; picker at 200% on h780dp: `button.top >= note.bottom`, every visible row above the note) and 48 dp "Copy address".
- **M2, an email without details before the first read:** `loaded` flag; "Email support" does nothing until the charges were read. The email's text is now `SupportEmailTemplates` (templates resolved in composition, filled at write time from the `ChargeOption` given), so a charge the screen hasn't listed still gets every line. Tests: a repository that never emits, then a tap, sends nothing; an unlisted charge gets its full body.
- **M3, weak encoding test:** subject and order id with `A&body=x#?+%20` decode exactly, no fragment, query keys exactly `subject, body`.
- **M4, tests claiming more than they check:** the label check runs a labelled "Gym" alarm through the real path (MainActivityTest: history shows "· Gym", the email and its URI never do); the picker layout check is the h780dp one above.
- **Low:** samples, tests and baselines use `support@example.com`; the real address stays only in `config/app-links.properties`.

Strings changed or added for the owner (EXPERIENCE.md updated): "No email app found. Write to:" (was "… Write to {email}."), "Copy address", "Address copied.".

## Verification

- Coordinator gate policy (2026-10-14, PC out of memory): locally, only this story's test classes, `spotlessCheck`, `detekt` and the affected modules' compile (`--no-daemon --max-workers=2`); CI runs the full `qualityGate` on the PR.
- Owner, in 4.18 #14 on the phone: the order history opens in Play, and the email opens pre-filled in Gmail without a duplicated body.
