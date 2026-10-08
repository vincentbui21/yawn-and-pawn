# The app's package name

- **Date:** 2026-10-08 (Story 4.1; the name itself was set in Story 1.4 with the Play Console app record)
- **Package name:** `com.yawnandpawn.app`
- **Where it is used:**
  - `applicationId` in `androidApp/build.gradle.kts`, for every build type. The debug build has no suffix, because Play Billing test purchases need the real id.
  - The Play Console app record (Story 1.4), the internal track and the Spike S1 product `spike_s1_test`.
  - `tools/play-catalog` (`SnoozeCatalog.PACKAGE_NAME`), which lists and writes the app's one-time products.

## Why it never changes

Play identifies an app by its package name for good: once an app is uploaded, the name can't change. The products, purchases, license testers and reviews all hang on it. A new package name would mean a new app in Play Console.

## Kept in line

`PackageIdTest` in `tools/play-catalog` fails when this file, the `applicationId` and the tool's constant disagree.
