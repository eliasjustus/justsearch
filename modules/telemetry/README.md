# telemetry

OpenTelemetry integration for distributed tracing, metrics collection, and structured logging.

## Overview

The `telemetry` module provides observability infrastructure for JustSearch. It wraps the OpenTelemetry SDK to provide metrics export, span tracing, and logging integration. Telemetry data is exported in NDJSON format to local files with automatic rotation and pruning.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `Telemetry` | Interface for telemetry operations |
| `LocalTelemetry` | Local implementation with file-based export |
| `NdjsonMetricExporter` | Exports metrics to NDJSON files with rotation |
| `NdjsonSpanExporter` | Exports spans to NDJSON files |
| `TracingBootstrap` | Bootstrap configuration for tracing |
| `JvmRuntimeGauges` | JVM metrics (memory, threads, GC) |
| `LlmTelemetry` | LLM-specific telemetry (tokens, latency) |
| `TelemetryHealthState` | Thread-safe counters for export health |
| `TelemetryHealthSnapshot` | Immutable snapshot for health reporting |

## Configuration

| Environment Variable | Purpose |
|---------------------|---------|
| `JUSTSEARCH_DATA_DIR` | Base directory for telemetry files |

Telemetry files are written to `{data_dir}/telemetry/`:
- `metrics-{date}.ndjson` - Metric exports (10MB rotation)
- `spans-{date}.ndjson` - Span exports

## Dependencies

**Depends on:**
- OpenTelemetry SDK (metrics, tracing)
- SLF4J (logging facade)

**Depended on by:**
- `app-ai` - AI operation tracing
- `app-indexing` - Indexing metrics
- `app-search` - Search latency metrics
- `pipeline-engine` - Pipeline execution tracing
- `ui` - Health endpoint (`/api/telemetry/health`)
