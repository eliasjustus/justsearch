"""Shared utilities for model build scripts."""

import hashlib
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
