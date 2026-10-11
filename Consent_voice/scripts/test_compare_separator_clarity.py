"""Small signal-level checks for the experimental leakage postfilter."""

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np

from compare_separator_clarity import PrecomputedSeparator, SAMPLE_RATE, blend_after_time, suppress_coherent_leakage


class LeakagePostfilterTest(unittest.TestCase):
    def test_reduces_coherent_interference_without_erasing_target_tone(self) -> None:
        time = np.arange(SAMPLE_RATE * 2, dtype=np.float32) / SAMPLE_RATE
        target = np.sin(2 * np.pi * 310 * time).astype(np.float32)
        interference = np.sin(2 * np.pi * 970 * time).astype(np.float32)
        retained = target + 0.25 * interference
        result = suppress_coherent_leakage(retained, interference, strength=0.8)

        def projection(signal: np.ndarray, reference: np.ndarray) -> float:
            return float(np.dot(signal, reference) / np.dot(reference, reference))

        self.assertEqual(len(result), len(retained))
        self.assertTrue(np.isfinite(result).all())
        self.assertGreater(projection(result, target), 0.9)
        self.assertLess(projection(result, interference), 0.125)

    def test_silent_reference_does_not_change_target(self) -> None:
        target = np.linspace(-0.25, 0.25, SAMPLE_RATE, dtype=np.float32)
        result = suppress_coherent_leakage(target, np.zeros_like(target), strength=0.8)
        np.testing.assert_array_equal(result, target)

    def test_rejects_misaligned_sources(self) -> None:
        with self.assertRaises(ValueError):
            suppress_coherent_leakage(np.zeros(100), np.zeros(99), strength=0.8)

    def test_hybrid_crossfade_preserves_length_and_endpoints(self) -> None:
        before = np.zeros(100, dtype=np.float32)
        after = np.ones(100, dtype=np.float32)
        result = blend_after_time(before, after, start_sample=40, fade_samples=20)
        self.assertEqual(len(result), 100)
        self.assertTrue(np.all(result[:41] == 0))
        self.assertTrue(np.all(result[59:] == 1))
        self.assertTrue(np.all(np.diff(result) >= 0))

    def test_precomputed_separator_rejects_missing_or_truncated_results(self) -> None:
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                PrecomputedSeparator(root, 4)
            result = root / "Result_0"
            result.mkdir()
            np.zeros(7, dtype="<f4").tofile(result / "sources_16000hz.raw")
            separator = PrecomputedSeparator(root, 4)
            with self.assertRaises(ValueError):
                separator(None)


if __name__ == "__main__":
    unittest.main()
