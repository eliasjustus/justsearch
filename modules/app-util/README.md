# app-util

Application utilities for path resolution, instance locking, and temporary file management.

## Overview

The `app-util` module provides common utilities used across the application. It handles repository path resolution, single-instance enforcement via file locking, and temporary file lifecycle management.

## Key Classes

| Class | Responsibility |
|-------|----------------|
| `RepoPaths` | Repository directory/file resolution |
| `AppInstanceLock` | Single-instance enforcement via file lock |
| `TempFileManager` | Temporary file creation and cleanup |

## Usage

```java
// Get repository paths
Path ssotDir = RepoPaths.ssot();
Path dataDir = RepoPaths.data();

// Ensure single instance
try (AppInstanceLock lock = AppInstanceLock.acquire()) {
    // Run application
}

// Manage temp files
TempFileManager temp = new TempFileManager();
Path file = temp.createTempFile("prefix", ".tmp");
temp.cleanup(); // Deletes all temp files
```

## Dependencies

**Depends on:**
- `configuration` - RepoRootLocator

**Depended on by:**
- `app-launcher` - Path utilities
- `app-config` - Path resolution
