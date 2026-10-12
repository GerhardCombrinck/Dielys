"""Renders the tick-off chime both apps play when an item is ticked off.

Run from the repo root: python docs/sound/make_chime.py

Writes the same WAV to android/app/src/main/res/raw/tick_chime.wav and
web/public/tick_chime.wav, so the phone and the browser sound alike. Two soft
bell notes a fifth apart (C6 then G6), each a sine with a quiet octave partial,
a 4 ms attack so it does not click, and an exponential decay. Kept short and
quiet: it plays on every tick, and a sound you hear forty times in a shop has
to stay out of the way.
"""

import math
import struct
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUTPUTS = [
    ROOT / "android/app/src/main/res/raw/tick_chime.wav",
    ROOT / "web/public/tick_chime.wav",
]

RATE = 24_000
LENGTH_S = 0.42
PEAK = 0.32

# (start seconds, frequency Hz, gain, decay per second)
NOTES = [
    (0.000, 1046.50, 0.75, 14.0),  # C6
    (0.070, 1567.98, 1.00, 10.0),  # G6
]
ATTACK_S = 0.004


def sample(t: float) -> float:
    total = 0.0
    for start, freq, gain, decay in NOTES:
        local = t - start
        if local < 0:
            continue
        envelope = min(local / ATTACK_S, 1.0) * math.exp(-decay * local)
        tone = math.sin(2 * math.pi * freq * local) + 0.18 * math.sin(4 * math.pi * freq * local)
        total += gain * envelope * tone
    return total


def render() -> bytes:
    count = int(RATE * LENGTH_S)
    raw = [sample(i / RATE) for i in range(count)]
    scale = PEAK / max(abs(s) for s in raw)
    # The last 20 ms fade to silence so the file never ends on a non-zero sample.
    fade = int(RATE * 0.02)
    frames = bytearray()
    for i, s in enumerate(raw):
        tail = min((count - i) / fade, 1.0)
        frames += struct.pack("<h", round(s * scale * tail * 32767))
    return bytes(frames)


def main() -> None:
    frames = render()
    for path in OUTPUTS:
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(RATE)
            out.writeframes(frames)
        print(f"wrote {path.relative_to(ROOT)} ({path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
