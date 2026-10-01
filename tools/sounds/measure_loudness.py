"""Measures one sound file like `ffmpeg -af ebur128=peak=sample` and prints the same summary (Story 1.17).

`./gradlew checkSoundLoudness` uses ffmpeg by default. On a machine that cannot run ffmpeg (for example a company PC
that blocks unsigned executables), `-Pyawnandpawn.loudnessMeasurer=python` runs this script instead, through uv, and
parses its output with the same parser:

    uv run --with soundfile --with numpy tools/sounds/measure_loudness.py <file>

It prints, on stderr like ffmpeg:

    [Parsed_ebur128_0 @ python] Summary:

      Integrated loudness:
        I:         -8.1 LUFS
        Threshold: -18.1 LUFS

      Sample peak:
        Peak:       -0.5 dBFS
"""

from __future__ import annotations

import math
import sys

import numpy as np
import soundfile as sf

from loudness import integrated_lufs, sample_peak_dbfs


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: measure_loudness.py <file>", file=sys.stderr)
        return 2
    samples, rate = sf.read(argv[1], dtype="float64", always_2d=True)
    lufs = integrated_lufs(samples, rate)
    peak = sample_peak_dbfs(samples)
    peak_text = "-inf" if math.isinf(peak) else f"{peak:.1f}"
    print("[Parsed_ebur128_0 @ python] Summary:", file=sys.stderr)
    print("", file=sys.stderr)
    print("  Integrated loudness:", file=sys.stderr)
    print(f"    I:         {lufs:.1f} LUFS", file=sys.stderr)
    print(f"    Threshold: {lufs - 10.0:.1f} LUFS", file=sys.stderr)
    print("", file=sys.stderr)
    print("  Sample peak:", file=sys.stderr)
    print(f"    Peak:      {peak_text} dBFS", file=sys.stderr)
    return 0


if __name__ == "__main__":
    np.seterr(all="ignore")
    sys.exit(main(sys.argv))
