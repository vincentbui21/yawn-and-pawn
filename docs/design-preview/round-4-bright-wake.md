# Design preview round 4: Bright wake screen (Story 5.2)

**Decision (owner, 2026-10-22): option C, "Ringing only".** The owner first picked A, then changed to C. Options A ("full at once") and B ("sunrise ramp") are dropped from the preview.

The wake screens keep the Sunrise tokens. What the setting changes is **when** the screen is at full brightness:

- **Full brightness:** the Ringing screen, and the snooze confirm sheet over it.
- **The phone's own level:** the checks (also when a check goes loud, and the snooze sheet over a check), the fallback check picker, Snoozed and Success. Also the emergency ring and the moment before the session shows.
- Brightness goes back to the phone's level when the alarm screen is left (Home, Recents, the screen off) and comes back to full when the user returns to the Ringing screen.
- Nothing changes when Extra dim is on and the app can read it. Android 12 and later hide it from apps
  (found in the Story 5.13 check), and then the screen still turns bright.
- Nothing changes when the setting is off: the app does not change the window brightness at all.
- The value is fixed when the alarm fires. Changing it during a session has no effect until the next one.

A screenshot cannot show screen brightness, so "the phone's own level" is simulated with a scrim of the Sunrise `inverse-surface` token. On the phone, the full-brightness state really turns the preview window to full brightness while it shows (window only, as the app does).

Open it on the phone: debug build → design preview → "Round 4 · Bright wake screen", or deep-link a state id from [states.md](states.md), for example `adb shell am start -S -n com.yawnandpawn.app/.debug.preview.PreviewActivity --es state bright-c-ringing`.

## Option C · Ringing only

| State | Screenshots |
|---|---|
| Ringing (full brightness) | `bright_c_ringing_sunrise.png`, `bright_c_ringing_sunrise_font200.png` |
| Math check (the phone's level, simulated) | `bright_c_check_sunrise.png`, `bright_c_check_sunrise_font200.png` |

All screenshot paths are under `androidApp/src/test/screenshots/preview/`.

## The Settings switch

The Settings "Wake" card has the "Bright wake screen" switch, on by default, with the caption "Raises screen brightness while the alarm rings." The old caption, "Raises screen brightness on alarm screens.", no longer fits option C. The new one is listed for the owner's sign-off, and EXPERIENCE.md has it.

- On: `bright_settings_on_light.png`, `bright_settings_on_dark.png`, `bright_settings_on_light_font200.png`
- Off: `bright_settings_off_light.png`, `bright_settings_off_dark.png`

Wake screens always use Sunrise, whatever the app theme, so they have no Light and Dark pair. Their primary states are also shown at 200 % font scale.

Related open item: D2 in EXPERIENCE.md asks whether a torch flash on the first ring should be added. It is not built, because of the photosensitivity concern.
