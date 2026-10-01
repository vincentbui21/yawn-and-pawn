"""Generates the built-in alarm sound library, androidApp/src/main/res/raw/alarm_<id>.ogg (Story 1.17).

Every tone is synthesised here (no samples), so it is ours to license: CC0 1.0, see docs/sounds/LICENSES.md. The
default sound, alarm_default.ogg, has its own generator (generate_default_alarm.py, Story 1.14) and is not rewritten.

Each sound is a 3 s mono 48 kHz loop that starts and ends in silence (so it loops without a click), normalised to a
-1.5 dBFS sample peak (room for the Vorbis overshoot) and, where a decaying timbre would be too quiet, gently
saturated until its integrated loudness is at least -11 LUFS: 3 LU above the -14 LUFS rule of
`./gradlew checkSoundLoudness`. The script measures the encoded files and fails if one misses the rule.

Run from the repository root, in user scope (no admin rights needed):

    uv run --with soundfile --with numpy tools/sounds/generate_alarm_sounds.py [id ...]

With ids, only those sounds are written. The tones are deterministic, but the file bytes are not (the Ogg stream
serial number is random), so commit a regenerated file only when its tone changes.
"""

from __future__ import annotations

import math
import sys
from collections.abc import Callable
from pathlib import Path

import numpy as np
import soundfile as sf

from loudness import MIN_LUFS, MIN_PEAK_DBFS, integrated_lufs, sample_peak_dbfs

RATE = 48_000
RAW = Path(__file__).resolve().parents[2] / "androidApp" / "src" / "main" / "res" / "raw"
TARGET_PEAK_DBFS = -1.5
TARGET_LUFS = -11.0
MIN_SECONDS = 2.0
MAX_SECONDS = 4.0
LOOP_SECONDS = 3.0


def time_axis(seconds: float) -> np.ndarray:
    return np.arange(int(round(seconds * RATE))) / RATE


def silence(seconds: float) -> np.ndarray:
    return np.zeros(int(round(seconds * RATE)))


def envelope(n: int, attack_s: float = 0.005, release_s: float = 0.02) -> np.ndarray:
    """Flat with a click-free linear attack and squared release."""
    env = np.ones(n)
    attack = min(n, int(attack_s * RATE))
    release = min(n - attack, int(release_s * RATE))
    if attack:
        env[:attack] = np.linspace(0.0, 1.0, attack, endpoint=False)
    if release:
        env[-release:] = np.linspace(1.0, 0.0, release) ** 2
    return env


def harmonic_tone(frequency: float, seconds: float, partials: dict[float, float]) -> np.ndarray:
    """A steady tone of [partials] (multiple of the frequency -> amplitude) with the standard envelope."""
    t = time_axis(seconds)
    tone = sum(amplitude * np.sin(2 * math.pi * frequency * multiple * t) for multiple, amplitude in partials.items())
    return tone * envelope(len(t))


def struck(frequency: float, seconds: float, partials: dict[float, tuple[float, float]]) -> np.ndarray:
    """A struck timbre: each partial (multiple -> (amplitude, decay time constant in s)) decays exponentially."""
    t = time_axis(seconds)
    tone = sum(
        amplitude * np.exp(-t / decay) * np.sin(2 * math.pi * frequency * multiple * t)
        for multiple, (amplitude, decay) in partials.items()
    )
    return tone * envelope(len(t), attack_s=0.002, release_s=0.03)


def fit(samples: np.ndarray, seconds: float = LOOP_SECONDS) -> np.ndarray:
    """Pads or trims to exactly [seconds], fading the tail so the loop point is silent."""
    n = int(round(seconds * RATE))
    out = np.zeros(n)
    out[: min(n, len(samples))] = samples[:n]
    fade = int(0.01 * RATE)
    out[-fade:] *= np.linspace(1.0, 0.0, fade)
    return out


# The sounds -------------------------------------------------------------------------------------------------------


def classic() -> np.ndarray:
    """The classic alarm-clock beep: four quick square-ish beeps at 2 kHz, a pause, twice."""
    beep = harmonic_tone(2048.0, 0.09, {1: 1.0, 3: 0.33, 5: 0.2})
    group = np.concatenate([np.concatenate([beep, silence(0.06)]) for _ in range(4)] + [silence(0.9)])
    return np.tile(group, 2)


def digital() -> np.ndarray:
    """A digital watch alarm: three short 2.7 kHz beeps, a short pause, four times."""
    beep = harmonic_tone(2700.0, 0.06, {1: 1.0, 2: 0.15})
    group = np.concatenate([np.concatenate([beep, silence(0.05)]) for _ in range(3)] + [silence(0.42)])
    return np.tile(group, 4)


def rising() -> np.ndarray:
    """Four rising sweeps from 500 Hz to 1.6 kHz."""
    t = time_axis(0.6)
    f_start, f_end = 500.0, 1600.0
    # Exponential sweep: the phase is the integral of the instantaneous frequency.
    k = math.log(f_end / f_start) / 0.6
    phase = 2 * math.pi * f_start * (np.exp(k * t) - 1) / k
    sweep = (np.sin(phase) + 0.3 * np.sin(2 * phase) + 0.15 * np.sin(3 * phase)) * envelope(len(t), release_s=0.05)
    return np.tile(np.concatenate([sweep, silence(0.15)]), 4)


def chimes() -> np.ndarray:
    """A bright up-and-down arpeggio of chime strikes (C6 E6 G6 C7 G6 E6 C6 rest)."""
    notes = [1046.5, 1318.5, 1568.0, 2093.0, 1568.0, 1318.5, 1046.5]
    timbre = {1: (1.0, 0.45), 2.0: (0.5, 0.25), 3.0: (0.25, 0.12), 4.2: (0.15, 0.06)}
    return np.concatenate([struck(f, 0.375, timbre) for f in notes] + [silence(0.375)])


def siren() -> np.ndarray:
    """A two-tone siren, 960 Hz and 770 Hz, half a second each, three times."""
    high = harmonic_tone(960.0, 0.5, {1: 1.0, 2: 0.25, 3: 0.12})
    low = harmonic_tone(770.0, 0.5, {1: 1.0, 2: 0.25, 3: 0.12})
    return np.tile(np.concatenate([high, low]), 3)


def pulse() -> np.ndarray:
    """A fast 1 kHz pulse train: eight 60 ms pulses, then a pause, twice."""
    tick = harmonic_tone(1000.0, 0.06, {1: 1.0, 2: 0.3, 3: 0.2})
    group = np.concatenate([np.concatenate([tick, silence(0.065)]) for _ in range(8)] + [silence(0.5)])
    return np.tile(group, 2)


def buzzer() -> np.ndarray:
    """A low buzzer: a band-limited 220 Hz sawtooth, 0.4 s on and 0.2 s off."""
    partials = {float(n): 1.0 / n for n in range(1, 21)}
    buzz = harmonic_tone(220.0, 0.4, partials)
    return np.tile(np.concatenate([buzz, silence(0.2)]), 5)


def marimba() -> np.ndarray:
    """A marimba-like melody: G5 B5 D6 G6 D6 B5, twice."""
    notes = [784.0, 987.8, 1174.7, 1568.0, 1174.7, 987.8]
    timbre = {1: (1.0, 0.3), 3.93: (0.35, 0.05), 9.0: (0.1, 0.02)}
    return np.tile(np.concatenate([struck(f, 0.25, timbre) for f in notes]), 2)


def bell() -> np.ndarray:
    """A mechanical alarm bell: fast hammer strikes on an inharmonic bell for 1 s, a pause, twice."""
    timbre = {1: (1.0, 0.08), 2.76: (0.6, 0.05), 5.4: (0.35, 0.03)}
    strike = struck(1400.0, 0.05, timbre)
    ringing = np.tile(strike, 20)
    return np.tile(np.concatenate([ringing, silence(0.5)]), 2)


def morning() -> np.ndarray:
    """A bright rising tune on a triangle wave: C5 E5 G5 C6 E6 C6, then a held G5."""
    triangle = {float(n): (1.0 / n**2) * (-1) ** ((n - 1) // 2) for n in range(1, 16, 2)}
    melody = [(523.3, 0.25), (659.3, 0.25), (784.0, 0.25), (1046.5, 0.25), (1318.5, 0.25), (1046.5, 0.25)]
    phrase = [harmonic_tone(f, s - 0.02, triangle) for f, s in melody]
    gaps = [silence(0.02)] * len(phrase)
    held = harmonic_tone(784.0, 1.2, triangle)
    return np.concatenate([x for pair in zip(phrase, gaps) for x in pair] + [held, silence(0.3)])


def sonar() -> np.ndarray:
    """Sonar pings: a 1.2 kHz ping with an echo, every 0.75 s."""
    ping = struck(1200.0, 0.5, {1: (1.0, 0.12), 2: (0.2, 0.05)})
    one = np.zeros(int(0.75 * RATE))
    one[: len(ping)] += ping
    echo = int(0.25 * RATE)
    one[echo : echo + len(ping)] += 0.5 * ping[: len(one) - echo]
    return np.tile(one, 4)


SOUNDS: dict[str, Callable[[], np.ndarray]] = {
    "classic": classic,
    "digital": digital,
    "rising": rising,
    "chimes": chimes,
    "siren": siren,
    "pulse": pulse,
    "buzzer": buzzer,
    "marimba": marimba,
    "bell": bell,
    "morning": morning,
    "sonar": sonar,
}


def master(samples: np.ndarray) -> np.ndarray:
    """Normalises to the target peak; saturates softly (tanh) until the loudness target is met."""
    samples = fit(samples)
    peak = 10 ** (TARGET_PEAK_DBFS / 20)
    out = samples * (peak / np.max(np.abs(samples)))
    drive = 1.0
    while integrated_lufs(out, RATE) < TARGET_LUFS and drive < 16:
        drive *= 1.25
        shaped = np.tanh(drive * samples / np.max(np.abs(samples)))
        out = shaped * (peak / np.max(np.abs(shaped)))
    return out


def main(argv: list[str]) -> int:
    wanted = argv[1:] or list(SOUNDS)
    unknown = [name for name in wanted if name not in SOUNDS]
    if unknown:
        print(f"unknown sound(s): {', '.join(unknown)}; known: {', '.join(SOUNDS)}", file=sys.stderr)
        return 2
    RAW.mkdir(parents=True, exist_ok=True)
    failures = []
    for name in wanted:
        path = RAW / f"alarm_{name}.ogg"
        sf.write(path, master(SOUNDS[name]()), RATE, format="OGG", subtype="VORBIS")
        decoded, rate = sf.read(path, dtype="float64")
        seconds = len(decoded) / rate
        peak = sample_peak_dbfs(decoded)
        lufs = integrated_lufs(decoded, rate)
        print(f"{path.name}: {seconds:.2f} s, {rate} Hz, peak {peak:.2f} dBFS, {lufs:.2f} LUFS, {path.stat().st_size} bytes")
        if not MIN_SECONDS <= seconds <= MAX_SECONDS:
            failures.append(f"{path.name}: length {seconds:.2f} s is outside {MIN_SECONDS}..{MAX_SECONDS} s")
        if peak < MIN_PEAK_DBFS:
            failures.append(f"{path.name}: peak {peak:.2f} dBFS is below {MIN_PEAK_DBFS} dBFS")
        if not lufs >= MIN_LUFS:
            failures.append(f"{path.name}: loudness {lufs:.2f} LUFS is below {MIN_LUFS} LUFS")
    for failure in failures:
        print(f"FAILED: {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
