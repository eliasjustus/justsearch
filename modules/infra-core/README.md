# infra-core

Infrastructure abstractions for health checks, deterministic hashing, secrets management, and similarity configuration.

## Overview

The `infra-core` module provides cross-cutting infrastructure concerns used throughout JustSearch. It includes health aggregation for component monitoring, deterministic JSON hashing for pipeline caching, secrets vault for credential management, and similarity configuration providers.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `InfraHealthAggregator` | Aggregates health signals from multiple components |
| `JsonDagHashingService` | Deterministic hashing of pipeline DAG configurations |
| `DagHashingService` | Interface for DAG hashing operations |
| `SecretsVault` | Secure credential storage and retrieval |
| `EgressGuard` | Network egress policy enforcement |
| `SimilarityProvider` | Provides similarity implementations |

## Dependencies

**Depends on:**
- Jackson (JSON processing)
- JSON Schema Validator (schema validation)

**Depended on by:**
- `app-config` - SecretsVault bootstrap
- `app-secrets` - SecretsVault interface
- `pipeline-engine` - Health monitoring hooks
- `telemetry` - Health monitoring integration
- `adapters-lucene` - Boost/similarity configuration
