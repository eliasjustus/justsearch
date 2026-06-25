"""YAML run configuration for reproducible eval runs (item 11)."""

from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

# Maps YAML config keys to environment variable names.
_ENV_MAP: dict[str, str] = {
    # GPU master switch (tempdoc 337) — enables GPU for all indexing models
    "gpu.enabled": "JUSTSEARCH_GPU_ENABLED",
    # GPU / embedding
    "gpu.embed.enabled": "JUSTSEARCH_EMBED_GPU_ENABLED",
    "gpu.embed.layers": "JUSTSEARCH_EMBED_GPU_LAYERS",
    "gpu.embed.mem_mb": "JUSTSEARCH_EMBED_GPU_MEM_MB",
    "gpu.embed.backend": "JUSTSEARCH_EMBED_BACKEND",
    "gpu.embed.onnx_model_path": "JUSTSEARCH_EMBED_ONNX_MODEL_PATH",
    # GPU / SPLADE
    "gpu.splade.enabled": "JUSTSEARCH_SPLADE_GPU_ENABLED",
    "gpu.splade.device_id": "JUSTSEARCH_SPLADE_GPU_DEVICE_ID",
    "gpu.splade.mem_mb": "JUSTSEARCH_SPLADE_GPU_MEM_MB",
    # GPU / NER
    "gpu.ner.enabled": "JUSTSEARCH_NER_GPU_ENABLED",
    "gpu.ner.device_id": "JUSTSEARCH_NER_GPU_DEVICE_ID",
    "gpu.ner.mem_mb": "JUSTSEARCH_NER_GPU_MEM_MB",
    # GPU / BGE-M3
    "gpu.bgem3.enabled": "JUSTSEARCH_BGE_M3_GPU_ENABLED",
    "gpu.bgem3.device_id": "JUSTSEARCH_BGE_M3_GPU_DEVICE_ID",
    "gpu.bgem3.mem_mb": "JUSTSEARCH_BGE_M3_GPU_MEM_MB",
    # GPU / reranker
    "gpu.rerank.enabled": "JUSTSEARCH_RERANK_GPU_ENABLED",
    "gpu.rerank.mem_mb": "JUSTSEARCH_RERANK_GPU_MEM_MB",
    # General
    "gpu.layers": "JUSTSEARCH_GPU_LAYERS",
    "data_dir": "JUSTSEARCH_DATA_DIR",
    "api_port": "JUSTSEARCH_API_PORT",
    "models_dir": "JUSTSEARCH_MODELS_DIR",
    "index_schema_mismatch_policy": "JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY",
    # Search pipeline (343)
    "search.chunk_aware_enabled": "JUSTSEARCH_SEARCH_CHUNK_AWARE_ENABLED",
    "search.cc_weight_sparse": "JUSTSEARCH_HYBRID_CC_WEIGHT_SPARSE",
    "search.cc_weight_dense": "JUSTSEARCH_HYBRID_CC_WEIGHT_DENSE",
    "search.cc_weight_splade": "JUSTSEARCH_HYBRID_CC_WEIGHT_SPLADE",
    "search.branch_weight_whole": "JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_WHOLE",
    "search.branch_weight_chunk": "JUSTSEARCH_HYBRID_BRANCH_CC_WEIGHT_CHUNK",
    # LLM / inference (369)
    "llm.enabled": "JUSTSEARCH_AI_AUTOSTART_ENABLED",
}


def load_config(path: Path) -> dict:
    """Load a YAML run configuration file.

    Returns a dict with keys: dataset, modes, embedding, splade, pipeline,
    env (dict of env var overrides), and any other top-level keys.
    """
    try:
        import yaml
    except ImportError:
        raise ImportError(
            "PyYAML is required for run config files. "
            "Install with: pip install pyyaml"
        )

    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"Run config must be a YAML mapping, got {type(data).__name__}")

    return data


def apply_env_overrides(config: dict) -> dict[str, str]:
    """Extract environment variable overrides from a config dict.

    Walks the config's nested structure and maps known keys to env vars.
    Returns a dict of {env_var_name: value_string} ready for os.environ.
    """
    env: dict[str, str] = {}

    # Direct env overrides (passthrough)
    if "env" in config and isinstance(config["env"], dict):
        for k, v in config["env"].items():
            env[k] = str(v)

    # Structured config → env var mapping
    flat = _flatten(config)
    for config_key, env_var in _ENV_MAP.items():
        if config_key in flat:
            env[env_var] = str(flat[config_key])

    return env


def config_to_cli_args(config: dict) -> dict[str, Any]:
    """Extract CLI-compatible arguments from a config dict.

    Returns a dict with keys matching cmd_run parameter names.
    """
    args: dict[str, Any] = {}

    if "dataset" in config:
        args["dataset"] = config["dataset"]
    if "modes" in config:
        modes = config["modes"]
        if isinstance(modes, list):
            args["modes"] = ",".join(str(m) for m in modes)
        else:
            args["modes"] = str(modes)
    if "embedding" in config:
        args["embedding"] = bool(config["embedding"])
    if "splade" in config:
        args["splade"] = bool(config["splade"])
    if "pipeline" in config:
        args["pipeline"] = bool(config["pipeline"])
    if "top_k" in config:
        args["top_k"] = int(config["top_k"])
    if "max_queries" in config:
        args["max_queries"] = int(config["max_queries"])
    if "output_dir" in config:
        args["output_dir"] = config["output_dir"]
    if "context_coverage" in config:
        args["context_coverage"] = bool(config["context_coverage"])

    backend = config.get("backend", {})
    if isinstance(backend, dict):
        if "clean" in backend:
            args["backend_clean"] = bool(backend["clean"])
        if "port" in backend:
            args["backend_port"] = int(backend["port"])

    return args


def _flatten(d: dict, prefix: str = "") -> dict[str, Any]:
    """Flatten a nested dict with dot-separated keys."""
    items: dict[str, Any] = {}
    for k, v in d.items():
        key = f"{prefix}{k}" if not prefix else f"{prefix}.{k}"
        if isinstance(v, dict):
            items.update(_flatten(v, key))
        else:
            items[key] = v
    return items
