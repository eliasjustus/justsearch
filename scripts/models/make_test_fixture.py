#!/usr/bin/env python3
"""
Build the tiny ONNX fixtures used by ModelCapabilityResolverEmbeddedMetadataTest
(tempdoc 711 Item 3).

Produces two ~1KB single-node graphs (Identity op, float[4] in/out — small
committed as base64 text (dodges the repo-wide *.onnx LFS rule; CI has no LFS blobs)
weight of a real model):

  modules/ort-common/src/test/resources/capability-fixtures/stamped.onnx.b64
    — metadata_props stamped via _common.stamp_capabilities() with a known,
      fully-declared capability set. Exercises the embedded-metadata read
      rung in ModelCapabilityResolver.

  modules/ort-common/src/test/resources/capability-fixtures/unstamped.onnx.b64
    — identical graph, zero metadata_props. Exercises the "absence of
      embedded metadata falls through to the existing rungs unchanged" case.

Usage:
    python scripts/models/make_test_fixture.py
"""

import sys
from pathlib import Path

import onnx
from onnx import TensorProto, helper

sys.path.insert(0, str(Path(__file__).parent))
from _common import stamp_capabilities  # noqa: E402

FIXTURE_CAPABILITIES = {
    "pooling_mode": "cls",
    "context_length": 512,
    "embedding_dimension": 4,
    "cpu_precision": "fp32",
    "gpu_precision": "fp16",
    "document_prefix": "search_document: ",
    "query_prefix": "search_query: ",
    "_comment": "tempdoc 711 Item 3 test fixture — not a real model, do not use for inference",
}


def build_minimal_graph() -> onnx.ModelProto:
    x = helper.make_tensor_value_info("input", TensorProto.FLOAT, [4])
    y = helper.make_tensor_value_info("output", TensorProto.FLOAT, [4])
    node = helper.make_node("Identity", ["input"], ["output"], name="identity")
    graph = helper.make_graph([node], "capability_fixture_graph", [x], [y])
    model = helper.make_model(graph, producer_name="justsearch-capability-fixture")
    model.opset_import[0].version = 18
    onnx.checker.check_model(model)
    return model


def main():
    repo_root = Path(__file__).resolve().parents[2]
    fixtures_dir = repo_root / "modules" / "ort-common" / "src" / "test" / "resources" / "capability-fixtures"
    fixtures_dir.mkdir(parents=True, exist_ok=True)

    # Committed as base64 TEXT (.onnx.b64), NOT as .onnx binaries: the repo-wide *.onnx
    # LFS rule would capture them, and CI checks out without LFS blobs, so tests would
    # receive pointer files (PR #140 first CI run). The Java test materializes them.
    import base64, tempfile

    with tempfile.TemporaryDirectory() as td:
        stamped_path = Path(td) / "stamped.onnx"
        onnx.save(build_minimal_graph(), str(stamped_path))
        stamped_keys = stamp_capabilities(stamped_path, FIXTURE_CAPABILITIES)
        out = fixtures_dir / "stamped.onnx.b64"
        out.write_text(base64.b64encode(stamped_path.read_bytes()).decode("ascii"), encoding="ascii")
        print(f"Wrote {out} ({stamped_path.stat().st_size} raw bytes), stamped keys: {stamped_keys}")

        unstamped_path = Path(td) / "unstamped.onnx"
        onnx.save(build_minimal_graph(), str(unstamped_path))
        out = fixtures_dir / "unstamped.onnx.b64"
        out.write_text(base64.b64encode(unstamped_path.read_bytes()).decode("ascii"), encoding="ascii")
        print(f"Wrote {out} ({unstamped_path.stat().st_size} raw bytes), no metadata_props")


if __name__ == "__main__":
    main()
