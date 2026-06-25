"""Regenerate the shared a11y baseline register from the DETERMINISTIC --fixtures
captures (tempdoc 615 §13 Move 2). Throwaway generator, not harness runtime.

The e2e `KNOWN_RULE_BASELINE` was calibrated against the now-dead demo state (§14
U1). This recaptures each structural view in the reproducible route-mock state and
writes `governance/ui-a11y-baseline.v1.json` — the ONE authority both the Python
ui-shot loop and the TS e2e gate consume (so the tiers can't silently disagree —
§13.3 / P3). Run once after a deliberate baseline change; commit the JSON.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from jseval import ui_shot  # noqa: E402

# surface -> (ui-shot step, [e2e view keys the surface covers])
SURFACES = [
    ("search", "home", ["search", "aria"]),
    ("library", "library", ["library"]),
    ("settings", "settings", ["settings"]),
    ("health", "health", ["health"]),
    ("brain", "ai-brain", ["brain"]),
    ("help", "help", []),
]

def _repo_root() -> Path:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "governance").is_dir() and (parent / "modules").is_dir():
            return parent
    raise FileNotFoundError("repo root (with governance/ + modules/) not found")


_OUT = _repo_root() / "governance" / "ui-a11y-baseline.v1.json"


def _axe_rule_ids(measure_path: str) -> list[str]:
    m = json.loads(Path(measure_path).read_text(encoding="utf-8"))
    viols = (m.get("axe") or {}).get("violations") or []
    return sorted({v["id"] for v in viols})


def main() -> int:
    surfaces_out = []
    for surface, step, e2e_views in SURFACES:
        res = ui_shot.execute_ui_shot(step, fixtures=True)
        if not res.get("ok"):
            print(f"  ! {step}: capture failed: {res.get('error')}")
            return 1
        rules = _axe_rule_ids(res["measure"]["measure_path"])
        print(f"  {surface:9s} (step {step:9s}): knownRules={rules}")
        surfaces_out.append({
            "surface": surface,
            "uiShotStep": step,
            "e2eViews": e2e_views,
            "knownRules": rules,
        })

    register = {
        "$schema": "./ui-a11y-baseline.schema.json",
        "version": 1,
        "description": (
            "Shared a11y known-violation baseline (tempdoc 615 §13 Move 2). ONE authority "
            "for the per-surface axe rules accepted in the DETERMINISTIC route-mock capture "
            "state (jseval ui-shot --fixtures). Consumed by the Python ui-shot measurement "
            "(flags NEW-vs-known) AND the TS e2e accessibility-audit gate, so the tiers cannot "
            "silently disagree about 'passing' (§13.3 / P3). knownRules = accepted debt; a "
            "violation NOT listed is NEW and must be investigated. Regenerate via "
            "scripts/jseval/experiments/regen_a11y_baseline.py after a deliberate change."
        ),
        "captureState": "route-mock-fixtures (no backend, deterministic)",
        "surfaces": surfaces_out,
    }
    _OUT.parent.mkdir(parents=True, exist_ok=True)
    _OUT.write_text(json.dumps(register, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"\nwrote {_OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
