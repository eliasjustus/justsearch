---
title: "Tempdoc 297 - Diagnostics Export Path Redaction"
---

# Tempdoc 297 - Diagnostics Export Path Redaction

**Status:** Partially completed (claim corrected 2026-09-03)
**Created:** 2026-03-14
**Updated:** 2026-03-14
**Goal:** Redact Windows usernames from file paths in diagnostics export zips, so exports can be shared with support without leaking `C:\Users\<username>\...` paths.

## Context

Tempdoc 289 (F9/A10) identified that file paths in logs expose Windows usernames. The log files themselves are local-only (acceptable), but `DiagnosticsController` bundles logs and state snapshots into a zip for sharing. Paths in these exports should be redacted.

`ApiErrorHandler.sanitizeMessage()` already has a path-redaction pattern (scrubs `C:\Users\...` and `/home/...` from exception messages sent to API consumers). The same approach can be applied at diagnostics-export time.

## Items

- [x] 1. Added path redaction to textual diagnostics-export members. Windows paths (`C:\Users\...`) and Unix paths (`/home/...`) are replaced with `[path]` using the same regex patterns as `ApiErrorHandler.sanitizeMessage()`. Redaction is applied to core state JSONs, policy files, GPU capabilities, uncompressed log files (line-by-line streaming for large files), crash reports, telemetry NDJSON, and runtime state snapshots. The implementation has since moved to `DiagnosticsServiceImpl`; its `redactPaths()` method remains package-private for testability.

- [ ] 1a. Gzipped `.gz` log archives are copied into the diagnostics zip without decompression or redaction (`DiagnosticsServiceImpl.addDirectoryRedacted`). A bundle containing such an archive is therefore not proven safe to share. Decompress-and-redact, omit compressed logs, or add an explicit user warning before calling this goal complete.

- [ ] 2. SlowRequestDumper output is still not collected in the export. This is a separate scope decision (not a redaction issue).

## Reference

- `modules/ui/src/main/java/io/justsearch/ui/api/DiagnosticsController.java` — export handler
- `modules/ui/src/main/java/io/justsearch/ui/api/ApiErrorHandler.java` — existing `sanitizeMessage()` pattern
- `modules/ui/src/main/java/io/justsearch/ui/api/SlowRequestDumper.java` — slow-request dumps (not in export)
