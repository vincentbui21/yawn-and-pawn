"""Generates the bundled default alarm sound, androidApp/src/main/res/raw/alarm_default.ogg (Story 1.14).

The tone is synthesised here, so it is ours to license (CC0, see docs/sounds/LICENSES.md). It is a 3 s loop of four
two-note beeps that starts and ends in silence, so it loops without a click. The script checks the encoded file against
the loudness gate of Story 1.17 (peak at or above -3 dBFS, integrated loudness at or above -14 LUFS, ITU-R BS.1770-4)
and fails if it misses it.

Run from the repository root, in user scope (no admin rights needed):

    uv run --with soundfile --with numpy tools/sounds/generate_default_alarm.py

The tone is deterministic, but the file is not byte for byte (the Ogg stream serial number is random), so commit a
regenerated file only when the tone itself changes.
"""

from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

SAMPLE_RATE = 48_000
OUTPUT = Path(__file__).resolve().parents[2] / "androidApp" / "src" / "main" / "res" / "raw" / "alarm_default.ogg"

# One 0.75 s phrase, played four times: low beep, short gap, high beep, longer gap. A6 and D7 sit where phone
# speakers are loud and the K-weighting of BS.1770 is flat.
LOW_HZ = 880.0
HIGH_HZ = 1174.66
BEEP_S = 0.18
SHORT_GAP_S = 0.07
LONG_GAP_S = 0.32
PHRASES = 4
ATTACK_S = 0.005
RELEASE_S = 0.03
TARGET_PEAK_DBFS = -1.0

MIN_SECONDS = 2.0
MAX_SECONDS = 4.0
MIN_PEAK_DBFS = -3.0
MIN_LUFS = -14.0


def beep(frequency: float) -> np.ndarray:
    """A short tone with two soft harmonics and a click-free attack and release."""
    t = np.arange(int(round(BEEP_S * SAMPLE_RATE))) / SAMPLE_RATE
    tone = (
        np.sin(2 * math.pi * frequency * t)
        + 0.3 * np.sin(2 * math.pi * 2 * frequency * t)
        + 0.15 * np.sin(2 * math.pi * 3 * frequency * t)
    )
    envelope = np.ones_like(t)
    attack = int(ATTACK_S * SAMPLE_RATE)
    release = int(RELEASE_S * SAMPLE_RATE)
    envelope[:attack] = np.linspace(0.0, 1.0, attack, endpoint=False)
    envelope[-release:] = np.linspace(1.0, 0.0, release) ** 2
    return tone * envelope


def silence(seconds: float) -> np.ndarray:
    return np.zeros(int(round(seconds * SAMPLE_RATE)))


def alarm() -> np.ndarray:
    phrase = np.concatenate([beep(LOW_HZ), silence(SHORT_GAP_S), beep(HIGH_HZ), silence(LONG_GAP_S)])
    loop = np.tile(phrase, PHRASES)
    return loop * (10 ** (TARGET_PEAK_DBFS / 20) / np.max(np.abs(loop)))


def biquad(x: np.ndarray, b: tuple[float, float, float], a: tuple[float, float, float]) -> np.ndarray:
    """Direct form I biquad (a[0] == 1)."""
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    for i, xi in enumerate(x):
        yi = b[0] * xi + b[1] * x1 + b[2] * x2 - a[1] * y1 - a[2] * y2
        x2, x1, y2, y1 = x1, xi, y1, yi
        y[i] = yi
    return y


def integrated_lufs(samples: np.ndarray) -> float:
    """ITU-R BS.1770-4 integrated loudness of a mono signal at 48 kHz (K-weighting, 400 ms blocks, both gates)."""
    shelf = biquad(samples, (1.53512485958697, -2.69169618940638, 1.19839281085285), (1.0, -1.69065929318241, 0.73248077421585))
    weighted = biquad(shelf, (1.0, -2.0, 1.0), (1.0, -1.99004745483398, 0.99007225036621))
    block = int(0.4 * SAMPLE_RATE)
    step = int(0.1 * SAMPLE_RATE)
    powers = np.array([np.mean(weighted[i : i + block] ** 2) for i in range(0, len(weighted) - block + 1, step)])

    def loudness(z: np.ndarray) -> float:
        return -0.691 + 10 * math.log10(float(np.mean(z)))

    above_absolute = powers[-0.691 + 10 * np.log10(np.maximum(powers, 1e-12)) > -70.0]
    relative_gate = loudness(above_absolute) - 10.0
    gated = above_absolute[-0.691 + 10 * np.log10(above_absolute) > relative_gate]
    return loudness(gated)


def main() -> int:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    sf.write(OUTPUT, alarm(), SAMPLE_RATE, format="OGG", subtype="VORBIS")

    decoded, rate = sf.read(OUTPUT, dtype="float64")
    seconds = len(decoded) / rate
    peak_dbfs = 20 * math.log10(float(np.max(np.abs(decoded))))
    lufs = integrated_lufs(decoded) if rate == SAMPLE_RATE else float("nan")
    print(f"{OUTPUT.name}: {seconds:.2f} s, {rate} Hz, peak {peak_dbfs:.2f} dBFS, {lufs:.2f} LUFS, {OUTPUT.stat().st_size} bytes")

    failures = []
    if not MIN_SECONDS <= seconds <= MAX_SECONDS:
        failures.append(f"length {seconds:.2f} s is outside {MIN_SECONDS}..{MAX_SECONDS} s")
    if peak_dbfs < MIN_PEAK_DBFS:
        failures.append(f"peak {peak_dbfs:.2f} dBFS is below {MIN_PEAK_DBFS} dBFS")
    if not lufs >= MIN_LUFS:
        failures.append(f"loudness {lufs:.2f} LUFS is below {MIN_LUFS} LUFS")
    for failure in failures:
        print(f"FAILED: {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
