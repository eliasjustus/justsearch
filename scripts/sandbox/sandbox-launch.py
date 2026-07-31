#!/usr/bin/env python3
"""
Launch a Windows Sandbox for JustSearch validation.

Stages the NSIS installer, project docs, sandbox CLAUDE.md, and a
sanitized .claude/ config into a shared folder, generates a .wsb
configuration, and launches Windows Sandbox. By default it maps the host
models directory as a shortcut, but --no-models stages a true fresh-install
round where Install AI must download the production model/runtime payloads.

Software inside the sandbox is installed manually by the user (Git,
Claude Code, JustSearch, etc.). Drop installers into
``tmp/sandbox/share/tools/`` before launch if you want them visible
inside the sandbox at ``Desktop\\JustSearchTest\\tools\\``.

Usage:
    python sandbox-launch.py
    python sandbox-launch.py --installer dist/installer/JustSearch-setup.exe
    python sandbox-launch.py --no-launch
"""

import argparse
import base64
import hashlib
import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlparse

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
SANDBOX_FOLDER = r"C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest"
SANDBOX_MODELS_FOLDER = r"C:\Users\WDAGUtilityAccount\Desktop\JustSearchModels"


def find_installer(explicit_path: str | None) -> Path:
    """Resolve the NSIS installer, searching standard locations if not specified."""
    if explicit_path:
        p = Path(explicit_path)
        if not p.is_absolute():
            p = REPO_ROOT / p
        if not p.exists():
            sys.exit(f"Installer not found: {p}")
        return p

    # Search ALL candidate dirs and pick the globally newest. Picking the
    # newest within the first dir that contains any installer (the previous
    # behaviour) silently re-launched stale builds when an old artefact was
    # left in dist/installer/ but the actual fresh build lived under
    # dist/installer/windows-installer/ or modules/shell/.../bundle/nsis/.
    search_dirs = [
        REPO_ROOT / "dist" / "installer" / "windows-installer",
        REPO_ROOT / "dist" / "installer",
        REPO_ROOT / "modules" / "shell" / "src-tauri" / "target" / "x86_64-pc-windows-msvc" / "release" / "bundle" / "nsis",
    ]
    candidates: list[Path] = []
    for d in search_dirs:
        if d.is_dir():
            candidates.extend(d.glob("*-setup.exe"))
    if candidates:
        return max(candidates, key=lambda f: f.stat().st_mtime)

    sys.exit("No installer found. Build one first or pass --installer.")


def resolve_main_checkout_root() -> Path:
    """Resolve the main checkout root even when this script runs from a git
    worktree, mirroring scripts/dev/dev-runner.cjs's resolveMainRepoRoot()
    (tempdoc 618 SS2). A worktree's models/ dir holds only tracked
    config/tokenizer JSON — the real ~11.5 GB of model weights are untracked
    and live only in the MAIN checkout, so resolving from the current
    checkout silently maps a weightless dir.

    Prefers `git rev-parse --git-common-dir` (robust to worktree nesting and
    symlinks); falls back to manual .git-file parsing (a worktree's .git is a
    file pointing at <main>/.git/worktrees/<name>); falls back to REPO_ROOT
    itself if git is unavailable or this isn't a worktree."""
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--git-common-dir"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
        common_dir = result.stdout.strip()
        if common_dir:
            p = Path(common_dir)
            if not p.is_absolute():
                p = REPO_ROOT / p
            return p.resolve().parent
    except (subprocess.CalledProcessError, FileNotFoundError, OSError):
        pass

    git_path = REPO_ROOT / ".git"
    try:
        if git_path.is_file():
            content = git_path.read_text(encoding="utf-8").strip()
            if content.startswith("gitdir:"):
                git_dir = Path(content.split(":", 1)[1].strip())
                if not git_dir.is_absolute():
                    git_dir = REPO_ROOT / git_dir
                return git_dir.resolve().parent.parent.parent
    except OSError:
        pass

    return REPO_ROOT


def dir_has_model_weights(d: Path) -> bool:
    """True if d contains at least one *.onnx or *.gguf file anywhere beneath
    it. A dir with only tracked config/tokenizer JSON (a worktree's models/)
    is weightless and must not be mapped as pre-staged-models."""
    if not d.is_dir():
        return False
    return any(d.rglob("*.onnx")) or any(d.rglob("*.gguf"))


def resolve_models_dir(explicit_path: str | None) -> tuple[Path | None, str | None]:
    """Resolve the host models directory to map into the sandbox, refusing to
    map a directory that holds no model weights.

    Returns (resolved_dir, gap_message). resolved_dir is None whenever no
    weight-bearing directory was found — including when an explicit
    --models-dir was weightless (refused, not mapped). gap_message is a
    staging-gaps.md entry explaining why, or None when a weight-bearing dir
    was found and will be mapped."""
    main_root = resolve_main_checkout_root()

    if explicit_path:
        p = Path(explicit_path)
        if not p.is_absolute():
            p = REPO_ROOT / p
        if not p.is_dir():
            sys.exit(f"--models-dir not found: {p}")
        if dir_has_model_weights(p):
            return p, None
        return None, (
            f"--models-dir {p} has no model weights (no *.onnx/*.gguf found "
            "under it) — refusing to map it and claim pre-staged-models. The "
            f"real ~11.5 GB of weights live only in the main checkout at "
            f"{main_root / 'models'}. Remedy: pass --models-dir "
            f"{main_root / 'models'}, or --no-models for a true fresh-install round."
        )

    for candidate in (main_root / "models", REPO_ROOT / "models"):
        if candidate.is_dir() and dir_has_model_weights(candidate):
            return candidate, None

    # Auto-detect found a models/ dir but it had no weights (the worktree
    # case) — report it explicitly rather than falling through to "no models
    # directory found at all", which understates what actually happened.
    for candidate in (main_root / "models", REPO_ROOT / "models"):
        if candidate.is_dir():
            return None, (
                f"Auto-detected models dir {candidate} has no model weights "
                "(no *.onnx/*.gguf found under it) — refusing to map it and "
                "claim pre-staged-models. This is a worktree checkout, which "
                "holds only tracked config/tokenizer JSON; the real weights "
                f"live only in the main checkout at {main_root / 'models'}. "
                f"Remedy: pass --models-dir {main_root / 'models'} explicitly, "
                "or --no-models for a true fresh-install round."
            )
    return None, None


def resolve_upgrade_from(explicit_path: str, candidate_installer: Path) -> Path:
    """Resolve and validate the --upgrade-from previous-release installer path
    for an upgrade-from-release round (tempdoc 750 Part C).

    FAILS CLOSED: the path must exist, must be a .exe, and must not share the
    candidate installer's filename -- staging the same file twice under two
    different labels (candidate vs. "previous release") would silently
    defeat the point of an upgrade round, which needs two DISTINCT binaries
    (e.g. JustSearch_0.1.0_x64-setup.exe upgraded to the current candidate)."""
    p = Path(explicit_path)
    if not p.is_absolute():
        p = REPO_ROOT / p
    if not p.is_file():
        sys.exit(f"--upgrade-from installer not found: {p}")
    if p.suffix.lower() != ".exe":
        sys.exit(f"--upgrade-from installer must be a .exe: {p}")
    if p.name == candidate_installer.name:
        sys.exit(
            f"--upgrade-from installer has the same filename as the candidate "
            f"installer ({p.name}) -- refusing to stage the same file twice as "
            "both 'previous release' and 'candidate'. Pass the actual previous "
            "public release installer (e.g. JustSearch_0.1.0_x64-setup.exe)."
        )
    return p


def clean_dir(path: Path):
    """Remove a directory tree, handling read-only files. Best-effort: a
    Hyper-V worker (vmwp.exe) holds VHDX overlays on a sandbox's mapped
    folder for some time after the sandbox terminates, which makes the dir
    unrenamable and unrmable from user session. Caller should fall back to
    a fresh staging dir if this raises."""
    if not path.exists():
        return
    for root, dirs, files in os.walk(path):
        for f in files:
            fp = Path(root) / f
            try:
                fp.chmod(0o666)
            except OSError:
                pass
    shutil.rmtree(path)


def archive_existing_evidence(stage_root: Path) -> Path | None:
    """Archive a previous round's captured evidence before staging can clobber it.

    A later round overwriting the mapped `share/` folder destroyed rounds 1-2's
    raw evidence (screenshots, traces, logs, findings) with no way to recover
    it afterward -- an audit could not verify that round's claims against
    them, permanently. Rounds 3-4 survived only because a human hand-copied
    `share/evidence/` out before relaunch (and, separately, once by luck: a
    Hyper-V lock blocked resolve_share_dir()'s clean_dir() and it fell back
    to a fresh timestamped dir instead of deleting). This function makes that
    hand-copy automatic and load-bearing, called from main() BEFORE
    resolve_share_dir() so the canonical `share` dir is moved aside -- never
    deleted -- whenever it holds evidence.

    Returns the archive destination when something was archived, or None
    when there was nothing to archive: no canonical `share` dir yet (first
    run), or a `share` dir whose evidence/ is absent or has zero files (a
    staged-but-never-launched round, or a round that never reached
    collect-evidence.ps1) -- both are the normal first-run case, not an
    error, per this function's contract.

    FAILS CLOSED: if the move itself raises, this exits the whole staging
    run non-zero rather than falling through to let resolve_share_dir()'s
    clean_dir() proceed and delete the unarchived evidence. The likely cause
    is the same Hyper-V VHDX-overlay lock class documented on clean_dir()'s
    docstring (a just-closed sandbox's vmwp.exe can hold handles on the
    mapped folder for a while) -- but whatever the cause, losing evidence
    must be impossible here, not unlikely, so no fallback path is offered.
    """
    canonical = stage_root / "share"
    if not canonical.exists():
        return None  # nothing staged yet -- first run

    evidence_dir = canonical / "evidence"
    has_evidence = evidence_dir.is_dir() and any(f.is_file() for f in evidence_dir.rglob("*"))
    if not has_evidence:
        print(f"No captured evidence under {evidence_dir} -- nothing to archive (first run or empty round).")
        return None

    archive_root = stage_root / "archive"
    archive_root.mkdir(parents=True, exist_ok=True)
    round_num = len([p for p in archive_root.iterdir() if p.is_dir()]) + 1

    # Ground the archive name in the evidence's own recorded mtimes, not
    # "now": archiving can run long after a round actually captured its
    # evidence (a staged share dir can sit for hours before the next
    # relaunch), so the newest file mtime inside evidence/ is the honest
    # "when did this round happen" stamp -- not wall-clock-at-archive-time.
    # The round_num prefix guarantees ordering/uniqueness even if two rounds'
    # evidence happens to share a timestamp.
    import time as _time

    newest_mtime = max(
        (f.stat().st_mtime for f in evidence_dir.rglob("*") if f.is_file()),
        default=canonical.stat().st_mtime,
    )
    stamp = _time.strftime("%Y%m%d-%H%M%S", _time.localtime(newest_mtime))
    dest = archive_root / f"round-{round_num:03d}-{stamp}"

    try:
        shutil.move(str(canonical), str(dest))
    except OSError as e:
        sys.exit(
            f"FAILED to archive previous round's evidence: could not move "
            f"{canonical} -> {dest} ({e.__class__.__name__}: {e}).\n"
            "Refusing to continue staging -- proceeding would let the next "
            "step (resolve_share_dir's clean_dir) delete this round's "
            "un-archived evidence (screenshots, traces, logs, findings), "
            "which is unrecoverable once gone. This is most likely the "
            "Hyper-V VHDX-overlay lock documented in clean_dir()'s "
            "docstring: a just-closed sandbox's vmwp.exe process can hold "
            "file handles on the mapped folder for a while after the "
            "window closes. Wait for the lock to clear (check Task Manager "
            "for vmwp.exe), or archive the evidence manually, then re-run."
        )

    print(f"Archived previous round's evidence: {canonical} -> {dest}")
    return dest


def resolve_share_dir(stage_root: Path) -> Path:
    """Pick a share directory under stage_root, sidestepping locks left by a
    prior sandbox's Hyper-V worker. Tries the canonical 'share' first; if it
    exists and can't be removed, allocates 'share-<timestamp>' instead."""
    canonical = stage_root / "share"
    try:
        clean_dir(canonical)
        return canonical
    except (PermissionError, OSError) as e:
        import time as _time
        ts = _time.strftime("%Y%m%d-%H%M%S")
        fallback = stage_root / f"share-{ts}"
        print(
            f"Note: {canonical} is locked ({e.__class__.__name__}). "
            f"Using fresh staging dir {fallback} instead."
        )
        return fallback


def stage_docs(share_dir: Path):
    """Copy project documentation for Claude Code context inside the sandbox."""
    docs_src = REPO_ROOT / "docs"
    if not docs_src.is_dir():
        return
    docs_dst = share_dir / "docs"
    docs_dst.mkdir(parents=True, exist_ok=True)

    for sub in ("explanation", "reference", "how-to", "decisions", "tempdocs"):
        src = docs_src / sub
        if src.is_dir():
            shutil.copytree(src, docs_dst / sub, dirs_exist_ok=True)

    llmstxt = docs_src / "llms.txt"
    if llmstxt.exists():
        shutil.copy2(llmstxt, docs_dst / "llms.txt")

    print("Staged docs (explanation, reference, how-to, decisions, tempdocs)")


def stage_scifact(share_dir: Path) -> str | None:
    """Copy the SciFact eval corpus into the sandbox so the validation agent
    can ingest it via POST /api/knowledge/ingest and run search-quality
    assertions. ~5,184 .txt docs, ~11 MB.

    Returns a staging-gap message if the host corpus isn't materialized (in
    which case nothing is staged and search-quality verification cannot run
    this round), or None if it staged successfully."""
    src = REPO_ROOT / "scripts" / "jseval" / "tmp" / "eval-corpora" / "scifact"
    if not src.is_dir():
        print(f"SciFact corpus not found at {src} — skipping (run jseval to materialize)")
        return (
            f"SciFact corpus not staged — not found at {src}. Search-quality "
            "assertions (POST /api/knowledge/ingest + query verification) cannot "
            "run this round. Remedy: from scripts/jseval, run "
            "`python -m jseval run --dataset scifact --modes lexical,hybrid --pipeline` "
            "to materialize the corpus, then re-run sandbox-launch.py."
        )
    dst = share_dir / "scifact"
    shutil.copytree(src, dst, dirs_exist_ok=True)
    file_count = sum(1 for _ in dst.iterdir() if _.is_file())
    print(f"Staged SciFact corpus ({file_count} files)")
    return None


def stage_golden_parity(share_dir: Path) -> str | None:
    """Stage the golden-parity search-quality harness (parity-with-dev, owner
    design): the fixed golden-queries.json query set always ships (it's
    checked in), plus the per-candidate golden-parity.json baseline when the
    operator has generated one for THIS candidate build.

    golden-parity.json is not committed — it's generated per candidate via
    `gen_golden_parity.py` against a running dev stack on the same build +
    corpus, and is looked for at its default output location next to this
    script (scripts/sandbox/golden-parity.json) for simplicity. An operator
    who wrote it elsewhere can drop/copy it there before staging.

    Returns a staging-gap message if the baseline is absent (in which case
    the round still stages the query set — the sandbox agent can capture
    responses via collect-evidence.ps1 — but has no baseline to check parity
    against at finalize), or None if the baseline staged successfully.
    """
    queries_src = SCRIPT_DIR / "golden-queries.json"
    if queries_src.exists():
        shutil.copy2(queries_src, share_dir / "golden-queries.json")
        print("Staged golden-queries.json")
    else:
        print(f"golden-queries.json not found at {queries_src} — golden-query capture will not run this round")

    baseline_src = SCRIPT_DIR / "golden-parity.json"
    if baseline_src.exists():
        shutil.copy2(baseline_src, share_dir / "golden-parity.json")
        print("Staged golden-parity.json (per-candidate baseline)")
        return None

    print(f"golden-parity.json not found at {baseline_src} — no per-candidate search-parity baseline for this round")
    return (
        "golden-parity baseline not generated for this candidate — run "
        "`python scripts/sandbox/gen_golden_parity.py --api-port <port> --corpus scifact` "
        "against the dev stack on this build with scifact ingested, then re-run sandbox-launch.py. "
        "Without it, the round can still capture golden-query responses via collect-evidence.ps1 but "
        "check_golden_parity.py has nothing to compare them against at finalize."
    )


def check_node_installer_staged(tools_cache: Path) -> str | None:
    """Check whether a Node.js Windows installer is present in the host tools
    cache. `collect-evidence.ps1`'s in-sandbox MCP Inspector check needs `npx`
    (Node) on PATH; if nothing is staged, that check silently depends on a
    mid-session internet download instead of a reproducible staged asset.

    Returns a staging-gap message if no installer is found, or None if one is
    already present."""
    if tools_cache.is_dir():
        found = list(tools_cache.glob("node*.msi")) + list(tools_cache.glob("node*.exe"))
        if found:
            return None
    return (
        f"No Node.js installer found in {tools_cache} — the in-sandbox MCP "
        "Inspector check (npx @modelcontextprotocol/inspector) has no staged "
        "Node runtime, so it depends on a mid-session download instead of a "
        "reproducible staged asset. Remedy: drop a Node LTS Windows installer "
        "(e.g. node-v*-x64.msi) into that directory before launching."
    )


def write_staging_gaps(share_dir: Path, gaps: list[str]):
    """Write the collected staging gaps to <share>/staging-gaps.md.

    Gaps do NOT abort staging — the fail-closed abort stays reserved for the
    coverage-brief step (an unclassified shipped surface). This file is the
    in-sandbox agent's authoritative record of assets the host failed to
    stage, so each entry must be recorded as a round-level coverage gap
    rather than silently absorbed. Future gap types append another string to
    the `gaps` list passed in — no format change needed here."""
    lines = ["# Staging Gaps", ""]
    if gaps:
        for gap in gaps:
            lines.append(f"- {gap}")
    else:
        lines.append("None — all documented assets staged.")
    lines.append("")
    lines.append(
        "The in-sandbox agent must read this file and record each listed gap "
        "as a round-level coverage gap, not silently absorb it."
    )
    (share_dir / "staging-gaps.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if gaps:
        print()
        print("WARNING: staging gaps detected — see staging-gaps.md")
        for gap in gaps:
            print(f"  - {gap}")
        print()
    else:
        print("Staged staging-gaps.md (no gaps)")


def _assert_no_dangling_hook_refs(settings: dict, claude_dst: Path):
    """Fail loudly if any hook command path in `settings` (the staged, already-
    sanitized settings dict) resolves outside the staged tree.

    Walks settings.get("hooks", {}) — a dict of event name -> list of matcher
    groups -> list of {command, args} entries, per Claude Code's hooks schema
    — and resolves every ${CLAUDE_PROJECT_DIR}-prefixed path in `args` against
    `claude_dst.parent` (the staged share dir, which stands in for the
    sandbox's project dir). Exits the whole staging run non-zero on the first
    dangling reference: a hook wired to a script that was never staged throws
    a module-resolution error on every tool call all round (the defect this
    guards against), so this must abort staging, not warn."""
    hooks = settings.get("hooks")
    if not hooks:
        return
    share_dir = claude_dst.parent
    dangling: list[str] = []
    for event_name, groups in hooks.items():
        if not isinstance(groups, list):
            continue
        for group in groups:
            for entry in group.get("hooks", []) if isinstance(group, dict) else []:
                if not isinstance(entry, dict):
                    continue
                for arg in entry.get("args", []):
                    if not isinstance(arg, str) or "${CLAUDE_PROJECT_DIR}" not in arg:
                        continue
                    rel = arg.replace("${CLAUDE_PROJECT_DIR}", "").lstrip("/\\")
                    resolved = share_dir / rel
                    if not resolved.exists():
                        dangling.append(f"{event_name}: {arg} -> {resolved} (missing)")
    if dangling:
        sys.exit(
            "Staged .claude/settings.json references hook script(s) that were "
            "never staged into the sandbox share — every tool call would throw "
            "a module-resolution error all round:\n"
            + "\n".join(f"  - {d}" for d in dangling)
            + "\nEither stage the referenced scripts, or strip the hooks key "
            "for this settings block."
        )


def stage_claude_settings(share_dir: Path):
    """Copy .claude/ project config, sanitizing for sandbox (no LSP plugins, no MCP).

    Keeps only the rules and skill files that make sense without dev tooling.
    """
    claude_src = REPO_ROOT / ".claude"
    if not claude_src.is_dir():
        return

    claude_dst = share_dir / ".claude"
    claude_dst.mkdir(parents=True, exist_ok=True)

    RULES_KEEP = {"agent-lessons.md", "context-efficiency.md"}
    rules_src = claude_src / "rules"
    if rules_src.is_dir():
        rules_dst = claude_dst / "rules"
        rules_dst.mkdir(parents=True, exist_ok=True)
        for f in rules_src.iterdir():
            if f.is_file() and f.name in RULES_KEEP:
                shutil.copy2(f, rules_dst / f.name)
        total = sum(1 for _ in rules_src.iterdir())
        print(f"Staged .claude/rules/ (kept {len(RULES_KEEP)} of {total} files)")

    # Skills: stage only the sandbox-aware start skill. Project skills
    # reference dev tools (jseval, gradle, MCP) and would mislead the agent.
    sandbox_start = SCRIPT_DIR / "sandbox-start-SKILL.md"
    if sandbox_start.exists():
        skills_dst = claude_dst / "skills" / "start"
        skills_dst.mkdir(parents=True, exist_ok=True)
        shutil.copy2(sandbox_start, skills_dst / "SKILL.md")
        print("Staged .claude/skills/start/ (sandbox-aware)")

    settings_src = claude_src / "settings.json"
    if settings_src.exists():
        try:
            settings = json.loads(settings_src.read_text(encoding="utf-8"))
            settings.pop("enabledPlugins", None)
            settings["enableAllProjectMcpServers"] = False
            settings.pop("enabledMcpjsonServers", None)
            # Strip the hooks block entirely. It wires every PreToolUse/
            # PostToolUse hook to ${CLAUDE_PROJECT_DIR}/scripts/agent-analytics/
            # hooks/*.mjs, but this function never stages scripts/agent-analytics/
            # (there is no git repo or dev tooling in the sandbox for those hooks
            # to act on anyway — the git-safety guards are meaningless without a
            # repo, and build-counter/ssot-hint/etc. have no gradle/SSOT to react
            # to). Left in place, every tool call in the round would throw a
            # module-resolution error, all round — desensitizing the agent to
            # hook noise and creating a false belief that a safety net is active
            # when none is. Matches this function's existing philosophy: skills
            # are staged as only the sandbox-aware subset, not the raw project
            # set, for the same "would mislead the agent" reason.
            settings.pop("hooks", None)
            # Sandbox is ephemeral and isolated — start Claude in bypass mode
            # so the user is not prompted for every tool call.
            settings.setdefault("permissions", {})["defaultMode"] = "bypassPermissions"

            # Fail-closed ratchet: assert no *staged* settings reference a hook
            # command path outside the staged tree. This is trivially satisfied
            # today (hooks are stripped above) but guards against a future edit
            # re-introducing a hooks block (or any other settings key that names
            # a ${CLAUDE_PROJECT_DIR}-relative script) without staging the
            # scripts it points at — turning a silent dead-hook regression back
            # into this exact defect instead of a loud staging failure.
            _assert_no_dangling_hook_refs(settings, claude_dst)

            (claude_dst / "settings.json").write_text(
                json.dumps(settings, indent=2), encoding="utf-8"
            )
            print("Staged .claude/settings.json (sanitized + bypassPermissions, hooks stripped)")
        except Exception as e:
            shutil.copy2(settings_src, claude_dst / "settings.json")
            print(f"Staged .claude/settings.json (raw copy, sanitization failed: {e})")


def _sha256_of(path: Path) -> str:
    """Return the hex SHA-256 digest of a file, reading in 1 MB chunks (avoids
    loading a whole installer exe into memory)."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def stage_upgrade_installer(share_dir: Path, upgrade_installer: Path) -> tuple[str, str]:
    """Stage the previous release's installer into share/previous-release/
    (tempdoc 750 Part C), next to the candidate under share root, and return
    (filename, sha256) for write_validation_mode()'s mode section.

    The candidate installer is already staged at share_dir root by main();
    this keeps the previous release distinctly namespaced so the in-sandbox
    agent cannot confuse which installer is which."""
    dst_dir = share_dir / "previous-release"
    dst_dir.mkdir(parents=True, exist_ok=True)
    dst = dst_dir / upgrade_installer.name
    shutil.copy2(upgrade_installer, dst)
    digest = _sha256_of(upgrade_installer)
    print(f"Staged previous-release installer: {upgrade_installer.name} (sha256 {digest})")
    return upgrade_installer.name, digest


def _ed25519_raw_public_key(public_key_path: Path) -> str:
    """Convert an Ed25519 SPKI PEM public key to the raw 32-byte base64 form
    consumed by the Rust updater. Fail closed on any other key shape."""
    text = public_key_path.read_text(encoding="utf-8")
    encoded = "".join(
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.startswith("-----")
    )
    try:
        der = base64.b64decode(encoded, validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        sys.exit(f"Updater metadata public key is not valid PEM/base64: {exc}")
    spki_prefix = bytes.fromhex("302a300506032b6570032100")
    if len(der) != len(spki_prefix) + 32 or not der.startswith(spki_prefix):
        sys.exit("Updater metadata public key must be an Ed25519 SPKI PEM key.")
    return base64.b64encode(der[-32:]).decode("ascii")


def stage_in_app_updater_assets(
    share_dir: Path,
    installer: Path,
    release_dir: Path,
    metadata_public_key_path: Path,
) -> dict[str, object]:
    """Verify and stage the authenticated release closed set for an in-app
    updater Sandbox round.

    Production consumes HTTPS. A Sandbox test candidate compiled with the
    explicit release test gate consumes this same byte-for-byte set over the
    loopback-only server staged below.
    """
    release_dir = release_dir.resolve()
    metadata_public_key_path = metadata_public_key_path.resolve()
    required = [
        release_dir / "release.v1.json",
        release_dir / "release.v1.json.sig",
        release_dir / "latest.json",
        release_dir / f"{installer.name}.sig",
        metadata_public_key_path,
    ]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        sys.exit("Missing in-app updater release asset(s): " + ", ".join(missing))

    try:
        descriptor = json.loads(required[0].read_text(encoding="utf-8"))
        latest = json.loads(required[2].read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        sys.exit(f"Updater release metadata is unreadable: {exc}")

    artifact = descriptor.get("artifact") or {}
    artifact_url = str(artifact.get("url") or "")
    parsed_url = urlparse(artifact_url)
    candidate_hash = _sha256_of(installer)
    latest_platform = (latest.get("platforms") or {}).get("windows-x86_64") or {}
    metadata_key_id = str(descriptor.get("metadataKeyId") or "")
    if (
        descriptor.get("schemaVersion") != 1
        or parsed_url.scheme != "http"
        or parsed_url.hostname not in {"127.0.0.1", "localhost"}
        or Path(parsed_url.path).name != installer.name
        or artifact.get("sha256") != candidate_hash
        or latest.get("version") != descriptor.get("version")
        or latest_platform.get("url") != artifact_url
        or latest_platform.get("signature") != artifact.get("signature")
        or not metadata_key_id
    ):
        sys.exit(
            "Updater release set is not a closed loopback Sandbox set for "
            "the selected candidate installer."
        )

    verifier = REPO_ROOT / "scripts" / "release" / "app-release-assets.mjs"
    verified = subprocess.run(
        [
            "node",
            str(verifier),
            "verify",
            "--installer",
            str(installer),
            "--artifact-signature",
            str(release_dir / f"{installer.name}.sig"),
            "--metadata-public-key",
            str(metadata_public_key_path),
            "--release-dir",
            str(release_dir),
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if verified.returncode != 0:
        detail = (verified.stderr or verified.stdout).strip()
        sys.exit(f"Authenticated updater release verification failed: {detail}")

    feed_dir = share_dir / "updater-release"
    feed_dir.mkdir(parents=True, exist_ok=True)
    for source in required[:4]:
        shutil.copy2(source, feed_dir / source.name)
    shutil.copy2(installer, feed_dir / installer.name)
    for script_name in (
        "serve-updater-feed.ps1",
        "start-in-app-update-test.ps1",
        "collect-updater-evidence.ps1",
    ):
        shutil.copy2(SCRIPT_DIR / script_name, share_dir / script_name)

    info: dict[str, object] = {
        "schemaVersion": 1,
        "mode": "in-app-update-from-release",
        "version": descriptor.get("version"),
        "sequence": descriptor.get("sequence"),
        "candidateInstaller": installer.name,
        "candidateSha256": candidate_hash,
        "descriptorUrl": artifact_url.rsplit("/", 1)[0] + "/release.v1.json",
        "metadataRootKeyId": metadata_key_id,
        "metadataRootPublicKey": _ed25519_raw_public_key(metadata_public_key_path),
        "durablePhases": [
            "PREPARED",
            "HEAD_STOPPED",
            "INSTALL_LAUNCHING",
            "INSTALL_LAUNCHED",
            "RECONCILING",
            "COMMITTED",
            "CANCELLED",
            "REPAIR_REQUIRED",
        ],
    }
    (share_dir / "updater-qualification.v1.json").write_text(
        json.dumps(info, indent=2) + "\n", encoding="utf-8"
    )
    (share_dir / "updater-qualification.md").write_text(
        "\n".join(
            [
                "# In-app updater qualification",
                "",
                "This lane is valid only when the installed SOURCE build "
                "contains the updater and was compiled from the previous "
                "release source with `JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE=1` "
                "and Tauri updater `dangerousInsecureTransportProtocol=true`. "
                "Production builds must reject this loopback transport. The "
                "ordinary `upgrade-from-release` lane separately qualifies the "
                "exact published previous installer; the loopback lane cannot "
                "be byte-identical because that production binary rejects "
                "runtime trust overrides by design.",
                "",
                "This lane proves the APPLY MACHINERY. That the user is asked before "
                "anything is applied is a separate claim, and it is covered by the "
                "operator-driven whole-product round, not here. Run both.",
                "",
                "1. Install the updater-capable previous-source Sandbox build "
                "and seed retained user state.",
                "2. Run `powershell -ExecutionPolicy Bypass -File .\\start-in-app-update-test.ps1`, "
                "adding `-Autorun` to drive check and install with no operator input. The app "
                "honours `-Autorun` only when compiled with the qualification gate; a production "
                "build ignores it.",
                "3. Without `-Autorun`: in Settings, check for the authenticated update and "
                "explicitly choose install. This is the consent path.",
                "4. Before any deliberate interruption, and after every restart, run "
                "`.\\collect-updater-evidence.ps1`. Preserve `evidence\\updater`.",
                "5. A normal round passes only when the installed version is the target, "
                "the intent is `COMMITTED`, and seeded state survives. With `-Autorun`, "
                "`updater-state.json` carries `autorunVerdict` directly — but it is written by "
                "the SECOND boot, because a successful apply exits the process that started it. "
                "A missing verdict after one boot means the handoff did not complete.",
                "",
                "Recovery oracles:",
                "- A source-version restart with a pre-launch intent must settle `CANCELLED`.",
                "- A target-version restart with a witnessed launch must settle `COMMITTED`.",
                "- An unprovable/invalid launch or unavailable recovery path must settle "
                "`REPAIR_REQUIRED` and keep the signed staged artifact for diagnosis.",
                "- `installer-launch-witness.v1.json` must agree with the intent attempt, "
                "digest, size, staged path, and process id.",
                "",
                "The Rust transition/fixture suite deterministically exercises every durable "
                "phase. This Sandbox lane supplies the real NSIS/Windows recovery oracle; do "
                "not claim a phase interruption unless its before/after evidence was captured.",
                "",
            ]
        ),
        encoding="utf-8",
    )
    print(
        "Staged authenticated loopback updater feed "
        f"(version {info['version']}, sequence {info['sequence']})."
    )
    return info


def write_validation_mode(
    share_dir: Path,
    installer: Path,
    models_dir: Path | None,
    no_models: bool,
    upgrade_info: tuple[str, str] | None = None,
    updater_info: dict[str, object] | None = None,
):
    """Write the actual launch mode into the mapped folder.

    The static sandbox docs describe both fresh and pre-staged model modes.
    This generated file is the authority for the current sandbox instance.

    upgrade_info, when given, is (previous_installer_filename, sha256) staged
    by stage_upgrade_installer() for an upgrade-from-release round (tempdoc
    750 Part C). It takes priority over no_models/models_dir: an upgrade
    round always exercises the real download path (like fresh-install),
    never the pre-staged-models shortcut (enforced in main()'s argument
    parsing -- --upgrade-from and --models-dir are mutually exclusive).

    Every mode also writes a machine-readable "ExpectPriorInstall" marker
    that collect-evidence.ps1 reads to decide whether a FOUND prior-install
    signal is the expected state (upgrade-from-release) or a warning
    (every other mode).
    """
    expect_prior_install = upgrade_info is not None

    if upgrade_info:
        prev_name, prev_sha256 = upgrade_info
        mode = (
            "in-app-update-from-release"
            if updater_info
            else "upgrade-from-release"
        )
        details = [
            "Host models mapped: no",
            "JUSTSEARCH_MODELS_DIR: must remain unset",
            "Install AI expectation: full clean download of models and cuda-runtime (like fresh-install)",
            "Coverage: production first-run download, manifest resolution, native-bin extraction, "
            "plus retained-user-data survival across an upgrade (ADR-0024)",
            f"Previous release installer: previous-release/{prev_name}",
            f"Previous release SHA-256: {prev_sha256}",
            "Instruction sequence:",
            "1. Install the PREVIOUS release from previous-release/, launch it once so it creates "
            "real user data, seed minimal data (add one folder, run one search), quit it fully.",
            "2. Run the CANDIDATE installer over it.",
            "3. Proceed with the normal mission -- data survival across the upgrade is itself a "
            "required observation, per ADR-0024 retained user data.",
        ]
        if updater_info:
            details.extend(
                [
                    "Candidate apply path: authenticated in-app updater over loopback Sandbox feed",
                    f"Target version: {updater_info.get('version')}",
                    f"Release sequence: {updater_info.get('sequence')}",
                    "Start command: powershell -ExecutionPolicy Bypass -File "
                    ".\\start-in-app-update-test.ps1",
                    "Evidence command: powershell -ExecutionPolicy Bypass -File "
                    ".\\collect-updater-evidence.ps1",
                ]
            )
    elif no_models:
        mode = "fresh-install"
        details = [
            "Host models mapped: no",
            "JUSTSEARCH_MODELS_DIR: must remain unset",
            "Install AI expectation: full clean download of models and cuda-runtime",
            "Coverage: production first-run download, manifest resolution, native-bin extraction",
        ]
    elif models_dir:
        mode = "pre-staged-models"
        details = [
            f"Host models mapped: yes, {models_dir}",
            f"Sandbox models path: {SANDBOX_MODELS_FOLDER}",
            f"JUSTSEARCH_MODELS_DIR: set to {SANDBOX_MODELS_FOLDER} only if intentionally using the shortcut",
            "Install AI expectation: shortcut mode; does not validate production download/extraction",
        ]
    else:
        mode = "fresh-install-no-host-models-found"
        details = [
            "Host models mapped: no host models directory was found",
            "JUSTSEARCH_MODELS_DIR: must remain unset",
            "Install AI expectation: full clean download of models and cuda-runtime",
            "Coverage: production first-run download, manifest resolution, native-bin extraction",
        ]

    text = [
        "# Sandbox Validation Mode",
        "",
        "This file is generated by `scripts/sandbox/sandbox-launch.py` and is",
        "the authority for this sandbox instance. If it conflicts with the",
        "static docs, follow this file.",
        "",
        f"- Mode: {mode}",
        f"- Installer: {installer.name}",
        *[f"- {line}" for line in details],
        f"- ExpectPriorInstall: {'true' if expect_prior_install else 'false'}",
        "",
        "The final validation summary must state this mode explicitly.",
        "",
    ]
    (share_dir / "validation-mode.md").write_text("\n".join(text), encoding="utf-8")
    print(f"Staged validation-mode.md ({mode})")


def _read_resolved_mode(share_dir: Path) -> tuple[str, str]:
    """Read the mode + a one-line meaning back out of the validation-mode.md
    that write_validation_mode() already wrote, without touching that
    function's own mode-resolution logic (out of scope for this change).

    Returns (mode, meaning). Tolerant of a missing/malformed file -- returns
    ("unknown", "") rather than raising, since a trailer with a degraded
    meaning is better than aborting an otherwise-valid charter stage."""
    path = share_dir / "validation-mode.md"
    if not path.is_file():
        return "unknown", ""
    mode = "unknown"
    meaning = ""
    found_mode = False
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not found_mode:
            if stripped.startswith("- Mode:"):
                mode = stripped[len("- Mode:"):].strip()
                found_mode = True
            continue
        if stripped.startswith("- "):
            meaning = stripped[2:].strip()
            break
    return mode, meaning


def stage_charter(share_dir: Path, charter_path: str | None, no_charter: bool):
    """Stage this round's charter (Session-Based Test Management, J. Bach &
    J. Bach, STQE 2000 -- charter/debrief/TBS time accounting, adapted;
    tempdoc 750 Part B). A charter pre-registers the round's purpose and
    each blocker's needs-round/needs-dig classification BEFORE the round
    starts, per Bach & Bach: "a charter... states what is to be tested,
    why, and how."

    With a charter path: validates the file exists and is non-empty, then
    stages it verbatim as charter.md with a generated "## Resolved launch
    mode" trailer section appended, reflecting the mode write_validation_mode()
    already resolved for this instance (read back via _read_resolved_mode(),
    not re-derived here).

    With no_charter: prints a one-line notice that this launch is
    non-qualifying and stages nothing.

    FAILS CLOSED on a missing or empty charter file -- an unreadable or
    blank charter is not a real pre-registration, and staging it as though
    it were would silently defeat the point of requiring one.
    """
    if no_charter:
        print("Non-qualifying launch: no charter staged (--no-charter).")
        return

    path = Path(charter_path)
    if not path.is_absolute():
        path = REPO_ROOT / path
    if not path.is_file():
        sys.exit(f"--charter file not found: {path}")

    content = path.read_text(encoding="utf-8")
    if not content.strip():
        sys.exit(f"--charter file is empty: {path}")

    mode, meaning = _read_resolved_mode(share_dir)
    trailer = f"\n## Resolved launch mode\n{mode}: {meaning}\n"
    (share_dir / "charter.md").write_text(content.rstrip("\n") + "\n" + trailer, encoding="utf-8")
    print(f"Staged charter.md ({path.name}, + resolved-mode trailer: {mode})")


def stage_coverage_brief(share_dir: Path):
    """Generate the per-candidate must-touch coverage brief (tempdoc 728, Part B1).

    Derives the surfaces this candidate must exercise from committed artifacts
    (route-manifest cohorts, CorePlugin.ts surfaces, interaction shapes) against
    governance/sandbox-coverage.v1.json. FAILS CLOSED: if the candidate ships a
    surface not yet classified in the register, the generator exits non-zero and
    we abort staging rather than validate against a silently-incomplete brief —
    that is the whole point (a new surface can no longer be forgotten).
    """
    gen = SCRIPT_DIR / "gen_coverage_brief.py"
    result = subprocess.run(
        [sys.executable, str(gen), "--out-dir", str(share_dir)],
        capture_output=True,
        text=True,
    )
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        sys.exit(
            "Coverage-brief generation FAILED (a shipped surface is unclassified in "
            "governance/sandbox-coverage.v1.json). Classify it there before validating."
        )
    print("Staged coverage-brief.md + coverage-manifest.json")


def stage_round_plan(share_dir: Path):
    """Derive round-plan.md from the just-staged coverage-manifest.json
    (tempdoc 729-followup, mechanical brief-to-plan derivation).

    A round previously built its task list from the operator's prose instead
    of from the generated coverage-brief.md, and missed a whole mandatory
    cohort plus 4 of 5 required shapes — caught only by luck at finalize.
    Deriving this checklist automatically at staging time (rather than
    leaving it as a script the round must remember to invoke) is what makes
    the round's plan actually DERIVED from the authority instead of merely
    read alongside it. Non-fatal by design: coverage-manifest.json's own
    generation (stage_coverage_brief, just above) is the fail-closed step —
    if that succeeded, deriving a checklist from its output should not itself
    be able to abort staging.
    """
    manifest_path = share_dir / "coverage-manifest.json"
    if not manifest_path.exists():
        print("WARNING: coverage-manifest.json not staged — skipping round-plan.md derivation")
        return
    derive = SCRIPT_DIR / "derive_round_plan.py"
    out_path = share_dir / "round-plan.md"
    result = subprocess.run(
        [sys.executable, str(derive), "--manifest", str(manifest_path), "--out", str(out_path)],
        capture_output=True,
        text=True,
    )
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        print("WARNING: round-plan.md derivation FAILED (non-fatal — coverage-brief.md/coverage-manifest.json remain the authority)")
    else:
        print("Staged round-plan.md (mechanically derived checklist)")


def stage_evidence_harness(share_dir: Path):
    """Copy the in-sandbox capture harness (tempdoc 728, cross-cutting)."""
    harness = SCRIPT_DIR / "collect-evidence.ps1"
    if harness.exists():
        shutil.copy2(harness, share_dir / "collect-evidence.ps1")
        print("Staged collect-evidence.ps1")


def stage_gui_harness(share_dir: Path):
    """Copy the native PowerShell GUI capture/input harness (tempdoc
    727-followup). This is the resolved-negative-on-Chrome, working-native
    alternative for surface-tier (screenshot) coverage: it drives the real
    Tauri WebView2 shell via CopyFromScreen/SendKeys/mouse_event, needs no
    computer-use tool, extension, pairing, or network. Staged next to
    collect-evidence.ps1 so no round has to reconstruct it from scratch."""
    gui_src = SCRIPT_DIR / "gui"
    if not gui_src.is_dir():
        return
    gui_dst = share_dir / "gui"
    gui_dst.mkdir(parents=True, exist_ok=True)
    count = 0
    for f in gui_src.iterdir():
        if f.is_file():
            shutil.copy2(f, gui_dst / f.name)
            count += 1
    print(f"Staged gui/ ({count} files — native PowerShell GUI capture/input harness)")


def stage_mcp_client_harness(share_dir: Path):
    """Copy the TYPED_CONFIRM MCP-client harness (D2, tempdoc 728-followup).

    The MCP Inspector CLI's `--tool-arg` string-coerces every value and cannot
    express `justsearch_ingest`'s `paths: string[]` argument, so the mutating
    -tool step of the cohort:mcp procedure cannot be driven through it
    (verified against a real round). Rather than promoting a second, divergent
    HTTP client, this stages the REAL shipped MCPB stdio bridge
    (packaging/mcpb/server/index.js, copied verbatim) plus a thin Node driver
    (mcp-typed-confirm.mjs) that spawns it and speaks JSON-RPC over its
    stdin/stdout -- exactly how a real MCP host drives it. That means the
    round validates the actual artifact JustSearch ships, not a parallel
    bespoke client. (A PowerShell predecessor was removed 2026-07-15: it hung
    waiting on the tools/call response even though the server and bridge were
    independently verified working -- Node is already a sandbox requirement
    via the MCP Inspector CLI's npx dependency, so a Node driver for the Node
    bridge is the natural, proven-working shape.)"""
    dst = share_dir / "mcp-client"
    dst.mkdir(parents=True, exist_ok=True)
    count = 0

    bridge_src = REPO_ROOT / "packaging" / "mcpb" / "server" / "index.js"
    if bridge_src.exists():
        shutil.copy2(bridge_src, dst / "index.js")
        count += 1
    else:
        print(f"WARNING: MCPB bridge not found at {bridge_src} -- mcp-client/ will be incomplete")

    driver_src = SCRIPT_DIR / "mcp-typed-confirm.mjs"
    if driver_src.exists():
        shutil.copy2(driver_src, dst / "mcp-typed-confirm.mjs")
        count += 1

    readme_src = SCRIPT_DIR / "mcp-client-README.md"
    if readme_src.exists():
        shutil.copy2(readme_src, dst / "README.md")
        count += 1

    print(f"Staged mcp-client/ ({count} files — real MCPB stdio bridge + TYPED_CONFIRM driver)")


def generate_wsb(wsb_path: Path, share_dir: Path, memory_mb: int, models_dir: Path | None = None):
    """Generate the .wsb configuration file with proper XML escaping.

    LogonCommand opens an Explorer window at the mapped folder so the user
    can launch installers and docs manually. Claude Code, Git, and any other
    tools are installed by the user from inside the sandbox.
    """
    # Enable per-request tracing for the round (JUSTSEARCH_HEAD_TRACING_LEVEL=detailed
    # → telemetry/traces.ndjson records every endpoint the round exercises, the
    # empirical input to the finalize coverage check — tempdoc 728). setx sets a
    # persistent USER env var inherited by the app the operator launches afterwards.
    # Restore the unofficial SAC-disable workaround that was dropped in the
    # 2026-04-28 bootstrap refactor (INS-001; tempdoc 374:646). A fresh Windows
    # Sandbox boots with Smart App Control enforcing and HARD-blocks the unsigned
    # installer (no "Run anyway") — confirmed still current behaviour (mid-2026).
    # This registry edit + CiTool refresh disables SAC at boot so a validation
    # round can reach the installed app (/mcp, Install AI, search).
    # NOTE: externally reported UNRELIABLE (Microsoft Q&A #5641557 — "didn't seem
    # to have an effect" for several users). If the block persists despite this,
    # it is NOT a JustSearch regression — the real fix is code-signing (374 G4),
    # deferred by owner budget. The block itself remains valid first-run evidence
    # (tempdoc 728): capture it before relying on this bypass.
    sac_disable = (
        r'reg add "HKLM\SYSTEM\CurrentControlSet\Control\CI\Policy" '
        r'/v VerifiedAndReputablePolicyState /t REG_DWORD /d 0 /f >nul 2>&1 & '
        r'CiTool.exe -r >nul 2>&1 & '
    )
    logon_cmd = (
        rf'cmd /c "{sac_disable}'
        rf'setx JUSTSEARCH_HEAD_TRACING_LEVEL detailed >nul & '
        rf'explorer.exe {SANDBOX_FOLDER}"'
    )

    config = ET.Element("Configuration")
    ET.SubElement(config, "vGPU").text = "Default"
    ET.SubElement(config, "Networking").text = "Enable"
    ET.SubElement(config, "MemoryInMB").text = str(memory_mb)

    folders = ET.SubElement(config, "MappedFolders")
    folder = ET.SubElement(folders, "MappedFolder")
    ET.SubElement(folder, "HostFolder").text = str(share_dir)
    ET.SubElement(folder, "SandboxFolder").text = SANDBOX_FOLDER
    ET.SubElement(folder, "ReadOnly").text = "false"

    if models_dir:
        models_folder = ET.SubElement(folders, "MappedFolder")
        ET.SubElement(models_folder, "HostFolder").text = str(models_dir)
        ET.SubElement(models_folder, "SandboxFolder").text = SANDBOX_MODELS_FOLDER
        # Writable: ORT writes .optimized cache files next to models
        ET.SubElement(models_folder, "ReadOnly").text = "false"

    logon = ET.SubElement(config, "LogonCommand")
    ET.SubElement(logon, "Command").text = logon_cmd

    tree = ET.ElementTree(config)
    ET.indent(tree, space="  ")
    tree.write(wsb_path, encoding="unicode", xml_declaration=False)
    with open(wsb_path, "a") as f:
        f.write("\n")

    print(f"WSB file: {wsb_path}")


def main():
    parser = argparse.ArgumentParser(description="Launch Windows Sandbox for JustSearch validation")
    parser.add_argument("--installer", help="Path to NSIS installer exe")
    parser.add_argument("--memory", type=int, default=16384, help="Sandbox RAM in MB (default: 16384)")
    parser.add_argument("--stage-dir", default="tmp/sandbox", help="Staging directory (relative to repo root)")
    parser.add_argument("--no-launch", action="store_true", help="Stage only, don't open sandbox")
    parser.add_argument("--models-dir", help="Host models directory to map into sandbox (shortcut; avoids the full Install AI download)")
    parser.add_argument(
        "--no-models",
        action="store_true",
        help=(
            "Skip mapping the host models directory. Required by Rule 14 of "
            "the sandbox /start skill: at least one validation round per "
            "release-candidate alpha must run as a TRUE FRESH INSTALL so "
            "Install AI does the full ~10 GB download. Without this flag the "
            "host models/ directory is auto-mapped, which is a sandbox "
            "shortcut and masks production-flow regressions (e.g., the alpha.21 "
            "Bug S NER GPU_FULL bug that 11 prior rounds missed)."
        ),
    )
    parser.add_argument(
        "--charter",
        help=(
            "Path to this round's charter markdown file (Session-Based Test "
            "Management, Bach & Bach, STQE 2000 -- charter/debrief/TBS time "
            "accounting, adapted; tempdoc 750 Part B). Staged verbatim into "
            "the share as charter.md with a generated 'Resolved launch mode' "
            "trailer appended. Mutually exclusive with --no-charter; exactly "
            "one is required on every real invocation of this script (this "
            "flag is enforced in main()'s argument parsing, which only runs "
            "when the script is actually executed -- staging-only "
            "(--no-launch) runs still generate charter.md/the notice, since "
            "generate_wsb() always runs too; importing this module for its "
            "functions, as test_sandbox_launch_evidence_archive.py does, "
            "never calls main() and so never trips this requirement)."
        ),
    )
    parser.add_argument(
        "--no-charter",
        action="store_true",
        help=(
            "Explicitly mark this launch as a non-qualifying round: no "
            "charter.md is staged, only a one-line notice is printed. "
            "Mutually exclusive with --charter; exactly one of the two is "
            "required (see --charter's help for the enforcement boundary)."
        ),
    )
    parser.add_argument(
        "--upgrade-from",
        help=(
            "Path to the previous public release's installer exe, for an "
            "upgrade-from-release round (tempdoc 750 Part C): the qualifying "
            "set for a release includes the existing fresh-install "
            "requirements plus at least one upgrade round. Stages the file "
            "into previous-release/ alongside the candidate installer, "
            "resolves the mode to 'upgrade-from-release', and records its "
            "filename + SHA-256 plus the install-then-upgrade instruction "
            "sequence in validation-mode.md. Mutually exclusive with "
            "--models-dir (an upgrade round exercises the real download "
            "path, like fresh-install, never the pre-staged-models "
            "shortcut); combines internally with the no-models resolution. "
            "The path must exist, be a .exe, and differ in filename from "
            "the candidate installer (refuses staging the same file twice)."
        ),
    )
    parser.add_argument(
        "--in-app-updater-assets",
        help=(
            "Directory containing the candidate's authenticated updater closed "
            "set (release.v1.json, release.v1.json.sig, latest.json, and "
            "<installer>.sig). Requires --upgrade-from and "
            "--metadata-public-key. The descriptor/artifact URLs must target "
            "loopback HTTP. The installed --upgrade-from SOURCE build must "
            "contain the updater and be compiled with its Sandbox test gate; "
            "production builds remain HTTPS-only."
        ),
    )
    parser.add_argument(
        "--metadata-public-key",
        help=(
            "Ed25519 SPKI PEM public key used to verify release.v1.json for an "
            "--in-app-updater-assets round. This is public trust material, not "
            "a signing secret."
        ),
    )
    args = parser.parse_args()
    if args.no_models and args.models_dir:
        sys.exit("--no-models and --models-dir are mutually exclusive.")

    if args.upgrade_from and args.models_dir:
        sys.exit(
            "--upgrade-from and --models-dir are mutually exclusive: an "
            "upgrade-from-release round exercises the real download path "
            "(like fresh-install), not the pre-staged-models shortcut."
        )

    if bool(args.in_app_updater_assets) != bool(args.metadata_public_key):
        sys.exit(
            "--in-app-updater-assets and --metadata-public-key must be supplied together."
        )
    if args.in_app_updater_assets and not args.upgrade_from:
        sys.exit(
            "--in-app-updater-assets requires --upgrade-from: the in-app lane "
            "must start from an installed updater-capable previous-source "
            "Sandbox build."
        )

    if args.charter and args.no_charter:
        sys.exit("--charter and --no-charter are mutually exclusive.")
    if not args.charter and not args.no_charter:
        sys.exit(
            "Exactly one of --charter <path-to-md> or --no-charter is required. "
            "Session-Based Test Management (Bach & Bach, STQE 2000) treats the "
            "charter as the round's pre-registered contract: a qualifying round "
            "pre-registers its purpose and each blocker's needs-round/needs-dig "
            "classification BEFORE the round starts (tempdoc 750 Part B). Pass "
            "--charter <path-to-md> for a qualifying round, or --no-charter to "
            "explicitly mark this launch non-qualifying."
        )

    if not (REPO_ROOT / "gradlew.bat").exists():
        sys.exit(f"Cannot find repo root from {SCRIPT_DIR}")

    # 1. Resolve installer
    installer = find_installer(args.installer)
    print(f"Installer: {installer}")

    upgrade_installer = None
    if args.upgrade_from:
        upgrade_installer = resolve_upgrade_from(args.upgrade_from, installer)
        print(f"Upgrade-from (previous release) installer: {upgrade_installer}")

    # 2. Stage shared folder
    stage_dir = Path(args.stage_dir)
    if not stage_dir.is_absolute():
        stage_dir = REPO_ROOT / stage_dir
    stage_dir.mkdir(parents=True, exist_ok=True)

    # Archive any previous round's evidence before staging can clobber it.
    # Must run before resolve_share_dir()'s clean_dir() -- never destructive:
    # fails the whole staging run rather than risk overwriting unarchived
    # evidence (see archive_existing_evidence()'s docstring).
    archive_existing_evidence(stage_dir)

    share_dir = resolve_share_dir(stage_dir)
    share_dir.mkdir(parents=True, exist_ok=True)

    shutil.copy2(installer, share_dir / installer.name)
    print(f"Staged installer: {installer.name}")

    # Copy environment doc
    shutil.copy2(SCRIPT_DIR / "sandbox-environment.md", share_dir / "sandbox-environment.md")

    # Copy docs
    stage_docs(share_dir)

    # Collect staging gaps as we go — surfaced loudly (WARNING block) and
    # written to staging-gaps.md so a gap fails loud instead of silently
    # dropping round coverage (e.g. the round that lost all search-quality
    # verification because the SciFact corpus was silently missing).
    gaps: list[str] = []

    # Stage SciFact corpus for ingest-and-quality validation
    scifact_gap = stage_scifact(share_dir)
    if scifact_gap:
        gaps.append(scifact_gap)

    # Stage the golden-parity search-quality harness (query set + per-candidate baseline)
    golden_parity_gap = stage_golden_parity(share_dir)
    if golden_parity_gap:
        gaps.append(golden_parity_gap)

    # Copy sandbox-specific CLAUDE.md
    sandbox_claude_md = SCRIPT_DIR / "sandbox-CLAUDE.md"
    if sandbox_claude_md.exists():
        shutil.copy2(sandbox_claude_md, share_dir / "CLAUDE.md")
        print("Staged CLAUDE.md (sandbox-specific)")
    else:
        claude_md = REPO_ROOT / "CLAUDE.md"
        if claude_md.exists():
            shutil.copy2(claude_md, share_dir / "CLAUDE.md")
            print("Staged CLAUDE.md (repo fallback)")

    # Copy and sanitize .claude/ settings
    stage_claude_settings(share_dir)

    # tools/ directory — auto-staged from a host-side cache so the user
    # doesn't have to download Git for Windows on every fresh launch.
    # Drop additional installers (Chrome, etc.) into ../tools-cache/ on the
    # host and they'll be copied here too. Cache lives outside share/ so
    # the staging clean_dir doesn't wipe it.
    tools_dst = share_dir / "tools"
    tools_dst.mkdir(parents=True, exist_ok=True)
    tools_cache = stage_dir / "tools-cache"
    if tools_cache.is_dir():
        staged = 0
        for f in tools_cache.iterdir():
            if f.is_file():
                shutil.copy2(f, tools_dst / f.name)
                staged += 1
        if staged:
            print(f"Staged tools/ from tools-cache ({staged} files)")

    node_gap = check_node_installer_staged(tools_cache)
    if node_gap:
        gaps.append(node_gap)

    # 3. Resolve models dir. Never map a weightless directory: a worktree's
    # own models/ holds only tracked config/tokenizer JSON (~42 MB) — the
    # real ~11.5 GB of *.onnx/*.gguf weights are untracked and live only in
    # the MAIN checkout. Mapping a weightless dir used to silently claim
    # pre-staged-models while the app found no models and the round died
    # with NO_EMBEDDING_MODEL. Resolved before write_staging_gaps below so a
    # refused/weightless dir surfaces through the same gap plumbing as every
    # other staging gap, rather than a second ad hoc mechanism.
    models_dir = None
    if args.no_models:
        print("Models directory: SKIPPED (--no-models, Rule 14 -- true fresh install)")
        print("  Install AI in the sandbox will do the full ~10 GB clean download.")
    elif upgrade_installer:
        # An upgrade-from-release round always exercises the real download
        # path (like fresh-install), never the pre-staged-models shortcut --
        # combined internally with the no-models resolution rather than
        # requiring the operator to also pass --no-models explicitly.
        print("Models directory: SKIPPED (--upgrade-from, exercising the real download path like fresh-install)")
        print("  Install AI in the sandbox will do the full ~10 GB clean download.")
    else:
        models_dir, models_gap = resolve_models_dir(args.models_dir)
        if models_gap:
            gaps.append(models_gap)
        if models_dir:
            model_size = sum(f.stat().st_size for f in models_dir.rglob("*") if f.is_file()) // (1024 * 1024)
            print(f"Models directory: {models_dir} ({model_size} MB)")
            print(f"  Mapped into sandbox at: {SANDBOX_MODELS_FOLDER}")
        else:
            print("No models directory with real weights found — models will need to be downloaded in sandbox")

    # Write the collected staging gaps now that staging (short of the
    # fail-closed coverage-brief step below) is complete.
    write_staging_gaps(share_dir, gaps)

    # Report share size
    total_bytes = sum(f.stat().st_size for f in share_dir.rglob("*") if f.is_file())
    print(f"\nShare directory: {share_dir}")
    print(f"Share size: {total_bytes // (1024 * 1024)} MB")

    # Stage the previous release's installer for an upgrade-from-release
    # round (tempdoc 750 Part C), before write_validation_mode() so its
    # filename + sha256 are available to record in validation-mode.md.
    upgrade_info = None
    if upgrade_installer:
        upgrade_info = stage_upgrade_installer(share_dir, upgrade_installer)
    updater_info = None
    if args.in_app_updater_assets:
        release_dir = Path(args.in_app_updater_assets)
        if not release_dir.is_absolute():
            release_dir = REPO_ROOT / release_dir
        metadata_public_key = Path(args.metadata_public_key)
        if not metadata_public_key.is_absolute():
            metadata_public_key = REPO_ROOT / metadata_public_key
        updater_info = stage_in_app_updater_assets(
            share_dir,
            installer,
            release_dir,
            metadata_public_key,
        )

    # 4. Stamp actual validation mode, generate the per-candidate coverage brief
    #    (fail-closed on an unclassified shipped surface), stage the capture
    #    harness, then generate .wsb (tempdoc 728).
    write_validation_mode(
        share_dir,
        installer,
        models_dir,
        args.no_models,
        upgrade_info,
        updater_info,
    )
    stage_charter(share_dir, args.charter, args.no_charter)
    stage_coverage_brief(share_dir)
    stage_round_plan(share_dir)
    stage_evidence_harness(share_dir)
    stage_gui_harness(share_dir)
    stage_mcp_client_harness(share_dir)
    wsb_path = stage_dir / "JustSearch-Validation.wsb"
    generate_wsb(wsb_path, share_dir, args.memory, models_dir)
    print(f"Sandbox RAM: {args.memory} MB")
    print()

    # 5. Launch
    if args.no_launch:
        print(f"Staged only (--no-launch). To launch:")
        print(f"  start {wsb_path}")
    else:
        print("Launching Windows Sandbox...")
        print()
        print("Inside the sandbox:")
        print(f"  Mapped folder: {SANDBOX_FOLDER}")
        print(f"  1. Install Git from tools/ (or download)")
        print(f"  2. Install Claude Code:  irm https://claude.ai/install.ps1 | iex")
        print(f"  3. Install JustSearch:   run the *-setup.exe from the mapped folder")
        print(f"  4. Run claude from the mapped folder for docs-aware help")
        print()
        subprocess.Popen(["cmd", "/c", "start", "", str(wsb_path)])


if __name__ == "__main__":
    main()
