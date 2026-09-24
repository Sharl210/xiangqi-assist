#!/usr/bin/env python3
"""Build XQDK's Large YOLO-compatible ensemble model.

The project already has two GPL-compatible XQ chess detectors with the same
[1, 640, 640, 3] -> [1, 25200, 20] contract:

* the Medium detector; and
* the universal/rotation-tolerant detector.

This script combines their per-anchor predictions.  The anchor with the higher
objectness*best-class score is selected, so the result remains a normal XQ
YOLO output tensor while evaluating both detectors.  It is intentionally an
ensemble Large model rather than a renamed copy of Medium.

The source SavedModels are generated from the VinXiangQi-derived ONNX models.
Before building, verify their hashes and the GPL-3.0 attribution recorded in
`docs/YOLO模型档位评估.md`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import tensorflow as tf

INPUT_SHAPE = (1, 640, 640, 3)
OUTPUT_SHAPE = (1, 25200, 20)


class LargeEnsemble(tf.Module):
    def __init__(self, medium, universal):
        super().__init__()
        self.medium = medium
        self.universal = universal

    @tf.function(input_signature=[tf.TensorSpec(INPUT_SHAPE, tf.float32, name="images")])
    def serving_default(self, images):
        medium_out = self.medium(images=images)["output"]
        universal_out = self.universal(images=images)["output"]
        medium_score = medium_out[..., 4:5] * tf.reduce_max(
            medium_out[..., 5:], axis=-1, keepdims=True
        )
        universal_score = universal_out[..., 4:5] * tf.reduce_max(
            universal_out[..., 5:], axis=-1, keepdims=True
        )
        return {"output": tf.where(universal_score > medium_score, universal_out, medium_out)}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--medium", type=Path, required=True, help="Medium SavedModel directory")
    parser.add_argument("--universal", type=Path, required=True, help="Universal SavedModel directory")
    parser.add_argument("--out", type=Path, required=True, help="Output directory")
    args = parser.parse_args()

    medium = tf.saved_model.load(str(args.medium))
    universal = tf.saved_model.load(str(args.universal))
    medium_fn = medium.signatures["serving_default"]
    universal_fn = universal.signatures["serving_default"]
    if medium_fn.structured_outputs["output"].shape != tf.TensorShape(OUTPUT_SHAPE):
        raise ValueError(f"Medium output contract mismatch: {medium_fn.structured_outputs}")
    if universal_fn.structured_outputs["output"].shape != tf.TensorShape(OUTPUT_SHAPE):
        raise ValueError(f"Universal output contract mismatch: {universal_fn.structured_outputs}")

    args.out.mkdir(parents=True, exist_ok=True)
    ensemble = LargeEnsemble(medium_fn, universal_fn)
    concrete = ensemble.serving_default.get_concrete_function()
    tf.saved_model.save(ensemble, str(args.out), signatures={"serving_default": concrete})

    converter = tf.lite.TFLiteConverter.from_saved_model(
        str(args.out), signature_keys=["serving_default"]
    )
    converter.target_spec.supported_types = [tf.float32]
    model = converter.convert()
    tflite_path = args.out / "yolov5l_xq_fp32.tflite"
    tflite_path.write_bytes(model)

    metadata = {
        "kind": "XQ YOLO Large ensemble",
        "selection": "per-anchor objectness * best-class score; universal wins ties over Medium",
        "input_shape": list(INPUT_SHAPE),
        "output_shape": list(OUTPUT_SHAPE),
        "dtype": "float32",
        "medium_saved_model": str(args.medium),
        "universal_saved_model": str(args.universal),
        "output_file": str(tflite_path),
        "output_sha256": sha256(tflite_path),
        "output_bytes": len(model),
    }
    (args.out / "SOURCE.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
