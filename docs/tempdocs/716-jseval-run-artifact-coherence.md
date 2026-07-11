# 716 — jseval run-artifact coherence + worktree ergonomics

- **status:** seed — takeover pending (chartered 2026-07-11 from the 711 close-out; no
  investigation performed yet)
- **created:** 2026-07-11

## Charter question

Three recurring per-session taxes in the jseval harness share a root — run artifacts,
data dirs, and the import path all resolve inconsistently across checkouts/worktrees. What is
the coherent layout, and does the per-run-ephemeral-data-dir shape (711 Item 4's named
retirement path) subsume the wipe-based clean?

## Evidence that motivates the charter (all hit live on 2026-07-11, during 711's publish)

1. **Gates can't find runs by default:** the gate commands' run discovery looks in
   `<data-dir>/eval-results` (`commands/gates.py` `--data-dir` help), but `jseval run`'s
   default `--output-dir` is `scripts/jseval/tmp/eval-results` (`_paths.py:100-101`,
   `DEFAULT_EVAL_RESULTS`). A defaults `run` followed by a defaults gate fails with
   `"no eval-results run with summary.json"` — worked around with manual `--run-dir` during
   711's gate run. Two defaults in one tool that don't compose.
2. **The editable-install/PYTHONPATH trap:** jseval is pip-installed editable against one
   checkout; invoking from any other worktree silently runs the wrong code unless
   `PYTHONPATH=<worktree>/scripts/jseval` is set (documented pitfall, CLAUDE.md table; paid
   again in every 711 detached run script). Candidate fixes: an entry wrapper that resolves
   the package relative to the invoking repo root, or a loud startup assertion that
   `jseval.__file__` is under the CWD's repo root.
3. **Wipe-based clean vs ephemeral run dirs:** 711 Item 4 made `--clean` fail-closed (shipped,
   live-proven incl. a real orphan-Worker kill), and named the structural successor: per-run
   ephemeral data dirs with the cross-run calibration state (`cohort_baselines/`,
   `non_determinism_envelopes/`) relocated OUTSIDE them — wiping becomes obsolete, and the
   orphan-sweep becomes best-effort hygiene instead of measurement-validity-critical. That
   shape was deliberately not built in 711 (scope); this tempdoc owns the judgment call.

## Constraints / relations

- Python-only; no retrieval semantics. The jseval test suite (~1600 tests) is the safety net;
  `test_backend.py` has the clean/orphan coverage from 711.
- The 645 lineage (jseval ownership) and tempdoc 704 Pillar 6 (isolated eval lane) are
  adjacent — check both before designing; Pillar 6 may already claim part of this ground.
- Cheapest evidence for the ephemeral-dir question: count what actually persists across runs
  by design vs by accident (the 711 E4 audit's per-file inventory of the data dir is the
  starting point).
