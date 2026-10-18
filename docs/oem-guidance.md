# Manufacturer background guidance

Story 5.5. Some makers ship their own battery tools that stop apps in the background, even when Android's own battery
optimization exemption is on. The reliability checklist's "Manufacturer settings" row shows on the six makers below and
opens their steps: 2 to 4 numbered steps, "Open settings" and "I've done this".

- **Code:** the maker mapping is `DeviceMaker` (`core/.../reliability/DeviceMaker.kt`); the steps are
  `DeviceMaker.guidanceSteps()` (`composeApp/.../ui/reliability/ManufacturerGuidance.kt`) over the `reliability_*_step*`
  strings; the screens "Open settings" tries are `OemIntents` (`androidApp/.../reliability/OemIntents.kt`), and every
  package they live in is listed under `<queries>` in `androidApp/src/main/AndroidManifest.xml`.
- **Status:** Xiaomi's steps are EXPERIENCE.md long-form copy. Every other step is an **owner draft** (not yet in
  EXPERIENCE.md). Only the Oppo/ColorOS steps and target get a device check (Oppo A96, Story 5.13); the other makers
  are unverified on a device.
- **Last reviewed:** 2026-10-16 (the dontkillmyapp.com maker pages were read that day; none shows an update date).

## How a phone is matched

`Build.MANUFACTURER` first, then `Build.BRAND`, exact alias match in any case with spaces trimmed. Anything else is
Other: the row is Hidden and the screen, if opened, shows no steps (its buttons still open the app details).

| Maker | Aliases |
|-------|---------|
| Xiaomi | xiaomi, redmi, poco |
| Samsung | samsung |
| Huawei | huawei, honor (an Honor phone after the 2020 split still gets the Huawei steps) |
| Oppo/Realme | oppo, realme |
| Vivo | vivo, iqoo |
| OnePlus | oneplus |

## "Open settings"

1. The maker's screens below, in order. A screen is started only when the phone resolves it (`resolveActivity`); one
   that throws is logged and the next is tried.
2. Otherwise the app details (`ACTION_APPLICATION_DETAILS_SETTINGS`).
3. Otherwise the system settings (`ACTION_SETTINGS`).

Every attempt starts as a new task. A phone with none of them logs it and never crashes. The component names are
community-collected and change between OS versions, which is why each one is resolved first and the app details are
the fallback.

## "I've done this"

Stores the maker (`Build.MANUFACTURER`) and the build fingerprint (`Build.FINGERPRINT`), and adds the row to the rows
seen OK. The row is then OK until a system update changes the fingerprint; it then reads Revoked: "Alarm may not ring:
check your phone's background settings again". The row never blocks an alarm and never raises Home's banner.

## Steps per maker

Each step is at most 25 words. "Lock the app in Recents so it stays open." (EXPERIENCE.md, Xiaomi step 3) ends every
maker's list.

### Xiaomi, Redmi, POCO (MIUI, HyperOS)

1. "Turn on Autostart for Yawn & Pawn." (EXPERIENCE.md)
2. "Set Battery saver to “No restrictions”." (EXPERIENCE.md)
3. "Lock the app in Recents so it stays open." (EXPERIENCE.md)

- **Screens:** `com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity` (Autostart),
  `com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity` (battery saver per app).
- **Sources:** dontkillmyapp.com/xiaomi; the AutoStarter library (github.com/judemanutd/AutoStarter, MIT).
- **Versions:** written for MIUI and HyperOS; menu names not checked version by version; unverified on a device.

### Samsung (One UI)

1. "In Battery, open Background usage limits and add Yawn & Pawn to “Never sleeping apps”." (owner draft)
2. "Turn off “Put unused apps to sleep”." (owner draft)
3. "Lock the app in Recents so it stays open."

- **Screens:** `com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity`, then
  `com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity` (Device care, Battery).
- **Sources:** dontkillmyapp.com/samsung; the AutoStarter library.
- **Versions:** written for recent One UI ("Background usage limits" under Battery); menu names not checked version by
  version; unverified on a device.

### Huawei, Honor (EMUI, Magic UI)

1. "In App launch, set Yawn & Pawn to “Manage manually”." (owner draft)
2. "Turn on Auto-launch, Secondary launch and Run in background." (owner draft)
3. "Lock the app in Recents so it stays open."

- **Screens:** `com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity`,
  `com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity`,
  `com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity`.
- **Sources:** dontkillmyapp.com/huawei; the AutoStarter library.
- **Versions:** written for EMUI ("App launch" under Battery); whether newer Honor phones (Magic UI) keep the same
  menu is not checked; unverified on a device.

### Oppo, Realme (ColorOS, Realme UI)

1. "In the app's Battery usage, turn on “Allow background activity”." (owner draft)
2. "Turn on “Allow auto launch” on the same screen." (owner draft)
3. "Lock the app in Recents so it stays open."

- **Screens:** `com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity`,
  `com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity`,
  `com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity`. On newer ColorOS none of these may resolve:
  the fallback is the app details screen, where "Battery usage" sits, so step 1 starts there.
- **Sources:** dontkillmyapp.com/oppo (written for an older model, the F1S) and dontkillmyapp.com/realme ("Allow
  auto-launch", "Allow background activity" under App battery management); the ColorOS 13 menu path Settings > Apps >
  App management > the app > Battery usage from techbone.de's ColorOS guides (in German); the AutoStarter library.
- **Versions:** written for recent ColorOS and Realme UI (App info, Battery usage); **Story 5.13 checks the steps and
  the "Open settings" target on the owner's Oppo A96.**

### Vivo, iQOO (Funtouch OS, OriginOS)

1. "In Battery, allow “High background power consumption” for Yawn & Pawn." (owner draft)
2. "Turn on Autostart for Yawn & Pawn." (owner draft)
3. "Lock the app in Recents so it stays open."

- **Screens:** `com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity`,
  `com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager`,
  `com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity`.
- **Sources:** dontkillmyapp.com/vivo; the AutoStarter library.
- **Versions:** written for recent Funtouch OS and OriginOS; menu names not checked version by version; unverified on
  a device.

### OnePlus (OxygenOS)

1. "In the app's Battery usage, turn on “Allow background activity”." (owner draft)
2. "In Battery optimization, turn off “Advanced optimization” if your phone has it." (owner draft)
3. "Lock the app in Recents so it stays open."

- **Screens:** `com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity`. On OxygenOS
  built on ColorOS it may not resolve: the fallback is the app details screen, where "Battery usage" sits.
- **Sources:** dontkillmyapp.com/oneplus; the AutoStarter library.
- **Versions:** written for recent OxygenOS ("Advanced optimization" exists on some versions only, hence "if your
  phone has it"); unverified on a device.

## Keeping it current

- A maker screen that stops resolving costs nothing but the shortcut: the app details open instead. Remove a name only
  when a source says it is gone everywhere.
- A new package needs its `<package>` entry in the manifest's `<queries>`; `ManufacturerSettingsTest` fails until both
  lists match. `<queries>` entries are package visibility only, not permissions.
- A changed step needs the owner's sign-off and, once approved, its line in EXPERIENCE.md long-form copy. Review this
  page when a maker ships a major OS version.
