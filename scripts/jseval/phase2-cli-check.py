#!/usr/bin/env python
"""Launch preflight (a): the `claude` CLI must be resolvable.

Exit 0 if `claude --version` resolves (the agentic-eval identity field the runs
record), 1 otherwise. Uses the SAME probe the run itself uses
(`agent_utility_run.claude_cli_version` -> `claude --version`, 10s timeout, no
cost, no model call), so a green here means the run can capture its cli_version.
Does NOT prove API auth; ANTHROPIC_API_KEY is still required at run time and the
first calibrate fails fast if it is absent.
"""
from __future__ import annotations

import sys


def main() -> int:
    from jseval.agent_utility_run import claude_cli_version

    v = claude_cli_version()
    if v:
        print(f"claude CLI resolvable: {v}")
        return 0
    print("FATAL: `claude --version` did not resolve -- CLI unavailable.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
