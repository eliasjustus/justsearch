# test-support

Shared test utilities including golden test runners, workflow validation, and test fixtures.

## Overview

The `test-support` module provides reusable test infrastructure for other modules. It includes golden test runners for SSOT-based testing, workflow validators for UI automation, sample document fixtures, and pagination testing harnesses.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `GoldenSmokeRunner` | SSOT-based golden test execution |
| `WorkflowValidator` | Validates UI automation workflows |
| `WorkflowLoader` | Loads workflow definitions |
| `WorkflowIndex` | Index of available workflows |
| `WorkflowIndexEntry` | Single workflow entry |
| `WorkflowValidationException` | Validation error |
| `SampleDoc` / `SampleDocs` | Test document fixtures |
| `MiniIndexFixture` | Minimal index for unit tests |
| `PagingDeterminismHarness` | Pagination consistency testing |
| `PageWindowAssert` | Pagination assertion helpers |

## Usage

```java
// Load golden test workflows
WorkflowIndex index = WorkflowLoader.loadIndex();
WorkflowValidator.validate(index);

// Use sample documents
SampleDoc doc = SampleDocs.simple();

// Create mini index
try (MiniIndexFixture fixture = new MiniIndexFixture()) {
    fixture.index(doc);
    // Run tests
}
```

## Dependencies

**Depends on:**
- `configuration` - Config for test setup
- `app-launcher` - Launcher integration
- Jackson, JSON Schema - Test data parsing
