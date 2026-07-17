"""jseval index-cache / index-identity CLI (tempdoc 751 WP3).

``index-cache`` inspects and manages the input-addressed eval-index store
(``list`` / ``inspect`` / ``prune``); ``index-identity`` is the v0 primitive that
prints the running backend's authoritative live identity. Both are thin CLIs over
:mod:`jseval.index_cache` / :mod:`jseval.index_identity` — no store or identity
logic is reimplemented here.
"""
from __future__ import annotations

import json
import os

import click

from .. import index_cache, index_identity


def _fmt_ts(ts) -> str:
    """Compact UTC timestamp for a stored epoch-seconds value, or ``-``."""
    if not ts:
        return "-"
    import datetime

    try:
        return datetime.datetime.fromtimestamp(
            float(ts), tz=datetime.timezone.utc,
        ).strftime("%Y-%m-%dT%H:%M:%SZ")
    except (TypeError, ValueError, OverflowError):
        return "-"


def _fmt_size(num_bytes: int) -> str:
    size = float(num_bytes)
    for unit in ("B", "KB", "MB", "GB"):
        if size < 1024 or unit == "GB":
            return f"{size:.0f}{unit}" if unit == "B" else f"{size:.1f}{unit}"
        size /= 1024
    return f"{size:.1f}GB"


def _has_corpus_axis(doc: dict) -> bool:
    components = (doc.get("identity") or {}).get("components") or {}
    return bool(components.get("corpus_signature"))


@click.group("index-cache")
def index_cache_group():
    """Inspect and manage the input-addressed eval-index cache (tempdoc 751)."""


@index_cache_group.command("list")
def cmd_index_cache_list():
    """List cache entries: key prefix, corpus?, built_at, last_adopted, size."""
    entries = index_cache.list_entries()
    if not entries:
        click.echo("(no index-cache entries)")
        return
    for entry in entries:
        doc = entry.doc
        key = (doc.get("selector_key") or "")[:16]
        corpus = "yes" if _has_corpus_axis(doc) else "no"
        built = _fmt_ts(doc.get("published_at"))
        adopted = _fmt_ts(doc.get("last_adopted_at"))
        size = _fmt_size(index_cache._dir_size(entry.dir))
        click.echo(
            f"{key}  corpus={corpus}  built={built}  last_adopted={adopted}  size={size}"
        )


@index_cache_group.command("inspect")
@click.argument("key_prefix")
def cmd_index_cache_inspect(key_prefix):
    """Pretty-print the entry.json for the entry whose key starts with KEY_PREFIX."""
    matches = [
        entry for entry in index_cache.list_entries()
        if (entry.doc.get("selector_key") or "").startswith(key_prefix)
    ]
    if not matches:
        click.echo(f"No index-cache entry matches key prefix {key_prefix!r}.", err=True)
        raise SystemExit(1)
    if len(matches) > 1:
        keys = ", ".join((e.doc.get("selector_key") or "")[:16] for e in matches)
        click.echo(
            f"Ambiguous key prefix {key_prefix!r} matches {len(matches)} entries: {keys}.",
            err=True,
        )
        raise SystemExit(1)
    click.echo(json.dumps(matches[0].doc, indent=2, default=str))


@index_cache_group.command("prune")
@click.option("--max-entries", type=int, default=8, show_default=True,
              help="Keep at most this many entries (LRU-by-last-adoption).")
@click.option("--max-bytes", type=int, default=None,
              help="Additionally bring the total store size at-or-under this many bytes.")
def cmd_index_cache_prune(max_entries, max_bytes):
    """Evict old entries down to the entry-count / byte budgets."""
    removed = index_cache.prune(max_entries=max_entries, max_bytes=max_bytes)
    if not removed:
        click.echo("Nothing pruned.")
        return
    for path in removed:
        click.echo(f"pruned {path.name}")
    click.echo(f"pruned {len(removed)} entr{'y' if len(removed) == 1 else 'ies'}.")


@click.command("index-identity")
@click.option("--base-url", default="http://127.0.0.1:33221", show_default=True,
              help="Backend base URL to read the live identity from.")
def cmd_index_identity(base_url):
    """Print the running backend's authoritative live index identity as JSON (751 v0)."""
    try:
        identity = index_identity.compute_live_identity(base_url, dict(os.environ))
    except index_identity.IdentityUnavailable as exc:
        click.echo(f"Index identity unavailable: {exc.reason}", err=True)
        raise SystemExit(1)
    click.echo(json.dumps(identity.to_doc(), indent=2, default=str))


COMMANDS = [index_cache_group, cmd_index_identity]
