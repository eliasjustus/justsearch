"""jseval CLI entry point — the ``main`` Click group + global options.

All commands now live in :mod:`jseval.commands` (split from the former
3.5k-line monolith — tempdoc 645). ``main`` stays importable as
``jseval.cli.main`` to preserve the ``jseval`` console-script and
``python -m jseval`` entry points.
"""
from __future__ import annotations

import logging

import click

from .commands import JsevalGroup

@click.group(cls=JsevalGroup)
@click.option("--verbose", "-v", is_flag=True, help="Enable debug logging.")
@click.option("--json", "json_mode", is_flag=True, help="Emit JSON to stdout.")
@click.pass_context
def main(ctx, verbose: bool, json_mode: bool) -> None:
    """JustSearch search evaluation toolkit."""
    ctx.ensure_object(dict)
    ctx.obj["json"] = json_mode
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    # Suppress httpcore/httpx request-level logging — it floods the output
    # with ~20 lines per HTTP call, drowning progress logging (14:1 noise ratio).
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


from .commands import register_all  # noqa: E402

register_all(main)
