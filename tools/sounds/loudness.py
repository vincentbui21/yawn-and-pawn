"""ITU-R BS.1770-4 loudness helpers shared by the sound generators and measure_loudness.py (Stories 1.14 and 1.17).

Integrated loudness uses the K-weighting filters for any sample rate (the same design as libebur128, which ffmpeg's
ebur128 filter uses), 400 ms blocks with 75% overlap, the -70 LUFS absolute gate and the -10 LU relative gate. Peaks
are sample peaks in dBFS, like `ffmpeg -af ebur128=peak=sample`.
"""

from __future__ import annotations

import math

import numpy as np

# The loudness rule of FR-SND-1, checked by `./gradlew checkSoundLoudness`.
MIN_PEAK_DBFS = -3.0
MIN_LUFS = -14.0

ABSOLUTE_GATE_LUFS = -70.0


def biquad(x: np.ndarray, b: tuple[float, float, float], a: tuple[float, float, float]) -> np.ndarray:
    """Direct form I biquad (a[0] == 1)."""
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    b0, b1, b2 = b
    _, a1, a2 = a
    for i, xi in enumerate(x):
        yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, xi, y1, yi
        y[i] = yi
    return y


def k_weighting(rate: int) -> list[tuple[tuple[float, float, float], tuple[float, float, float]]]:
    """The two BS.1770 K-weighting stages (high shelf, then high pass) for [rate], as (b, a) pairs."""
    f0 = 1681.974450955533
    gain_db = 3.999843853973347
    q = 0.7071752369554196
    k = math.tan(math.pi * f0 / rate)
    vh = 10 ** (gain_db / 20)
    vb = vh**0.4996667741545416
    a0 = 1 + k / q + k * k
    shelf = (
        ((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0),
        (1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0),
    )
    f0 = 38.13547087602444
    q = 0.5003270373238773
    k = math.tan(math.pi * f0 / rate)
    a0 = 1 + k / q + k * k
    high_pass = ((1.0, -2.0, 1.0), (1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0))
    return [shelf, high_pass]


def integrated_lufs(samples: np.ndarray, rate: int) -> float:
    """Integrated loudness of [samples] (frames x channels, or mono), in LUFS; -70.0 when nothing passes the gate."""
    channels = samples.reshape(len(samples), -1)
    block = int(round(0.4 * rate))
    step = int(round(0.1 * rate))
    if len(channels) < block:
        return ABSOLUTE_GATE_LUFS
    power = np.zeros((len(channels) - block) // step + 1)
    for c in range(channels.shape[1]):
        weighted = channels[:, c].astype(np.float64)
        for b, a in k_weighting(rate):
            weighted = biquad(weighted, b, a)
        squares = np.concatenate([[0.0], np.cumsum(weighted**2)])
        starts = np.arange(len(power)) * step
        power += (squares[starts + block] - squares[starts]) / block

    def loudness(z: np.ndarray) -> float:
        return -0.691 + 10 * math.log10(float(np.mean(z)))

    with np.errstate(divide="ignore"):
        block_lufs = -0.691 + 10 * np.log10(power)
    above_absolute = power[block_lufs > ABSOLUTE_GATE_LUFS]
    if len(above_absolute) == 0:
        return ABSOLUTE_GATE_LUFS
    relative_gate = loudness(above_absolute) - 10.0
    gated = power[block_lufs > max(relative_gate, ABSOLUTE_GATE_LUFS)]
    return loudness(gated)


def sample_peak_dbfs(samples: np.ndarray) -> float:
    peak = float(np.max(np.abs(samples))) if len(samples) else 0.0
    return 20 * math.log10(peak) if peak > 0 else float("-inf")
