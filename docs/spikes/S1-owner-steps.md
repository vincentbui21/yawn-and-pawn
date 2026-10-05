# Spike S1: owner steps

These are the steps to run Spike S1 ("pay for a snooze over the lock screen") on your Oppo A96. Write the results into [S1.md](S1.md).

The prototype is on branch `spike/s1-billing-lockscreen` only. That branch is never merged to `main`. The spike build is the normal app (versionCode **101**, versionName **0.1.1-spike**) with one extra launcher icon, **Spike S1**.

## 1. Check the payments profile

1. Open [Play Console](https://play.google.com/console).
2. Go to **Settings** (gear icon, left menu), then **Payments profile**. It must show an active payments profile (merchant account). If it asks you to create one, finish that first. Without it you cannot create in-app products.
3. Go to **Settings**, then **License testing**. Make sure the Google account on the Oppo is in the list and **License response** is `RESPOND_NORMALLY`. This makes your purchases test purchases (no real charge) and gives you the test cards.

## 2. Build and sign the spike bundle

Run this in PowerShell in your normal checkout. You type the passwords yourself; they stay in this PowerShell window only and are removed at the end.

```powershell
cd C:\Users\BuiTua\Personal\yawn-and-pawn
git fetch origin
git switch spike/s1-billing-lockscreen
$env:JAVA_HOME = "C:\Users\BuiTua\AppData\Local\Programs\jdk17\jdk-17.0.20.1+1"
$env:UPLOAD_KEYSTORE_FILE = "C:\path\to\upload.jks"
$env:UPLOAD_KEY_ALIAS = "upload"
$env:UPLOAD_KEYSTORE_PASSWORD = [Net.NetworkCredential]::new("", (Read-Host "Upload keystore password" -AsSecureString)).Password
$env:UPLOAD_KEY_PASSWORD = [Net.NetworkCredential]::new("", (Read-Host "Upload key password" -AsSecureString)).Password
.\gradlew.bat :androidApp:bundleRelease
Remove-Item Env:UPLOAD_KEYSTORE_PASSWORD, Env:UPLOAD_KEY_PASSWORD, Env:UPLOAD_KEYSTORE_FILE, Env:UPLOAD_KEY_ALIAS
& "$env:JAVA_HOME\bin\jarsigner.exe" -verify androidApp\build\outputs\bundle\release\androidApp-release.aab
git switch main
```

- Replace `C:\path\to\upload.jks` and `upload` with your upload keystore and alias (the ones from Story 1.4). If you made the keystore with the `keytool` command in `docs/ci-release.md`, the key password is the same as the keystore password.
- Do not pass `-Pyawnandpawn.versionName`: the branch already sets `0.1.1-spike` and versionCode 101.
- The last check must print `jar verified.` If it says the jar is unsigned, the four variables were not all set.
- The bundle is `androidApp\build\outputs\bundle\release\androidApp-release.aab`.
- `git switch` needs a clean working tree. If it refuses, commit or set aside your local changes first.

## 3. Upload it to Internal testing as release 101

1. Play Console → **Yawn & Pawn** → **Test and release** → **Testing** → **Internal testing**.
2. **Create new release**. Upload `androidApp-release.aab`. Play shows **101 (0.1.1-spike)**.
3. Release name: `101 (0.1.1-spike) Spike S1`. Release notes: `Spike S1 prototype. Internal testing only.`
4. **Next**, then **Save and publish** (or **Start rollout to Internal testing**).

> Important: Play never accepts the same versionCode twice. After this spike, the next release from `main` must be versionName **0.1.2 or later** (versionCode 102+), because 0.1.1 would be 101 again.

## 4. Create the test product `spike_s1_test`

Play only lets you create in-app products once a bundle with the billing permission has been uploaded, so do this after step 3.

1. Play Console → **Yawn & Pawn** → **Monetize with Play** → **Products** → **One-time products** → **Create one-time product**.
2. **Product ID:** `spike_s1_test` (exactly this; it cannot be changed later).
3. **Name:** `Spike S1 test`. **Description:** `Test product for Spike S1. Not for sale.`
4. Add a purchase option of type **Buy** (the default one is fine). Set the price to the **lowest price Play allows** in USD (if you type a price that is too low, Play shows the minimum) and let Play fill in the other countries.
5. If you see an option for pending transactions (paying later, for example with cash), keep it **allowed**. Leave multi-quantity purchases **off**.
6. **Save**, then **Activate** the product (and the purchase option, if it has its own switch).

There is no "consumable" switch in Play: the app makes it consumable by consuming it (the **Consume all** button).

## 5. Install the spike build on the Oppo

1. Uninstall the debug app first: **Settings → Apps → Yawn & Pawn → Uninstall**. The debug app is signed with a different key, so the Play version cannot install over it. (This removes your alarms in the debug app.)
2. In Play Console → **Internal testing** → **Testers**, copy the opt-in link. Open it on the Oppo with the Google account that is a license tester, tap **Accept** (if asked), then **Download it on Google Play** and install.
3. In the Play Store page, check that the version is **0.1.1-spike**. If it still shows 0.1.0, wait a few minutes and reopen the Store.
4. You now have two icons: **Yawn & Pawn** and **Spike S1**.

Set a PIN or pattern screen lock if the phone does not have one (the locked runs need a real keyguard).

## 6. Run the spike

### Recording the evidence

- **Log:** connect the phone by USB and, before each block of runs, start
  `adb logcat -c` then `adb logcat -v time -s SpikeS1 > s1-<block>.txt`.
  Without a computer, tap **Share log** at the end of a block and send it to yourself.
- **Video:** for locked runs, film the screen with another phone. The built-in screen recorder may show the Play sheet as black.
- After each run, write one row in the **Runs** table in `S1.md`.

### Before every purchase run

1. Open **Spike S1**. The alarm sound starts. Tap **Stop sound**.
2. Tap **Ring in 15 s** (the first time it asks for notification permission: allow it, then tap **Ring in 15 s** again).
3. For a **locked** run: press the power button now and wait. After 15 s the screen turns on, the sound plays and Spike S1 shows over the lock screen with **LOCKED: YES**.
   For an **unlocked** run: stay on the Spike S1 screen; it rings after 15 s.
4. Wait until **Billing: CONNECTED** and **Product: loaded** show.

### After every purchase

Tap **Query purchases** (this checks `obfuscatedProfileId` on the query, question 5), then **Consume all**. If you forget, the next purchase fails with `ITEM_ALREADY_OWNED`.

### Runs

| Run | Lock | What you do |
|---|---|---|
| L1–L5 | locked | Tap **Pay**. Unlock with your PIN when asked. In the Play sheet, pick **Test card, always approves** and buy. Then Query purchases, Consume all. |
| U1–U5 | unlocked | Tap **Pay**. Buy with **Test card, always approves**. Then Query purchases, Consume all. |
| V1 | locked | Tap **Pay without unlock**. Note what you see: the Play sheet over the lock screen, an unlock prompt, or nothing. If the sheet shows, buy with the approving card (question 1). |
| V2 | locked | Tap **Pay**, then cancel the unlock prompt (Back, or swipe it away). Note whether the sound keeps playing. |
| V3 | locked | Tap **Pay**, enter a wrong PIN twice, then the right one. Note which callback the log shows. |
| V4 | locked | Tap **Pay**, unlock, then close the Play sheet without buying. |
| C1 | locked | Tap **Pay**, unlock, pick **Test card, always declines**. |
| C2 | locked | Tap **Pay**, unlock, pick **Slower test card, approves after a few minutes**. The result is first `PENDING`. Keep the app open and wait (5–15 minutes); note whether a later `onPurchasesUpdated` with `PURCHASED` arrives on its own. Then Query purchases (it should show `PURCHASED` and the profile id), and Consume all. |
| P1 | locked | Promo code (question 5d, optional if Play does not offer it for this product): in Play Console → **Monetize with Play** → **Promo codes**, create one code for `spike_s1_test`. Tap **Pay**, unlock, and in the Play sheet choose **Redeem code**, enter the code. Then Query purchases, Consume all. |
| N1 | locked | Turn on **airplane mode** (Wi-Fi off too). Tap **Ring in 15 s**, lock, then tap **Pay** and unlock. Note what happens. Turn airplane mode off. |
| N2 | locked | Tap **Pay**, unlock, and when the Play sheet is open turn on airplane mode from the quick settings, then try to buy. Turn airplane mode off. |

While each sheet is open, listen: does the alarm keep ringing at full volume? The log's `sound monitor` lines say `playing=true alarmVolume=x/y` every 2 s.

The screen's **Pay→result** line shows the number of results, median and max since the app was opened. The `RESULT ... payToResultMs=` lines in the log have every value.

## 7. After the spike

1. Play Console → **Monetize with Play** → **Products** → **One-time products** → `spike_s1_test` → **Deactivate**. Write the date in `S1.md`.
2. Fill in the **Decision** section of `S1.md` and commit it to `main` (only `docs/spikes/S1.md` and this file, not the prototype code).
3. You can keep the spike build installed; the next internal release (0.1.2 or later) replaces it. To go back to the debug app, uninstall the Play version first.
