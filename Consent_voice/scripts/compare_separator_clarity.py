"""Offline A/B audition of a 16 kHz SepFormer against an existing app export.

Inputs and output WAVs are private local fixtures. The separated candidates are
not sanitized exports: a human must check protected-speaker leakage before any
candidate can replace the app's working 8 kHz path.
"""

from __future__ import annotations

import argparse
import math
import wave
from pathlib import Path

import numpy as np
import torch
from scipy.ndimage import uniform_filter1d
from scipy.signal import istft, resample_poly, stft

from export_sepformer_onnx import load_model

SAMPLE_RATE = 16_000


def read_pcm16(path: Path) -> tuple[np.ndarray, int]:
    with wave.open(str(path), "rb") as wav:
        if wav.getsampwidth() != 2 or wav.getnchannels() != 1:
            raise ValueError(f"{path} must be mono PCM16 WAV")
        rate = wav.getframerate()
        samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2")
    return samples.astype(np.float32) / 32768.0, rate


def write_pcm16(path: Path, samples: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    peak = float(np.max(np.abs(samples))) if samples.size else 0.0
    gain = min(1.0, 0.95 / peak) if peak else 1.0
    pcm = np.round(np.clip(samples * gain, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        wav.writeframes(pcm.tobytes())
    print(f"{path.name}: peak={peak:.4f}, output_gain={gain:.4f}")


def correlation(left: np.ndarray, right: np.ndarray) -> float:
    denominator = float(np.linalg.norm(left) * np.linalg.norm(right))
    return float(np.dot(left, right) / denominator) if denominator > 1e-9 else 0.0


def suppress_coherent_leakage(
    retained: np.ndarray,
    other: np.ndarray,
    strength: float,
    minimum_gain: float = 0.2,
) -> np.ndarray:
    """Experimental postfilter; reduces energy shared with the other stream.

    This is not a speaker-identity or privacy test. The output must be listened
    to for both residual protected speech and retained-speaker distortion.
    """
    if retained.shape != other.shape or retained.ndim != 1:
        raise ValueError("Both streams must be aligned one-dimensional arrays")
    if retained.size == 0 or not np.isfinite(retained).all() or not np.isfinite(other).all():
        raise ValueError("Streams must contain finite audio")
    if not (0.0 <= strength <= 1.0 and 0.0 <= minimum_gain <= 1.0):
        raise ValueError("strength and minimum_gain must be between zero and one")
    if not strength or np.max(np.abs(other)) < 1e-8:
        return retained.copy()

    # SepFormer channels have independent arbitrary gain. Equalize their
    # overall RMS only for estimating coherence, not in the returned signal.
    retained_rms = float(np.sqrt(np.mean(np.square(retained))))
    other_rms = float(np.sqrt(np.mean(np.square(other))))
    if retained_rms < 1e-8 or other_rms < 1e-8:
        return retained.copy()
    _, _, target_spec = stft(retained, fs=SAMPLE_RATE, nperseg=512, noverlap=384)
    _, _, other_spec = stft(other * (retained_rms / other_rms), fs=SAMPLE_RATE, nperseg=512, noverlap=384)
    target_power = uniform_filter1d(np.abs(target_spec) ** 2, size=9, axis=1)
    other_power = uniform_filter1d(np.abs(other_spec) ** 2, size=9, axis=1)
    shared = uniform_filter1d(target_spec * np.conj(other_spec), size=9, axis=1)
    coherence = np.clip(np.abs(shared) ** 2 / (target_power * other_power + 1e-12), 0.0, 1.0)
    other_fraction = other_power / (target_power + other_power + 1e-12)
    gain = np.clip(1.0 - strength * coherence * other_fraction, minimum_gain, 1.0)
    gain = uniform_filter1d(gain, size=3, axis=1)
    _, result = istft(target_spec * gain, fs=SAMPLE_RATE, nperseg=512, noverlap=384)
    return result[: retained.size].astype(np.float32)


def blend_after_time(
    before: np.ndarray,
    after: np.ndarray,
    start_sample: int,
    fade_samples: int,
) -> np.ndarray:
    """Crossfade two aligned experimental streams at a reported leak location."""
    if before.shape != after.shape or before.ndim != 1:
        raise ValueError("Both streams must have the same one-dimensional shape")
    if start_sample < 0 or fade_samples < 1:
        raise ValueError("Crossfade bounds must be nonnegative")
    output = before.copy()
    start = min(start_sample, len(output))
    end = min(start + fade_samples, len(output))
    if end > start:
        position = np.linspace(0.0, np.pi, end - start, endpoint=True)
        after_weight = (1.0 - np.cos(position)) / 2.0
        output[start:end] = before[start:end] * (1.0 - after_weight) + after[start:end] * after_weight
    output[end:] = after[end:]
    return output


def separate(model: torch.nn.Module, mixture: np.ndarray, block_seconds: int = 4) -> np.ndarray:
    block_size = SAMPLE_RATE * block_seconds
    hop_size = block_size // 2
    final_start = max(0, len(mixture) - block_size)
    hops = max(1, math.ceil(final_start / hop_size))
    starts = sorted({index * final_start // hops for index in range(hops + 1)})
    sums = np.zeros((len(mixture), 2), dtype=np.float64)
    weights = np.zeros(len(mixture), dtype=np.float64)
    window = 0.02 + 0.98 * np.hanning(block_size)
    previous = None
    previous_start = 0
    for start in starts:
        block = np.zeros(block_size, dtype=np.float32)
        usable = min(block_size, len(mixture) - start)
        block[:usable] = mixture[start : start + usable]
        with torch.inference_mode():
            output = model(torch.from_numpy(block[None, :])).cpu().numpy()[0]
        if output.shape != (block_size, 2) or not np.isfinite(output).all():
            raise ValueError(f"Unexpected separator output: {output.shape}")
        if previous is not None:
            overlap = previous_start + block_size - start
            same = sum(correlation(previous[-overlap:, i], output[:overlap, i]) for i in range(2))
            swapped = sum(correlation(previous[-overlap:, i], output[:overlap, 1 - i]) for i in range(2))
            if swapped > same + 0.05:
                output = output[:, ::-1]
        sums[start : start + usable] += output[:usable] * window[:usable, None]
        weights[start : start + usable] += window[:usable]
        previous = output
        previous_start = start
    print(f"blocks={len(starts)}, starts_samples={starts}")
    return (sums / weights[:, None]).astype(np.float32)


class PrecomputedSeparator:
    """Read QAIRT float32 outputs in the same order as the input blocks."""

    def __init__(self, result_dir: Path, block_samples: int) -> None:
        paths = sorted(result_dir.glob("Result_*/sources_16000hz.raw"), key=lambda p: int(p.parent.name.split("_")[-1]))
        if not paths or [int(p.parent.name.split("_")[-1]) for p in paths] != list(range(len(paths))):
            raise ValueError("QAIRT results must be consecutive Result_0, Result_1, ...")
        self.paths = paths
        self.block_samples = block_samples
        self.next_index = 0

    def __call__(self, block: torch.Tensor) -> torch.Tensor:
        if self.next_index >= len(self.paths):
            raise ValueError("Fewer QAIRT outputs than mixture blocks")
        path = self.paths[self.next_index]
        self.next_index += 1
        output = np.fromfile(path, dtype="<f4")
        if output.size != self.block_samples * 2:
            raise ValueError(f"Wrong QAIRT output size in {path}: {output.size}")
        return torch.from_numpy(output.reshape(1, self.block_samples, 2))

    def check_consumed(self) -> None:
        if self.next_index != len(self.paths):
            raise ValueError("More QAIRT outputs than mixture blocks")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mixture-wav", type=Path, required=True)
    parser.add_argument("--baseline-wav", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--checkpoint-dir", type=Path, default=Path("models/separation/speechbrain-sepformer-whamr16k"))
    parser.add_argument("--block-seconds", type=int, choices=(2, 4), default=4)
    parser.add_argument("--npu-block-dir", type=Path, help="Use QAIRT Result_N float32 outputs instead of the float checkpoint")
    parser.add_argument("--reported-leak-after-seconds", type=float)
    args = parser.parse_args()

    mixture, mixture_rate = read_pcm16(args.mixture_wav)
    baseline, baseline_rate = read_pcm16(args.baseline_wav)
    if mixture_rate != SAMPLE_RATE:
        mixture = resample_poly(mixture, SAMPLE_RATE, mixture_rate).astype(np.float32)
    if baseline_rate != SAMPLE_RATE:
        baseline = resample_poly(baseline, SAMPLE_RATE, baseline_rate).astype(np.float32)
    if not mixture.size or abs(len(mixture) - len(baseline)) > SAMPLE_RATE // 20:
        raise ValueError("Mixture and baseline must be the same recording and duration (within 50 ms)")
    baseline = baseline[: len(mixture)]
    if len(baseline) < len(mixture):
        baseline = np.pad(baseline, (0, len(mixture) - len(baseline)))

    torch.set_num_threads(min(4, torch.get_num_threads()))
    model = (PrecomputedSeparator(args.npu_block_dir, args.block_seconds * SAMPLE_RATE)
             if args.npu_block_dir else load_model(args.checkpoint_dir))
    sources = separate(model, mixture, args.block_seconds)
    if args.npu_block_dir:
        model.check_consumed()
    active = np.abs(baseline) > 0.01
    scores = [correlation(baseline[active], sources[active, i]) for i in range(2)]
    # SepFormer can flip waveform polarity; that does not change how it sounds.
    # Use magnitude only for this diagnostic, never for a privacy decision.
    likely_retained = int(np.argmax(np.abs(scores)))
    print(f"duration_seconds={len(mixture) / SAMPLE_RATE:.3f} block_seconds={args.block_seconds}")
    print(f"baseline_active_fraction={float(np.mean(active)):.3f}")
    print(f"baseline_correlations={scores}")
    print(f"likely_retained_source={likely_retained + 1} (correlation is NOT a privacy check)")
    prefix = "candidate-16k" if args.block_seconds == 4 else "candidate-16k-2s"
    if args.npu_block_dir:
        prefix += "-npu"
    for index in range(2):
        write_pcm16(args.output_dir / f"{prefix}-source-{index + 1}.wav", sources[:, index])
    other_index = 1 - likely_retained
    cleaned_versions = {}
    for name, strength in (("gentle", 0.45), ("strong", 0.8)):
        cleaned = suppress_coherent_leakage(sources[:, likely_retained], sources[:, other_index], strength)
        cleaned_versions[name] = cleaned
        write_pcm16(args.output_dir / f"{prefix}-{name}-EXPERIMENTAL.wav", cleaned)
    if args.npu_block_dir:
        for name in ("source-1", "source-2", "gentle-EXPERIMENTAL"):
            reference = args.output_dir / f"candidate-16k-2s-{name}.wav"
            candidate = args.output_dir / f"{prefix}-{name}.wav"
            if reference.is_file():
                left, left_rate = read_pcm16(reference)
                right, right_rate = read_pcm16(candidate)
                if left_rate != SAMPLE_RATE or right_rate != SAMPLE_RATE or left.shape != right.shape:
                    raise ValueError(f"Cannot compare misaligned WAVs: {reference} and {candidate}")
                print(f"{name}: npu_vs_float_cosine={correlation(left, right):.8f}, "
                      f"pcm_rmse={float(np.sqrt(np.mean(np.square(left - right)))):.8f}")
    if args.reported_leak_after_seconds is not None:
        if not 0 <= args.reported_leak_after_seconds <= len(mixture) / SAMPLE_RATE:
            raise ValueError("Reported leak time must be inside the recording")
        hybrid = blend_after_time(
            cleaned_versions["gentle"],
            cleaned_versions["strong"],
            int(args.reported_leak_after_seconds * SAMPLE_RATE),
            int(0.08 * SAMPLE_RATE),
        )
        write_pcm16(args.output_dir / f"{prefix}-gentle-then-strong-EXPERIMENTAL.wav", hybrid)


if __name__ == "__main__":
    main()
