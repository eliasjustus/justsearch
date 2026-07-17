#!/usr/bin/env python
"""Per-dataset corpus-signature preflight + args emitter for chain-step2.bat.

WHY THIS EXISTS (load-bearing, see LAUNCH-README.md "Corpus-certification blocker"):
`utility-run` computes corpus_signature over whatever `--corpus-dir` you pass and then:
  * raises if a 64-hex `--corpus-signature` disagrees with it
    (agent_utility_inspect.py:1099), and
  * if `--corpus-certification` is given, raises unless the cert's `corpus_signature`
    EQUALS that same materialized signature (corpus_certify.certification_snapshot
    -> "707 certification corpus signature disagrees with materialized corpus",
    corpus_certify.py:617-619).

For a LEAK-SAFE run, `--corpus-dir` MUST be the `<dataset>/corpus-dir` SUBDIR
(stage_corpus_dir copies it into an answer-key-free temp so queries.json is
structurally unreachable; tests assert the subdir, agent_retrieval_eval.py:50-91,
test_agent_utility_inspect.py:1308-1353). That subdir's files-mode signature is
what the run records and what the cert must match.

But the committed 707 certs (scripts/jseval/707-corpora/<member>/structural-
certification.v1.json) carry the DATASET-DIR signature (sha256 of corpus.jsonl +
qrels/test.tsv), NOT the corpus-dir subdir files-mode hash. So today's certs and a
leak-safe corpus-dir CANNOT both be satisfied -> a certified run aborts.

This helper computes the LIVE subdir signature (robust to re-materialization),
compares it to the cert, and emits the exact extra utility-run args:
  declared mode (DEFAULT) -> emit --corpus-signature <live subdir hash> only, NO
                      --corpus-certification. Campaign-D parity (its source-identity
                      recorded declared_signature=4a686d49...). The pre-registration
                      records the cert/hash-equivalence chain separately.
  strict mode (opt-in)    -> requires cert.corpus_signature == the live subdir hash;
                      ABORT (exit 1) with remediation if not. For a future run once
                      the 707 certs are regenerated to the subdir signature.

Exit codes: 0 = proceed (args written), 1 = ABORT this dataset (do not spend).
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def _subdir_signature(subdir: Path) -> str:
    from jseval.corpus_identity import corpus_signature

    files = sorted(
        (p for p in subdir.rglob("*") if p.is_file()),
        key=lambda p: p.relative_to(subdir).as_posix(),
    )
    sig = corpus_signature(subdir, files)
    if sig is None:
        raise SystemExit(f"ABORT: no corpus files under {subdir}")
    return sig


def _cert_signature(cert_path: Path, dataset: str) -> str:
    doc = json.loads(cert_path.read_text(encoding="utf-8"))
    matches = []
    for _size, variants in (doc.get("datasets") or {}).items():
        for _variant, cell in (variants or {}).items():
            if cell.get("dataset") == dataset:
                matches.append(cell)
    if len(matches) != 1:
        raise SystemExit(
            f"ABORT: cert {cert_path} must contain exactly one {dataset!r} cell "
            f"(found {len(matches)})"
        )
    return matches[0].get("corpus_signature")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus-dir", required=True, help="The <dataset>/corpus-dir SUBDIR.")
    ap.add_argument("--cert", required=True, help="structural-certification.v1.json path.")
    ap.add_argument("--dataset", required=True, help="e.g. mixed/en-legal-clerc-1k-verbose")
    ap.add_argument("--mode", choices=["declared", "strict"], default="declared")
    ap.add_argument("--out", required=True, help="File to write the extra utility-run args into.")
    a = ap.parse_args()

    subdir = Path(a.corpus_dir)
    if not subdir.is_dir():
        print(f"ABORT: corpus-dir subdir does not exist: {subdir}", file=sys.stderr)
        return 1

    live = _subdir_signature(subdir)
    cert = _cert_signature(Path(a.cert), a.dataset)
    matched = live == cert

    print(f"[{a.dataset}] live corpus-dir signature = {live}")
    print(f"[{a.dataset}] cert corpus_signature     = {cert}  (match={matched})")

    if a.mode == "strict":
        if not matched:
            print(
                "ABORT (strict): the committed 707 cert signs the DATASET dir, but a "
                "leak-safe run hashes the corpus-dir SUBDIR. Remediation before launch: "
                "(a) regenerate the 707 certs so corpus_signature == the corpus-dir "
                "subdir files-mode hash, OR (b) relaunch in the default declared mode "
                "(unset STEP2_CERT_MODE; drops --corpus-certification, Campaign-D parity).",
                file=sys.stderr,
            )
            return 1
        args = f'--corpus-signature {live} --corpus-certification "{a.cert}"'
    else:  # declared (default) -- Campaign-D parity, no --corpus-certification
        args = f"--corpus-signature {live}"

    Path(a.out).write_text(args, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
