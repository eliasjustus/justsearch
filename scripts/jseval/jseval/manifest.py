"""Run manifest aggregation (tempdoc 400 LR1-a).

Captures the identity-relevant state of the system at run start so that
cross-run comparisons can identify cohorts (identical manifest hash =
same configuration surface) and bisect deltas via manifest-axis diff.

The manifest is the fixed point that tempdoc 400 Layer 1 establishes:
two runs with the same ``manifest_hash`` must produce results inside the
non-determinism envelope (§9.1). Any delta outside ±2σ is signal and
feeds LR5-d bisection.

Design constraints (from 400 §13.9 A-tier findings):

- Keep ``workflow_run_id``, ``run_id``, and trace IDs distinct (272).
- Do not pre-allocate eval identity fields (``evalRunId`` etc.) — 272
  left them deferred; when a harness consumes workflow runs, a follow-up
  tempdoc adds them.
- Stay cohort-stable: the fields that define ``manifest_hash`` must
  remain invariant across identical re-runs. Volatile per-run data
  (timestamps, UUIDs, envelope stats) is excluded from the hash.
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

log = logging.getLogger(__name__)

# Fields that live on the manifest document but are excluded from the
# cohort-identity hash. The hash defines "is this the same configuration
# as a previous run?" — that question must be stable across re-runs.
#
# Phase 2.0 expansion (tempdoc 400 §24): the following fields were moved
# out of cohort identity after Q1 proved that 3 identical ``--clean
# --pipeline`` runs produced 3 distinct ``manifest_hash`` values. The
# raw state-endpoint payloads include runtime state (uptime, queue
# depths, searcher generation, per-commit UUIDs/timestamps, disk
# pressure, top-N processes captured at that instant) that varies per
# run. These snapshots are retained on the manifest document for
# operator inspection but excluded from the cohort hash.
_VOLATILE_FIELDS = frozenset({
    "run_id",
    "timestamp",
    "workflow_run_id",
    "manifest_hash",
    "non_determinism_envelope",
    "envelope_staleness_days",
    "status_snapshot",
    "debug_state_snapshot",
    "inference_status_snapshot",
    "env_fingerprint",
    "telemetry_health_tag",
})

# The 8 identity-stable fields inside ``/api/debug/commit-metadata`` —
# fingerprints/hashes of the current index identity that survive a
# restart. Mirrors the span-attribute set emitted by
# ``modules/worker-services/.../CommitMetadataSpanAttrs.java::KEYS``
# (LR2-d.2). Changing this list should be done in lockstep with the
# Java side so the Python manifest and Java span attributes hash the
# same identity surface.
_COMMIT_METADATA_IDENTITY_FIELDS = frozenset({
    "schema_fp",
    "field_catalog_hash",
    "analyzer_fp",
    "synonyms_hash",
    "grammar_hash",
    "similarity_fp",
    "boosts_fp",
    "index_schema_fp",
})

# State endpoints captured for manifest identity. Each contributes either
# a response hash or embedded content. Kept ordered for log readability.
_STATE_ENDPOINTS = (
    "/api/status",
    "/api/debug/state",
    "/api/debug/commit-metadata",
    "/api/debug/session-policies",
    "/api/telemetry/health",
    "/api/inference/status",
)


def _sha256_canonical(obj: Any) -> str:
    """SHA-256 of canonical JSON (sorted keys, compact separators)."""
    canon = json.dumps(
        obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    )
    return hashlib.sha256(canon.encode("utf-8")).hexdigest()


def _git_sha_full() -> str | None:
    """Return the full 40-char git SHA, or None if unavailable."""
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            capture_output=True, text=True, timeout=5, check=False,
        )
        if result.returncode == 0:
            return result.stdout.strip() or None
    except Exception as e:
        log.debug("git rev-parse failed: %s", e)
    return None


def capture_state_snapshots(base_url: str, timeout: float = 5.0) -> dict:
    """Fetch raw JSON from each state endpoint.

    Missing / degraded endpoints get a stub marker instead of raising:
    the manifest records honest unavailability rather than failing the
    run. In eval mode (``runHeadlessEval``) ``/api/debug/session-policies``
    typically returns ``configStatus: worker-unreachable`` and
    ``/api/debug/commit-metadata`` returns 503 until the Worker is ready
    — both are valid inputs for a cohort hash as long as the shape is
    consistent across re-runs.
    """
    snapshots: dict = {}
    with httpx.Client(base_url=base_url, timeout=timeout) as client:
        for ep in _STATE_ENDPOINTS:
            try:
                resp = client.get(ep)
                if resp.status_code == 200:
                    snapshots[ep] = resp.json()
                else:
                    snapshots[ep] = {"_http_status": resp.status_code}
                    log.debug(
                        "Manifest capture %s returned HTTP %d",
                        ep, resp.status_code,
                    )
            except Exception as e:
                snapshots[ep] = {"_error": type(e).__name__}
                log.debug("Manifest capture %s failed: %s", ep, e)
    return snapshots


def _envelope_staleness_days(envelope: dict) -> int | None:
    """Days elapsed between ``envelope.calibrated_at`` and ``now``.

    Returns ``None`` if ``calibrated_at`` is missing / unparseable. This
    is an informational signal only — no invalidation policy fires on a
    stale envelope. Consumers that care about staleness (e.g. a future
    Phase-3 LR4-b that weights σ by age) read this field and apply their
    own threshold. See tempdoc 400 §25.4 deferral: a full freshness
    policy is Phase 3/4 territory.
    """
    raw = envelope.get("calibrated_at")
    if not isinstance(raw, str) or not raw:
        return None
    try:
        # ISO-8601 with trailing Z → Python's +00:00.
        if raw.endswith("Z"):
            raw = raw[:-1] + "+00:00"
        ts = datetime.fromisoformat(raw)
        # Compute against a timezone-aware "now" to avoid naive/aware mix.
        now = datetime.now(timezone.utc)
        delta = now - ts
        return max(0, delta.days)
    except (ValueError, TypeError) as e:  # pragma: no cover — defensive
        log.debug("envelope calibrated_at unparseable (%r): %s", raw, e)
        return None


def _extract_telemetry_tag(state_snapshots: dict) -> str:
    """Extract the telemetry-stack lifecycle tag."""
    t = state_snapshots.get("/api/telemetry/health") or {}
    state = t.get("state")
    if isinstance(state, str):
        return state
    return "UNKNOWN"


def _normalise_commit_metadata(raw: dict) -> dict:
    """Filter commit metadata to the 8 identity-stable fields (Phase 2.0).

    The raw ``/api/debug/commit-metadata`` response carries ~17 fields,
    most of which are identity (schema_fp, field_catalog_hash, ...) but
    three are runtime artefacts that change every Lucene commit:

    - ``commit_id`` — a random UUID assigned per commit
    - ``commit_time`` — an ISO timestamp per commit
    - several versioned / build-state fields (``schema_ver``,
      ``grammar_ver``, ``template_ver``, ``prompt_pack_hash``,
      ``vector_format``, ``splade_model_sha256``, ``build_state``,
      ``grammar_on``) that can vary without the index identity
      changing or are redundant with the 8 core hashes.

    Pre-Phase-2.0 the normaliser passed all fields through, which meant
    ``commit_id`` + ``commit_time`` destabilised the cohort hash across
    every identical re-run (Q1 finding in tempdoc 400 §24). Filtering to
    ``_COMMIT_METADATA_IDENTITY_FIELDS`` keeps the same 8 fields that
    LR2-d.2's ``commit.*`` span attributes already commit to — single
    authority of truth across Python + Java telemetry.
    """
    if not isinstance(raw, dict):
        return {}
    if "_http_status" in raw or "_error" in raw:
        return {}
    return {k: raw[k] for k in _COMMIT_METADATA_IDENTITY_FIELDS if k in raw}


def _compute_cohort_hash(manifest: dict) -> str:
    """Cohort identity: SHA-256 over canonical manifest minus volatile fields.

    Two runs with identical ``manifest_hash`` share the same configuration
    surface — the same git, the same policy, the same index identity, the
    same models, the same corpus, the same eval protocol. The
    non-determinism envelope (σ per metric) applies to this cohort
    specifically.
    """
    filtered = {k: v for k, v in manifest.items() if k not in _VOLATILE_FIELDS}
    return _sha256_canonical(filtered)


def compute_manifest(
    *,
    dataset_name: str,
    meta: Any,
    env_fingerprint: dict | None,
    models_snapshot: dict | None,
    eval_protocol: dict,
    state_snapshots: dict,
    workflow_run_id: str | None = None,
    non_determinism_envelope: dict | None = None,
    envelope_data_dir: Path | None = None,
) -> dict:
    """Build the run manifest document.

    Combines identity signals from state endpoints + run-invariant inputs
    (git SHA, corpus, qrels, eval protocol, env fingerprint). The
    resulting ``manifest_hash`` is the cohort identifier that tempdoc 400
    §9.2 bisection consumes.

    The ``non_determinism_envelope`` field is per-cohort (not per-run):

    - If passed explicitly (non-None), that value wins unchanged.
    - Else if ``envelope_data_dir`` is set, the manifest's
      ``manifest_hash`` is looked up under
      ``<envelope_data_dir>/non_determinism_envelopes/`` via
      :func:`jseval.calibrate.read_envelope`; a hit embeds the envelope,
      a miss leaves the field ``None``.
    - Else the field stays ``None`` — an uncalibrated cohort.

    Consumers that observe ``None`` must treat cross-run deltas
    qualitatively (§13.9 C4).
    """
    commit_metadata = _normalise_commit_metadata(
        state_snapshots.get("/api/debug/commit-metadata") or {},
    )

    manifest: dict = {
        # Volatile — identifies this specific run.
        "run_id": str(uuid.uuid4()),
        "workflow_run_id": workflow_run_id,
        "timestamp": datetime.now(timezone.utc).isoformat(),

        # Cohort inputs — invariant across re-runs with the same config.
        "git_sha": _git_sha_full(),
        "dataset": dataset_name,
        "doc_count": int(meta.doc_count),
        "query_count": int(meta.query_count),

        # Policy snapshot hash — identity-stable by design (built at
        # Worker boot from config + hardware, not from runtime state).
        # In eval mode pre-LR1-c it is hash of the worker-unreachable
        # stub; both forms are stable across identical reruns.
        "policy_hash": _sha256_canonical(
            state_snapshots.get("/api/debug/session-policies") or {}),

        # Commit metadata — filtered to the 8 identity fields by
        # _normalise_commit_metadata (Phase 2.0). Drops commit_id /
        # commit_time and version/build-state fields that varied per
        # run and destabilised the cohort hash pre-Phase-2.0.
        "commit_metadata": commit_metadata,

        # Identity inputs captured outside state endpoints.
        "corpus_identity": {
            "profile_id": os.environ.get("JUSTSEARCH_CORPUS_PROFILE_ID"),
            "signature": os.environ.get("JUSTSEARCH_CORPUS_SIGNATURE"),
        },

        # Model fingerprints — already captured from /api/status by
        # run._snapshot_models() and passed in here. Identity-stable.
        "model_fingerprints": models_snapshot or {},

        # Eval protocol identity.
        "eval_protocol_hash": _sha256_canonical(eval_protocol),

        # --- Informational-only fields (excluded from cohort hash
        # --- via _VOLATILE_FIELDS). Retained on the manifest for
        # --- operator inspection and audit trail, but not part of
        # --- cohort identity because their payloads include runtime
        # --- state (uptime, queue depths, searcher generation, PID,
        # --- per-process captured_at timestamps) that varies per run
        # --- regardless of configuration (Phase 2.0, Q1 finding).
        "status_snapshot": state_snapshots.get("/api/status") or {},
        "debug_state_snapshot": state_snapshots.get("/api/debug/state") or {},
        "inference_status_snapshot": state_snapshots.get("/api/inference/status") or {},
        "env_fingerprint": env_fingerprint or {},
        "telemetry_health_tag": _extract_telemetry_tag(state_snapshots),

        # Volatile — per-cohort calibration artefact (LR1-b).
        "non_determinism_envelope": non_determinism_envelope,
    }

    manifest["manifest_hash"] = _compute_cohort_hash(manifest)

    # Phase 2.2b: if no envelope was explicitly passed and a registry is
    # configured, look up the calibrated envelope for this cohort. Missing
    # sidecar (uncalibrated cohort) leaves the field None; consumers must
    # treat cross-run deltas qualitatively in that case.
    if non_determinism_envelope is None and envelope_data_dir is not None:
        try:
            from jseval.calibrate import read_envelope  # avoid import cycle at module load
            manifest["non_determinism_envelope"] = read_envelope(
                envelope_data_dir, manifest["manifest_hash"],
            )
        except Exception as e:  # pragma: no cover — defensive best-effort
            log.debug("envelope lookup failed (best-effort): %s", e)

    # Phase 2.2d (staleness signal, tempdoc 400 §25.4): surface how old
    # the embedded envelope is so downstream consumers can weight the
    # envelope's σ against its age without committing to any specific
    # invalidation rule. A full freshness policy (GPU driver / model
    # reload / schema change triggers) is deferred to Phase 3/4 where
    # LR4-b's consumer semantics will inform the decision.
    envelope = manifest.get("non_determinism_envelope")
    if isinstance(envelope, dict):
        manifest["envelope_staleness_days"] = _envelope_staleness_days(envelope)
    else:
        manifest["envelope_staleness_days"] = None

    return manifest


def write_manifest(manifest: dict, run_dir: Path) -> Path:
    """Write manifest.json alongside summary.json in the run directory."""
    path = run_dir / "manifest.json"
    path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return path
