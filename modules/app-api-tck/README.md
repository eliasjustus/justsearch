# app-api-tck

Technology Compatibility Kit (TCK) for testing compliance with app-api contracts.

## Overview

The `app-api-tck` module provides contract tests that verify implementations comply with the `app-api` interfaces. It tests DTO shapes, error mapping, streaming behavior, deadlines/cancellation, and happy-path search operations.

## Test Classes

| Class | Purpose |
|-------|---------|
| `DtoDefaultsAndShapeTest` | Verifies DTO structure and defaults |
| `ErrorMappingTest` | Tests error translation and mapping |
| `StreamingContractTest` | Verifies streaming API contracts |
| `DeadlinesAndCancellationTest` | Tests deadline and cancellation handling |
| `InProcSearchHappyPathTest` | Happy-path search integration |

## Running Tests

```bash
./gradlew :modules:app-api-tck:test
```

## Dependencies

**Depends on:**
- `app-api` - Contracts being tested
- `app-ai` - AI service implementation
- `app-services` - Worker coordination
- `core`, `telemetry`, `ipc-common` - Test infrastructure
