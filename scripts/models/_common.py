"""Shared utilities for model build scripts."""

import hashlib
import json
import subprocess
import sys
from pathlib import Path


def sha256_file(path: Path) -> str:
    """Compute SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_model(model_path: Path):
    """Run CPU smoke test via verify-model.py. Exits on failure."""
    script = Path(__file__).parent / "verify-model.py"
    if not script.exists():
        print(f"  WARNING: {script} not found, skipping verification")
        return
    print(f"  Verifying {model_path.name}...")
    result = subprocess.run(
        [sys.executable, str(script), str(model_path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print(f"  VERIFICATION FAILED:\n{result.stderr}", file=sys.stderr)
        sys.exit(1)
    print(f"  Verification OK")


def resolve_hf_commit(repo_id: str) -> str | None:
    """Resolve the current HEAD commit hash for a HuggingFace repo.

    Returns the 40-character SHA, or None if the API call fails
    (e.g., no network, repo doesn't exist).
    """
    try:
        from huggingface_hub import model_info
        info = model_info(repo_id)
        return info.sha
    except Exception as e:
        print(f"  WARNING: Could not resolve commit hash for {repo_id}: {e}")
        return None


def posix_relpath(path: Path) -> str:
    """Return a forward-slash relative path string for use in build commands.

    Converts Windows backslashes to forward slashes so build_command is
    portable and copy-pasteable on any platform.
    """
    return str(path).replace("\\", "/")


def get_tool_versions() -> dict:
    """Collect installed tool versions relevant to model builds."""
    versions = {}
    for mod_name in ("onnx", "onnxruntime", "transformers", "numpy",
                     "huggingface_hub"):
        try:
            mod = __import__(mod_name)
            versions[mod_name] = mod.__version__
        except (ImportError, AttributeError):
            pass
    # optimum uses importlib.metadata for its version
    try:
        from importlib.metadata import version
        versions["optimum"] = version("optimum")
    except Exception:
        pass
    return versions


# Manifest `capabilities` field name -> reverse-DNS ONNX metadata_props key
# (tempdoc 711 Item 3). `_comment` is deliberately excluded — it's authoring
# provenance prose, not a resolvable capability fact
# (io.justsearch.ort.ModelCapabilityResolver never reads it).
_METADATA_PROPS_KEY_MAP = {
    "pooling_mode": "io.justsearch.pooling_mode",
    "context_length": "io.justsearch.context_length",
    "embedding_dimension": "io.justsearch.embedding_dimension",
    "cpu_precision": "io.justsearch.cpu_precision",
    "gpu_precision": "io.justsearch.gpu_precision",
    "document_prefix": "io.justsearch.document_prefix",
    "query_prefix": "io.justsearch.query_prefix",
}


def load_manifest_capabilities(model_dir: Path) -> dict:
    """Read model_manifest.json's `capabilities` section, if present.

    Single authoring surface (tempdoc 711 Item 3): the manifest stays the one
    place a human declares a capability fact; stamp_capabilities() projects
    it into the ONNX file rather than the build script re-deriving it.

    Returns an empty dict if the manifest or its `capabilities` section is
    absent — legacy model dirs predating tempdoc 710 Wave 2's manifest
    authoring pass have neither, and build scripts must not crash on them.
    """
    manifest_path = Path(model_dir) / "model_manifest.json"
    if not manifest_path.exists():
        return {}
    with open(manifest_path) as f:
        manifest = json.load(f)
    return manifest.get("capabilities") or {}


def stamp_capabilities(model_path: Path, capabilities: dict) -> list[str]:
    """Embed `capabilities` into `model_path`'s ONNX metadata_props.

    Upserts a reverse-DNS `io.justsearch.*` key (see
    `_METADATA_PROPS_KEY_MAP`) for every capability present and non-null in
    `capabilities` — a manifest-declared empty string (e.g. `""` for a
    task-instruction prefix that's declared-but-empty) IS stamped, since it
    is non-null; a key absent from `capabilities` (undeclared) is never
    stamped. `io.justsearch.ort.ModelCapabilityResolver` reads these keys as
    the rung directly below the manifest itself.

    Round-trips the model file (onnx.load -> mutate -> onnx.save) so it can
    run as a post-hoc pass after any build path (in-memory ModelProto before
    its own onnx.save, a quantize_dynamic() call that writes the file
    directly, or a pre-built file downloaded verbatim) — every build script
    calls this the same way, once per finalized .onnx artifact.

    Returns the list of stamped `io.justsearch.*` keys (empty if
    `capabilities` had nothing stampable) for the caller to record in its
    build.json payload as `stamped_metadata_keys`.
    """
    import onnx

    stampable = {
        _METADATA_PROPS_KEY_MAP[field]: value
        for field, value in capabilities.items()
        if field in _METADATA_PROPS_KEY_MAP and value is not None
    }
    if not stampable:
        return []

    model = onnx.load(str(model_path))
    existing_by_key = {entry.key: entry for entry in model.metadata_props}
    stamped_keys = []
    for key, value in stampable.items():
        value_str = str(value)
        if key in existing_by_key:
            existing_by_key[key].value = value_str
        else:
            entry = model.metadata_props.add()
            entry.key = key
            entry.value = value_str
        stamped_keys.append(key)
    onnx.save(model, str(model_path))
    return stamped_keys
