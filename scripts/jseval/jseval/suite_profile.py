"""Suite profile projection (tempdoc 635 §R-3) — a *profile, not a number*.

A thin projection over the committed self-demo members of a suite: it reads each
`golden/<member>/metadata.json` (the governed corpus artifact — closed-book +
fidelity certification) and, when present, the member's retrieval `release.v1` and
agent `utility-comparison.v1` records, into one comparison table. This realizes the
R-3 goal — a type-spanning *profile* exposing where the engine is strong/weak across
document/query types — without adding a new authority (it derives from the per-member
records, the canonical-authority-and-projection seam).
"""

from __future__ import annotations

import json
from pathlib import Path


def _safe_load(p: Path):
    try:
        return json.loads(p.read_text(encoding="utf-8")) if p.is_file() else None
    except Exception:
        return None


def build_profile(suite: str, datasets_dir: Path, records_root: Path | None = None,
                  *, engine_git_sha: str | None = None, generated_date: str | None = None) -> dict:
    """Scan golden/* for members tagged `suite`; project their certification + records.

    `records_root` (optional) holds per-member `635-<name>/release.v1.json` +
    `utility-comparison.v1.json` produced by the run; metadata is read from the dataset.
    `engine_git_sha`/`generated_date` (optional) stamp the snapshot so a dated ceiling is
    attributable to the engine version it was certified against (the levers are default-on
    per ResolvedConfigBuilder, so the SHA pins the retrieval behaviour).
    """
    golden = datasets_dir / "golden"
    members = []
    for d in sorted(golden.glob("*")) if golden.is_dir() else []:
        meta = _safe_load(d / "metadata.json")
        if not meta or meta.get("suite") != suite:
            continue
        cert = meta.get("closed_book_certification") or {}
        fid = meta.get("fidelity") or {}
        row = {
            "member": d.name,
            "type_axis": meta.get("type_axis"),
            "contamination_class": meta.get("contamination_class"),
            "corpus_size": meta.get("corpus_size"),
            "closed_book_passed": cert.get("passed"),
            "closed_book_accuracy": cert.get("closed_book_accuracy"),
            "fidelity_passed": fid.get("passed"),
            "retrieval_ndcg": fid.get("retrieval_ndcg"),
            "retrieval_mode": fid.get("retrieval_mode"),
            "retrieval_ndcg_by_mode": fid.get("retrieval_ndcg_by_mode"),
            "comparable": fid.get("comparable"),
            "retrieval_difficulty": fid.get("retrieval_difficulty"),
            "memory_independence": fid.get("memory_independence"),
            "shortcut_leak_rate": fid.get("shortcut_leak_rate"),
            "corpus_signature": (meta.get("corpus_signature") or "")[:12],
        }
        # optional agent record
        if records_root:
            util = _safe_load(records_root / f"635-{d.name}" / "utility-comparison.v1.json")
            if util:
                cells = util.get("measured", {})
                cell = next((c for by in cells.values() for c in by.values()), None)
                if cell:
                    row["agent_acc_delta"] = cell.get("accuracy", {}).get("delta")
                    tok = cell.get("tokens_unique", {})
                    row["agent_token_delta_mean"] = tok.get("delta_mean")
        members.append(row)
    out = {"schema": "suite-profile.v1", "suite": suite, "n_members": len(members),
           "members": members}
    if engine_git_sha:
        out["engine_git_sha"] = engine_git_sha
    if generated_date:
        out["generated_date"] = generated_date
    return out
