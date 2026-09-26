---
type: review
target: ARCHITECTURE-SPINE.md
lens: technology currency and fit
date: '2026-09-26'
method: 'Web check of official docs, GitHub release pages and mvnrepository (Maven Central, Google Maven, Gradle Plugin Portal and GitHub API were blocked by the proxy)'
---

# Currency Review: Architecture Spine, Pay Per Snooze

Legend: **confirmed** = current and fits · **outdated** = a newer stable exists (correct value given) · **wrong** = claim does not hold as written · **unverified** = could not confirm from a primary source.

## Summary

| Outcome | Items |
|---|---|
| Outdated | Room KMP 2.8.5 (Room 3.0.3 is the current stable KMP line; 2.x is in maintenance), Nav3 CMP 1.1.1 (should be 1.1.2, the version bundled with CMP 1.12.1), MediaPipe tasks-vision 0.10.35 (1.0.0 is out) |
| Wrong / needs change | Roborazzi Gradle plugin does not create tasks in `com.android.kotlin.multiplatform.library` modules (issue open), so "`:composeApp` host tests + Roborazzi verify" does not work as written. Play Billing `setObfuscatedProfileId` expects `setObfuscatedAccountId` to be set too, and AD-7 does not set it |
| Confirmed with caveats | AGP 9.3.3 / Gradle 9.7.1 (newer 9.4.0 / 9.8.0 exist, but Kotlin 2.4.20 is tested only up to AGP 9.3.1 / Gradle 9.7.0, so staying on 9.3.x / 9.7.x is right), detekt 2.0.0-alpha.5 (still alpha; stable is 1.23.8), mediaPlayback FGS on Android 17 (needs exact-alarm permission + `USAGE_ALARM` once targetSdk is 37), Room/DataStore in device-protected storage (pass an absolute path; do not call `.applicationContext` on the DP context) |
| Unverified | Whether Play always fills `AccountIdentifiers` for every purchase path (pending→purchased outside the app, promo codes); ImageEmbedder API unchanged in tasks-vision 1.0.0; Spotless 8.8.0 being the latest (only saw releases through June 29) |

## Stack table

| # | Item | Spine | Finding | Verdict | Source |
|---|---|---|---|---|---|
| 1 | Kotlin | 2.4.20 | Latest stable, 2026-09-07 (tooling release). 2.5.0 is planned for Dec 2026 | confirmed | https://kotlinlang.org/docs/releases.html |
| 2 | Compose Multiplatform | 1.12.1 | Released 2026-09-22, latest stable (1.13.0-alpha01 is a pre-release). Built on Jetpack Compose 1.12.1, Lifecycle 2.11.0, Navigation3 1.1.2 | confirmed | https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1 , https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html |
| 3 | AGP | 9.3.3 | 9.3.3 is the latest 9.3 patch. AGP 9.3 needs Gradle 9.5.0 or newer and supports API up to 37. AGP 9.4.0 (Sept 2026, needs Gradle 9.6.0+) is newer, but Kotlin 2.4.20 is fully supported only up to AGP 9.3.1. Keep 9.3.3 | confirmed (newer 9.4.0 exists; do not adopt until KGP supports it) | https://developer.android.com/build/releases/past-releases/agp-9-3-0-release-notes , https://developer.android.com/build/releases/gradle-plugin , https://kotlinlang.org/docs/gradle-configure-project.html |
| 4 | Gradle | 9.7.1 | Released 2026-08-19. 9.8.0 came out 2026-09-24, but KGP 2.4.20 is tested up to 9.7.0. 9.7.1 is a patch of the tested line | confirmed (newer 9.8.0 exists; do not adopt yet) | https://gradle.org/releases/ , https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html |
| 5 | JDK | 17 | Minimum and default for AGP 9.3/9.4 | confirmed | AGP release notes above |
| 6 | compileSdk/targetSdk/minSdk | 37/36/26 | AGP 9.3 supports API 37. Deferring targetSdk 37 matches the Android 17 background-audio hardening (see B-2) | confirmed | https://developer.android.com/about/versions/17/behavior-changes-17 |
| 7 | KSP | 2.3.11 | Latest, 2026-08-03. KSP2 versions are no longer tied to the Kotlin version | confirmed | https://github.com/google/ksp/releases |
| 8 | Room KMP | 2.8.5 | 2.8.5 (2026-09-09) is the latest **2.x**. But **Room 3.0** (`androidx.room3:room3-*`, KMP-first, coroutines-only, KSP-only, SQLiteDriver required) went stable 2026-07-01, and **3.0.3** (2026-09-09) is the current stable. The docs say Room 2.x is in maintenance mode, and the official KMP setup page now shows `room3`. On a greenfield KMP project, choose Room 3.0.3 (plugin `androidx.room3`, `room3 { schemaDirectory(...) }`), or record a reason for staying on 2.x | outdated → **Room 3.0.3** (`androidx.room3`) | https://developer.android.com/jetpack/androidx/releases/room3 , https://developer.android.com/kotlin/multiplatform/room , https://developer.android.com/jetpack/androidx/releases/room |
| 9 | DataStore | 1.2.1 | Latest stable (2026-03-11). 1.3.0-alpha11 is a pre-release | confirmed | https://developer.android.com/jetpack/androidx/releases/datastore |
| 10 | kotlinx-coroutines | 1.11.0 | Latest stable on the releases page | confirmed | https://github.com/Kotlin/kotlinx.coroutines/releases |
| 11 | kotlinx-datetime | 0.8.0 | Latest stable. `Instant`/`Clock` were removed in favour of `kotlin.time`, which matches AD-3 and the Time convention | confirmed | https://github.com/Kotlin/kotlinx-datetime/releases |
| 12 | kotlinx-serialization | 1.11.0 | Latest stable | confirmed | https://github.com/Kotlin/kotlinx.serialization/releases |
| 13 | Navigation 3 (JetBrains CMP) | 1.1.1 | The docs page still shows 1.1.1, but CMP 1.12.1 lists **Navigation3 1.1.2**, and dependabot/renovate bumps from 1.1.1 to 1.1.2 exist | outdated → **1.1.2** | https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.1 , https://github.com/Q42/Template.ComposeMultiplatform/pull/239 |
| 14 | Koin | 4.2.2 | Latest stable (June 15). Maintenance release for 4.2.x | confirmed | https://github.com/InsertKoinIO/koin/releases |
| 15 | Play Billing Library | 9.1.0 | Latest (2026-06-18). Play requires v8+ from 2026-08-31 | confirmed | https://developer.android.com/google/play/billing/release-notes |
| 16 | CameraX | 1.6.2 | Latest stable (2026-08-26). 1.7.0-alpha03 is a pre-release | confirmed | https://developer.android.com/jetpack/androidx/releases/camera |
| 17 | ML Kit barcode-scanning (bundled) | 17.3.0 | Still the latest (Aug 2024); the integration guide shows `barcode-scanning:17.3.0` | confirmed | https://developers.google.com/ml-kit/release-notes , https://developers.google.com/ml-kit/vision/barcode-scanning/android |
| 18 | MediaPipe tasks-vision | 0.10.35 | 0.10.35 (2026-04-27) was the last 0.10.x. **1.0.0** was released 2026-07-27. The ImageEmbedder Android guide uses `latest.release` and shows no deprecation | outdated → **1.0.0** (let Spike S3 confirm the ImageEmbedder API) | https://mvnrepository.com/artifact/com.google.mediapipe/tasks-vision , https://github.com/google-ai-edge/mediapipe/releases/tag/v1.0.0 , https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder/android |
| 19 | Firebase BoM | 34.19.0 | Latest (2026-09-09) | confirmed | https://firebase.google.com/support/release-notes/android |
| 20 | Kover | 0.9.8 | Latest. Includes Android multiplatform library fixes | confirmed | https://github.com/Kotlin/kotlinx-kover/releases |
| 21 | detekt | 2.0.0-alpha.5 | Latest 2.0 pre-release (2026-06-17), built against Kotlin 2.4.0. Stable is still 1.23.8, which is too old for Kotlin 2.4. The alpha is the only realistic option, and the Deferred note is correct | confirmed (alpha risk acknowledged) | https://github.com/detekt/detekt/releases |
| 22 | Spotless | 8.8.0 | gradle/8.8.0 (June 29) is the newest release seen | confirmed (no newer release seen) | https://github.com/diffplug/spotless/releases |
| 23 | Roborazzi | 1.74.0 | Latest release. But see B-7: the Gradle plugin does not work with android-kmp-library modules | confirmed (version); fit: see B-7 | https://github.com/takahirom/roborazzi/releases |
| 24 | Turbine | 1.2.1 | Latest | confirmed | https://github.com/cashapp/turbine/releases |

## Behavioural claims

### B-1: Room KMP DB in device-protected storage (AD-6): confirmed with caveat
Room's KMP Android builder takes `(context, name = absolutePath)`. `createDeviceProtectedStorageContext()` returns a normal `Context` whose `getDatabasePath()` points to DE storage. Build the database with `Room.databaseBuilder<AppDb>(context = dpContext, name = dpContext.getDatabasePath("app.db").absolutePath)` and use `BundledSQLiteDriver`.
- **Pitfall:** the official snippet calls `context.applicationContext` first. Calling `.applicationContext` on a DP context returns the normal credential-protected app context. Always derive the path from the DP context.
- **Pitfall:** the same applies to DataStore. Use `PreferenceDataStoreFactory.createWithPath { dpContext.filesDir... }`, not the `preferencesDataStore` delegate, which resolves paths through `applicationContext`.
- For Room 3 (see item 8) the builder shape is the same: `Room.databaseBuilder<T>(context, name)` with a driver.
- Sources: https://developer.android.com/kotlin/multiplatform/room , https://developer.android.com/privacy-and-security/direct-boot

### B-2: mediaPlayback FGS started from an exact alarm (`setAlarmClock`) on Android 14–17 (AD-5): confirmed with conditions
- Exact alarms are listed as an exemption from background FGS-start restrictions: "exact alarms aren't affected by foreground service launch restrictions".
- `setAlarmClock` still needs an exact-alarm permission. `SCHEDULE_EXACT_ALARM` is denied by default for new installs targeting 33+ on Android 14. `USE_EXACT_ALARM` is auto-granted but restricted by Play policy to alarm/calendar apps. **The spine names neither permission.** Add `USE_EXACT_ALARM` (alarm clock is the core function) and/or `SCHEDULE_EXACT_ALARM` with a `canScheduleExactAlarms()` check.
- Declare `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and complete the Play Console FGS declaration (video + description).
- **Android 17 background audio hardening:** on all apps on Android 17, background audio needs a visible activity or a non-`shortService` FGS; WakeService satisfies this. Apps **targeting 37** also need either WIU capabilities (a background-started FGS does not get them) or the exact-alarm permission while using `USAGE_ALARM` streams. The spine's `USAGE_ALARM` + exact alarm design satisfies the exemption, but only if the exact-alarm permission is actually granted. This is what the Deferred item "targetSdk 37" must test, including audio focus and volume calls, which the rule also covers.
- Alternative worth recording: FGS type `systemExempted` may be used by apps holding `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`. `mediaPlayback` is still acceptable.
- Sources: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start , https://developer.android.com/develop/background-work/services/alarms , https://developer.android.com/about/versions/17/changes/bg-audio , https://developer.android.com/develop/background-work/services/fgs/service-types

### B-3: Full-screen intent auto-grant for alarm apps (AD-5): confirmed with condition
On Android 14+, `USE_FULL_SCREEN_INTENT` is auto-granted only to apps whose core function is alarms or calls, **and only after the Play Console App Content declaration is made**. Since 2025-01-22, apps not pre-approved must ask the user. The user can still revoke it, so check `NotificationManager.canUseFullScreenIntent()` and deep-link `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` in the reliability checklist. The April 2026 policy preview keeps the alarm auto-grant.
- Sources: https://developer.android.com/about/versions/14/behavior-changes-14 , https://support.google.com/googleplay/android-developer/answer/13392821 , https://support.google.com/googleplay/android-developer/answer/16965181

### B-4: LOCKED_BOOT_COMPLETED + directBootAware receivers (AD-4): confirmed
The documented pattern is `android:directBootAware="true"` on the receiver with a `LOCKED_BOOT_COMPLETED` filter, with data in DE storage. Caveat: every component on the pre-unlock ring path must also be `directBootAware`: alarm receiver, `WakeService`, `WakeActivity`, and the `Application`/Koin init. Anything touching credential-protected storage must be deferred: Firebase init, media files (custom sounds and recordings must fall back to a bundled default sound before first unlock).
- Source: https://developer.android.com/privacy-and-security/direct-boot

### B-5: Android 15 blocks starting a mediaPlayback FGS from BOOT_COMPLETED (AD-4): confirmed
For apps targeting 35+, `BOOT_COMPLETED` receivers cannot launch `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` or `microphone` FGS; doing so throws `ForegroundServiceStartNotAllowedException`. The spine's "re-arm via exact alarm, no FGS from boot" is correct.
- Source: https://developer.android.com/about/versions/15/behavior-changes-15

### B-6: Play Billing `obfuscatedProfileId` in `Purchase.getAccountIdentifiers()` (AD-7): confirmed, with a wrong detail
`AccountIdentifiers.getObfuscatedProfileId()` returns the value set via `BillingFlowParams.Builder.setObfuscatedProfileId()` (max 64 chars; a UUID of 36 fits). **However**, the `setObfuscatedProfileId` docs say it "requests the user's obfuscated account id to be passed via `setObfuscatedAccountId`". AD-7 sets only the profile id. Fix: also set `obfuscatedAccountId` to a stable, non-PII install id (for example a random UUID stored in DE storage). Play also uses these ids for fraud heuristics. A per-session profile id is allowed (no PII) but unusual.
- Unverified: whether `getAccountIdentifiers()` is non-null for every path the reconciler handles (pending purchases completed outside the app, promo redemptions). The reconciler should treat a null or mismatched id as `OfferReuse`/`LeaveForAutoRefund`, not as a crash.
- Sources: https://developer.android.com/reference/com/android/billingclient/api/AccountIdentifiers , https://developer.android.com/reference/com/android/billingclient/api/BillingFlowParams.Builder

### B-7: AGP 9 KMP module layout (android-kmp-library plugin, separate androidApp): confirmed
With AGP 9+, the KMP plugin is incompatible with `com.android.application`/`com.android.library` in the same module. Shared modules must use `com.android.kotlin.multiplatform.library`, and the app is a separate `com.android.application` module (`androidApp`). This matches the spine exactly.
- Notes: host tests are off by default (`withHostTest { isIncludeAndroidResources = true }`, source set `androidHostTest`). Android resources are off by default (`androidResources { enable = true }`), which is required for Compose resources. There are no build types, flavors or BuildConfig in library modules, so debug-only receivers and Firebase projects belong in `:androidApp`, which the spine already does. The docs now use the `kotlin { android { } }` block; `androidLibrary { }` appears in older snippets.
- Sources: https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html , https://developer.android.com/kotlin/multiplatform/plugin

### B-8: Roborazzi with AGP 9 / android-kmp-library host tests (AD-14): wrong as written
Roborazzi issue #757 ("No tasks for Android Gradle Library Plugin for KMP", high priority) is **open**: with `com.android.kotlin.multiplatform.library` and `androidHostTest`, the Roborazzi Gradle plugin creates no tasks. A 2026 project PR also found that applying the plugin broke Compose imports in `androidHostTest` and abandoned it. The README has no android-kmp-library setup. "`:composeApp` host tests + Roborazzi verify" in `qualityGate` will not work as written.
- Options:
  - (a) Put Roborazzi screenshot tests in `:androidApp` (`com.android.application`, where the plugin works) and render `:composeApp` composables there.
  - (b) Use Roborazzi's runtime library without the Gradle plugin, in `androidHostTest`, driven by the `roborazzi.test.record`/`roborazzi.test.verify` system properties. This is unverified with AGP 9.
  - (c) Add a `jvm()` desktop target and use Roborazzi's Compose Desktop support.
- Record this as a spike or story risk.
- Sources: https://github.com/takahirom/roborazzi/issues/757 , https://github.com/ai-kurou/KoDriver/pull/1545 , https://github.com/takahirom/roborazzi

### B-9: Compose Multiplatform resources for strings (AD-11): confirmed with notes
Put strings in `commonMain/composeResources/values/strings.xml`, add the `compose.components.resources` dependency, and use `stringResource(Res.string.x)`. With android-kmp-library you must set `androidResources.enable = true`. For non-composable callers (WakeService notification text, `MoneyFormatter` copy), use the suspend `getString(Res.string.x)`. Robolectric/host tests need `isIncludeAndroidResources = true`.
- Source: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html

### B-10: Nav3 `subclassesOfSealed` (AD-11): confirmed, experimental
The official CMP Nav3 docs register routes with `SavedStateConfiguration { serializersModule = SerializersModule { polymorphic(NavKey::class) { subclassesOfSealed<Route>() } } }` and pass it to `rememberNavBackStack(config, ...)`. `subclassesOfSealed` is annotated `@ExperimentalSerializationApi`, so an opt-in is required. It throws if any subclass is an open polymorphic class, so keep all `Route` leaves `@Serializable` data objects/classes.
- Sources: https://kotlinlang.org/docs/multiplatform/compose-navigation-3.html , https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization.modules/-polymorphic-module-builder/subclasses-of-sealed.html

## Suggested spine edits (for the owner; spine not modified)
1. Stack: Room KMP → Room 3.0.3 (`androidx.room3`), or record why 2.8.5 stays; Nav3 → 1.1.2; MediaPipe tasks-vision → 1.0.0 (gated by Spike S3).
2. AD-7: set `obfuscatedAccountId` (stable install id) as well as `obfuscatedProfileId = sessionId`.
3. AD-5: name the exact-alarm permission (`USE_EXACT_ALARM`), `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and the Play Console FSI/FGS declarations. List all directBootAware components.
4. AD-14: change where Roborazzi runs (`:androidApp`, or plugin-less), because the Roborazzi plugin does not support android-kmp-library.
5. Stack note: pin AGP ≤ 9.3.x and Gradle ≤ 9.7.x until a Kotlin release lists AGP 9.4 / Gradle 9.8 as supported.
