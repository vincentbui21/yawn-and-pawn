# Design preview round 4: Bright wake screen (Story 5.2)

A mock-up for the owner to choose from. It is not the feature: nothing outside the debug design preview changed.

The wake screens keep the Sunrise tokens in every option. What changes is **when** the screen is at full brightness. A screenshot cannot show screen brightness, so "the phone's own level" is simulated with a scrim of the Sunrise `inverse-surface` token. On the phone, the full-brightness states really turn the preview window to full brightness while they show (window-only, as the story specifies).

Open it on the phone: debug build → design preview → "Round 4 · Bright wake screen", or deep-link a state id from [states.md](states.md), for example `adb shell am start -S -n com.yawnandpawn.app/.debug.preview.PreviewActivity --es state bright-a-ringing`.

## Options

| Option | In one line | Screenshots |
|---|---|---|
| **A · Full at once** (the spec as written) | Every wake screen goes to full brightness on the first ring and stays there until "Done". | `androidApp/src/test/screenshots/preview/bright_a_ringing_sunrise.png`, `bright_a_ringing_sunrise_font200.png`, `bright_a_check_sunrise.png` |
| **B · Sunrise ramp** | Brightness rises from the phone's own level to full over the quiet time (20 s by default), together with the volume ramp; the checks are then at full. | `bright_b_ringing_start_sunrise.png` (0 s, simulated), `bright_b_ringing_start_sunrise_font200.png`, `bright_b_ringing_half_sunrise.png` (10 s, simulated), `bright_b_check_sunrise.png` (full) |
| **C · Ringing only** | Full brightness while it rings and on the snooze sheet; a check goes back to the phone's own level, so solving is easier on the eyes. | `bright_c_ringing_sunrise.png` (full), `bright_c_check_sunrise.png` (simulated), `bright_c_check_sunrise_font200.png` |

All screenshot paths are under `androidApp/src/test/screenshots/preview/`.

In every option:
- brightness goes back to normal on "Done", on the Snoozed screen, and when the alarm screen is left;
- nothing changes with Extra dim on;
- nothing changes with the setting off.

## The Settings switch

The Settings "Wake" card has the "Bright wake screen" switch, on by default, with the caption "Raises screen brightness on alarm screens." (EXPERIENCE.md Key strings). It is the same in every option.

- On: `bright_settings_on_light.png`, `bright_settings_on_dark.png`, `bright_settings_on_light_font200.png`
- Off: `bright_settings_off_light.png`, `bright_settings_off_dark.png`

Wake screens are always Sunrise, whatever the app theme, so they have no Light and Dark pair. Their primary states are also at 200 % font scale.

## Question for the owner

Which brightness behaviour should Story 5.2 build: **A** (full at once, as specified), **B** (a 20 s sunrise ramp with the volume), or **C** (full only while it rings)?

Related open item: D2 in EXPERIENCE.md asks whether a torch flash on the first ring should be added. It is not mocked here, because of the photosensitivity concern.
