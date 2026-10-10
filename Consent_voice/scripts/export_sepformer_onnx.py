"""Reconstruct the local SpeechBrain SepFormer checkpoint and export fixed-shape ONNX.

The output is an experiment for operator inspection and QNN conversion. It is not
an application asset until numerical, quality, latency, memory and device tests pass.
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
import torch.nn.functional as functional
from hyperpyyaml import load_hyperpyyaml


class SepFormerExport(torch.nn.Module):
    def __init__(self, encoder: torch.nn.Module, masknet: torch.nn.Module, decoder: torch.nn.Module):
        super().__init__()
        self.encoder = encoder
        self.masknet = masknet
        self.decoder = decoder

    def forward(self, mixture: torch.Tensor) -> torch.Tensor:
        encoded = self.encoder(mixture)
        masks = self.masknet(encoded)
        separated = torch.stack((encoded, encoded)) * masks
        sources = torch.cat(
            (
                self.decoder(separated[0]).unsqueeze(-1),
                self.decoder(separated[1]).unsqueeze(-1),
            ),
            dim=-1,
        )
        original_length = mixture.shape[1]
        estimated_length = sources.shape[1]
        if original_length > estimated_length:
            sources = functional.pad(sources, (0, 0, 0, original_length - estimated_length))
        return sources[:, :original_length, :]


def load_model(checkpoint_dir: Path) -> SepFormerExport:
    with (checkpoint_dir / "hyperparams.yaml").open("r", encoding="utf-8") as stream:
        config = load_hyperpyyaml(stream)
    modules = config["modules"]
    for name in ("encoder", "masknet", "decoder"):
        state = torch.load(checkpoint_dir / f"{name}.ckpt", map_location="cpu", weights_only=True)
        modules[name].load_state_dict(state, strict=True)
        modules[name].eval()
    return SepFormerExport(modules["encoder"], modules["masknet"], modules["decoder"]).eval()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--checkpoint-dir",
        type=Path,
        default=Path("models/separation/speechbrain-sepformer-wsj02mix"),
    )
    parser.add_argument("--output", type=Path, default=Path("models/qnn-export/sepformer-wsj02mix-4s.onnx"))
    parser.add_argument("--seconds", type=int, default=4)
    args = parser.parse_args()

    if args.seconds < 1:
        raise ValueError("--seconds must be positive")
    sample_rate = 8_000
    sample_count = sample_rate * args.seconds
    args.output.parent.mkdir(parents=True, exist_ok=True)

    # Disable the inference-only fused attention path so ONNX sees portable operators.
    torch.backends.mha.set_fastpath_enabled(False)
    torch.manual_seed(7)
    model = load_model(args.checkpoint_dir)
    example = torch.randn(1, sample_count, dtype=torch.float32) * 0.01

    started = time.perf_counter()
    with torch.inference_mode():
        reference = model(example).cpu().numpy()
    pytorch_ms = (time.perf_counter() - started) * 1_000

    torch.onnx.export(
        model,
        (example,),
        args.output,
        input_names=["mixture_8khz"],
        output_names=["sources_8khz"],
        opset_version=17,
        do_constant_folding=True,
        dynamic_axes=None,
    )

    graph = onnx.load(args.output)
    onnx.checker.check_model(graph)
    operator_types = sorted({node.op_type for node in graph.graph.node})

    session = ort.InferenceSession(str(args.output), providers=["CPUExecutionProvider"])
    started = time.perf_counter()
    candidate = session.run(["sources_8khz"], {"mixture_8khz": example.numpy()})[0]
    onnx_ms = (time.perf_counter() - started) * 1_000
    max_error = float(np.max(np.abs(reference - candidate)))
    cosine = float(np.dot(reference.ravel(), candidate.ravel()) / (
        np.linalg.norm(reference.ravel()) * np.linalg.norm(candidate.ravel()) + 1e-12
    ))

    print(f"output={args.output.resolve()}")
    print(f"bytes={args.output.stat().st_size}")
    print(f"input_shape={tuple(example.shape)} output_shape={tuple(candidate.shape)}")
    print(f"pytorch_ms={pytorch_ms:.1f} onnxruntime_ms={onnx_ms:.1f}")
    print(f"max_abs_error={max_error:.8f} cosine={cosine:.8f}")
    print("operators=" + ",".join(operator_types))
    if cosine < 0.999:
        raise RuntimeError(f"ONNX numerical agreement is too low: {cosine}")


if __name__ == "__main__":
    main()
