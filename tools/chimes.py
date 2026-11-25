#!/usr/bin/env python3
"""Generates the app's startup and shutdown chimes (soft, bell-like, mono WAV).

    python3 tools/chimes.py      # writes audiobox/app/src/main/res/raw/chime_*.wav

Synthesised here so there is no licence question. Each note is a sine with two
quiet overtones and an exponential decay; the start chime rises, the shutdown
one falls.
"""
import math, os, struct, wave

RATE = 22050
PEAK = 0.35  # about -9 dBFS: soft even at full volume
OUT = os.path.join(os.path.dirname(__file__), "..", "audiobox", "app", "src", "main", "res", "raw")


def chime(notes, length):
    """notes: (start s, frequency Hz, gain); returns float samples."""
    n = int(length * RATE)
    buf = [0.0] * n
    for start, freq, gain in notes:
        s0 = int(start * RATE)
        for i in range(n - s0):
            t = i / RATE
            env = min(1.0, t / 0.008) * math.exp(-t / 0.45)  # 8 ms attack, gentle decay
            v = (math.sin(2 * math.pi * freq * t)
                 + 0.25 * math.sin(2 * math.pi * 2 * freq * t) * math.exp(-t / 0.15)
                 + 0.08 * math.sin(2 * math.pi * 3 * freq * t) * math.exp(-t / 0.08))
            buf[s0 + i] += gain * env * v
    fade = int(0.05 * RATE)  # no click at the end
    for i in range(fade):
        buf[n - 1 - i] *= i / fade
    top = max(abs(v) for v in buf)
    return [v / top * PEAK for v in buf]


def write(name, samples):
    path = os.path.join(OUT, name)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(v * 32767)) for v in samples))
    print(f"wrote {os.path.relpath(path)} ({len(samples) / RATE:.1f} s)")


E5, G5, C5, C6 = 659.26, 783.99, 523.25, 1046.50
os.makedirs(OUT, exist_ok=True)
write("chime_start.wav", chime([(0.00, E5, 1.0), (0.16, G5, 0.9), (0.32, C6, 0.8)], 1.8))
write("chime_off.wav", chime([(0.00, G5, 0.9), (0.22, E5, 0.9), (0.44, C5, 1.0)], 2.0))
