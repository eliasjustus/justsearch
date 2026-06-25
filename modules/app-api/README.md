# app-api

Public API contracts defining core application facades, service interfaces, and DTOs.

## Overview

The `app-api` module defines the public contract for JustSearch's application layer. It contains service interfaces, request/response DTOs, and lifecycle state definitions. This module has no implementation—only interfaces and data classes that other modules implement.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `AppFacade` | Main entrypoint facade for UI/launchers |
| `SearchRequest` / `SearchResponse` | Search operation DTOs |
| `IndexingService` | Indexing operations interface |
| `DocumentService` | Document CRUD operations |
| `OnlineAiService` | Online AI service interface |
| `OnlineAiRuntimeControl` | AI runtime control interface |
| `OnlineAiRuntimeIntrospection` | AI runtime introspection |
| `LifecycleState` | Application state enum (STARTING, READY, DEGRADED, ERROR) |
| `LifecycleSnapshotV1` | Health snapshot for API responses |
| `LifecycleReasonCode` | Reason codes for health states |
| `KnowledgeIngestRequest/Response` | RAG knowledge ingestion |
| `KnowledgeSearchRequest/Response` | RAG knowledge search |
| `KnowledgeStatus` | Knowledge base status |
| `ChunkFormat` | Streaming chunk format |
| `SummaryRejection` / `SummaryRejectedException` | Summary rejection handling |

## Dependencies

**Depends on:**
- `core` - Domain types (exported via api())

**Depended on by:**
- All `app-*` modules
- `ui` - REST API implementation
- `indexer-worker` - Service implementations
