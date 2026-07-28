#!/usr/bin/env python
"""782 hero campaign preflight — assert every precondition MECHANICALLY, before spend.

Runs against committed artifacts only: no backend, no paid API call, no network.
Prints PASS / FAIL / PENDING per item and exits non-zero if any item FAILs.

    python scripts/jseval/782-hero/preflight.py

Item classes:
  PASS     — verified now against the committed tree.
  FAIL     — a real, present blocker. Exit code 1. Do not launch.
  PENDING  — produced by the campaign itself (calibrations, the sonnet closed-book
             measurement, the run dir). Not a defect at freeze time; each names the
             command that produces it. PENDING never fails the run -- but every
             PENDING must be PASS before the corresponding phase launches.

Design note (782 §H): this script exists because §G.1 predicted the schema-coverage
failure would be detectable pre-launch from the committed qid list rather than at
compose time on a paid record. It is expected to FAIL on BLOCKER-1 until the founder
resolves it -- a green preflight over an unpromotable campaign would be the defect.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
CELLS = json.loads((HERE / "cells.v1.json").read_text(encoding="utf-8"))

PASS, FAIL, PENDING = "PASS", "FAIL", "PENDING"
_results: list[tuple[str, str, str]] = []


def record(status: str, item: str, detail: str) -> None:
    _results.append((status, item, detail))
    print(f"  [{status:<7}] {item}\n            {detail}")


def _sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _canonical_selection_digests(rows: list[dict]) -> tuple[str, str, dict[str, int]]:
    """Recompute 782 §E.8's two digests + the schema census over the first 20 rows.

    The selection rule is truncation in committed order (`--max-queries 20`), which is
    the only certification-preserving subset: `agent_utility_inspect.run_utility_eval`
    hashes the WHOLE queries file's bytes and truncates only the sample rows, so a
    rewritten subset file fails `query_gold_sha256`.
    """
    selected = rows[:20]
    qids = [f"q{i + 1:04d}" for i in range(len(selected))]
    qid_list_sha = _sha256_text("\n".join(qids) + "\n")
    canonical = [
        {
            "qid": qids[i],
            "query": r["query"],
            "answer": r["answer"],
            "question_type": r["question_type"],
        }
        for i, r in enumerate(selected)
    ]
    content_sha = _sha256_text(json.dumps(canonical, separators=(",", ":")))
    census: dict[str, int] = {}
    for r in selected:
        census[r["question_type"]] = census.get(r["question_type"], 0) + 1
    return qid_list_sha, content_sha, census


# --- 1. active claim policy is the ratified v3 -------------------------------------


def check_policy() -> dict:
    active = []
    for path in sorted((REPO / "scripts" / "jseval").glob("utility-claim-policy.*.json")):
        doc = json.loads(path.read_text(encoding="utf-8"))
        if doc.get("status") == "active":
            active.append((path, doc))
    if len(active) != 1:
        record(FAIL, "policy: exactly one active policy",
               f"found {len(active)} with status=active: {[p.name for p, _ in active]}")
        return {}
    path, doc = active[0]
    want = CELLS["protocol"]["policy_id"]
    ok = doc.get("policy_id") == want and doc.get("unresolved") == []
    record(PASS if ok else FAIL, "policy: ratified v3 is active",
           f"{path.name} policy_id={doc.get('policy_id')!r} (want {want!r}) "
           f"unresolved={doc.get('unresolved')!r}")
    return doc


def check_policy_strata(policy: dict) -> None:
    want = [s["stratum_id"] for s in CELLS["strata"]]
    got = [s.get("stratum_id") for s in policy.get("required_strata", [])]
    ok = got == want
    record(PASS if ok else FAIL, "policy: required_strata == frozen §E.1 order",
           f"{len(got)} strata, cheapest-first match={ok}")
    bad = [
        s.get("stratum_id") for s in policy.get("required_strata", [])
        if s.get("query_count") != 20 or s.get("seed_ids") != [0, 1, 2]
        or s.get("requested_model") != "sonnet"
    ]
    record(PASS if not bad else FAIL, "policy: 20q x seeds{0,1,2} x sonnet on every stratum",
           "all three match" if not bad else f"mismatched: {bad}")


# --- 2. corpora certified, signatures pinned ---------------------------------------


def check_certifications() -> None:
    for st in CELLS["strata"]:
        cert_path = REPO / st["corpus_certification"]
        if not cert_path.exists():
            record(FAIL, f"cert[{st['name']}]: file present", f"missing {cert_path}")
            continue
        cert = json.loads(cert_path.read_text(encoding="utf-8"))
        top_ok = (
            cert.get("status") == "fully-certified"
            and cert.get("fully_certified") is True
            and cert.get("structural_passed") is True
        )
        record(PASS if top_ok else FAIL, f"cert[{st['name']}]: member fully-certified",
               f"status={cert.get('status')!r} fully_certified={cert.get('fully_certified')!r} "
               f"(authority: corpus_certify.py:815-817 -- NOT member.v1.json, whose "
               f"remaining_gates is pre-#311 residue)")
        cell = (cert.get("datasets") or {}).get(str(st["size"]), {}).get(st["query_variant"])
        if not cell:
            record(FAIL, f"cert[{st['name']}]: cell present",
                   f"no datasets[{st['size']}][{st['query_variant']}]")
            continue
        sig_ok = (
            cell.get("corpus_signature") == st["corpus_signature"]
            and cell.get("query_gold_sha256") == st["query_gold_sha256"]
            and cell.get("dataset") == st["dataset"]
        )
        record(PASS if sig_ok else FAIL, f"cert[{st['name']}]: §E.2 P1 signature pins",
               f"corpus_signature={cell.get('corpus_signature', '')[:16]}... "
               f"query_gold_sha256={cell.get('query_gold_sha256', '')[:16]}... match={sig_ok}")
        qc_ok = cell.get("query_count") == st["query_count_committed"]
        record(PASS if qc_ok else FAIL, f"cert[{st['name']}]: committed query_count",
               f"{cell.get('query_count')} (expect {st['query_count_committed']}; "
               f"the campaign measures the first 20 -- §E.8)")
        checks = cell.get("checks") or {}
        fs = cell.get("field_selectivity") or {}
        fs_ok = (
            checks.get("field_selectivity") is True and fs.get("passed") is True
            and fs.get("max_field_separability") is not None
            and fs.get("native_base_rate") is not None
            and float(fs["max_field_separability"]) <= float(fs["native_base_rate"])
        )
        record(PASS if fs_ok else FAIL, f"cert[{st['name']}]: §E.2 P2 field_selectivity",
               f"worst_field={fs.get('worst_field')!r} "
               f"max_field_separability={fs.get('max_field_separability')} <= "
               f"native_base_rate={fs.get('native_base_rate')} n_fields={fs.get('n_fields_compared')}")
        all_checks_ok = bool(checks) and all(checks.values())
        record(PASS if all_checks_ok else FAIL, f"cert[{st['name']}]: all structural checks",
               f"{sum(1 for v in checks.values() if v)}/{len(checks)} true")
        gates = cell.get("scientific_gates") or {}
        gates_ok = bool(gates) and all(g.get("passed") is True for g in gates.values())
        record(PASS if gates_ok else FAIL, f"cert[{st['name']}]: scientific gates",
               ", ".join(f"{k}={g.get('passed')}" for k, g in gates.items()) or "none")


# --- 3. the frozen 20-qid selection reproduces -------------------------------------


def check_qids() -> dict[str, dict[str, int]]:
    censuses: dict[str, dict[str, int]] = {}
    for st in CELLS["strata"]:
        src = REPO / st["gold_source_cell"]
        if not src.exists():
            record(FAIL, f"qids[{st['name']}]: gold source present", f"missing {src}")
            continue
        rows = json.loads(src.read_text(encoding="utf-8"))
        qid_sha, content_sha, census = _canonical_selection_digests(rows)
        censuses[st["name"]] = census
        ok = qid_sha == st["qid_list_sha256"] and content_sha == st["selected_query_sha256"]
        record(PASS if ok else FAIL, f"qids[{st['name']}]: frozen §E.8 digests reproduce",
               f"qid_list_sha256={qid_sha[:16]}... selected_query_sha256={content_sha[:16]}... "
               f"match={ok}")
    return censuses


# --- 4. datasets resolvable at their paths -----------------------------------------


def check_datasets() -> None:
    roots = [Path(r) for r in CELLS["dataset_roots_searched"]]
    for st in CELLS["strata"]:
        slug = st["dataset"].split("/", 1)[1]
        found = None
        for root in roots:
            d = root / slug
            if (d / "corpus-dir").is_dir() and (d / "queries.json").is_file():
                found = d
                break
        if found is None:
            record(FAIL, f"dataset[{st['name']}]: resolvable",
                   f"no root has both corpus-dir/ and queries.json for {slug}; "
                   f"searched {[str(r) for r in roots]}")
            continue
        mat = json.loads((found / "queries.json").read_text(encoding="utf-8"))
        gold = json.loads((REPO / st["gold_source_cell"]).read_text(encoding="utf-8"))
        first20 = mat[:20]
        aligned = len(first20) == 20 and all(
            m["query"] == g["query"] and m["answer"] == g["answer"]
            and m.get("question_type") == g.get("question_type")
            and m.get("query_family_id") == f"q{i + 1:04d}"
            for i, (m, g) in enumerate(zip(first20, gold[:20]))
        )
        record(PASS if aligned else FAIL, f"dataset[{st['name']}]: materialization aligns",
               f"{found} n={len(mat)} first-20 aligned with committed gold={aligned}")


# --- 5. schema coverage vs the policy's known_schemas (BLOCKER-1) -------------------


def check_schema_coverage(policy: dict, censuses: dict[str, dict[str, int]]) -> None:
    spec = policy.get("required_schema_strata") or {}
    known = list(spec.get("known_schemas") or [])
    require_all = bool(spec.get("require_all_present"))
    if not known:
        record(FAIL, "schema: policy declares known_schemas", "known_schemas empty or absent")
        return
    for st in CELLS["strata"]:
        census = censuses.get(st["name"], {})
        missing = [s for s in known if s not in census]
        ok = not (require_all and missing)
        record(PASS if ok else FAIL, f"schema[{st['name']}]: schema_strata_reported can pass",
               f"selection covers {sorted(census)} ; policy known_schemas={known} ; "
               f"missing={missing} ; require_all_present={require_all}"
               + ("" if ok else "  -- 782 §H BLOCKER-1: the gate "
                  "(utility_claim_policy.py:517-542) will refuse the composed record; "
                  "FOUNDER DECISION required, do not launch"))


# --- 6. closed-book measurement tier (FINDING-2) -----------------------------------


def check_closed_book(policy: dict) -> None:
    ceiling = (policy.get("thresholds") or {}).get("maximum_closed_book_accuracy")
    for st in CELLS["strata"]:
        cert = json.loads((REPO / st["corpus_certification"]).read_text(encoding="utf-8"))
        cell = (cert.get("datasets") or {}).get(str(st["size"]), {}).get(st["query_variant"], {})
        obs = ((cell.get("scientific_gates") or {}).get("closed_book") or {}).get("observed") or {}
        acc, model = obs.get("closed_book_accuracy"), obs.get("model")
        gate_ok = isinstance(acc, (int, float)) and not isinstance(acc, bool) \
            and ceiling is not None and float(acc) <= float(ceiling) and bool(model)
        record(PASS if gate_ok else FAIL,
               f"closed-book[{st['name']}]: policy gate closed_book_at_hero_tier",
               f"accuracy={acc} <= ceiling={ceiling}, model={model!r} (the GATE does not "
               f"check the measurement tier -- utility_claim_policy.py:497-506)")
        p3_ok = model == "sonnet" and acc == 0
        record(PASS if p3_ok else PENDING,
               f"closed-book[{st['name']}]: 782 §E.2 P3 bar (0.000 AT SONNET)",
               f"committed measurement is at model={model!r} -- P3 is the stricter bar and is "
               f"NOT satisfied by committed artifacts. Produce it in campaign Step 0b "
               f"(see campaign-plan.md); 782 §H FINDING-2.")


# --- 7. tooling present -------------------------------------------------------------


def check_tooling() -> None:
    guard = REPO / CELLS["campaign"]["budget_guard"]
    record(PASS if guard.is_file() else FAIL, "tooling: budget guard present",
           f"{guard} (cap ${CELLS['campaign']['cap_usd']}, --total "
           f"{CELLS['campaign']['n_strata']})")
    claude = shutil.which("claude") or shutil.which("claude.cmd") or shutil.which("claude.exe")
    if claude:
        record(PASS, "tooling: claude CLI resolvable", f"{claude}")
    else:
        probe = REPO / "scripts" / "jseval" / "phase2-cli-check.py"
        try:
            rc = subprocess.run([sys.executable, str(probe)], capture_output=True,
                                text=True, timeout=60, cwd=str(REPO / "scripts" / "jseval"))
            ok = rc.returncode == 0
        except Exception as exc:  # noqa: BLE001 - preflight must never crash
            ok, rc = False, None
            record(FAIL, "tooling: claude CLI resolvable", f"probe failed: {exc}")
            return
        record(PASS if ok else FAIL, "tooling: claude CLI resolvable",
               f"phase2-cli-check.py rc={rc.returncode} "
               f"{(rc.stdout or rc.stderr).strip().splitlines()[-1] if (rc.stdout or rc.stderr).strip() else ''}")
    scorer = REPO / "scripts" / "jseval" / "jseval" / "agent_utility_inspect.py"
    text = scorer.read_text(encoding="utf-8")
    record(PASS if "scorer=substring_scorer()" in text else FAIL,
           "tooling: frozen scorer identity (§E.3 / §E.7 row 9)",
           "scorer=substring_scorer() present in agent_utility_inspect.py "
           "(any change here voids the frozen §E.3 branch selection)")


# --- 8. campaign-produced artifacts (PENDING by construction) ----------------------


def check_campaign_artifacts() -> None:
    record(PENDING, "campaign: per-stratum sonnet calibrations",
           "produced by `jseval utility-calibrate` on the campaign day; feeds the "
           "$300 budget guard and the frozen max_budget derivation rule (§E.1)")
    record(PENDING, "campaign: run directory",
           f"{CELLS['campaign']['run_dir_pattern']} -- created on launch day (§E.7 row 11)")
    record(PENDING, "campaign: founder launch authorization",
           "founder-gated, recorded in the §E.6 ledger; NOT granted at freeze")


def main() -> int:
    print("=" * 78)
    print("782 HERO CAMPAIGN PREFLIGHT  --  committed artifacts only, no backend, no paid call")
    print(f"repo: {REPO}")
    print("=" * 78)

    print("\n[1] active claim policy")
    policy = check_policy()
    if policy:
        check_policy_strata(policy)

    print("\n[2] corpus certifications (§E.2 P1/P2)")
    check_certifications()

    print("\n[3] frozen 20-qid selection (§E.8)")
    censuses = check_qids()

    print("\n[4] dataset resolvability")
    check_datasets()

    print("\n[5] schema coverage vs policy known_schemas")
    if policy:
        check_schema_coverage(policy, censuses)

    print("\n[6] closed-book (§E.2 P3 vs the policy gate)")
    if policy:
        check_closed_book(policy)

    print("\n[7] tooling")
    check_tooling()

    print("\n[8] campaign-produced artifacts")
    check_campaign_artifacts()

    n_pass = sum(1 for s, _, _ in _results if s == PASS)
    n_fail = sum(1 for s, _, _ in _results if s == FAIL)
    n_pend = sum(1 for s, _, _ in _results if s == PENDING)
    print("\n" + "=" * 78)
    print(f"PREFLIGHT {'FAIL' if n_fail else 'PASS'}  --  "
          f"{n_pass} PASS, {n_fail} FAIL, {n_pend} PENDING")
    if n_fail:
        print("\nBLOCKING ITEMS:")
        for s, item, detail in _results:
            if s == FAIL:
                print(f"  - {item}: {detail}")
        print("\nDo not launch. A FAIL here is the preflight working: 782 §G.1 predicted this "
              "class would be findable pre-launch rather than at compose time on a paid record.")
    print("=" * 78)
    return 1 if n_fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
