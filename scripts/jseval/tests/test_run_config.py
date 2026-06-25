"""Tests for run_config.py — YAML config loading and env mapping."""

from __future__ import annotations

from pathlib import Path

import pytest

from jseval.run_config import apply_env_overrides, config_to_cli_args, load_config


class TestLoadConfig:
    def test_loads_yaml(self, tmp_path):
        config_file = tmp_path / "config.yaml"
        config_file.write_text(
            "dataset: scifact\n"
            "modes: [lexical, hybrid]\n"
            "embedding: true\n",
            encoding="utf-8",
        )
        config = load_config(config_file)
        assert config["dataset"] == "scifact"
        assert config["modes"] == ["lexical", "hybrid"]
        assert config["embedding"] is True

    def test_rejects_non_dict(self, tmp_path):
        config_file = tmp_path / "config.yaml"
        config_file.write_text("- item1\n- item2\n", encoding="utf-8")
        with pytest.raises(ValueError, match="YAML mapping"):
            load_config(config_file)


class TestApplyEnvOverrides:
    def test_gpu_config(self):
        config = {
            "gpu": {
                "embed": {"enabled": True, "layers": 32, "mem_mb": 2048},
                "splade": {"enabled": True},
            },
        }
        env = apply_env_overrides(config)
        assert env["JUSTSEARCH_EMBED_GPU_ENABLED"] == "True"
        assert env["JUSTSEARCH_EMBED_GPU_LAYERS"] == "32"
        assert env["JUSTSEARCH_EMBED_GPU_MEM_MB"] == "2048"
        assert env["JUSTSEARCH_SPLADE_GPU_ENABLED"] == "True"

    def test_gpu_master_switch(self):
        config = {"gpu": {"enabled": True}}
        env = apply_env_overrides(config)
        assert env["JUSTSEARCH_GPU_ENABLED"] == "True"

    def test_gpu_ner_and_bgem3(self):
        config = {
            "gpu": {
                "ner": {"enabled": True, "mem_mb": 512},
                "bgem3": {"enabled": True, "device_id": 1},
            },
        }
        env = apply_env_overrides(config)
        assert env["JUSTSEARCH_NER_GPU_ENABLED"] == "True"
        assert env["JUSTSEARCH_NER_GPU_MEM_MB"] == "512"
        assert env["JUSTSEARCH_BGE_M3_GPU_ENABLED"] == "True"
        assert env["JUSTSEARCH_BGE_M3_GPU_DEVICE_ID"] == "1"

    def test_direct_env_passthrough(self):
        config = {
            "env": {
                "MY_CUSTOM_VAR": "hello",
                "JUSTSEARCH_SOMETHING": "world",
            },
        }
        env = apply_env_overrides(config)
        assert env["MY_CUSTOM_VAR"] == "hello"
        assert env["JUSTSEARCH_SOMETHING"] == "world"

    def test_data_dir_and_port(self):
        config = {
            "data_dir": "/tmp/eval-data",
            "api_port": 33222,
        }
        env = apply_env_overrides(config)
        assert env["JUSTSEARCH_DATA_DIR"] == "/tmp/eval-data"
        assert env["JUSTSEARCH_API_PORT"] == "33222"

    def test_empty_config(self):
        env = apply_env_overrides({})
        assert env == {}


class TestConfigToCliArgs:
    def test_basic_args(self):
        config = {
            "dataset": "scifact",
            "modes": ["lexical", "hybrid"],
            "embedding": True,
            "splade": True,
            "pipeline": True,
            "top_k": 5,
            "max_queries": 100,
        }
        args = config_to_cli_args(config)
        assert args["dataset"] == "scifact"
        assert args["modes"] == "lexical,hybrid"
        assert args["embedding"] is True
        assert args["pipeline"] is True
        assert args["top_k"] == 5
        assert args["max_queries"] == 100

    def test_backend_config(self):
        config = {
            "dataset": "scifact",
            "backend": {"clean": True, "port": 33222},
        }
        args = config_to_cli_args(config)
        assert args["backend_clean"] is True
        assert args["backend_port"] == 33222

    def test_modes_string(self):
        config = {"modes": "lexical"}
        args = config_to_cli_args(config)
        assert args["modes"] == "lexical"

    def test_empty_config(self):
        args = config_to_cli_args({})
        assert args == {}
