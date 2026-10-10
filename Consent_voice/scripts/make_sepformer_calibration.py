#!/usr/bin/env python3
"""Create fixed-length float32 inputs for SepFormer/QNN calibration."""

from __future__ import annotations

import argparse
import wave
from pathlib import Path

import numpy as np


def read_pcm16_mono(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise ValueError(f"Expected mono 16-bit PCM WAV: {path}")
        rate = wav.getframerate()
        audio = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")
    return audio.astype(np.float32) / 32768.0, rate


def resample_linear(audio: np.ndarray, source_rate: int, target_rate: int) -> np.ndarray:
    if source_rate == target_rate:
        return audio
    output_length = round(len(audio) * target_rate / source_rate)
    source_positions = np.arange(len(audio), dtype=np.float64)
    target_positions = np.arange(output_length, dtype=np.float64) * source_rate / target_rate
    return np.interp(target_positions, source_positions, audio).astype(np.float32)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("inputs", nargs="+", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--sample-rate", type=int, default=8000)
    parser.add_argument("--seconds", type=float, default=4.0)
    parser.add_argument("--hop-seconds", type=float, default=2.0)
    args = parser.parse_args()

    args.output_dir.mkdir(parents=True, exist_ok=True)
    window = round(args.sample_rate * args.seconds)
    hop = round(args.sample_rate * args.hop_seconds)
    entries: list[str] = []

    for input_path in args.inputs:
        audio, source_rate = read_pcm16_mono(input_path)
        audio = resample_linear(audio, source_rate, args.sample_rate)
        starts = list(range(0, max(len(audio) - window + 1, 1), hop))
        final_start = max(0, len(audio) - window)
        if not starts or starts[-1] != final_start:
            starts.append(final_start)

        for index, start in enumerate(starts):
            chunk = audio[start : start + window]
            if len(chunk) < window:
                chunk = np.pad(chunk, (0, window - len(chunk)))
            output_path = args.output_dir / f"{input_path.stem}-{index:02d}.raw"
            chunk.astype("<f4").tofile(output_path)
            entries.append(str(output_path.resolve()))

    input_list = args.output_dir / "input_list.txt"
    input_list.write_text("\n".join(entries) + "\n", encoding="utf-8")
    print(f"Created {len(entries)} calibration inputs in {args.output_dir}")
    print(input_list.resolve())


if __name__ == "__main__":
    main()
