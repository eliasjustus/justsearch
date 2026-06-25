"""Agent-utility eval cohort identity (tempdoc 624).

The agent-utility measurement is the *condition-paired* sibling of the
retrieval-quality benchmark release (tempdoc 623): where a 623 release projects
over runs cohort-identical on every axis except ``dataset``, a utility
comparison projects over agent-eval runs cohort-identical on every axis except
``condition`` (with-tool vs. without-tool). This module supplies the missing
*run identity* for agent-eval runs — the defect that made the "92%/62%" number
an identity-less fork (tempdoc 624 §C-2 / §Confidence-pass R1/R2).

Design (verified against the ``manifest.py`` / ``release.py`` substrate):

- **Parallel builder, not ``manifest.compute_manifest``.** ``compute_manifest``
  is coupled to live search-backend ``/api/*`` snapshots that do not exist for
  an agent run — and *cannot* exist for the without-tool arm (R1). We reuse only
  the pure hashing helpers (``_sha256_canonical``, ``_git_sha_full``).
- **``agent_cohort_key`` excludes the range axes.** A utility-comparison record
  ranges over ``{corpus x agent_model x condition x seed}`` (corpus/model are
  reported per-cell like 623's datasets; condition is paired; seed is
  aggregated). So the cohort key hashes only the *harness* identity held fixed
  across the whole record: git, CLI version, MCP tool-surface, judge, prompt
  template, decoding, eval limits.
- **The search-config component co-varies with ``condition``** (real for the
  with-tool arm, ``None`` for arm A), so it is recorded per-run but is NOT part
  of the cohort key — else arms A and C would never pair (R2). The composer
  checks the with-tool arms share one search config and records it at the
  record level.
- **Pairing key = ``agent_cohort_key`` + the matched range axes minus
  ``condition``** = ``(agent_cohort_key, corpus, agent_model, seed)``.
"""

from __future__ import annotations

from jseval.manifest import _git_sha_full, _sha256_canonical

# Fields recorded on an agent-run manifest but EXCLUDED from agent_cohort_key,
# because they are the axes a utility-comparison record ranges over / pairs on /
# aggregates, or co-vary with condition. Documented so the exclusion is legible.
_RANGE_AND_COVARYING_FIELDS = (
    "corpus",                    # reported per-cell (like 623's dataset)
    "agent_model",               # reported per-cell (the model tier)
    "condition",                 # the paired axis (A vs C)
    "seed",                      # aggregated into the seed envelope
    "search_config_cohort_key",  # co-varies with condition (None for arm A) — R2
)


def mcp_tool_surface_hash(tools: list[dict] | None) -> str:
    """Stable hash of the MCP tool surface the agent was offered (R8).

    Hashes the deterministic ``tools/list`` payload — each tool's ``name`` +
    ``description`` + ``inputSchema`` — sorted by name so ordering is not part of
    identity. ``None``/empty (the without-tool arm) hashes a sentinel so arm A's
    surface is a stable, distinct identity rather than an error.
    """
    if not tools:
        return _sha256_canonical({"tools": "none"})
    norm = sorted(
        (
            {
                "name": t.get("name"),
                "description": t.get("description"),
                "inputSchema": t.get("inputSchema") or t.get("input_schema"),
            }
            for t in tools
        ),
        key=lambda t: t.get("name") or "",
    )
    return _sha256_canonical({"tools": norm})


def judge_identity(
    *,
    kind: str,
    model: str | None = None,
    version: str | None = None,
    prompt_hash: str | None = None,
) -> dict:
    """Normalize a judge identity (the hybrid scorer, tempdoc 624 §C-6).

    ``kind="substring-em"`` is the deterministic baseline scorer (no LLM); an
    ``llm-judge`` additionally pins model + version + prompt so a judge swap
    changes cohort identity (the self-preference control: the judge should be a
    different family from the agent under test). A change to *either* changes the
    ``agent_cohort_key`` — so two records scored differently never silently merge.
    """
    ident: dict = {"kind": kind}
    if kind == "llm-judge":
        ident.update({"model": model, "version": version, "prompt_hash": prompt_hash})
    return ident


def build_agent_manifest(
    *,
    corpus: dict,
    agent_model: str,
    agent_model_version: str | None,
    cli_version: str | None,
    mcp_tool_surface: list[dict] | None,
    judge: dict,
    prompt_template: str,
    condition: str,
    seed: int,
    decoding: dict | None = None,
    eval_limits: dict | None = None,
    search_config_cohort_key: str | None = None,
    hardware: dict | None = None,
    non_determinism_envelope: dict | None = None,
    run_id: str | None = None,
    timestamp: str | None = None,
) -> dict:
    """Build one agent-run manifest (a single corpus x model x condition x seed cell).

    ``run_id``/``timestamp`` are volatile (they identify the specific run) and are
    excluded from ``agent_cohort_key`` by construction — they are not in the
    hashed field set. All other fields are identity-stable across re-runs of the
    same cell.
    """
    manifest = {
        # volatile — identifies this specific run, never part of cohort identity
        "run_id": run_id,
        "timestamp": timestamp,
        # harness identity (hashed into agent_cohort_key)
        "git_sha": _git_sha_full(),
        "cli_version": cli_version,
        "mcp_tool_surface_hash": mcp_tool_surface_hash(mcp_tool_surface),
        "judge": judge,
        "prompt_template_hash": _sha256_canonical(prompt_template),
        "decoding": decoding or {"temperature": 0, "max_tokens": None},
        "eval_limits": eval_limits or {},
        # range / co-varying axes (recorded, excluded from cohort key)
        "corpus": corpus,
        "agent_model": agent_model,
        "agent_model_version": agent_model_version,
        "condition": condition,
        "seed": seed,
        "search_config_cohort_key": search_config_cohort_key,
        # recorded context (projected, not hashed — mirrors 623 hardware handling)
        "hardware": hardware,
        "non_determinism_envelope": non_determinism_envelope,
    }
    manifest["agent_cohort_key"] = agent_cohort_key(manifest)
    return manifest


def agent_cohort_key(manifest: dict) -> str:
    """Harness-identity equivalence key for a utility-comparison record (R1/R2).

    Two runs sharing this key were produced by the *same harness configuration*
    (same git, CLI, MCP tool surface, judge, prompt, decoding, limits),
    regardless of corpus, model tier, condition, or seed — exactly the axes a
    utility-comparison record ranges over. Excludes the volatile run fields and
    everything in ``_RANGE_AND_COVARYING_FIELDS`` (notably the search-config
    component, which co-varies with ``condition`` — R2).
    """
    key_surface = {
        "git_sha": manifest.get("git_sha"),
        "cli_version": manifest.get("cli_version"),
        "mcp_tool_surface_hash": manifest.get("mcp_tool_surface_hash"),
        "judge": manifest.get("judge"),
        "prompt_template_hash": manifest.get("prompt_template_hash"),
        "decoding": manifest.get("decoding"),
        "eval_limits": manifest.get("eval_limits"),
    }
    return _sha256_canonical(key_surface)


def pairing_key(manifest: dict) -> tuple:
    """The tuple that must match for two runs to form an A<->C pair (R2).

    Everything held fixed within a pair *except* ``condition``: the harness
    cohort key plus the matched range axes ``(corpus signature, agent_model,
    seed)``. ``condition`` is deliberately absent — it is the paired axis — and so
    is ``search_config_cohort_key`` (it co-varies with condition; including it
    would stop arm A pairing with arm C).
    """
    corpus = manifest.get("corpus") or {}
    return (
        manifest.get("agent_cohort_key") or agent_cohort_key(manifest),
        corpus.get("signature") or corpus.get("sha256") or corpus.get("dataset"),
        manifest.get("agent_model"),
        manifest.get("seed"),
    )
