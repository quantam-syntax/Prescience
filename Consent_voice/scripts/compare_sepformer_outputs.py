#!/usr/bin/env python3
"""Compare a QAIRT SepFormer output with the float ONNX reference."""

from __future__ import annotations

import argparse
from itertools import permutations
from pathlib import Path

import numpy as np
import onnxruntime as ort


def cosine(left: np.ndarray, right: np.ndarray) -> float:
    denominator = np.linalg.norm(left) * np.linalg.norm(right)
    return float(np.dot(left, right) / denominator) if denominator else 0.0


def si_sdr(reference: np.ndarray, estimate: np.ndarray) -> float:
    reference = reference - reference.mean()
    estimate = estimate - estimate.mean()
    scale = np.dot(estimate, reference) / (np.dot(reference, reference) + 1e-12)
    target = scale * reference
    noise = estimate - target
    return float(10.0 * np.log10((np.dot(target, target) + 1e-12) / (np.dot(noise, noise) + 1e-12)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--min-cosine", type=float, default=0.98)
    parser.add_argument("--min-si-sdr-db", type=float, default=15.0)
    args = parser.parse_args()

    mixture = np.fromfile(args.input, dtype="<f4").reshape(1, -1)
    session = ort.InferenceSession(str(args.onnx), providers=["CPUExecutionProvider"])
    reference = session.run(None, {session.get_inputs()[0].name: mixture})[0].reshape(-1, 2)
    candidate = np.fromfile(args.candidate, dtype="<f4").reshape(-1, 2)
    if candidate.shape != reference.shape:
        raise ValueError(f"Shape mismatch: candidate {candidate.shape}, reference {reference.shape}")

    best = None
    for order in permutations(range(2)):
        aligned = candidate[:, order]
        cosines = [cosine(reference[:, i], aligned[:, i]) for i in range(2)]
        score = sum(cosines)
        if best is None or score > best[0]:
            best = (score, order, aligned, cosines)

    assert best is not None
    _, order, aligned, cosines = best
    print(f"speaker_order={order}")
    print(f"mae={np.mean(np.abs(reference - aligned)):.8f}")
    print(f"rmse={np.sqrt(np.mean(np.square(reference - aligned))):.8f}")
    print(f"cosine_speaker_0={cosines[0]:.8f}")
    print(f"cosine_speaker_1={cosines[1]:.8f}")
    si_sdrs = [si_sdr(reference[:, i], aligned[:, i]) for i in range(2)]
    print(f"si_sdr_speaker_0_db={si_sdrs[0]:.4f}")
    print(f"si_sdr_speaker_1_db={si_sdrs[1]:.4f}")
    print(f"reference_peak={np.max(np.abs(reference)):.8f}")
    print(f"candidate_peak={np.max(np.abs(aligned)):.8f}")
    if min(cosines) < args.min_cosine or min(si_sdrs) < args.min_si_sdr_db:
        raise SystemExit(
            "REJECTED: output failed the minimum channel fidelity gate "
            f"(cosine >= {args.min_cosine}, SI-SDR >= {args.min_si_sdr_db} dB)"
        )
    print("ACCEPTED: output passed the channel fidelity gate")


if __name__ == "__main__":
    main()
