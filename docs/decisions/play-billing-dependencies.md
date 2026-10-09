# Play Billing and its location libraries

- **Date:** 2026-10-09 (Story 4.12; owner decision 3 of 2026-10-08)
- **Decision:** Play Billing Library 9.1.0 ships with its transitive `play-services-location` and `play-services-places-placereport`. They are not excluded. Default taken in the Epic 4 auto-run, owner can change.
- **Why not exclude them:** `billing-9.1.0.pom` declares them, and Google does not document running Billing without them. Excluding them risks a runtime failure inside Billing, on the payment path.

## What the app adds (resolved release runtime classpath, 2026-10-09)

| Coordinate | Version | Licence | Network | Notes |
|---|---|---|---|---|
| `com.android.billingclient:billing` | 9.1.0 | Android SDK License / Google Play terms (proprietary) | Only through the Play Store app (bound service) | Connects only after the first unlock (AD-15) |
| `com.android.billingclient:billing-ktx` | 9.1.0 | same | none of its own | Kotlin extensions |
| `com.google.android.gms:play-services-location` | 19.0.0 | Google Play services SDK terms (proprietary) | none called by the app | Never called; no location permission |
| `com.google.android.gms:play-services-places-placereport` | 17.0.0 | same | none called by the app | Comes with `play-services-location` |

Billing's other dependencies were already on the classpath and in `config/dependency-allowlist.txt`: `play-services-base` (CameraX/ML Kit, Story 3.10), `play-services-basement`, `play-services-tasks` and the `datatransport` stack (Crashlytics, Story 1.19).

**Merged manifest:** Billing adds only `com.android.vending.BILLING`, which `config/permission-allowlist.txt` already lists. It also adds a `<queries>` entry for Play's billing service and two non-exported proxy activities. No location permission (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`) is merged. `checkPermissionAllowlist` fails the build if one ever appears.

## For the Data safety form (Epic 8)

- **Purchase history:** collected by Google Play for the in-app purchases (snoozes). The app keeps a hash of each token and the price on the phone only (Story 4.10). It does not share them.
- **App info and performance, device or other IDs:** Play Billing and the `datatransport` stack it shares with Crashlytics may log diagnostics to Google. Declare them as Google Play's own processing, under the same entry as Crashlytics.
- **Location:** not collected. The location libraries ship inside Billing, but the app never calls a location API and holds no location permission. If Play Console's SDK index flags them, answer "the SDK is present but no location data is collected".
- **Identifiers sent to Play:** `obfuscatedAccountId` is a random install id (Story 4.8; never backed up) and `obfuscatedProfileId` is a random session id. Neither is personal data.

## Open-source notices

Play Billing and the Play services libraries are proprietary Google SDKs. List them, with WorkManager (Apache-2.0, Story 4.3), when the notices screen is built (Epic 5/8).

## Single-quantity products

The adapter ignores `Purchase.getQuantity()`: one purchase grants one snooze and is consumed once (Story 4.10). Multi-quantity must stay off on every snooze product's Buy option. `tools/play-catalog` creates them that way and flags any that is not (see its README); never turn it on in Play Console.
