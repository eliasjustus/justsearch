# app-inference

Inference lifecycle management for AI/ML model readiness, health checks, and mode transitions.

## Overview

The `app-inference` module manages the lifecycle of inference services (embedding models, LLMs). It handles startup, readiness checks, degraded operation, and graceful shutdown. Mode transitions are validated to prevent invalid state changes.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `InferenceLifecycleManager` | Main lifecycle coordinator (1800+ lines) |
| `InferenceConfig` | Inference configuration |
| `ModeTransitionException` | Invalid mode transition error |

## Lifecycle States

1. **OFFLINE** - Inference not available
2. **STARTING** - Models loading
3. **READY** - Inference available
4. **DEGRADED** - Partial functionality

## Dependencies

**Depends on:**
- `ai-bridge` - Local LLM bridge
- `app-api` - Service contracts
- `configuration` - Config management

**Depended on by:**
- `app-ai` - AI service integration
- `ui` - Inference status API
