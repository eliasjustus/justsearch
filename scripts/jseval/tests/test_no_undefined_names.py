"""Guard against the missing-import / undefined-global bug class (tempdoc 645 A3).

The cli.py split's one real escaped bug was a module that used ``click`` without
importing it — a runtime-silent ``NameError`` no test caught (it only fires on a
specific path). No Python linter gate exists in this repo, so this test is the
zero-dependency stand-in: it statically flags any name that is *referenced* but
*bound nowhere in its module* (no import, assignment, parameter, comprehension
target, def/class, …) and is not a builtin.

Design (sound for the target class, zero false positives): a missing import is
bound nowhere, so it is flagged; a comprehension/lambda variable is a Store
target / parameter, so it is collected as bound and never flagged. The known
limitation — a name bound in one function but used undefined in another — is NOT
the missing-import class and is out of scope here.
"""

from __future__ import annotations

import ast
import builtins
from pathlib import Path

PKG_ROOT = Path(__file__).resolve().parent.parent / "jseval"

_BUILTINS = set(dir(builtins)) | {
    "__name__", "__file__", "__doc__", "__builtins__", "__spec__",
    "__loader__", "__package__", "__path__", "__dict__", "__class__",
}


def _bound_names(tree: ast.AST) -> set[str]:
    """Every name bound anywhere in the module (over-approximated, tree-wide)."""
    names: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
            names.add(node.id)
        elif isinstance(node, ast.arg):
            names.add(node.arg)
        elif isinstance(node, ast.Import):
            for alias in node.names:
                names.add((alias.asname or alias.name).split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            for alias in node.names:
                names.add(alias.asname or alias.name)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            names.add(node.name)
        elif isinstance(node, (ast.Global, ast.Nonlocal)):
            names.update(node.names)
        elif isinstance(node, ast.ExceptHandler) and node.name:
            names.add(node.name)
    return names


def undefined_names(path: Path) -> list[tuple[int, str]]:
    """Return (lineno, name) for every Load of a name bound nowhere + not a builtin."""
    tree = ast.parse(path.read_text(encoding="utf-8"), str(path))
    available = _bound_names(tree) | _BUILTINS
    out: list[tuple[int, str]] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
            if node.id not in available:
                out.append((node.lineno, node.id))
    return sorted(set(out))


def test_checker_detects_a_real_missing_import(tmp_path):
    """Self-test: the checker must flag a genuinely undefined name (incl. via closure)."""
    p = tmp_path / "broken.py"
    p.write_text("def f():\n    return click.echo('x')\n", encoding="utf-8")
    assert any(name == "click" for _, name in undefined_names(p))
    ok = tmp_path / "ok.py"
    ok.write_text("import click\ndef f():\n    return click.echo('x')\n", encoding="utf-8")
    assert undefined_names(ok) == []


def test_jseval_package_has_no_undefined_names():
    """No module in the jseval package references an unbound/undefined name."""
    problems: dict[str, list[tuple[int, str]]] = {}
    for path in sorted(PKG_ROOT.rglob("*.py")):
        found = undefined_names(path)
        if found:
            problems[str(path.relative_to(PKG_ROOT))] = found
    assert not problems, f"undefined names found: {problems}"
