# CI and releases

## CI (pull requests and pushes to main)

`.github/workflows/ci.yml` runs on GitHub Actions for every pull request and every push to `main`:

1. Tests the secrets guard, then fails if git tracks a keystore or key file (`*.jks`, `*.keystore`, `*.p12`, `*.pem`, `*.pk8`, `*.p8`), `keystore.properties`, `local.properties`, a `.env` file, a service-account JSON or `google-services.json`.
2. `./gradlew qualityGate` (includes `checkDependencyAllowlist` and `checkPermissionAllowlist`).
3. `./gradlew :androidApp:bundleRelease` (R8 release bundle, unsigned).
4. The emulator smoke test on a Gradle Managed Device (ATD image, API 34): `:androidApp:atdApi34DebugAndroidTest`.

When something fails, the job summary lists the failing Gradle tasks, and the test, lint and screenshot reports are attached as the `reports` artifact.

## Releases (tag `vX.Y.Z`)

Push a tag such as `v0.1.0`:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

`.github/workflows/release.yml` checks that the tag is `vX.Y.Z` (minor and patch 0-99, major at most 20999, not v0.0.0), runs `qualityGate`, builds a signed release AAB with `versionName` X.Y.Z (versionCode X*10000 + Y*100 + Z) and uploads it to the Play **internal** track. The signed AAB and R8 `mapping.txt` are kept as a workflow artifact for 90 days. If any secret below is missing, the upload job is skipped with a notice and the run stays green.

**The very first upload to a new Play app must be done by hand in Play Console** (Story 1.4). Play only accepts API uploads after the app exists and has one release. If Play rejects the upload with "Only releases with status draft may be created on draft app", change `status: completed` to `status: draft` in `release.yml` until the app has passed its first review.

## GitHub secrets the release workflow expects

Add them in GitHub: repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.

| Secret | What it holds |
|---|---|
| `UPLOAD_KEYSTORE_BASE64` | The upload keystore file, base64-encoded |
| `UPLOAD_KEYSTORE_PASSWORD` | The keystore password |
| `UPLOAD_KEY_ALIAS` | The key alias inside the keystore |
| `UPLOAD_KEY_PASSWORD` | The key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | The full JSON of a Google Cloud service account with release access to the app in Play Console |

Base64-encode the keystore in PowerShell and paste the result into the secret:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\upload.jks")) | Set-Clipboard
```

Keep the keystore and the service-account JSON outside the repo (or in git-ignored files). Never commit them: CI fails if you do.

## Creating the secrets (once)

1. **Upload keystore.** Run this with the JDK's `keytool` (Android Studio ships one in `jbr\bin`). Pick strong passwords and store them in your password manager together with a backup of the file:

   ```powershell
   keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```

   The alias (`upload`) goes into `UPLOAD_KEY_ALIAS`, and the two passwords go into `UPLOAD_KEYSTORE_PASSWORD` and `UPLOAD_KEY_PASSWORD`. Base64-encode the file with the command above for `UPLOAD_KEYSTORE_BASE64`.
2. **Play App Signing.** In Play Console → the app → **Test and release** → **App integrity** → **App signing**, enrol in Play App Signing and let Google generate the app signing key. Your keystore is then only the *upload* key, and Google can reset it if it is lost or leaked.
3. **Service account.** In Google Cloud Console, create (or choose) a project, enable the **Google Play Android Developer API**, then go to **IAM & Admin** → **Service accounts** → **Create service account**. Open it, go to **Keys** → **Add key** → **JSON**, and paste the whole downloaded file into `PLAY_SERVICE_ACCOUNT_JSON`. Then delete the local copy.
4. **Release access.** In Play Console → **Users and permissions** → **Invite new users**, enter the service account's e-mail address, give it access to Yawn & Pawn only, and grant **Release apps to testing tracks** (and **View app information**).

## If a secret leaks

- **Service-account key:** delete that key in Google Cloud Console (service account → **Keys**), create a new one, and update `PLAY_SERVICE_ACCOUNT_JSON`.
- **Upload keystore or its passwords:** in Play Console → **App integrity** → **App signing**, request an upload key reset and follow the steps. Create a new keystore as in step 1 and update the four `UPLOAD_*` secrets once Google confirms the reset.
- If the secret was committed, removing the file from git is not enough, because it stays in the history. Rotate it as above in every case.

## Building a signed bundle locally (optional)

The release build signs only when all four variables are set; otherwise it stays unsigned:

```powershell
$env:UPLOAD_KEYSTORE_FILE = "C:\path\to\upload.jks"
$env:UPLOAD_KEYSTORE_PASSWORD = "..."
$env:UPLOAD_KEY_ALIAS = "..."
$env:UPLOAD_KEY_PASSWORD = "..."
.\gradlew.bat :androidApp:bundleRelease "-Pyawnandpawn.versionName=0.1.0"
```
