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


def stage_scifact(share_dir: Path):
    """Copy the SciFact eval corpus into the sandbox so the validation agent
    can ingest it via POST /api/knowledge/ingest and run search-quality
    assertions. ~5,184 .txt docs, ~11 MB."""
    src = REPO_ROOT / "scripts" / "jseval" / "tmp" / "eval-corpora" / "scifact"
    if not src.is_dir():
        print(f"SciFact corpus not found at {src} — skipping (run jseval to materialize)")
        return
    dst = share_dir / "scifact"
    shutil.copytree(src, dst, dirs_exist_ok=True)
    file_count = sum(1 for _ in dst.iterdir() if _.is_file())
    print(f"Staged SciFact corpus ({file_count} files)")


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


def generate_wsb(wsb_path: Path, share_dir: Path, memory_mb: int, models_dir: Path | None = None):
    """Generate the .wsb configuration file with proper XML escaping.

    LogonCommand opens an Explorer window at the mapped folder so the user
    can launch installers and docs manually. Claude Code, Git, and any other
    tools are installed by the user from inside the sandbox.
    """
    logon_cmd = rf"explorer.exe {SANDBOX_FOLDER}"

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

    # Stage SciFact corpus for ingest-and-quality validation
    stage_scifact(share_dir)

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

    # 4. Stamp actual validation mode, then generate .wsb
    write_validation_mode(share_dir, installer, models_dir, args.no_models)
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
