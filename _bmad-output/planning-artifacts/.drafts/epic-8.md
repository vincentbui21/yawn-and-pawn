## Epic 8: Launch on Google Play

The app is live on Google Play: the store listing, screenshots and graphics are made from real screens, every App content declaration (Data safety, content rating, target audience 18+, exact alarm, full-screen intent, foreground service with demo video) is complete and matches what the app does, the EU DSA trader launch blocker is resolved, releases are signed through Play App Signing and uploaded from a git tag, closed-test feedback is triaged to exit criteria, and production rolls out 10% → 50% → 100% behind Android vitals and Crashlytics gates, followed by a weekly monitoring routine. Play Console setup was done in Story 1.4 and the closed test has been running since the end of Epic 5; this epic finalises the preliminary answers entered then for the full feature set (including whichever Epic 7 features shipped).

Most stories here are owner tasks in Play Console and are tagged `human-verify`: each lists objective completion criteria, the owner records pass/fail, date and evidence in the story file, and automation never marks them done. Where a story commits text or assets to the repo, automated checks verify them and `./gradlew qualityGate` must pass. PRD launch blockers (§13) are Stories 8.3, 8.5, 8.6, 8.8, 8.9 and 8.10; production submission (Story 8.12) is not allowed until all are done.

### Story 8.1: Store listing text

As the owner,
I want an honest, policy-safe store listing that explains pay-to-snooze and the free way to wake up,
So that users know exactly what they are installing and reviewers see nothing misleading.
**Refs:** FR-MSG-1, FR-MSG-4, NFR-5, NFR-13, AD-14; PRD §5 principles, §12 policy risks, §13 · **Priority:** Must · **Verify:** auto, plus (human-verify) Play Console entry

**Acceptance Criteria:**

**Given** `docs/store/en-US/`
**When** the listing is written
**Then** it contains `title.txt` (≤ 30 characters, the app name decided in PRD Q8 / `docs/decisions/package-id.md`), `short-description.txt` (≤ 80 characters) and `full-description.txt` (≤ 4,000 characters), all owner-approved [ASSUMPTION: listing copy is owner-approved, not an EXPERIENCE.md key string]
**And** the full description states plainly: snoozing costs real money through Google Play, the first snooze costs the base fee the user sets and each further snooze in the same morning costs more; waking up with a check is always free and works offline; the alarm keeps ringing until the check is done or a snooze is paid, the phone stays usable (calls, other apps, emergency calls), and the other ways to stop it (the FR-ONB-5 disclosure); no account, data stays on the phone; the app is for adults (18+)
**And** it lists the check types that actually ship (House Hunt only if Epic 7 Stories 7.6 to 7.9 shipped) and never claims features that were cut

**Given** a `StoreListingTest` in the `qualityGate`
**When** it runs over `docs/store/**`
**Then** it fails on a length limit breach, on an em dash, on the banned words from `CopyRulesTest`, on emoji, on a hard-coded price or currency symbol, on superlatives or ranking claims ("best", "#1", "top", "free alarm" without the snooze-cost statement), on invented statistics (any percentage or "x times" claim), and on keyword lists (the same word more than 5 times), per the Play metadata policy
**And** the test has one failing and one passing fixture per rule

**Given** Play Console > Grow > Store presence > Main store listing
**When** the owner enters the committed text and sets the app category (Tools or Lifestyle, recorded in the story), contact email, and privacy policy URL (GitHub Pages from Epic 5, returning HTTP 200)
**Then** the Console shows no listing errors and the text matches the committed files exactly (owner pastes a diff-free check result in the story file)
**And** the story file lists each item with pass/fail, date and notes; automation never marks the Console part done
**And** `./gradlew qualityGate` passes

### Story 8.2: Screenshots, feature graphic and icon from real screens

As the owner,
I want store graphics generated from the real app with realistic demo data,
So that the listing shows exactly what users get and can be regenerated after any UI change.
**Refs:** NFR-5, NFR-9, NFR-10, AD-10, AD-14, UX-DR2, UX-DR77 · **Priority:** Must · **Verify:** auto, plus (human-verify) Play Console upload

**Acceptance Criteria:**

**Given** a debug-only `DemoDataSeeder` (never in release; the release-manifest test from Story 1.18 extended to its class) with three alarms, 8 weeks of history matching F9, and cached USD prices
**When** `./gradlew captureStoreScreenshots` runs Roborazzi (or the OQ-3 fallback) at 1080 × 1920 px
**Then** it writes to `docs/store/screenshots/phone/` at least 4 and at most 8 PNGs, in this order: Ringing screen (Sunrise) with "I'm up" and "Snooze · $1"; Snooze confirm sheet; a check screen with the grace countdown; Home with the hero card and alarm list (Dark); Progress (Light); the Reliability checklist
**And** the images are the real composables with the committed tokens and strings, with no device frames, no added marketing text over the UI, no fake system notifications, and prices shown exactly as Play formats them in en-US

**Given** the feature graphic and icon
**When** `captureStoreScreenshots` runs
**Then** it renders `docs/store/feature-graphic.png` at 1024 × 500 px from a debug-only composable using the Sunrise tokens, the app name and a crop of the real ringing screen (no gradient text, no glow, UX-DR77), and exports `docs/store/icon-512.png` at 512 × 512 px from the adaptive launcher icon (which is added in this story if earlier epics left the default icon; icon concept per EXPERIENCE.md D1, owner-approved)

**Given** a `StoreAssetsTest` in the `qualityGate`
**When** it inspects `docs/store/`
**Then** it fails unless every screenshot is PNG or JPEG, 24-bit with no alpha, each side between 320 and 3,840 px, the long side ≤ 2 × the short side, at least 4 are ≥ 1080 px on the short side; the feature graphic is exactly 1024 × 500 with no alpha; the icon is exactly 512 × 512, 32-bit PNG, ≤ 1 MB
**And** the images are regenerated and the test re-run whenever a UI story changes a shown screen (documented in `docs/store/README.md`)

**Given** Play Console > Main store listing > Graphics
**When** the owner uploads the icon, feature graphic and screenshots
**Then** the Console accepts them with no warnings and the listing preview shows them in order
**And** the story file lists each item with pass/fail, date and notes; automation never marks the Console part done
**And** `./gradlew qualityGate` passes

### Story 8.3: Data safety form (launch blocker)

As the owner,
I want the Data safety form to match exactly what the app and its libraries collect and share,
So that users are told the truth and Play does not reject or pull the app.
**Refs:** NFR-4, NFR-14, NFR-15, FR-ONB-6, FR-SET-6, AD-6, AD-15; PRD §9 Play Console declarations, §13 launch blockers · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the release build's resolved runtime dependencies (`config/dependency-allowlist.txt`), merged release manifest and the privacy policy from Epic 5
**When** the owner prepares `docs/store/data-safety.md`
**Then** it records every Play Data safety question with the answer and its source (requirement, dependency, or official Google documentation URL with the date read), covering at least:
1. Crashlytics (always on): crash logs and diagnostics collected, app instance / Crashlytics installation ID as "Device or other IDs", purpose App functionality / Analytics, not shared, encrypted in transit, not optional.
2. Firebase Analytics (opt-in, default off): app interactions (`session_outcome`, `snooze_count`, `check_type` only) and app instance ID, purpose Analytics, collection optional, not shared, no advertising ID.
3. Purchases: handled by Google Play Billing; purchase history stays on the device (not collected by the developer).
4. Photos (House Hunt), audio (recordings, custom sounds), alarms, settings and history: processed and stored only on the device, not collected, not shared.
5. ML Kit barcode scanning and MediaPipe: any usage or diagnostics logging these libraries send, as found in their documentation and the Spike S3 notes, declared accordingly.
6. Android Auto Backup of alarms, settings and history to the user's own Google account: answered per Google's current Data safety guidance for platform backup, with the citation recorded (NFR-14).
7. Data deletion: in-app "Delete all data" (Epic 5) for on-device data; the support email for Crashlytics / Analytics deletion requests.
**And** the document states the privacy policy sections that disclose each item, and any mismatch found between the policy, the app and the form becomes a fix to the policy page or a bug story before this story closes

**Given** Play Console > Policy > App content > Data safety
**When** the owner submits the form from `docs/store/data-safety.md`
**Then** the Console shows the Data safety section as complete with no warnings, and the store listing preview shows the expected "Data safety" summary (screenshot attached in the story file)
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/store/data-safety.md` committed

### Story 8.4: Content rating, target audience 18+ and other App content declarations

As the owner,
I want the content rating, target audience and remaining App content declarations answered correctly,
So that the app is rated honestly, kept away from children's surfaces, and not blocked in review.
**Refs:** NFR-5; PRD §9, §12 (minors pay, policy rejection), Q6 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** Play Console > Policy > App content > Content rating
**When** the owner completes the IARC questionnaire (category "Utility, Productivity, Communication, or Other")
**Then** the answers are recorded in `docs/store/app-content.md`: no violence, sexual content, profanity, drugs, or user-to-user communication; no gambling or simulated gambling (a snooze is a fixed-price digital purchase with no chance element); digital purchases: yes; location sharing: no
**And** the issued rating certificate (expected low age rating with "In-App Purchases") is recorded with its IARC id and date; any unexpected rating is escalated to the owner before continuing

**Given** Target audience and content
**When** the owner sets it
**Then** the only selected age group is 18 and over, "appeals to children" is No, and the app is not opted into Designed for Families (NFR-5, Q6)

**Given** the other App content declarations
**When** the owner completes them
**Then** `docs/store/app-content.md` records: Ads = No; App access = all functionality available without login, with reviewer instructions (how to create an alarm, run "Test alarm" to see the ringing screen without payment, where the snooze purchase appears and that it is a real consumable at the base fee); Government app = No; Financial features = none; Health = none; News = No; privacy policy URL set
**And** the Console "App content" page shows every section complete (screenshot in the story file)
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/store/app-content.md` committed

### Story 8.5: Exact alarm, full-screen intent and foreground service declarations with demo video (launch blocker)

As the owner,
I want the sensitive-permission declarations filed with a demo video that shows the free path and a phone that stays usable,
So that Play approves the alarm permissions and does not see the app as holding the phone hostage.
**Refs:** FR-ALM-3, FR-ALM-4, FR-ALM-12, FR-SES-4, FR-SES-9, FR-RNG-9, NFR-13, AD-4, AD-5; PRD §9 Play Console declarations, §12, §13 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the release build's merged manifest (checked by the Story 1.2 permission allowlist)
**When** the owner prepares `docs/store/declarations.md`
**Then** it lists each declaration with its text: `USE_EXACT_ALARM` (API 33+) for an alarm clock as core functionality and `SCHEDULE_EXACT_ALARM` limited to API ≤ 32; `USE_FULL_SCREEN_INTENT` for the alarm use case; the foreground service type chosen in Spike S2 (`docs/spikes/S2.md`: `mediaPlayback` or `systemExempted`) with the task ("plays the user's alarm on the alarm stream until they finish their check or pay to snooze"), what the user sees (ongoing notification), and the impact if deferred (the alarm would stop)
**And** it confirms the merged manifest has no `SYSTEM_ALERT_WINDOW`, accessibility service, device admin, lock-task, `READ_PHONE_STATE`, `READ_MEDIA_*` or storage permission (quoting the allowlist check output)

**Given** a demo video (screen recording of a release-signed build from the internal track, 60 to 180 s, uploaded as an unlisted YouTube video)
**When** the owner records it on a real phone
**Then** it shows in order: the alarm ringing over the lock screen; "I'm up" and the check shown first and completed for free; a second ring where the user presses Home, opens another app and the sound continues with the ongoing notification; tapping the notification returns to the ringing screen; an incoming call works and pauses the alarm; the lock-screen emergency call is reachable; the snooze price and confirm sheet (without necessarily paying); and the FR-ONB-5 disclosure screen with "I understand"
**And** the video URL, recording date, device and build version are recorded in `docs/store/declarations.md`

**Given** a pre-submission policy review against the device-hostage risk (PRD §12, §13)
**When** the owner completes the checklist in `docs/store/declarations.md`
**Then** each NFR-13 and FR-SES-9 item (Home, Recents, power menu, calls, emergency dialer and other apps always work; no background activity starts; no overlays, accessibility service, lock-task or device admin; alarm audio on the alarm stream at the set volume without rerouting; volume keys captured only in the foreground; free path shown first and working offline) has evidence (test name, device checklist item from an earlier epic, or video timestamp) and pass/fail

**Given** Play Console > App content (Sensitive app permissions, Full-screen intent, Foreground service permissions)
**When** the owner submits the declarations with the video link
**Then** each declaration shows as submitted, and approval (or the reviewer's question and the owner's response) is recorded with dates; a rejection becomes a bug story or a `bmad-correct-course` proposal
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with `docs/store/declarations.md` committed

### Story 8.6: EU DSA trader status and EU consumer rules (launch blocker)

As the owner,
I want the EU Digital Services Act trader status settled without publishing my home address,
So that the app can launch legally in the EU, or deliberately without the EU.
**Refs:** NFR-5; PRD §9, §12 (DSA exposes personal address), §13 launch blockers, Q17 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the app sells in-app products (so the developer is expected to declare as a trader under the DSA)
**When** the owner decides
**Then** `docs/decisions/eu-dsa-trader.md` records one option with reasons and date: (a) declare trader status with a publishable business address, phone and email that are not the owner's home (for example a registered business address or a mail-handling service that accepts legal mail), or (b) launch without the EU/EEA and revisit later

**Given** option (a)
**When** the owner completes Play Console > Settings > Developer account > Trader status
**Then** the address, phone and email pass Google's verification, the Console shows trader status as verified, and the store listing preview for an EU country shows the trader contact details (screenshot in the story file)

**Given** option (b)
**When** the owner configures production country availability
**Then** all 27 EU member states plus Iceland, Liechtenstein and Norway are excluded from production and closed tracks that would reach EU users, and the country list is recorded in the decision file

**Given** PRD Q17 (EU/UK right of withdrawal for instantly delivered consumables)
**When** the owner resolves it with the privacy/terms author
**Then** the decision file records whether Google Play's checkout covers the consent and waiver or whether the app must show its own consent text before the first purchase; if the app must, a bug story is created in the backlog with the approved text before Story 8.10 can pass
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with the decision file committed

### Story 8.7: Release signing and tag-driven upload to the internal track

As the owner,
I want every `vX.Y.Z` tag on `main` to produce one signed, R8-checked, correctly numbered release uploaded to the internal track,
So that releases are repeatable and a hotfix is one tag away.
**Refs:** NFR-6, NFR-11, NFR-15, AD-14, AD-15; Architecture Environments and operations (versioning, Play App Signing, hotfix) · **Priority:** Must · **Verify:** auto, plus (human-verify) first real upload

**Acceptance Criteria:**

**Given** the versioning scheme `versionCode = major*10000 + minor*100 + patch`
**When** the build reads the version from the tag (`-PreleaseVersion=X.Y.Z`, defaulting to the committed version locally)
**Then** it fails unless the version is plain semver with minor ≤ 99 and patch ≤ 99, and unit tests cover 1.0.0 → 10000, 1.2.3 → 10203, 1.99.99 → 19999 and rejections of 1.100.0, 1.0.100 and 1.0.0-rc1

**Given** the `release.yml` workflow from Story 1.2
**When** a tag `vX.Y.Z` is pushed
**Then** it fails unless the tagged commit is on `main` (`git merge-base --is-ancestor`), `docs/release-notes/X.Y.Z/en-US.txt` exists and is ≤ 500 characters and passes the `StoreListingTest` copy rules, and `./gradlew qualityGate` passes on the tag
**And** it builds `bundleRelease` signed with the upload key from the GitHub secrets (`UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`), verifies the AAB signature with `jarsigner -verify`, and uploads it with the R8 `mapping.txt` and release notes to the internal track through the Play Developer API using `PLAY_SERVICE_ACCOUNT_JSON` (Play App Signing re-signs for distribution)
**And** the Crashlytics Gradle plugin uploads the release mapping file so stack traces are deobfuscated, using `GOOGLE_SERVICES_JSON_RELEASE`
**And** a versionCode already used on any track fails the job with a message naming it, before upload
**And** without secrets the upload is skipped with a notice, as in Story 1.2

**Given** R8 can break reflection-based code in release only
**When** the `qa` build type is added (`initWith(release)`, debug signing, minify on, no debug receivers)
**Then** CI runs a Gradle Managed Device smoke test against the minified `qa` APK that launches the app, creates and saves an alarm, kills and restarts the process, finds the alarm again, and round-trips every `SessionState` variant through kotlinx-serialization, Room and Koin wiring; keep rules live in `androidApp/proguard-rules.pro` with a comment per rule
**And** a hotfix procedure (patch tag `vX.Y.(Z+1)` from `main`) is written in `docs/release/README.md`

**Given** the first real tag after this story
**When** the workflow completes
**Then** the owner confirms in Play Console that the build is on the internal track with the right versionName, versionCode and release notes, installs it from the internal opt-in link on a real phone, and sees a test crash from that build deobfuscated in the Crashlytics prod project (human-verify, recorded with date)
**And** `./gradlew qualityGate` passes

### Story 8.8: Release-build device verification checklist (launch blocker)

As the owner,
I want to run the most important morning paths on the Play-signed, R8-minified build on the device matrix,
So that nothing that only breaks in release reaches testers or production.
**Refs:** FR-ALM-3, FR-ALM-4, FR-ALM-11, FR-SES-1, FR-SES-2, FR-SES-4, FR-RNG-3, FR-RNG-4, FR-RNG-6, FR-PWK-7, FR-PRG-4, NFR-1, NFR-2, NFR-7, NFR-11, NFR-15 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** the release build from Story 8.7 installed from the internal track (not a debug build) on each device of the matrix (Pixel, Samsung, Xiaomi, budget device), with a license tester account
**When** the owner runs the checklist
**Then** for each item the story file records pass/fail, device, Android version, versionName and date:
1. First launch runs onboarding to a locked-screen test alarm that rings with "Test · no charge".
2. A real alarm 2 minutes ahead rings within 2 s with the screen off and locked, and the ringing screen shows within 1 s (NFR-1, NFR-7).
3. Each shipped check type completes, including QR/Barcode with the camera (and House Hunt if shipped).
4. A paid snooze with a license tester completes, shows "Snoozed. Next ring at {time}.", re-rings with the next price, and appears in Purchase history with the localized price.
5. Pending (license-tester slow card) and cancelled payments show their EXPERIENCE.md messages and "No charge."
6. Swiping the app from Recents and "Stop" in the OEM task manager during a ring: the alarm re-rings within 60 s (FR-SES-2).
7. An overnight reboot before first unlock rings with Math and the default sound (FR-ALM-11).
8. Home, Progress, calendar and the weekly summary work with real history; "Delete all data" clears everything.
9. Analytics stays off until consent; after opting in, the three events appear in Firebase DebugView and nothing else; Crashlytics receives a forced test crash, deobfuscated.
10. Installing an update from the internal track over the previous release keeps alarms, history and settings, and the alarm still rings.

**Given** any failed item
**When** the owner records it
**Then** each failure becomes a new bug story referencing this item, and this story stays open until all items pass or are explicitly waived by the owner in the story file
**And** automation never marks this story done

### Story 8.9: Closed-test feedback triage and exit (launch blocker)

As the owner,
I want every closed-test report triaged and the Play closed-test requirement met with evidence,
So that production launches only when testers found no alarm, payment or policy blockers.
**Refs:** NFR-1, NFR-2, NFR-11; PRD §11 SM-1, SM-2, CM-1, CM-4, §13 release plan, Q11, Q13, Q14 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** feedback sources: Play Console testers' private feedback, the support email and feedback channel set up in Epic 5, Crashlytics and Android vitals for the closed track, and the Play pre-launch report
**When** the owner triages
**Then** `docs/closed-test/feedback.md` lists every item with id, date, source, device and Android version, summary, severity (S1 blocker: alarm didn't ring or rang late, charged without a snooze or double-charged, crash or ANR in the wake path, anything resembling device-hostage behaviour; S2 major; S3 minor) and decision (bug story id, won't fix with reason, or duplicate of id)
**And** every pre-launch report crash, accessibility warning and security warning is listed and decided the same way

**Given** the exit criteria
**When** the owner checks them
**Then** the story file records, with evidence: at least 12 testers opted in continuously for at least 14 days (Play Console closed-testing dashboard screenshot, required for new personal accounts per Story 1.4); zero open S1 items and zero open S2 items, each fix verified by the reporter or owner on a newer closed-track build; SM-1 = 0 confirmed "alarm didn't ring" reports; Crashlytics crash-free sessions ≥ 99.5% on the last closed build (SM-2)
**And** the revisit conditions are checked and recorded: fallback check share of sessions > 10% (CM-4) reopens PRD Q11; refund rate > 5% (CM-1) reopens Q13; tester reports of the call pause as an escape reopen Q14; each reopened question goes to `bmad-correct-course` before production

**Given** Play Console > Production > "Apply for production" (required for new personal developer accounts)
**When** the exit criteria pass
**Then** the owner submits the application with answers drawn from `docs/closed-test/feedback.md` (how testers were recruited, what feedback was received, what changed), and records the submission date and Google's decision
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done

### Story 8.10: Launch readiness review

As the owner,
I want one checklist that proves every launch blocker is done before I submit to production,
So that nothing is forgotten on launch day.
**Refs:** NFR-5, NFR-6, NFR-11, NFR-13; PRD §13 launch blockers, Q17 · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** `docs/release/launch-readiness.md`
**When** the owner completes it
**Then** each row has pass/fail, evidence link and date:
1. EU DSA trader status or EU exclusion (Story 8.6), and Q17 resolved with any required in-app consent shipped.
2. Privacy policy live on GitHub Pages (HTTP 200) and consistent with the Data safety form (Story 8.3).
3. Exact alarm, full-screen intent and foreground service declarations approved, with the demo video and pre-submission policy review (Story 8.5).
4. Content rating, target audience 18+ and App content complete (Story 8.4); store listing and graphics published (Stories 8.1, 8.2).
5. Every `human-verify` checklist story passed or explicitly waived: 1.21, the Epic 2 to Epic 5 checklists, 6.10, 7.12 and 8.8 (story ids listed).
6. Closed test complete and production access granted (Story 8.9).
7. targetSdk 36 and Play Billing Library ≥ 8 (9.1.0) in the release build, meeting Play's current deadlines.
8. `tools/play-catalog` in dry-run mode reports no difference between the repo and the 50 live products, and all 31 reachable products are active.
9. The production candidate AAB is the same versionCode that passed Stories 8.8 and 8.9 (no rebuild).
10. `docs/runbooks/post-launch-monitoring.md` exists (Story 8.11).
**And** any failing row blocks Story 8.12, and the story file lists each item with pass/fail, date and notes; automation never marks this story done
**And** `./gradlew qualityGate` passes on `main` with the readiness file committed

### Story 8.11: Post-launch monitoring runbook

As the owner,
I want a written weekly routine and incident playbooks, with a reminder that creates the checklist for me,
So that crashes, missed alarms and payment problems are caught fast after launch.
**Refs:** NFR-1, NFR-2, NFR-15, AD-14; PRD §11 SM-1 to SM-6, CM-1 to CM-4, §12; Architecture Environments and operations (monitoring) · **Priority:** Must · **Verify:** auto, plus (human-verify) first weekly check

**Acceptance Criteria:**

**Given** `docs/runbooks/post-launch-monitoring.md`
**When** it is written
**Then** it defines the weekly check with thresholds and sources: Android vitals user-perceived crash rate < 1.09% and ANR rate < 0.47% (Play bad-behaviour thresholds); Crashlytics crash-free sessions ≥ 99.5% (SM-2); any crash or ANR in `WakeService`, `WakeActivity`, alarm receivers, `SessionEngine` or billing is a release blocker; Play rating ≥ 4.3 and new reviews answered within 3 days (SM-5) [ASSUMPTION: response time owner-approved]; refund rate ≤ 5% (CM-1, Play order management); opt-in Analytics SM-3 and CM-2 to CM-4; D30 retention (SM-4)
**And** it contains incident playbooks with first steps and owner decisions for: "my alarm didn't ring" (collect device, Android version, OEM settings, reliability checklist state; reproduce with the device matrix; S1 bug story); "I was charged but didn't get a snooze" (FR-SET-5 support flow, Google 48-hour refund, check the stranded-purchase path); wake-path crash spike (halt rollout, patch tag per `docs/release/README.md`); a Play policy notice or rejection (respond within the stated deadline, `bmad-correct-course` if a product change is needed)
**And** it lists recurring deadlines: Play target API level each August, Play Billing Library deprecation dates, Firebase BoM and dependency updates each quarter through the dependency allowlist review, and the deferred items from the Architecture Spine (targetSdk 37, detekt stable)

**Given** `.github/workflows/weekly-monitoring.yml`
**When** it runs every Monday on schedule (and on manual dispatch)
**Then** it opens a GitHub issue "Weekly monitoring {date}" whose body is the runbook's weekly checklist as task items, and it never needs secrets beyond the default token
**And** a unit test (or workflow lint) asserts the issue template stays in sync with the runbook's checklist section

**Given** the first week after the runbook is merged
**When** the owner completes the first generated issue
**Then** every item is ticked or noted with numbers, and the issue link is recorded in the story file (human-verify); automation never marks that part done
**And** `./gradlew qualityGate` passes

### Story 8.12: Staged production rollout

As the owner,
I want production to roll out 10%, then 50%, then 100%, moving on only when the health gates pass,
So that a problem reaches as few people as possible and can be halted.
**Refs:** NFR-1, NFR-2, NFR-15; PRD §11 SM-1, SM-2, CM-1, §13; Architecture Environments and operations (staged rollout, hotfix) · **Priority:** Must · **Verify:** human-verify

**Acceptance Criteria:**

**Given** Story 8.10 passed and production access is granted
**When** the owner creates the production release
**Then** it promotes the exact AAB (same versionCode) that passed Stories 8.8 and 8.9, with the committed release notes, country availability per Story 8.6, and staged rollout at 10%
**And** `docs/release/rollout-vX.Y.Z.md` records each stage's start date, percentage and the gate numbers below

**Given** each stage (10% held at least 3 days, then 50% held at least 3 days) [ASSUMPTION: hold times owner-approved]
**When** the owner checks the gates before increasing
**Then** all must pass: zero crashes or ANRs in the wake path in Crashlytics; Crashlytics crash-free sessions ≥ 99.5%; Android vitals crash and ANR rates below the bad-behaviour thresholds (or "not enough data" recorded, in which case Crashlytics decides); zero confirmed "alarm didn't ring" reports (SM-1); refund rate ≤ 5% (CM-1); no Play policy notice
**And** only then the owner raises the rollout to 50%, then to 100%, recording date and numbers for each step

**Given** any gate fails
**When** the owner acts
**Then** the rollout is halted in Play Console the same day, an S1 bug story is created, the fix ships as a patch tag `vX.Y.(Z+1)` through the internal track (and Story 8.8 items relevant to the fix are re-run), and the rollout resumes from the halted percentage with the gates re-checked; every step is recorded in the rollout file

**Given** 100% rollout reached
**When** 7 days pass with the gates still green
**Then** the owner records "launch complete" with date in the rollout file, and weekly monitoring continues per Story 8.11
**And** the story file lists each item with pass/fail, date and notes; automation never marks this story done
