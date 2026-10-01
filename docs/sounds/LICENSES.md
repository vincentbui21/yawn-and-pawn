# Bundled sound licences

Every sound the app ships is listed here with its source, author and licence. Each new file gets a row.

Source URL for every generated file: this repository, https://github.com/vincentbui21/yawn-and-pawn (the generator
script named in the row). Author: Yawn & Pawn contributors. No samples or third-party recordings are used: every tone
is synthesised from sine waves by the script, so there is nothing to attribute.

| File | Name in the app | Source | Author | Licence | How it was made |
|---|---|---|---|---|---|
| `androidApp/src/main/res/raw/alarm_default.ogg` | Sunrise (default) | Generated in this repository by `tools/sounds/generate_default_alarm.py` (synthesised tones, no samples) | Yawn & Pawn contributors | CC0 1.0 (public domain dedication) | `uv run --with soundfile --with numpy tools/sounds/generate_default_alarm.py` |
| `androidApp/src/main/res/raw/alarm_classic.ogg` | Classic | `tools/sounds/generate_alarm_sounds.py` (`classic`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py classic` |
| `androidApp/src/main/res/raw/alarm_digital.ogg` | Digital | `tools/sounds/generate_alarm_sounds.py` (`digital`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py digital` |
| `androidApp/src/main/res/raw/alarm_rising.ogg` | Rising | `tools/sounds/generate_alarm_sounds.py` (`rising`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py rising` |
| `androidApp/src/main/res/raw/alarm_chimes.ogg` | Chimes | `tools/sounds/generate_alarm_sounds.py` (`chimes`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py chimes` |
| `androidApp/src/main/res/raw/alarm_siren.ogg` | Siren | `tools/sounds/generate_alarm_sounds.py` (`siren`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py siren` |
| `androidApp/src/main/res/raw/alarm_pulse.ogg` | Pulse | `tools/sounds/generate_alarm_sounds.py` (`pulse`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py pulse` |
| `androidApp/src/main/res/raw/alarm_buzzer.ogg` | Buzzer | `tools/sounds/generate_alarm_sounds.py` (`buzzer`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py buzzer` |
| `androidApp/src/main/res/raw/alarm_marimba.ogg` | Marimba | `tools/sounds/generate_alarm_sounds.py` (`marimba`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py marimba` |
| `androidApp/src/main/res/raw/alarm_bell.ogg` | Bell | `tools/sounds/generate_alarm_sounds.py` (`bell`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py bell` |
| `androidApp/src/main/res/raw/alarm_morning.ogg` | Morning | `tools/sounds/generate_alarm_sounds.py` (`morning`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py morning` |
| `androidApp/src/main/res/raw/alarm_sonar.ogg` | Sonar | `tools/sounds/generate_alarm_sounds.py` (`sonar`) | Yawn & Pawn contributors | CC0 1.0 | `uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py sonar` |
| `composeApp/src/androidMain/res/raw/wheel_tick.wav` | (time-wheel tick, UI sound) | Generated in this repository (design preview) | Yawn & Pawn contributors | CC0 1.0 | Exempt from the loudness gate: a deliberately quiet 12 ms UI sound |

## The loudness gate

`./gradlew checkSoundLoudness` (part of `qualityGate`, Story 1.17) measures every `androidApp/src/main/res/raw/alarm_*`
file and fails, naming the file, below a -3 dBFS sample peak or -14 LUFS integrated loudness (FR-SND-1). It uses
`ffmpeg -af ebur128=peak=sample` (ffmpeg on PATH or the Gradle property `yawnandpawn.ffmpeg`; CI installs it). On a
machine that cannot run ffmpeg, `yawnandpawn.loudnessMeasurer=python` measures with `tools/sounds/measure_loudness.py`
through uv (`yawnandpawn.uv`, or uv on PATH) and the same BS.1770-4 method (`tools/sounds/loudness.py`). Values at the
time of writing (sample peak, integrated):

| File | Peak (dBFS) | Loudness (LUFS) |
|---|---|---|
| alarm_default | -0.5 | -8.1 |
| alarm_classic | -0.5 | -5.6 |
| alarm_digital | -0.1 | -9.0 |
| alarm_rising | -1.1 | -6.1 |
| alarm_chimes | -1.3 | -9.3 |
| alarm_siren | -1.1 | -5.0 |
| alarm_pulse | -1.1 | -10.8 |
| alarm_buzzer | -0.8 | -9.9 |
| alarm_marimba | -1.5 | -10.1 |
| alarm_bell | -1.2 | -9.5 |
| alarm_morning | -0.9 | -6.8 |
| alarm_sonar | -1.0 | -11.0 |

## The built-in library (Story 1.17)

`tools/sounds/generate_alarm_sounds.py` writes the 11 sounds after the default: 3 s mono 48 kHz OGG Vorbis loops that
start and end in silence, normalised to a -1.5 dBFS sample peak and, where a decaying timbre would be too quiet, gently
saturated (tanh) until they reach -11 LUFS, 3 LU above the rule. The script measures each encoded file and fails if one
misses the rule. Regenerate one sound, or all of them, from the repository root:

```sh
uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py [classic digital ...]
```

As with the default, the tones are deterministic but the file bytes are not (random Ogg serial number), so commit a
regenerated file only when its tone changes.

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
