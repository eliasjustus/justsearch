"""jseval ops commands (split from cli.py — tempdoc 645)."""
from __future__ import annotations

import json
import sys
from pathlib import Path
import httpx
import logging

import click
from ._common import _DEFAULT_BASE_URL

log = logging.getLogger(__name__)


@click.command("materialize")
@click.option("--dataset", required=True)
@click.option("--output-dir", required=True, type=click.Path())
def cmd_materialize(dataset, output_dir):
    """Materialize a BEIR corpus to .txt files."""
    import ir_datasets
    from .. import corpora, materialize

    if dataset not in corpora.BEIR_DATASETS:
        click.echo(f"Error: unknown BEIR dataset: {dataset}", err=True)
        sys.exit(1)

    ds = ir_datasets.load(corpora.BEIR_DATASETS[dataset])
    count = materialize.materialize(ds.docs_iter(), Path(output_dir))
    click.echo(f"Materialized {count} documents to {output_dir}")


@click.command("preflight")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.pass_context
def cmd_preflight(ctx, base_url):
    """Check backend health, models, GPU status before running eval."""
    from .. import preflight

    result = preflight.execute_preflight(base_url)
    if ctx.obj.get("json"):
        click.echo(json.dumps(result, indent=2, default=str))
    else:
        click.echo(preflight.format_console(result))
    if result["status"] == "unreachable":
        sys.exit(1)


@click.command("log-path")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
def cmd_log_path(base_url):
    """Discover and print the worker.log path from the running backend."""
    from .. import preflight

    status = preflight._fetch_endpoint(base_url, "/api/status", 10)
    if status is None:
        click.echo("Error: backend unreachable", err=True)
        sys.exit(1)

    index_base = status.get("indexBasePath", "")
    if not index_base:
        click.echo("Error: indexBasePath not available in status", err=True)
        sys.exit(1)

    # indexBasePath is like <data_dir>/index/default — go up 2 levels
    data_dir = Path(index_base).parent.parent
    log_path = data_dir / "logs" / "worker.log"
    click.echo(str(log_path))


@click.command("dev")
@click.option("--clean", is_flag=True, help="Clean data directory before starting.")
@click.option("--llm", is_flag=True, help="Enable LLM (Brain/llama-server) autostart in the backend.")
@click.option("--port", default=33221, show_default=True)
@click.pass_context
def cmd_dev(ctx, clean, llm, port):
    """Start the eval backend and keep it running until Ctrl-C.

    If a backend is already healthy on the target port, attaches to it
    (skips start, skips stop on exit). Otherwise starts a fresh backend.

    With --llm, passes -Pllm=true to Gradle and waits for inference
    readiness in addition to index health.
    """
    from .. import backend as backend_mod
    from .. import preflight

    base_url = f"http://127.0.0.1:{port}"
    attached = False

    # Check if backend is already running
    status = preflight._fetch_endpoint(base_url, "/api/status", 5)
    if status and status.get("indexAvailable"):
        click.echo(f"Backend already running on port {port} — attaching (won't stop on exit)")
        attached = True
    else:
        click.echo(f"Starting backend on port {port} (clean={clean}, llm={llm})...")
        backend_info = backend_mod.start_backend(clean=clean, port=port, llm=llm)
        click.echo(f"Backend ready (PID={backend_info.proc.pid})")

    click.echo("Press Ctrl-C to stop.")
    try:
        import threading
        threading.Event().wait()
    except KeyboardInterrupt:
        pass
    finally:
        if not attached:
            click.echo("Stopping backend...")
            backend_mod.stop_backend(backend_info.proc)
            click.echo("Stopped.")
        else:
            click.echo("Detached (backend still running).")


@click.command("search")
@click.option("--query", "-q", required=True, help="Search query text.")
@click.option("--mode", "-m", default="hybrid", show_default=True,
              help="Search mode (lexical, hybrid, vector, splade, etc.).")
@click.option("--limit", "-n", default=10, show_default=True)
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
@click.option("--ce/--no-ce", default=None,
              help="Force cross-encoder on/off (overrides mode default).")
@click.pass_context
def cmd_search(ctx, query, mode, limit, base_url, ce):
    """Send a single search query and display the full pipeline response."""
    from .. import retriever

    pipeline = None
    if mode in retriever.MODE_PIPELINES:
        pipeline = dict(retriever.MODE_PIPELINES[mode])
        if ce is not None:
            pipeline["crossEncoderEnabled"] = ce

    body: dict = {"query": query, "limit": limit}
    if pipeline:
        body["pipeline"] = pipeline
    else:
        body["mode"] = mode

    try:
        resp = httpx.post(f"{base_url}/api/knowledge/search", json=body, timeout=60)
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        click.echo(f"Search failed: {e}", err=True)
        sys.exit(1)

    if ctx.obj.get("json"):
        click.echo(json.dumps(data, indent=2))
        return

    # Human-readable output
    click.echo(f"Query: {query}")
    click.echo(f"Mode: {data.get('effectiveMode', mode)}  Hits: {data.get('totalHits', '?')}")
    click.echo()

    # Pipeline components
    pe = data.get("pipelineExecution", {})
    components = pe.get("components", {})
    if components:
        click.echo("Pipeline:")
        click.echo(f"  retrieval: {pe.get('retrievalMs', '?')}ms")
        for name, comp in sorted(components.items()):
            status = comp.get("status", "?")
            reason = comp.get("reason", "")
            timing_key = f"{name.replace('_', '')}Ms"
            # Try to find timing in pipelineExecution
            ms = pe.get(f"{name}Ms") or pe.get(timing_key)
            ms_str = f" ({ms}ms)" if ms else ""
            reason_str = f" [{reason}]" if reason else ""
            click.echo(f"  {name}: {status}{ms_str}{reason_str}")
        # Explicit CE/LM timing
        ce_ms = pe.get("crossEncoderMs")
        lm_ms = pe.get("lambdaMartMs")
        if ce_ms:
            click.echo(f"  cross_encoder_ms: {ce_ms}")
        if lm_ms:
            click.echo(f"  lambdamart_ms: {lm_ms}")
    click.echo()

    # Results
    results = data.get("results", [])
    click.echo(f"Results ({len(results)}):")
    for i, hit in enumerate(results[:limit], 1):
        fields = hit.get("fields", {})
        title = fields.get("title", "")
        filename = fields.get("filename", "")
        label = title or filename or hit.get("id", "?")
        click.echo(f"  {i:>2}. [{hit.get('score', 0):.3f}] {label[:70]}")
    click.echo()

    # Skip reasons
    skip = data.get("crossEncoderSkipReason")
    if skip:
        click.echo(f"CE skip: {skip}")
    cap = data.get("indexCapabilities", {})
    if cap:
        click.echo(f"Capabilities: embed={cap.get('embeddingCoverage', 0):.0%} "
                    f"splade={cap.get('spladeCoverage', 0):.0%} "
                    f"ce_avail={cap.get('crossEncoderAvailable')}")


@click.command("logs")
@click.option("--source", type=click.Choice(["worker", "head", "all"]),
              default="worker", show_default=True)
@click.option("--filter", "-f", "pattern", default=None, help="Filter by message content.")
@click.option("--level", "-l", "min_level", default=None,
              help="Minimum log level (DEBUG, INFO, WARN, ERROR).")
@click.option("--tail", "-t", "tail_mode", is_flag=True, help="Follow log file for new entries.")
@click.option("--lines", "-n", "max_lines", default=50, show_default=True,
              help="Number of recent lines to show (0 = all).")
@click.option("--base-url", default=_DEFAULT_BASE_URL, show_default=True)
def cmd_logs(source, pattern, min_level, tail_mode, max_lines, base_url):
    """Read and filter structured JSON logs from the running backend."""
    from .. import preflight

    # Discover data dir
    status = preflight._fetch_endpoint(base_url, "/api/status", 5)
    if status is None:
        click.echo("Error: backend unreachable — cannot discover log path", err=True)
        sys.exit(1)

    index_base = status.get("indexBasePath", "")
    if not index_base:
        click.echo("Error: indexBasePath not available", err=True)
        sys.exit(1)

    data_dir = Path(index_base).parent.parent
    log_files = []
    if source in ("worker", "all"):
        log_files.append(("worker", data_dir / "logs" / "worker.log"))
    if source in ("head", "all"):
        log_files.append(("head", data_dir / "logs" / "app.log"))

    level_order = {"TRACE": 0, "DEBUG": 10, "INFO": 20, "WARN": 30, "ERROR": 40}
    min_level_val = level_order.get((min_level or "").upper(), 0)

    def _parse_and_filter(line: str, src: str) -> str | None:
        try:
            entry = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            return None

        level = entry.get("level", "INFO")
        if level_order.get(level, 20) < min_level_val:
            return None

        msg = entry.get("message", "")
        if pattern and pattern.lower() not in msg.lower():
            return None

        ts = entry.get("@timestamp", "")[-15:-6]  # HH:MM:SS
        logger = entry.get("logger_name", "")
        # Shorten logger: keep last segment
        short_logger = logger.rsplit(".", 1)[-1] if logger else ""
        prefix = f"[{src}] " if source == "all" else ""
        return f"{prefix}{ts} {level:<5} {short_logger}: {msg[:200]}"

    # Read existing lines
    for src, log_path in log_files:
        if not log_path.is_file():
            click.echo(f"Log file not found: {log_path}", err=True)
            continue

        lines = []
        with open(log_path, "r", encoding="utf-8", errors="replace") as f:
            for raw_line in f:
                formatted = _parse_and_filter(raw_line.strip(), src)
                if formatted:
                    lines.append(formatted)

        # Show last N lines
        if max_lines > 0:
            lines = lines[-max_lines:]
        for line in lines:
            click.echo(line)

    # Tail mode
    if tail_mode:
        import time as _time

        # Seek to end of each file
        handles = []
        for src, log_path in log_files:
            if log_path.is_file():
                fh = open(log_path, "r", encoding="utf-8", errors="replace")
                fh.seek(0, 2)  # EOF
                handles.append((src, fh))

        click.echo("--- tailing (Ctrl-C to stop) ---")
        try:
            while True:
                had_output = False
                for src, fh in handles:
                    for raw_line in fh:
                        formatted = _parse_and_filter(raw_line.strip(), src)
                        if formatted:
                            click.echo(formatted)
                            had_output = True
                if not had_output:
                    _time.sleep(0.5)
        except KeyboardInterrupt:
            pass
        finally:
            for _, fh in handles:
                fh.close()


@click.command("datasets")
@click.pass_context
def cmd_datasets(ctx):
    """List available datasets (BEIR, golden, mixed)."""
    from .. import corpora

    datasets: list[dict] = []

    # BEIR datasets
    for name in sorted(corpora.BEIR_DATASETS):
        datasets.append({"name": name, "source": "beir", "ir_datasets_id": corpora.BEIR_DATASETS[name]})

    # Local datasets (mixed/, golden/)
    base = corpora._default_base_dir()
    for prefix in ("mixed", "golden"):
        prefix_dir = base / prefix
        if prefix_dir.is_dir():
            for sub in sorted(prefix_dir.iterdir()):
                if sub.is_dir():
                    datasets.append({"name": f"{prefix}/{sub.name}", "source": prefix})

    if ctx.obj.get("json"):
        click.echo(json.dumps(datasets, indent=2))
    else:
        click.echo("Available datasets:")
        for d in datasets:
            click.echo(f"  {d['name']:<30s}  ({d['source']})")


@click.command("modes")
@click.pass_context
def cmd_modes(ctx):
    """List available search modes and their pipeline components."""
    from .. import retriever

    modes: dict[str, dict] = {}

    # Client-side modes (explicit pipeline dicts)
    for name, pipeline in sorted(retriever.MODE_PIPELINES.items()):
        components = [k.replace("Enabled", "") for k, v in pipeline.items()
                      if k.endswith("Enabled") and v]
        modes[name] = {"resolution": "client", "components": components}

    # Server-passthrough modes
    modes["hybrid"] = {
        "resolution": "server",
        "components": ["sparse", "dense", "rrf", "lambdamart"],
        "note": "Resolved server-side via SearchMode.HYBRID",
    }

    if ctx.obj.get("json"):
        click.echo(json.dumps(modes, indent=2))
    else:
        click.echo("Available modes:")
        for name, info in sorted(modes.items()):
            comps = ", ".join(info["components"]) if info["components"] else "(default)"
            res = info["resolution"]
            click.echo(f"  {name:<15s}  [{res}]  {comps}")
            if info.get("note"):
                click.echo(f"  {'':15s}  {info['note']}")


@click.command("commands")
@click.option("--group", "group_filter", default=None,
              help="Only show commands in this group (e.g. gates, corpus, ui).")
@click.pass_context
def cmd_commands(ctx, group_filter):
    """List every jseval command + its group; ``--json`` adds full options.

    A machine-readable projection of the command registry — agents/devs discover
    the whole surface without reading the source (tempdoc 645 A1). The group map
    is projected from ``jseval.commands.command_groups``, never re-derived.
    """
    from itertools import groupby

    from ..cli import main
    from . import GROUP_ORDER, command_groups

    groups = command_groups()
    root = main.to_info_dict(click.Context(main))
    entries: list[dict] = []
    for name, cinfo in root.get("commands", {}).items():
        grp = groups.get(name, "other")
        if group_filter and grp != group_filter:
            continue
        options = []
        for p in cinfo.get("params", []):
            if p.get("param_type_name") != "option" or p.get("hidden"):
                continue
            options.append({
                "opts": p.get("opts"),
                "type": (p.get("type") or {}).get("name"),
                "required": p.get("required", False),
                "is_flag": p.get("is_flag", False),
                "default": p.get("default"),
                "help": p.get("help"),
            })
        help_text = (cinfo.get("help") or "").strip().split("\n", 1)[0]
        entries.append({
            "name": name,
            "group": grp,
            "summary": cinfo.get("short_help") or help_text,
            "options": options,
        })
    entries.sort(key=lambda e: (GROUP_ORDER.index(e["group"]) if e["group"] in GROUP_ORDER else 99, e["name"]))

    if ctx.obj.get("json"):
        click.echo(json.dumps(entries, indent=2, default=str))
    else:
        if not entries:
            click.echo(f"No commands in group {group_filter!r}.")
            return
        for grp, rows in groupby(entries, key=lambda e: e["group"]):
            click.echo(f"\n{grp}:")
            for e in rows:
                click.echo(f"  {e['name']:<28s}  {e['summary'] or ''}")


COMMANDS = [cmd_materialize, cmd_preflight, cmd_log_path, cmd_dev, cmd_search, cmd_logs, cmd_datasets, cmd_modes,
            cmd_commands]
