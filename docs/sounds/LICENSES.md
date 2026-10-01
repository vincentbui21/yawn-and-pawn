# Bundled sound licences

Every sound the app ships is listed here with its source and licence. Story 1.17 adds the built-in sound library;
each new file gets a row.

| File | Source | Licence | How it was made |
|---|---|---|---|
| `androidApp/src/main/res/raw/alarm_default.ogg` | Generated in this repository by `tools/sounds/generate_default_alarm.py` (synthesised tones, no samples) | CC0 1.0 (public domain dedication) | `uv run --with soundfile --with numpy tools/sounds/generate_default_alarm.py` |

## alarm_default.ogg

The default alarm sound and the never-silent fallback (Story 1.14): when the chosen sound cannot be opened or fails
while ringing, this one plays in the same ring. A 3 s seamless loop of four two-note beeps (880 Hz and 1175 Hz with
two soft harmonics), mono, 48 kHz, OGG Vorbis. The generator checks the encoded file against the Story 1.17 loudness
gate: peak at or above -3 dBFS (it is about -0.5 dBFS) and integrated loudness at or above -14 LUFS (about -8 LUFS,
ITU-R BS.1770-4).

Regenerate it from the repository root (uv installs the two packages for the run only):

```sh
uv run --with soundfile --with numpy tools/sounds/generate_default_alarm.py
```

The tone is deterministic; the file bytes are not (the Ogg stream serial number is random), so regenerate and commit it
only when the tone changes.
