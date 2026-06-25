# core

Foundational domain model providing search/indexing DTOs, port interfaces, and analyzer abstractions.

## Overview

The `core` module defines the fundamental types shared across all JustSearch modules. It contains no business logic—only data transfer objects, port interfaces, and analyzer descriptors. This module has zero internal dependencies, making it the base layer of the dependency graph.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `Query` | Search request DTO with query text, filters, pagination |
| `Result` | Search result DTO with matched documents and metadata |
| `Cursor` | Pagination cursor for result set traversal |
| `Facet` | Facet aggregation result for filtering UI |
| `SearchPort` | Port interface for search operations |
| `AnalyzerRegistry` | Registry of available text analyzers |
| `AnalyzerDescriptor` | Metadata describing a text analyzer |
| `DegradedIntentBuilder` | Fallback query synthesis when AI intent parsing fails |

## Dependencies

**Depends on:**
- None (foundational module)

**Depended on by:**
- Nearly all modules via transitive dependency through `app-api`
