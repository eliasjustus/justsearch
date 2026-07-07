"""Committed command inventory — a generated projection of the registry (tempdoc 645 B5).

``inventory.generated.json`` is the deliberate, reviewable lock on the public
command surface: it is *projected* from the registry's ``command_groups`` (never
hand-typed), and the surface-lock test asserts the live registry matches it.
Adding/removing a command makes the test fail until the file is regenerated —
turning a silent surface change into an explicit committed diff.

Regenerate / check::

    python -m jseval.commands.inventory --write   # rewrite the committed file
    python -m jseval.commands.inventory --check    # exit 1 if it would change
"""
from __future__ import annotations

import json
from pathlib import Path

INVENTORY_PATH = Path(__file__).with_name("inventory.generated.json")


def current() -> list[dict]:
    """The live inventory (sorted ``[{name, group}]``) projected from the registry."""
    import jseval.cli  # noqa: F401  — importing cli runs register_all(main), populating the registry

    from . import command_groups

    return sorted(
        ({"name": name, "group": group} for name, group in command_groups().items()),
        key=lambda e: e["name"],
    )


def render() -> str:
    """The canonical on-disk text for the committed inventory."""
    return json.dumps(current(), indent=2) + "\n"


def load() -> list[dict]:
    """The committed inventory."""
    return json.loads(INVENTORY_PATH.read_text(encoding="utf-8"))


def _main(argv: list[str]) -> int:
    if "--check" in argv:
        if not INVENTORY_PATH.exists() or render() != INVENTORY_PATH.read_text(encoding="utf-8"):
            print("inventory.generated.json is stale — run: python -m jseval.commands.inventory --write")
            return 1
        print(f"inventory OK ({len(load())} commands)")
        return 0
    INVENTORY_PATH.write_text(render(), encoding="utf-8")
    print(f"wrote {INVENTORY_PATH} ({len(current())} commands)")
    return 0


if __name__ == "__main__":
    import sys

    raise SystemExit(_main(sys.argv[1:]))
