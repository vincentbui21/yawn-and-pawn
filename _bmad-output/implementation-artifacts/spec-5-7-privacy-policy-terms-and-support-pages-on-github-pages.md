---
title: 'Story 5.7: Privacy policy, terms and support pages on GitHub Pages'
type: 'feature'
created: '2026-10-15'
status: 'review'
baseline_revision: '1c5573f48ea7c50c9f74cbc08c7103230d7772a9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/docs/prd.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** Play Console needs a privacy policy URL before the closed test (5.14), the You tab (5.8) needs privacy, terms and support links, and nothing is published yet. The pages must say exactly what the app does with data and money, and stay correct as the app changes.

**Approach:**
- **Pages:** `docs/site/` holds `index.md`, `privacy.md`, `terms.md`, `support.md` and `payments.md` in plain Markdown with a minimal Jekyll `_config.yml`. Each page sets a `permalink` (`/privacy/`, `/terms/`, `/support/`, `/payments/`), so the URLs never depend on Jekyll's `.html` default.
- **Publishing:** `.github/workflows/pages.yml` uses the official Pages actions (`configure-pages`, `jekyll-build-pages` with `source: ./docs/site`, `upload-pages-artifact`, `deploy-pages`). It deploys on pushes to `main` that touch `docs/site/**`, `config/app-links.properties` or the workflow, and on `workflow_dispatch`. Its first job runs `./gradlew checkSitePages`, and on pull requests that touch the same paths it runs only that check. This covers the CI gap, because `ci.yml` ignores `docs/**` and `**/*.md`.
- **Check:** `checkSitePages`, a new build-logic plugin `yawnandpawn.site-pages` on the root project, is added to `qualityGate`. Its rules are pure (`SitePagesRules`) and unit-tested.
- **Links:** `config/app-links.properties` gains `privacy.url`, `terms.url` and `support.url`, next to the existing `support.email`. The build turns them into `BuildConfig` fields, and the app gets them in `AppLinks`.

## Boundaries & Constraints

**Always:**
- The legal text is a plain-English **draft for the owner to review; not legal advice**. The owner reviews it and confirms the live URLs (human-verify). Automation never marks this story done.
- Only `docs/site/` is published. Spike notes, decisions and other files under `docs/` stay unpublished.
- The pages follow the copy rules: no em dash, no banned hype words, and never "backup check". The currency rule is not applied, because the terms state USD tiers.
- The pages describe what the app actually does, or what is specified for stories not built yet (5.6 analytics, 5.9 "Delete all data", Epic 7 photos and recordings). Every such statement is listed for the owner review.
- The only email address published is the support placeholder already in `config/app-links.properties`.

**Never:**
- Change the GitHub repository settings or enable Pages from automation. That is an owner action.
- Use the phone or adb.
- Change the in-app copy or EXPERIENCE.md. The cap wording issue is deferred to 5.8 for owner approval.
- Build any UI. The You tab rows belong to Story 5.8.

## AC deviations and decisions (fast mode: DEFAULT TAKEN (owner can change))

1. **Key names:** the story names the keys `privacyUrl`, `termsUrl`, `supportUrl` and `supportEmail`. The existing file uses dotted keys (`support.email`), so the new keys are `privacy.url`, `terms.url` and `support.url`, and `support.email` stays. In code they are `AppLinks.privacyUrl`, `termsUrl`, `supportUrl` and `supportEmail`.
2. **The cap wording:** the in-app `payments_cap_body` says "one morning never costs more than {cap}", but the $50 cap is per snooze, and a morning can cost up to $150. `payments.md` says "A single snooze never costs more than $50." instead of mirroring the wrong sentence. Fixing the app string and the EXPERIENCE.md key string is a deferred item for Story 5.8, after the owner approves it.
3. **The analytics events:** with consent on, Story 5.6 leaves Firebase's automatic events (`first_open`, `session_start`, `app_remove`) on. So `privacy.md` names the three app events plus these automatic open, session and uninstall events. It does not say "only three events".
4. **Google's diagnostics:** Play Billing, Crashlytics and ML Kit can send diagnostic logs to Google. Each section where this applies says so.
5. **Effective date:** 15 October 2026. The owner updates it when the pages go live.
6. **Who runs the app:** the pages say "an independent developer". They give no name and no postal address (the EU DSA trader details are an Epic 8 launch blocker).
7. **Q17:** the visible placeholder in `terms.md` says the EU/UK right-of-withdrawal wording is still being reviewed. The `TODO` sits inside `<!-- Q17-PLACEHOLDER-START -->` and `<!-- Q17-PLACEHOLDER-END -->`, the only place `checkSitePages` allows it.
8. **Theme:** the default GitHub Pages theme (Primer), with no custom layout. These are text pages, not app screens, so no design review comes first.
9. **The Google backup copy:** one sentence tells the user how to remove the copy kept in their Google account.
10. **Mirroring:** `checkSitePages` also fails if `payments.md` is missing an in-app sentence. It checks each mirrored key from `strings.xml` (everything on the Payments screen except `payments_cap_body`), word for word after normalising whitespace. This makes "mirrors the in-app text" testable.
11. **The link check:** `checkSitePages` fails unless each `*.url` equals the `_config.yml` `url` + `baseurl` + the page's `permalink`, and unless `support.md` and `privacy.md` contain `support.email`.
12. **Deferred items:** `deferred-work.md` assigns none to 5.7. One is added (item 2, for 5.8).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| All good | 5 pages, config, links, mirrored strings | `checkSitePages` passes | none |
| Page missing | no `support.md` (or no `_config.yml`) | fails naming the file | GradleException listing every problem |
| Heading missing | `privacy.md` lacks `## Backup` | fails naming the heading | same |
| TODO | "TODO" outside the Q17 markers, a marker without its pair, or markers outside `terms.md` | fails | same |
| Copy rule | an em dash, "seamless", or "backup check" | fails naming page and rule | same |
| Link drift | `privacy.url` ≠ url+baseurl+permalink, or a page with no permalink | fails | same |
| Email drift | `support.email` not in `support.md` or `privacy.md` | fails | same |
| Mirror drift | an in-app sentence not in `payments.md` | fails naming the key | same |

</intent-contract>

## Code Map

- `build-logic/src/main/kotlin/com/yawnandpawn/app/buildlogic/WordList.kt` -- the pattern: a pure rules object, a task and a plugin with an extension. `SoundLoudnessPlugin.kt` shows a root-only plugin.
- `build-logic/build.gradle.kts` -- `gradlePlugin { plugins { register(...) } }`: register `sitePages`.
- `build.gradle.kts` (root) -- `plugins { id("yawnandpawn.word-list") }`, the `wordList { }` block and the `qualityGate` `dependsOn` list: add `checkSitePages` the same way.
- `composeApp/src/androidHostTest/kotlin/com/yawnandpawn/app/ui/CopyRules.kt` -- the em-dash, banned-word and backup-check regexes to copy. Gradle can't reuse them, because they're test code.
- `composeApp/src/commonMain/composeResources/values/strings.xml:394-404` -- the `payments_*`, `disclosure_*` and `purchase_auth_tip` texts. `PaymentsScreen` (`ui/payments/PaymentsScreens.kt:46`) shows their order.
- `config/app-links.properties` -- the existing `support.email` and `play.orderHistory`.
- `androidApp/build.gradle.kts:38-53, 84-85` -- `appLink(key)` turns each key into a `BuildConfig` field and fails on a missing key.
- `composeApp/src/commonMain/kotlin/com/yawnandpawn/app/ui/links/ExternalLinks.kt` -- `AppLinks(supportEmail, orderHistoryUrl)`.
- `androidApp/src/main/kotlin/com/yawnandpawn/app/YawnAndPawnApp.kt:248` -- the `AppLinks` Koin binding.
- `androidApp/src/test/kotlin/com/yawnandpawn/app/android/screen/AndroidExternalLinksTest.kt:116` -- the test that `BuildConfig` matches the properties file.
- `composeApp/src/commonTest/kotlin/com/yawnandpawn/app/ui/payments/ProblemWithChargeViewModelTest.kt:49` -- the only other place that constructs `AppLinks`.
- `androidApp/src/main/res/xml/data_extraction_rules.xml` -- the backup facts for `privacy.md`: `app.db` and the settings DataStore are included. Excluded: `runtime.db` (the active session, purchase intents and the grant ledger), `install_id`, `price_cache`, per-device prefs and all credential-protected storage (media).
- `.github/workflows/ci.yml` -- its `paths-ignore` skips `docs/**`. The action versions to match: `checkout@v7`, `setup-java@v6`, `setup-gradle@v6`.

## Tasks & Acceptance

**Execution:**
- `docs/site/_config.yml`, `index.md`, `privacy.md`, `terms.md`, `support.md`, `payments.md` -- write the pages: the content the epic ACs list, plus decisions 2 to 9.
- `build-logic/.../SitePages.kt` -- add `SitePagesRules`, `CheckSitePagesTask` and `SitePagesPlugin` (root only), with the extension inputs `siteDir`, `appLinks` and `appStrings`, a `mirroredKeys` list and a marker output.
- `build-logic/build.gradle.kts` -- register `yawnandpawn.site-pages`.
- `build-logic/src/test/.../SitePagesTest.kt` -- one test per row of the I/O matrix, plus a TestKit run on a fixture project.
- `build.gradle.kts` -- apply the plugin, configure `sitePages { }` and add `checkSitePages` to `qualityGate`.
- `config/app-links.properties` -- add `privacy.url`, `terms.url` and `support.url` under the base `https://vincentbui21.github.io/yawn-and-pawn/`.
- `androidApp/build.gradle.kts`, `ExternalLinks.kt`, `YawnAndPawnApp.kt`, `AndroidExternalLinksTest.kt`, `ProblemWithChargeViewModelTest.kt` -- add `BuildConfig` `PRIVACY_URL`, `TERMS_URL` and `SUPPORT_URL`, extend `AppLinks`, bind it, and assert that the values equal the file.
- `.github/workflows/pages.yml` -- add the check, build and deploy jobs. The deploy job gets `pages: write` and `id-token: write`, the environment `github-pages`, and concurrency that never cancels a deploy.
- `README.md` -- add one row pointing at `docs/site/`.
- `_bmad-output/implementation-artifacts/deferred-work.md` -- add the cap-wording item, assigned to Story 5.8.

**Acceptance Criteria:**
- Given `docs/site/`, when the pages are read, then `privacy.md` has the headings Data on your phone, Backup, Crash reports, Usage stats, Payments, Camera and microphone, Deleting your data and Contact, and covers every point in the epic AC list. `terms.md` covers B × N, the $50 cap, max snoozes, the commitment lock, Google handling payments and refunds, pending and unused payments, no ringing guarantee and the marked Q17 placeholder. `support.md` has the email plus refunds, pending, unused and reliability answers. `payments.md` mirrors the in-app text.
- Given `./gradlew checkSitePages`, when any row of the matrix is broken, then it fails with the reason. With the committed files, it passes.
- Given the app build, when `AndroidExternalLinksTest` runs, then `BuildConfig.PRIVACY_URL`, `TERMS_URL`, `SUPPORT_URL` and `SUPPORT_EMAIL` equal the properties file.
- Given a push to `main` that touches `docs/site/**`, when `pages.yml` runs and Pages is enabled, then the check passes before the site deploys.

## Owner actions

1. **Enable GitHub Pages:** open https://github.com/vincentbui21/yawn-and-pawn, then go to **Settings** › **Pages** (left sidebar, under "Code and automation"). Under **Build and deployment** › **Source**, choose **GitHub Actions**. There is nothing to save beyond that choice. Then go to **Actions** › **Pages** › **Run workflow** on `main`, or wait for the next push to `docs/site/`. Until Pages is enabled, the `configure-pages` step fails on `main`. The site appears at https://vincentbui21.github.io/yawn-and-pawn/.
2. **Review the text:** read all five pages against the app (draft for the owner to review; not legal advice). Check the statements about features that aren't built yet: 5.6 analytics, 5.9 "Delete all data" and Epic 7 photos and recordings. Check the effective date, the support address (a personal Gmail address, published) and Q17. Record the live URLs and the date under "Owner verification" below.
   The statements written to the specified behaviour of stories not built yet (re-check each against the app):
   - **5.6 usage stats** (`privacy.md` "Usage stats"): off until yes, asked once in setup and changeable in Settings; the three app events plus Firebase's first-open, session-start and app-removed events; device facts and an approximate country from the network; no advertising ID, ad storage off; test alarms and pre-consent events never sent; turning it off deletes the on-phone analytics data and id. Also "Delete all data" turning stats off (`privacy.md` "Deleting your data").
   - **5.9 Delete all data** (`privacy.md` "Deleting your data"): You › "Delete all data" deletes alarms, history, purchase records, settings, photos and recordings, deletes unsent crash reports, makes a new install id, keeps only what an unfinished Play payment needs, and is unavailable while an alarm rings or is snoozed.
   - **Epic 7 photos and recordings** (`privacy.md` "Data on your phone", "Backup", "Camera and microphone"): House Hunt reference photos and motivation recordings stay on the phone, are not backed up, are never uploaded; the stop-the-alarm photo is compared on the phone; the microphone is used only while recording; custom sounds are a reference only and not backed up.
   - **5.1 and 5.4** (`privacy.md` settings list mentions the theme; `support.md` "My alarm didn't ring" names the reliability checklist in Settings and its items).
   - Other facts to confirm: Crashlytics keeps reports 90 days (Google's documented retention); unused payments are refunded "after about three days"; Google Play's "Request a refund" label in order history; the Google Drive path "Storage, then Backups" for removing the backup copy.
3. **Approve the cap wording fix** for Story 5.8 (deferred item).

## Spec Change Log

## Review Triage Log

### Pass 1 (2026-10-16, one combined self-review, fast mode; the coordinator runs the independent review)

Verdicts: high 0, medium 2, low 1, false 0, maybe-false 0.

| # | Verdict | Route | Finding and evidence | Action |
|---|---------|-------|----------------------|--------|
| 1 | medium | patch | `privacy.md` short version said "Nothing you do in the app is sent to us", but crash reports and opted-in usage stats reach the developer's Firebase project. | Now "Your alarms, history and payments are never sent to us." |
| 2 | medium | patch | `privacy.md` said "we never sell or share your data", but the data goes to Google as a processor (Crashlytics, Analytics, Billing), which a reader can count as sharing. | Now "we never sell your data or use it for advertising." |
| 3 | low | patch | "Test alarms send nothing": with consent on, Firebase's automatic events can still fire while a test alarm runs; only the app events are skipped (5.6 default). | Now "Test alarms send no app events". |

Checked and kept: the terms match PRD §6.2 (8-hour lock, weakening changes) and the stranded reuse offer ("Use it", FR-RNG-10). The backup lists match `data_extraction_rules.xml`. All four official Pages action tags exist (`configure-pages@v6`, `jekyll-build-pages@v1`, `upload-pages-artifact@v5`, `deploy-pages@v5`). The only `AppLinks(` constructors are the Koin binding and `ProblemWithChargeViewModelTest`.

## Verification

**Commands:**
- `./gradlew --no-daemon --max-workers=2 checkSitePages` -- expected: passes.
- `./gradlew --no-daemon --max-workers=2 -p build-logic test --tests '*SitePages*'` -- expected: passes.
- `./gradlew --no-daemon --max-workers=2 :androidApp:testDebugUnitTest --tests '*AndroidExternalLinksTest*' :composeApp:testAndroidHostTest --tests '*ProblemWithChargeViewModelTest*'` -- expected: passes.
- `./gradlew --no-daemon --max-workers=2 spotlessCheck detekt` -- expected: passes.

## Auto Run Result

Status: review. The coordinator pushes, reviews, runs CI and merges. Automation never marks this story done (human-verify).

- **Built:**
  - `docs/site/` holds the five pages and `_config.yml`.
  - `checkSitePages` is a build-logic plugin with 11 tests, added to `qualityGate`.
  - `.github/workflows/pages.yml` runs check, then build, then deploy. Pull requests run the check only.
  - `privacy.url`, `terms.url` and `support.url` are in `config/app-links.properties`, reach the app as `BuildConfig` fields and are in `AppLinks`.
  - One deferred item (the cap wording) is assigned to Story 5.8.
- **Verification on 2026-10-16:** all passed.
  - `checkSitePages`.
  - `:build-logic:test --tests '*SitePages*'` (11 tests).
  - `:androidApp:testDebugUnitTest --tests '*AndroidExternalLinksTest*'` (7 tests).
  - `:composeApp:testAndroidHostTest --tests '*ProblemWithChargeViewModelTest*'` (16 tests).
  - `spotlessCheck` and `detekt`, all modules.
  - Everything ran with `--no-daemon --max-workers=2`. The full `qualityGate` is left to CI.
- **Matrix audit:** every I/O row has a `SitePagesTest` case, and the TestKit test runs the task on a fixture project.
- **Follow-up review recommended:** false. 2 medium and 1 low were patched, all of them copy accuracy. No code risk is unverified beyond CI.

## Owner verification

(human-verify, not done by automation) Live URLs: _not yet recorded_. Date: _not yet recorded_.
