# Geist tabular figures (tnum)

- **Date:** 2026-09-26 (Story 1.3)
- **Question (DESIGN.md > Typography):** does Geist support the OpenType `tnum` feature, or does `clock-xl` need Geist Mono?
- **Decision:** Geist has `tnum`. All tabular styles (`clock-xl`, `display`, `title`, `button-wake`) use Geist with `fontFeatureSettings = "tnum"`. Geist Mono is not bundled.

## Evidence

- Source: the official Geist release [v1.7.2](https://github.com/vercel/geist-font/releases/tag/v1.7.2) (`geist-font-v1.7.2.zip`, SHA-256 `7fc800d2ac6b92844895196e5041aca55d814c15db70c44f79b3b83ab82b04e2`), static TTFs from `Geist/ttf/`.
- The GSUB feature list of `Geist-Light.ttf`, `Geist-Regular.ttf`, `Geist-Medium.ttf` and `Geist-SemiBold.ttf` contains `tnum` (next to `pnum`, `case`, `frac`, `ss01`..`ss11` and others).
- `GeistFontTest` (`:composeApp` host test, part of `qualityGate`) reads the GSUB table of every bundled font and fails if `tnum` is missing, so a future font swap cannot silently drop tabular figures.

## Bundled files

| Resource | Source file | Weight | Used by |
|---|---|---|---|
| `font/geist_light.ttf` | `Geist-Light.ttf` | 300 | `clock-xl` |
| `font/geist_regular.ttf` | `Geist-Regular.ttf` | 400 | `body`, `caption` |
| `font/geist_medium.ttf` | `Geist-Medium.ttf` | 500 | `display`, `button-wake`, `label` |
| `font/geist_semibold.ttf` | `Geist-SemiBold.ttf` | 600 | `headline`, `title` |

Licence: SIL Open Font License 1.1, copied to `docs/licenses/geist-OFL.txt`. Glyphs Geist lacks fall back to the system sans-serif font.
