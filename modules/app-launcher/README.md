# app-launcher

Executable launcher and CLI orchestrating application lifecycle, smoke testing, and diagnostics.

## Overview

The `app-launcher` module provides the main entry point for running JustSearch. It includes CLI commands for smoke testing, reindexing, verification, and snapshot capture. The launcher bootstraps all services and coordinates the full application lifecycle.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `Launcher` | Main CLI entry point |
| `LauncherBootstrap` | Data-dir setup before SLF4J init |
| `LauncherCommands` | CLI subcommands |
| `LauncherEnvironment` | Environment setup |
| `SmokeDriver` | Smoke test execution |

## CLI Commands

| Command | Description |
|---------|-------------|
| `smoke` | Run smoke tests |
| `reindex` | Trigger full reindex |
| `verify` | Verify index integrity |
| `snapshot` | Capture diagnostic snapshot |

## Dependencies

**Depends on:**
- Nearly all `app-*` modules
- `ui` - Web UI integration
- `indexer-worker` - Worker coordination
- `telemetry` - Observability

**Depended on by:**
- Desktop app entry point
- CI/CD smoke tests
