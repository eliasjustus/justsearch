# app-observability

Health signal aggregation and diagnostics for infrastructure, AI, and pipeline components.

## Overview

The `app-observability` module aggregates health signals from all system components. It provides health endpoints, capabilities reporting, and diagnostic data collection. Integrates with gRPC for distributed health monitoring and MDC for structured logging.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `InfraHealthBootstrap` | Bootstraps health monitoring |
| `InfraHealthController` | HTTP health endpoint handler |
| `InfraHealthGrpcService` | gRPC health service |
| `InfraDiagnosticsService` | Diagnostic data collection |
| `CapabilitiesService` | Reports system capabilities |
| `CapabilitiesController` | Capabilities endpoint handler |
| `MdcContext` | MDC context management for logging |

## Endpoints

| Endpoint | Description |
|----------|-------------|
| `/api/health` | Component health status |
| `/api/capabilities` | System capabilities |
| `/api/diagnostics/export` | Diagnostic bundle export |

## Dependencies

**Depends on:**
- `configuration` - ObservabilityConfig
- `app-config` - App-level config
- `ai-bridge` - AI health monitoring
- `pipeline-engine`, `pipeline-executor` - Pipeline health
- `search`, `adapters-lucene` - Search health
- `infra-core`, `ipc-common` - Infrastructure health

**Depended on by:**
- `ui` - REST API registration
