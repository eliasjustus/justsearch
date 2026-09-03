import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "model_promotion_planner.py"
FIXTURES = Path(__file__).parent / "fixtures"
REPO_ROOT = Path(__file__).parents[3]
spec = importlib.util.spec_from_file_location("model_promotion_planner", SCRIPT)
planner = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = planner
assert spec.loader is not None
spec.loader.exec_module(planner)


def load(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def fixture_state():
    return {
        path.relative_to(FIXTURES).as_posix(): (path.stat().st_size, path.stat().st_mtime_ns, planner._sha256(path))
        for path in sorted(FIXTURES.rglob("*")) if path.is_file()
    }


class ModelPromotionPlannerTest(unittest.TestCase):
    def setUp(self):
        self.registry = load("registry.fixture.json")
        self.candidate = load("candidate-ready.fixture.json")
        self.candidate_path = (FIXTURES / "candidate-ready.fixture.json").resolve()

    def plan(self, candidate=None, registry=None, package_id="embedding"):
        return planner.build_plan(registry or self.registry, candidate or self.candidate, self.candidate_path, package_id)

    def test_deterministic_and_write_free(self):
        before = fixture_state()
        first = json.dumps(self.plan(), indent=2, sort_keys=True)
        second = json.dumps(self.plan(), indent=2, sort_keys=True)
        self.assertEqual(first, second)
        self.assertEqual(before, fixture_state())
        self.assertEqual("ready", self.plan()["status"])

    def test_cli_is_deterministic_and_write_free(self):
        before = fixture_state()
        command = [sys.executable, str(SCRIPT), "--registry", str(FIXTURES / "registry.fixture.json"), "--package", "embedding", "--candidate", str(self.candidate_path)]
        first = subprocess.run(command, check=True, capture_output=True, text=True).stdout
        second = subprocess.run(command, check=True, capture_output=True, text=True).stdout
        self.assertEqual(first, second)
        self.assertEqual(before, fixture_state())

    def test_explicit_noop(self):
        registry = copy.deepcopy(self.registry)
        registry["packages"][0] = copy.deepcopy(self.candidate["proposedPackage"])
        candidate = copy.deepcopy(self.candidate)
        candidate["projections"]["model-inventory"] = {
            "status": "no-change",
            "ref": "fixture://inventory",
            "result": "unchanged",
            "diff": "",
        }
        plan = self.plan(registry=registry, candidate=candidate)
        self.assertEqual("no-op", plan["status"])
        self.assertEqual("not-required", plan["indexMigration"])

    def test_noop_with_projection_drift_blocks(self):
        registry = copy.deepcopy(self.registry)
        registry["packages"][0] = copy.deepcopy(self.candidate["proposedPackage"])
        plan = self.plan(registry=registry)
        self.assertEqual("blocked", plan["status"])
        self.assertIn("no-op-projections", plan["blockers"])

    def test_missing_onnx_provenance_blocks(self):
        candidate = copy.deepcopy(self.candidate)
        del candidate["provenance"]["source"]["revision"]
        with self.assertRaisesRegex(planner.InvalidInput, "missing required properties: revision"):
            self.plan(candidate=candidate)

    def test_candidate_schema_rejects_unknown_property_and_invalid_uri(self):
        candidate = copy.deepcopy(self.candidate)
        candidate["unexpected"] = True
        with self.assertRaisesRegex(planner.InvalidInput, "unknown properties: unexpected"):
            self.plan(candidate=candidate)

        candidate = copy.deepcopy(self.candidate)
        candidate["proposedPackage"]["termsUrl"] = "not a URI"
        with self.assertRaisesRegex(planner.InvalidInput, "must be an absolute URI"):
            self.plan(candidate=candidate)

    def test_incomplete_variants_and_support_block(self):
        candidate = copy.deepcopy(self.candidate)
        candidate["proposedPackage"]["variants"] = candidate["proposedPackage"]["variants"][:1]
        candidate["proposedPackage"]["supportingFiles"] = candidate["proposedPackage"]["supportingFiles"][:1]
        plan = self.plan(candidate=candidate)
        self.assertIn("variant-closure", plan["blockers"])
        self.assertIn("supporting-file-closure", plan["blockers"])

    def test_reusing_url_for_changed_bytes_blocks(self):
        candidate = copy.deepcopy(self.candidate)
        old_url = self.registry["packages"][0]["variants"][0]["downloadUrl"]
        candidate["proposedPackage"]["variants"][0]["downloadUrl"] = old_url
        candidate["remoteVerification"]["cpu.bytes"]["url"] = old_url
        plan = self.plan(candidate=candidate)
        self.assertIn("changed-bytes-new-url", plan["blockers"])

    def test_index_affecting_classification_is_exact(self):
        actual = {key for key, value in planner.ADAPTERS.items() if value.index_affecting}
        self.assertEqual({"embedding", "splade", "ner"}, actual)

    def test_gguf_support_and_provenance_are_separate_blockers(self):
        registry = copy.deepcopy(self.registry)
        current = {
            "id": "chat",
            "termsUrl": "https://huggingface.co/example/chat",
            "license": "Apache-2.0",
            "variants": [{"filename": "chat.gguf", "precision": "GGUF", "targetEP": "LLAMA_SERVER", "sha256": "A" * 64, "sizeBytes": 1, "downloadUrl": "https://example.invalid/releases/download/old/chat.gguf"}],
            "supportingFiles": [{"filename": "mmproj-F16.gguf", "sha256": "B" * 64, "sizeBytes": 1, "downloadUrl": "https://example.invalid/releases/download/old/mmproj-F16.gguf"}],
        }
        registry["packages"] = [current if item["id"] == "chat" else item for item in registry["packages"]]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staged = root / "staged"
            staged.mkdir()
            chat_path = staged / "chat.gguf"
            chat_path.write_bytes(b"chat")
            sha = planner._sha256(chat_path)
            candidate = {
                "schemaVersion": 1,
                "packageId": "chat",
                "stagedRoot": "staged",
                "proposedPackage": {
                    "id": "chat", "termsUrl": current["termsUrl"], "license": current["license"],
                    "variants": [{"filename": "chat.gguf", "precision": "GGUF", "targetEP": "LLAMA_SERVER", "sha256": sha, "sizeBytes": 4, "downloadUrl": "https://example.invalid/releases/download/new/chat.gguf"}],
                    "supportingFiles": [],
                },
                "provenance": {
                    "kind": "gguf-source",
                    "sources": {"unrelated.gguf": {}},
                    "quantization": {"tool": "fixture", "format": "fixture"},
                    "toolVersions": {"fixture": "1"},
                },
                "remoteVerification": {"chat.gguf": {"status": "pass", "url": "https://example.invalid/releases/download/new/chat.gguf", "sha256": sha, "sizeBytes": 4}},
                "evidence": {
                    "license-approval": {
                        "status": "pass", "ref": "fixture://license-review", "license": "Apache-2.0",
                    },
                },
                "projections": {
                    "model-inventory": {
                        "status": "pass", "ref": "fixture://inventory", "result": "updated", "diff": "+chat fixture",
                    },
                    "notices": {
                        "status": "no-change", "ref": "fixture://notices", "result": "unchanged", "diff": "",
                    },
                },
            }
            plan = planner.build_plan(registry, candidate, root / "candidate.json", "chat")
        self.assertIn("supporting-file-closure", plan["blockers"])
        self.assertIn("provenance-complete", plan["blockers"])
        self.assertEqual("not-required", plan["indexMigration"])

    def test_notice_noop_and_identity_license_drift(self):
        unchanged = self.plan()
        self.assertEqual("no-op", unchanged["changes"]["noticeProjection"])

        changed_candidate = copy.deepcopy(self.candidate)
        changed_candidate["proposedPackage"]["termsUrl"] = "https://huggingface.co/example/new-embedding"
        changed_candidate["proposedPackage"]["license"] = "MIT"
        changed_candidate["evidence"]["license-approval"]["license"] = "MIT"
        changed_candidate["projections"]["notices"] = {
            "status": "pass",
            "ref": "fixture://notices",
            "result": "updated",
            "diff": "-Apache-2.0\n+MIT",
        }
        changed = self.plan(candidate=changed_candidate)
        self.assertEqual("regenerate", changed["changes"]["noticeProjection"])
        self.assertNotIn("notice-projection-classification", changed["blockers"])

    def test_remote_and_runtime_evidence_are_blocking(self):
        candidate = copy.deepcopy(self.candidate)
        del candidate["remoteVerification"]["gpu.bytes"]
        del candidate["evidence"]["gpu-production"]
        plan = self.plan(candidate=candidate)
        self.assertIn("remote-byte-verification", plan["blockers"])
        self.assertIn("evidence-gpu-production", plan["blockers"])

    def test_review_bundle_retains_provenance_remote_evidence_and_projection_details(self):
        plan = self.plan()
        self.assertEqual(planner._canonical(self.candidate["provenance"]), plan["provenance"])
        self.assertEqual(planner._canonical(self.candidate["remoteVerification"]), plan["remoteVerification"])
        self.assertEqual("fixture://gpu", plan["evidence"]["gpu-production"]["ref"])
        self.assertEqual("fixture://license-review", plan["evidence"]["license-approval"]["ref"])
        self.assertEqual("updated", plan["projections"]["model-inventory"]["result"])
        self.assertIn("+new fixture", plan["projections"]["model-inventory"]["diff"])
        self.assertEqual("", plan["projections"]["notices"]["diff"])
        human = planner.render_human(plan)
        self.assertIn('\"kind\": \"onnx-build\"', human)
        self.assertIn("fixture://license-review", human)
        self.assertIn("+new fixture", human)

    def test_missing_license_approval_blocks_ready_candidate(self):
        candidate = copy.deepcopy(self.candidate)
        del candidate["evidence"]["license-approval"]
        plan = self.plan(candidate=candidate)
        self.assertEqual("blocked", plan["status"])
        self.assertIn("license-approval", plan["blockers"])

    def test_cuda_runtime_is_explicitly_excluded(self):
        with self.assertRaisesRegex(planner.InvalidInput, "excluded"):
            self.plan(package_id="cuda-runtime")

    def test_adapter_policy_covers_every_model_registry_package(self):
        planner._validate_policy_coverage(self.registry)
        live_registry = json.loads((REPO_ROOT / "modules/configuration/src/main/resources/ai/model-registry.v2.json").read_text(encoding="utf-8"))
        planner._validate_policy_coverage(live_registry)
        registry = copy.deepcopy(self.registry)
        registry["packages"].append({"id": "new-model"})
        with self.assertRaisesRegex(planner.InvalidInput, "adapter coverage mismatch"):
            planner._validate_policy_coverage(registry)

    def test_contract_schemas_are_valid_json_with_matching_version(self):
        for name in ("model-promotion-candidate.schema.json", "model-promotion-plan.schema.json"):
            schema = json.loads((SCRIPT.parent / name).read_text(encoding="utf-8"))
            self.assertEqual("https://json-schema.org/draft/2020-12/schema", schema["$schema"])
            self.assertEqual(1, schema["properties"]["schemaVersion"]["const"])


if __name__ == "__main__":
    unittest.main()
