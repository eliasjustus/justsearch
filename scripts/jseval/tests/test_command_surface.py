"""Surface-lock guard for the jseval CLI command set (tempdoc 645).

The public command surface is assembled by ``register_all`` from many modules
and **locked** against the committed, generated ``inventory.generated.json``
(itself a projection of the registry — tempdoc 645 B5, projection-not-fork).
A dropped/renamed/added command, or a broken command module, fails the build
**loudly** until the inventory is deliberately regenerated
(``python -m jseval.commands.inventory --write``).
"""

from __future__ import annotations

import importlib

import click
import pytest

from jseval.cli import main
from jseval.commands import (
    _GROUP_MODULES,
    _LEGACY_MODULES,
    command_groups,
    inventory,
    register,
    register_all,
    registry,
    reset_registry_for_tests,
)

# Derived from the committed inventory — NOT hand-typed (that was the fork B5 removes).
EXPECTED_NAMES = {e["name"] for e in inventory.load()}


def test_inventory_is_in_sync_with_registry():
    """The committed inventory equals the live registry projection — the drift lock.

    If this fails, a command was added/removed without regenerating the inventory:
    run ``python -m jseval.commands.inventory --write`` and commit the diff.
    """
    assert inventory.current() == inventory.load()


def test_command_set_matches_committed_inventory():
    """The Click surface equals the committed inventory — no silent add/drop."""
    assert set(main.commands) == EXPECTED_NAMES


def test_registry_matches_committed_inventory():
    """The name-keyed registry mirrors the Click surface and the inventory."""
    assert set(registry()) == EXPECTED_NAMES
    assert set(registry()) == set(main.commands)


def test_every_command_has_a_group():
    """Every command carries a group label (the substrate the projections read)."""
    groups = command_groups()
    assert set(groups) == EXPECTED_NAMES
    assert all(groups.values()), "a command has an empty group label"


def test_register_rejects_duplicate_name():
    """A duplicate command name fails loudly (not Click's silent overwrite)."""
    reset_registry_for_tests()

    @click.command("dup-probe")
    def _a():  # pragma: no cover - body never runs
        pass

    @click.command("dup-probe")
    def _b():  # pragma: no cover - body never runs
        pass

    register(_a, "test")
    with pytest.raises(ValueError, match="duplicate jseval command name"):
        register(_b, "test")
    # Restore the real registry so this test leaves no global state behind.
    register_all(main)
    assert set(registry()) == EXPECTED_NAMES


def test_every_command_is_a_well_formed_click_command():
    """Each registered entry is a real click.Command (force-materialized)."""
    for name, info in registry().items():
        assert isinstance(info.command, click.Command), f"{name} is not a click.Command"
        assert info.command.name == name


def test_every_command_module_imports_and_exposes_COMMANDS():
    """Force-load every command module so a broken one fails loudly here.

    Asserts each module exposes a non-empty ``COMMANDS`` list of click.Command
    objects — restoring the 'import the CLI ⇒ all commands checked' property.
    """
    seen: set[str] = set()
    modnames = [f"jseval.commands.{m}" for m in _GROUP_MODULES]
    modnames += [f"jseval.{m}" for m in _LEGACY_MODULES]
    for modname in modnames:
        mod = importlib.import_module(modname)
        assert hasattr(mod, "COMMANDS"), f"{modname} has no COMMANDS list"
        assert mod.COMMANDS, f"{modname}.COMMANDS is empty"
        for cmd in mod.COMMANDS:
            assert isinstance(cmd, click.Command)
            seen.add(cmd.name)
    # The union of every module's COMMANDS is exactly the public surface.
    assert seen == EXPECTED_NAMES
