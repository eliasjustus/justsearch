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
import json
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

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
            # Sandbox is ephemeral and isolated — start Claude in bypass mode
            # so the user is not prompted for every tool call.
            settings.setdefault("permissions", {})["defaultMode"] = "bypassPermissions"
            (claude_dst / "settings.json").write_text(
                json.dumps(settings, indent=2), encoding="utf-8"
            )
            print("Staged .claude/settings.json (sanitized + bypassPermissions)")
        except Exception as e:
            shutil.copy2(settings_src, claude_dst / "settings.json")
            print(f"Staged .claude/settings.json (raw copy, sanitization failed: {e})")


def write_validation_mode(share_dir: Path, installer: Path, models_dir: Path | None, no_models: bool):
    """Write the actual launch mode into the mapped folder.

    The static sandbox docs describe both fresh and pre-staged model modes.
    This generated file is the authority for the current sandbox instance.
    """
    if no_models:
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
        "",
        "The final validation summary must state this mode explicitly.",
        "",
    ]
    (share_dir / "validation-mode.md").write_text("\n".join(text), encoding="utf-8")
    print(f"Staged validation-mode.md ({mode})")


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


def stage_evidence_harness(share_dir: Path):
    """Copy the in-sandbox capture harness (tempdoc 728, cross-cutting)."""
    harness = SCRIPT_DIR / "collect-evidence.ps1"
    if harness.exists():
        shutil.copy2(harness, share_dir / "collect-evidence.ps1")
        print("Staged collect-evidence.ps1")


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
    args = parser.parse_args()
    if args.no_models and args.models_dir:
        sys.exit("--no-models and --models-dir are mutually exclusive.")

    if not (REPO_ROOT / "gradlew.bat").exists():
        sys.exit(f"Cannot find repo root from {SCRIPT_DIR}")

    # 1. Resolve installer
    installer = find_installer(args.installer)
    print(f"Installer: {installer}")

    # 2. Stage shared folder
    stage_dir = Path(args.stage_dir)
    if not stage_dir.is_absolute():
        stage_dir = REPO_ROOT / stage_dir
    stage_dir.mkdir(parents=True, exist_ok=True)
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

    # Write the collected staging gaps now that staging (short of the
    # fail-closed coverage-brief step below) is complete.
    write_staging_gaps(share_dir, gaps)

    # Report share size
    total_bytes = sum(f.stat().st_size for f in share_dir.rglob("*") if f.is_file())
    print(f"\nShare directory: {share_dir}")
    print(f"Share size: {total_bytes // (1024 * 1024)} MB")

    # 3. Resolve models dir
    models_dir = None
    if args.no_models:
        print("Models directory: SKIPPED (--no-models, Rule 14 — true fresh install)")
        print("  Install AI in the sandbox will do the full ~10 GB clean download.")
    elif args.models_dir:
        models_dir = Path(args.models_dir)
        if not models_dir.is_absolute():
            models_dir = REPO_ROOT / models_dir
    else:
        # Default: use repo models/ if it has ONNX models
        default_models = REPO_ROOT / "models"
        if (default_models / "onnx").is_dir():
            models_dir = default_models

    if models_dir and models_dir.is_dir():
        model_size = sum(f.stat().st_size for f in models_dir.rglob("*") if f.is_file()) // (1024 * 1024)
        print(f"Models directory: {models_dir} ({model_size} MB)")
        print(f"  Mapped into sandbox at: {SANDBOX_MODELS_FOLDER}")
    elif not args.no_models:
        models_dir = None
        print("No models directory — models will need to be downloaded in sandbox")

    # 4. Stamp actual validation mode, generate the per-candidate coverage brief
    #    (fail-closed on an unclassified shipped surface), stage the capture
    #    harness, then generate .wsb (tempdoc 728).
    write_validation_mode(share_dir, installer, models_dir, args.no_models)
    stage_coverage_brief(share_dir)
    stage_evidence_harness(share_dir)
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
