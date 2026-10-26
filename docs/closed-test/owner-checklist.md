# Closed test: the owner's Play Console checklist (Story 5.14)

Do the steps in order. Each step says where to click and what to pick. The answers come from the code on `main` (manifest, Firebase and Billing code, dependency allowlist) as of 2026-10-26, not from the plans.

- **OWNER DECIDES** marks a choice only the owner can make. Each one has a recommendation.
- Play Console menu names change from time to time. If a name here doesn't match, look for the closest one, and note the real name in the Story 5.14 file.
- This repo is public. Never commit tester emails, names, the IARC email, the Play account's personal details or any key.

Related files: `docs/closed-test.md` (the tester guide), `feedback-form.md` (the Google Form), `store-listing-draft.md` (the listing text).

---

## 0. Before you start

- [ ] **Story 4.18** is done (it is) and **Story 5.13** has been run, or its failures are waived in writing in its story file. The story says the closed test starts only after both.
- [ ] **Read the "PRIVACY MISMATCH" section below.** Those items are launch blockers. They don't stop the closed test from starting, but the privacy policy and the Data safety form must agree before production. The recommended order is to fix the policy page first, so testers see a correct policy.
- [ ] **GitHub secrets** (repository > Settings > Secrets and variables > Actions): the five in `docs/ci-release.md`, **plus `GOOGLE_SERVICES_JSON_RELEASE`** (the production Firebase project's `google-services.json`). `docs/ci-release.md` doesn't list that one yet. Without it the release build has no Crashlytics and no usage stats, so tester crashes would be invisible. The release run prints a warning when it is missing.
- [ ] **Firebase console** (production project):
  - Project settings > Integrations: no Google Ads or AdMob link.
  - Analytics > Admin > Data settings > Data retention: **2 months** (the privacy policy says 2 months).
  - Analytics > Admin > Data collection: **Google signals off**.
  - Analytics > Admin > Account settings > Data sharing settings: turn **off** "Google products & services" and "Modeling contributions & business insights". This keeps the Data safety answer "not shared" true.
- [ ] **Pages are live:** open https://vincentbui21.github.io/yawn-and-pawn/privacy/ and https://vincentbui21.github.io/yawn-and-pawn/support/ in a private browser window. Both must open (HTTP 200) and show the current text.
- [ ] **EU DSA trader status** (Story 8.6). **OWNER DECIDES**, see section 3.16. Do this before you create the closed release, because it decides which countries the closed track can reach.

---

## 1. Epic 8 items pulled forward into the closed test

Play won't publish a closed track until the listing and App content are complete, so parts of Epic 8 happen now. Copy this table into the Story 5.14 file.

| Epic 8 story | What is pulled forward now | What stays in Epic 8 |
|---|---|---|
| 8.1 Store listing text | Name, short and full description from `store-listing-draft.md`, category, contact email, privacy URL | `docs/store/en-US/*.txt`, `StoreListingTest`, the owner's final wording |
| 8.2 Screenshots, feature graphic, icon | Interim graphics (section 2): icon, feature graphic, 2 to 8 phone screenshots | `captureStoreScreenshots`, `StoreAssetsTest`, the final set |
| 8.3 Data safety | The whole form, answered from section 3.6 | `docs/store/data-safety.md` with sources and dates; fixing the PRIVACY MISMATCH items |
| 8.4 Content rating, target audience, other App content | All of it (sections 3.2 to 3.5, 3.7 to 3.11) | `docs/store/app-content.md` with the IARC id and screenshots |
| 8.5 Exact alarm, full-screen intent, FGS declarations | The three declarations (3.12 to 3.14), with a demo video | `docs/store/declarations.md`, the full pre-submission policy review |
| 8.6 EU DSA trader status | The decision, if Play asks for it before a closed release reaches the EU | `docs/decisions/eu-dsa-trader.md`, Q17 (right of withdrawal) |

---

## PRIVACY MISMATCH (launch blocker)

Found by comparing `docs/site/privacy.md` with the code on `main` and Google's own documentation for the SDKs in the app (Firebase "Play data disclosure" page and the Google Analytics "Data disclosures" page, read 2026-10-26). None of these is a code bug; each needs the policy, the Data safety form or the code changed before production. The recommended fix for each is a separate pull request to `docs/site/privacy.md`.

1. **Automatic in-app purchase events in usage stats.** With usage stats on, Google Analytics for Firebase logs Google Play in-app purchases by itself (`in_app_purchase`: product id, price, value, currency; and `app_store_refund` on a refund). The app doesn't log them, but the SDK does. The policy says the stats hold "no amounts paid" and lists only three automatic events. Google ignores test and licence-tester purchases, so this won't show in DebugView during the closed test.
   - Fix (recommended): say in the policy's "Usage stats" section that, with usage stats on, Google Analytics also records each snooze purchase (product, price and currency, never card details) and Google Play refunds. Declare "Purchase history" in Data safety (section 3.6).
   - **OWNER DECIDES:** a code change that stops it instead. There is no documented manifest switch for this event on Android, so changing the policy is the safer route.
2. **Other automatic Analytics events are missing from the policy.** With usage stats on, Firebase also logs `user_engagement` (time the app is on screen), `app_update`, `os_update`, `app_clear_data` and, because Crashlytics is in the app, `app_exception` (a crash happened, fatal or not). The policy names only first open, app open (session start) and uninstall.
   - Fix: list them in plain words ("how long the app is on screen, app and Android updates, clearing the app's data, and that a crash happened").
3. **Firebase Sessions runs even with usage stats off.** Crashlytics brings the Firebase Sessions SDK (`firebase-sessions` is in `config/dependency-allowlist.txt`). It sends app-session data (when the app comes to the foreground, package, Android version, phone maker and model, network type) to compute crash-free numbers, whatever the usage-stats switch says. The policy describes crash reports only as sent "when the app crashes" plus "its own diagnostic logs".
   - Fix: add one sentence to "Crash reports": "To count how many people a crash affects, Crashlytics also records when the app is opened, with the phone model and Android version, even if usage stats are off." Declare it in Data safety as "Other app performance data", required.
4. **Crashlytics breadcrumbs.** With usage stats on, Crashlytics attaches the recent Analytics events (breadcrumbs) to a crash report. The policy says "The app adds no logs or notes of its own to a report", which is true of the app's own code, but a reader would not expect the usage-stats events in a crash report.
   - Fix: add "With usage stats on, a crash report also lists the usage-stats events from just before the crash."
5. **"Approximate location ... that Google ... doesn't keep."** Google doesn't keep the IP address, but it does keep the derived country, region and city with the stats. The sentence can be read as "the location isn't kept".
   - Fix: "an approximate location (country, region and city) that Google works out from the network address. Google doesn't keep the network address itself."
6. **Features described that this build doesn't have (low).** The policy describes House Hunt photos, motivation recordings, the microphone permission and custom sound files (Epic 7, not built). The merged manifest has no `RECORD_AUDIO`. Over-describing is not a Data safety problem (all of it stays on the phone), but the policy says the app "asks for permission" for the microphone, which this build never does.
   - Fix (recommended): leave it, with a note in Story 5.7 that it describes Epic 7 ahead of time, or mark those parts "if your version has House Hunt / recordings". **OWNER DECIDES.** Related, not privacy: the reliability checklist strings still mention House Hunt and a Microphone item (`reliability_camera_reason`, `reliability_microphone`); worth a check in Story 5.13 that the Microphone row never shows.
7. **Data deletion route.** Story 8.3 plans "the support email for Crashlytics/Analytics deletion requests", but the policy's "Deleting your data" section offers no such route. The Data safety deletion answer (section 3.6, question 4) must match the policy, whichever way the owner decides.

Things that do match (checked): no advertising ID (the `AD_ID`, ad-services and install-referrer permissions are removed in the manifest; ad storage, ad user data and ad personalisation are denied; SSAID collection is off); no user id or user property is ever set (`AnalyticsManifestTest`); three custom events only (`session_outcome`, `snooze_count`, `check_type`, each with one category or number); test alarms send no app events (`SessionTelemetry`); Crashlytics is always on, starts only after the first unlock, and strips the message from every error the app reports; Play Billing gets only the random install id and session id; no location permission even though Play Billing brings `play-services-location` (`docs/decisions/play-billing-dependencies.md`); ML Kit uses the bundled model.

---

## 2. Main store listing

Play Console > Yawn & Pawn > **Grow users > Store presence > Store listings > Main store listing** (older menus: Grow > Store presence > Main store listing).

- [ ] **App name, short description, full description:** paste from `store-listing-draft.md`.
- [ ] **App icon** (512 × 512 PNG), **feature graphic** (1024 × 500), **at least 2 phone screenshots.** **OWNER DECIDES:** Story 8.2's generated set isn't built. Recommendation: take interim screenshots on your phone now (Home, the ringing screen from a test alarm, the snooze confirm sheet, a check, Progress), with no personal names in alarm labels and a clean status bar. Replace them in Story 8.2. For the feature graphic, a plain Sunrise-colour background with the app name is enough for a closed test.
- [ ] **Store settings** (Store presence > Store settings): App category **OWNER DECIDES**, recommended **Tools**; contact email: the support address from the support page; website: https://vincentbui21.github.io/yawn-and-pawn/
- [ ] **Save.** The page must show no errors.

**OWNER DECIDES, support address:** the support address is a personal Gmail address, already published on the site (`config/app-links.properties` notes a dedicated address is an owner action before launch). Recommendation: keep it for the closed test; switch to a dedicated address before production (it changes `config/app-links.properties` and the site pages together).

---

## 3. App content

Play Console > Yawn & Pawn > **Policy and programs > App content** (older menus: Policy > App content). Do each item under "Needs attention" until nothing is left.

### 3.1 Privacy policy

- [ ] URL: `https://vincentbui21.github.io/yawn-and-pawn/privacy/`. Save.

### 3.2 App access

- [ ] Pick **"All functionality in my app is available without any access restrictions"**. There is no login or account.
- Play may still ask for instructions. If there is a text box, paste:

```text
No account or login. Create an alarm on the Alarms tab. In the alarm editor, "Test alarm" rings the full ringing screen at once and never charges. The free way to stop it is "I'm up", then a short check such as Math. "Snooze" opens Google Play's purchase sheet for a real one-time product at the fee set in Settings (a licence tester account sees a test card). Snooze is not offered during a test alarm or offline.
```

### 3.3 Ads

- [ ] **"No, my app does not contain ads."** (No ads SDK; the AdMob-related permissions are removed in the manifest.)

### 3.4 Content rating

- [ ] Start the questionnaire. Email: your Play account contact address (don't write it in the repo).
- [ ] Category: **"All Other App Types"** / "Utility, Productivity, Communication, or Other" (the label varies).
- [ ] Answers:

| Question (short form) | Answer | Why |
|---|---|---|
| Violence, blood, fear, horror | No | |
| Sexual content or nudity | No | |
| Bad language, crude humour | No | Word Unscramble words are checked against `config/word-blocklist.txt` |
| Drugs, alcohol, tobacco | No | |
| Real or simulated gambling | **No** | A snooze is a fixed-price digital purchase with no chance element |
| Users can interact or exchange content (chat, posts, sharing photos) | No | Nothing is shared between users |
| Shares the user's current location with other users | No | No location permission |
| Users can buy digital goods | **Yes** | Snoozes are one-time in-app products |
| Unrestricted internet access (a web browser or search engine) | No | Links open in the phone's browser |
| Nazi symbols or similar (asked for Germany) | No | |
| Any other question | No, unless a question clearly describes the app | |

- [ ] Submit, then **Apply** the rating. Expect a low age rating with "In-App Purchases". If any rating is higher than you expect, stop and check the answers before going on. Record the IARC certificate id and date in the Story 5.14 file.

### 3.5 Target audience and content

- [ ] Target age groups: **only "18 and over"**. Nothing else ticked.
- [ ] "Could your store listing unintentionally appeal to children?": **No**.
- [ ] Don't opt into Designed for Families (it is not offered for 18+ only).

### 3.6 Data safety

Data safety > **Start** (or Manage). The answers below cover the release build with `GOOGLE_SERVICES_JSON_RELEASE` set (Crashlytics and Analytics present). Where Google says "collected", it means sent off the phone; data kept only on the phone (alarms, history, purchase records, the QR fingerprint, settings) is not collected.

**Step 1, data collection and security**

| # | Question | Answer |
|---|---|---|
| 1 | Does your app collect or share any of the required user data types? | **Yes** |
| 2 | Is all of the user data collected by your app encrypted in transit? | **Yes** (Firebase, Play Billing and ML Kit use HTTPS; the app keeps cleartext traffic blocked) |
| 3 | Which account creation methods does your app support? | **"My app does not allow users to create an account"** |
| 4 | Do you provide a way for users to request that their data is deleted? | **OWNER DECIDES** (see below) |

**Question 4, OWNER DECIDES.** Recommendation: if the form offers "No, but user data is automatically deleted within 90 days", pick it: crash reports are kept 90 days and Analytics user-level data 2 months (set the retention in section 0). Otherwise answer **Yes** and add one line to the privacy policy's "Deleting your data": "To ask us to delete crash reports or usage stats sent from your phone, email us." (PRIVACY MISMATCH 7). Don't answer Yes without that line.

If the form asks about an independent security review: **No**. About the Families policy: not shown for 18+.

**Step 2, data types.** Tick exactly these:

- Location: **Approximate location**
- Financial info: **Purchase history** (**OWNER DECIDES** below)
- App activity: **App interactions**
- App info and performance: **Crash logs**, **Diagnostics**, **Other app performance data**
- Device or other IDs: **Device or other IDs**

Leave everything else unticked: Personal info (name, email, user ids, address, phone, etc.), Precise location, User payment info, Credit score, Other financial info, Health and fitness, Messages, Photos and videos, Audio, Files and docs, Calendar, Contacts, In-app search history, Installed apps, Other user-generated content, Other actions, Web browsing.

**Step 3, for each ticked type.** For every type below: **Shared: No** (Firebase and Google Play process data for us as service providers; nothing is sold or used for ads) and **Processed ephemerally: No**.

| Data type | Collected | Required or optional | Purposes | Where it comes from |
|---|---|---|---|---|
| Approximate location | Yes | **Optional** (users can choose) | Analytics | Google Analytics derives country, region and city from the IP address, only with usage stats on |
| Purchase history | Yes | **OWNER DECIDES**, recommended **Required** | App functionality, Analytics | Analytics' automatic `in_app_purchase` events (usage stats on), and the Play order records the developer sees in Play Console for every snooze |
| App interactions | Yes | **Optional** | Analytics | The three usage-stats events and Firebase's automatic events, only with usage stats on |
| Crash logs | Yes | **Required** | App functionality, Analytics | Crashlytics, always on |
| Diagnostics | Yes | **Required** | App functionality, Analytics | Crashlytics device state (memory, storage, OS); diagnostic logs from Crashlytics, Play Billing and ML Kit |
| Other app performance data | Yes | **Required** | Analytics | Firebase Sessions (app-open times for crash-free numbers), always on with Crashlytics |
| Device or other IDs | Yes | **Required** | App functionality, Analytics | Crashlytics installation id and Firebase installation id (always); Analytics app-instance id (usage stats on) |

**Purchase history, OWNER DECIDES.** Google says payment data collected only by Google Play's billing system needn't be declared, but purchase history the app (or an SDK in it) receives must be. Recommendation: declare it, and mark it **Required**, not optional, because Play gives the developer every snooze order in Play Console whatever the usage-stats switch says. Declaring more than strictly needed is safe; declaring less is not.

**Not declared, with the reason (OWNER DECIDES to confirm):**

- **Android Auto Backup** of alarms, history, settings and purchase records goes to the user's own Google account; the developer can't read it. Recommendation: don't declare it as collection. Story 8.3 item 6 asks for Google's current guidance with a citation; check the Data safety help page's backup FAQ before production.
- **Camera frames** for QR/Barcode are read on the phone by the bundled ML Kit model and never sent. On-device only, so not collected.
- **Install id and session id** given to Play Billing as obfuscated account and profile ids: random, part of the payment that Google Play handles; covered by the purchase history entry.

- [ ] Preview the summary, then **Submit**. Take a screenshot of the listing preview's Data safety section for the Story 5.14 file.

### 3.7 Government apps

- [ ] **No.**

### 3.8 Financial features

- [ ] **"My app doesn't provide any financial features."** The snoozes are in-app purchases through Google Play, not a financial service (no loans, payments between people, banking, crypto or trading).

### 3.9 Health apps

- [ ] **"My app does not have any health features."** Recommendation, **OWNER DECIDES** only if you disagree: the app is an alarm clock; it doesn't track sleep, health or fitness. The Progress screen shows wake-up times and snoozes, which is not "Sleep management" in Play's sense. If Play lists "Sleep management" and a reviewer asks, answer that the app does no sleep tracking.

### 3.10 News apps, and anything else Play lists

- [ ] News app: **No.**
- [ ] If Play lists COVID-19 contact tracing or similar: **No / not applicable.**
- [ ] Account deletion: not shown (no accounts). If shown, say the app has no accounts.
- [ ] Photo and video permissions: not shown (no `READ_MEDIA_*` permission). If shown, check the merged manifest and stop: it would be a bug.

### 3.11 Advertising ID

- [ ] **"No"**, the app does not use an advertising ID. The manifest removes `com.google.android.gms.permission.AD_ID`, so the release has no AD_ID permission, and Analytics' advertising-id collection is off.

### 3.12 Exact alarms (`USE_EXACT_ALARM`)

The manifest declares `USE_EXACT_ALARM` (Android 13+) and `SCHEDULE_EXACT_ALARM` only up to Android 12L (`maxSdkVersion="32"`). `USE_EXACT_ALARM` is a restricted permission: Play allows it for alarm clock apps.

- [ ] If Play shows an "Exact alarm" declaration (under App content, or as an error when you send the release for review): pick **"Alarm clock"** as the core functionality. If there is a text box, paste:

```text
Yawn & Pawn is an alarm clock. Its core function is ringing at the exact time the user sets for their wake-up alarm. It schedules alarms only with AlarmManager.setAlarmClock, so the system shows them as the next alarm. It uses exact alarms only for the user's alarms: the wake-up time, the end of a snooze, and bringing a ringing alarm back if the app was stopped.
```

### 3.13 Full-screen intent (`USE_FULL_SCREEN_INTENT`)

On Android 14+, Play grants full-screen intent only to apps whose core function is calling or alarms.

- [ ] App content > **Full-screen intent** (or "Use of full-screen intent"): **Yes, my app uses it**; use case **"Alarm clock" / "Setting alarms"**.
- If there is a text box:

```text
When a wake-up alarm the user set goes off, the ringing notification's full-screen intent shows the alarm screen over the lock screen, like the phone's own clock app. It is used only for the user's alarms and test alarms, never for ads or other notifications.
```

### 3.14 Foreground service permissions (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`)

The manifest's `WakeService` uses `foregroundServiceType="mediaPlayback"`. The manifest comment marks it "[ASSUMPTION pending Spike S2 (systemExempted)]".

- [ ] App content > **Foreground service permissions** > Start. Select **Media playback**.
- [ ] Task: pick the closest listed task (such as "Audio playback" / "Media playback"); if there is "Other", use the text below.
- [ ] Description:

```text
WakeService plays the alarm sound the user chose, on the alarm audio stream, from the moment their alarm rings until they finish their wake-up check or snooze. The user sees an ongoing "alarm ringing" notification the whole time; tapping it returns to the alarm screen. Calls, other apps and emergency calls keep working while it plays.
```

- [ ] Impact if the task were deferred or interrupted:

```text
The alarm would go silent or ring late, so the user could oversleep. Alarm audio must start at the set time and keep playing until the user acts.
```

- [ ] **Video link.** Play needs a video showing the feature. **OWNER DECIDES:** record the full Story 8.5 video now (recommended: one recording covers the closed test and production), or a short one now (alarm rings over the lock screen; the ongoing notification; press Home, the sound continues; tap the notification to come back; "I'm up" and a check stops it for free) and the full one in Story 8.5. Record a release build from the internal track. Upload to YouTube as **Unlisted**. Don't show a real card, your email or your contacts in the video.
- **Risk to know:** Play may say `mediaPlayback` is meant for media apps. If the declaration is rejected, the fallback is the `systemExempted` type that Android allows for alarm apps; that is a code change and becomes a bug story (Story 8.5 says the same).

### 3.15 Check

- [ ] App content shows every item done, with nothing under "Needs attention". Screenshot for the Story 5.14 file.

### 3.16 EU DSA trader status (Story 8.6)

The app sells in-app products, so under the EU Digital Services Act the developer counts as a trader, and Play shows a trader's address, phone and email to EU users.

- **OWNER DECIDES.** Options: (a) declare trader status now with a business address and phone that are not your home (a registered business address or a mail service that accepts legal mail); or (b) keep the closed track (and later production) out of the EU and EEA until (a) is ready.
- Recommendation: check Play Console > **Settings > Developer account > Account details** (trader status) first. If Play already blocks EU distribution until it is set, and your 2 friends are in the EU, you need (a) before the test starts. If not, start the closed test with option (b) for EU countries only if none of your testers is in the EU; otherwise do (a) now. Record the choice in `docs/decisions/eu-dsa-trader.md` when Story 8.6 runs.

---

## 4. The tester list

Two ways to list testers. You can use both on one track.

- **Email list in Play Console** (Test and release > Testing > Closed testing > track > Testers > Create email list): you type each Gmail address. Simple, and you need the same addresses for licence testing anyway.
- **Google Group** (groups.google.com > Create group): testers join the group. Testers Community may ask you to add their group.

**OWNER DECIDES.** Recommendation: one Play email list named "Closed test" for every tester whose Gmail you have (your 2 friends and each Testers Community tester), plus Testers Community's Google Group only if they require it.

- [ ] If you create a Google Group: name it like `yawn-and-pawn-testers`; "Who can see group": group members; "Who can join": invited users only (or "anyone can ask" if Testers Community needs it); "Who can view members": group managers only, so testers can't see each other's addresses.
- [ ] Plan for at least **15 testers** (your 2 friends + 15 from Testers Community = 17). Play needs **12 opted in for 14 days in a row**; a buffer covers people who drop out.

## 5. Licence testing (free test payments)

Licence testing is for the whole developer account, not one app.

- [ ] Play Console **home** (all apps) > **Settings > License testing** (the story calls it Setup > License testing).
- [ ] Add every tester's Gmail address (an email list here; Google Groups don't work for licence testing, so it needs the individual addresses). Keep your own account on it.
- [ ] **License response: RESPOND_NORMALLY.** Save.
- [ ] Tell testers in writing that snoozes are free test orders only for accounts on this list. `docs/closed-test.md` does this; send them its link.
- [ ] Check once with a friend's phone: the Play purchase sheet shows a test card ("Test card, always approves"). The tester must be signed into Play with the listed account.
- [ ] A tester not on the list would pay real money. If that happens: refund the order in **Order management**, then add them here.

## 6. Build and closed track

- [ ] **Version:** look at Test and release > Testing > **Internal testing** for the highest release. The new tag must be higher (versionCode = X*10000 + Y*100 + Z; for example 0.1.3 is 103).
- [ ] **Tag from `main`:** `git tag vX.Y.Z` then `git push origin vX.Y.Z` (see `docs/ci-release.md`). Check that the GitHub Actions release run is green, has no "this release has no Crashlytics" warning, and uploaded to the internal track.
- [ ] **Smoke test** the internal build from Google Play on your phone: a test alarm rings over the lock screen; a real alarm 2 minutes ahead; one snooze with the test card.
- [ ] **Promote:** Test and release > Testing > Internal testing > Releases > the new release > **Promote release > Closed testing** (the default closed track, often called "Alpha"). Release name: the version. Release notes:

```text
First closed test build. Please read the tester guide we sent you, and keep a second alarm.
```

- [ ] **Countries / regions** tab of the closed track: add the countries your testers are in. **OWNER DECIDES** with the DSA decision in 3.16. Testers Community testers come from many countries: ask them which ones, or add all countries the DSA decision allows.
- [ ] **Testers** tab: tick your email list (and the Testers Community group if used). **Feedback URL or email address:** the Google Form link from `feedback-form.md` (or the support email until the form exists). This turns on Play's private tester feedback. Save.
- [ ] **Send for review:** Publishing overview > **Send changes for review**. The first review of a new app can take several days. Play shows errors here if an App content item or declaration is missing; fix it and send again.
- [ ] When the track is live, copy the **"Join on the web"** opt-in link from the Testers tab (it looks like `https://play.google.com/apps/testing/com.yawnandpawn.app`).

## 7. Fill in `docs/closed-test.md`

- [ ] Replace `[OPT-IN LINK: owner fills in]` with the opt-in link.
- [ ] Replace `[FEEDBACK FORM LINK: owner fills in]` with the Google Form link (build it from `feedback-form.md`).
- [ ] Replace `[START DATE ...]` and `[END DATE ...]`: the end date is the start date + 14 days. The start is the day at least 12 testers have opted in.
- [ ] Commit on a branch with `docs(5.14): fill in the opt-in and feedback links`, open a pull request and merge it.
- [ ] Send testers the GitHub link to `docs/closed-test.md`: https://github.com/vincentbui21/yawn-and-pawn/blob/main/docs/closed-test.md (check the repository path).

## 8. Testers Community (15 testers)

- [ ] Buy the 15-tester package (EUR 14 when this was written). Read their current instructions; they change.
- [ ] Give them what they ask for, usually: the app name, the package name `com.yawnandpawn.app`, the web opt-in link, and the test length (14 days). If they give you a Google Group, add it to the closed track's Testers tab (section 6).
- [ ] **Ask for each tester's Gmail address**, explaining why: "So that snooze payments in the app are free test orders, we add each tester's Google account to Google Play's licence testing. Without it, a snooze would charge real money." Add the addresses to your Play email list (section 4) and to licence testing (section 5). Keep them only in Play Console, never in the repo.
- [ ] If they can't share addresses: tell them in writing (in the message with the guide) never to pay a snooze unless the payment sheet shows a test card. Those testers still count toward the 12; they just test the free path. **OWNER DECIDES** whether that is acceptable; recommendation: accept it, since at least your 2 friends will test payments.
- [ ] Send them the link to `docs/closed-test.md` and ask them to stay opted in until the end date and open the app on most days.

## 9. Track the 14 days

Record in the Story 5.14 file (create it under `_bmad-output/implementation-artifacts/` if it doesn't exist):

- [ ] The start date (the first day with 12 or more opted-in testers) and the planned end date (start + 14 days).
- [ ] The opted-in count on day 1, then once a week until the end. Play Console's **Dashboard** shows the production-access requirement with the opted-in count and days.
- [ ] Read feedback (form, email, Play's tester feedback) **at least twice a week**. Each real problem becomes a bug story that names Story 5.14.
- [ ] If the count drops below 12, the 14 days start again: add testers quickly.
- [ ] The story is done when the test has started with 12 or more opted-in testers. The full 14 days and the production-access application are tracked in Epic 8 (Story 8.9).

---

## All OWNER DECIDES items in this file

| # | Item | Recommendation |
|---|---|---|
| 1 | PRIVACY MISMATCH 1: automatic purchase events | Update the privacy policy and declare Purchase history; no code change |
| 2 | PRIVACY MISMATCH 6: Epic 7 features described ahead of time | Leave, with a note in Story 5.7 (or mark those parts "if your version has ...") |
| 3 | Interim store graphics (2) | Real phone screenshots now; replace in Story 8.2 |
| 4 | App category (2) | Tools |
| 5 | Support address (2) | Keep the published address for the closed test; switch to a dedicated one before production |
| 6 | Data safety: deletion question (3.6) | "Automatically deleted within 90 days" if offered; else Yes plus a policy line |
| 7 | Data safety: Purchase history required or optional (3.6) | Declare it, Required |
| 8 | Data safety: Auto Backup not declared (3.6) | Don't declare; confirm against Google's backup FAQ in Story 8.3 |
| 9 | Health apps (3.9) | No health features |
| 10 | FGS demo video (3.14) | Record the full Story 8.5 video now |
| 11 | EU DSA trader status (3.16) | Check the Console first; declare a non-home business address if any tester is in the EU, otherwise exclude the EU for now |
| 12 | Tester list type (4) | Play email list, plus Testers Community's group only if required |
| 13 | Closed-track countries (6) | The testers' countries, within the DSA decision |
| 14 | Testers who can't be licence testers (8) | Accept, with a written "never pay without a test card" note |
