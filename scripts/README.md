# Scripts Directory

Developer tooling, automation, and CI/CD scripts for JustSearch.

## Quick Start

**Most common tasks:**

```powershell
# Start local dev server (backend + frontend)
powershell -ExecutionPolicy Bypass -File scripts/dev/dev-all.ps1

# Check AI prerequisites (models, VRAM, llama-server)
node scripts/verify-prerequisites.mjs

# Run all tests
./gradlew test

# Validate documentation
node scripts/docs/docs-validate.mjs

# Run benchmark or evaluation suites
python -m jseval --help
```

## Choose Your Tool

| What you want to do | Script/Directory |
|---------------------|------------------|
| Start local dev server | `scripts/dev/dev-all.ps1` |
| Start API-only (no frontend) | `scripts/dev/run-headless-api.ps1` |
| Run benchmarks / eval | `python -m jseval` ([scripts/jseval/](jseval/)) |
| Check AI setup | `scripts/verify-prerequisites.mjs` |
| Run CI checks locally | `scripts/ci/` |
| Generate reliability budget report | `node scripts/ci/report-reliability-budget.mjs` |
| Validate documentation | `node scripts/docs/docs-validate.mjs` |
| Debug GPU capabilities | `scripts/diagnostics/` |
| Bootstrap dev environment | `scripts/setup/preflight.ps1` |
| Verify pre-merge checks | See [CLAUDE.md "Verification Workflow"](../CLAUDE.md) step 5 |

## Directory Guide

### Core Development

| Directory | Purpose |
|-----------|---------|
| `dev/` | Dev server orchestration, MCP server for Claude Code |
| `setup/` | Bootstrap scripts for Node + prerequisites |
| `ci/` | CI/CD automation (build, sign, package, smoke tests) |

### Benchmarking & Evaluation

| Directory | Purpose |
|-----------|---------|
| `jseval/` | Canonical benchmark + eval CLI (Python, Click-based, 45+ subcommands). Supersedes the prior `scripts/bench/` + `scripts/eval/` + `scripts/perf/` infrastructure deleted by commit `a9c484f59` (2026-03-16). |
| `bench/` | Manual hardware-perf utilities (`passmark-*`, `run-B1.ps1`, `ort-perf-probe.py`, etc.) — independent of jseval. |
| `search/` | Data-conversion utilities for BEIR / known-item datasets (the produced corpus files are committed; these scripts run rarely). |

### Quality & Validation

| Directory | Purpose |
|-----------|---------|
| `governance/` | Unified discipline-gate kernel (tempdoc 530): registry-driven gate runner, SARIF emitter, dashboard, run-history, per-gate enforcers under `gates/<id>/`. Wire-evolution lives at `gates/wire/` (migrated from the prior `contract-governance/` kernel in Phase F). |
| `docs/` | Documentation validation, linting, transformation. |
| `evidence/` | EvidenceBundle validation and determinism checks. |
| `architecture/` | Dependency analysis (`module-deps.mjs`), IPC usage snapshot (`ipc-usage.mjs`). |
| `wire-contract/` | Buf workspace + npm-pinned buf binary for the wire protocol (slice 3a-1-8). |
| `agent-analytics/` | Session telemetry hooks for Claude Code. |

### Specialized

| Directory | Purpose |
|-----------|---------|
| `ai/` | AI model packaging (`pack-author.ps1`). |
| `diagnostics/` | GPU/NVML capability detection. |
| `test-support/` | Test utilities and fixtures. |

### Deployment / Ops

| Directory | Purpose |
|-----------|---------|
| `ops/` | Manual ops + deployment capture scripts. |
| `vmware/` | Offline VM installer packaging. |
| `sandbox/` | Isolated environment setup docs. |
| `ui/` | UI-specific manual debug utilities. |
| `models/` | Model packaging and distribution helpers. |
| `prod/` | Production helpers. |

### Root Scripts

| Script | Purpose |
|--------|---------|
| `run-ui-local-llm.ps1` | Run UI with local LLM backend. |
| `verify-prerequisites.mjs` | Verify AI prerequisites (models, VRAM). |

There is no single "canonical gate" wrapper (slice 3a-1-8f §B.12 + §B.14, 2026-05-12). Pre-merge verification is per-subject — see [CLAUDE.md "Verification Workflow"](../CLAUDE.md) step 5.

## Prerequisites

- **Node.js** 24.x (see `scripts/setup/bootstrap-node-win.ps1`)
- **Java** 25 (Gradle will download if missing)
- **PowerShell** 7+ (for Windows scripts)
- **Python** 3.x (for jseval; install via `pip install -e scripts/jseval`)

## MCP Integration

`scripts/dev/justsearch-dev-mcp.mjs` provides Model Context Protocol integration for Claude Code (registered in `.mcp.json` as `justsearch-dev`):

- `justsearch_dev_start` — Start dev server
- `justsearch_dev_stop` — Stop dev server
- `justsearch_dev_status` — Check server health
- `justsearch_dev_wait_ready` — Wait for backend readiness
- `justsearch_dev_tail_log` — Tail server logs
- `justsearch_dev_capture_evidence` — Capture EvidenceBundle

## Further Reading

- [jseval CLI surface](jseval/) — canonical benchmark + eval tool
- [CLAUDE.md](../CLAUDE.md) — Claude Code instructions, including the canonical Verification Workflow
- [docs/explanation/09-testing-strategy.md](../docs/explanation/09-testing-strategy.md) — test pyramid + pre-merge checks
