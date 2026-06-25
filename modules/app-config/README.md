# app-config

Bootstrap configuration resolver combining RuntimeConfig, SecretsVault, and RepoRootLocator.

## Overview

The `app-config` module provides application-level configuration bootstrapping. It coordinates between multiple configuration sources (files, environment, secrets vault) and provides a unified configuration snapshot to the application.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `ConfigManagerBootstrap` | Bootstraps configuration manager |
| `ConfigSnapshot` | Immutable configuration snapshot |
| `ConfigSnapshotListener` | Listener for configuration changes |

## Dependencies

**Depends on:**
- `adapters-lucene` - RuntimeConfig
- `app-secrets` - SecretsVault bootstrap
- `configuration` - RepoRootLocator
- `infra-core` - SecretsVault interface

**Depended on by:**
- `app-launcher` - Application bootstrap
- `app-observability` - Config for health checks
