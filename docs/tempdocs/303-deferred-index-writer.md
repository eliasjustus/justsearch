---
title: "303: Deferred IndexWriter Opening"
type: tempdoc
status: done
created: 2026-03-14
updated: 2026-03-14
---

> NOTE: Noncanonical working tempdoc. Verify behavioral claims against canonical docs, code, and
> tests before promotion.

# 303: Deferred IndexWriter Opening

## Problem

After tempdoc 302's deferred model loading (-54%), the Worker sync path is ~950ms. The
`lucene` phase is the largest remaining component at ~303ms. Of that, the `IndexWriter`
open (read segments, acquire write lock, initialize merge scheduler) accounts for an
estimated 100-150ms. Search only needs a `DirectoryReader` — the writer is only needed
for indexing, which starts later via `IndexingLoop`.

This tempdoc investigates deferring `IndexWriter` to a background thread, opening a
read-only `DirectoryReader` + `SearcherManager` immediately for search, then upgrading
to a write-capable runtime in the background.

## Prior art

- Tempdoc 290 work stream 1 (Strategy A) — same pattern: defer heavy init past gRPC
  readiness, wire via setters when ready
- Blue/Green migration in `KnowledgeServer` — already opens a read-only `searchRuntime`
  and a separate `ingestRuntime` in production
- `createReadOnlyRuntime()` exists in `KnowledgeServer` (line 680)

## Code review findings (2026-03-14)

### ComponentsFactory.build() — two code paths

**Read-only** (lines 255-289): `DirectoryReader.open(dir)` → `SearcherManager`. No
`IndexWriter`, no CRTRT thread. Returns `Components(writer=null, crtrt=null)`.

**Read-write** (lines 292-318): `IndexWriter(dir, cfg)` → `DirectoryReader.open(writer)`
(NRT reader) → `SearcherManager`. CRTRT thread for NRT visibility.

The NRT `SearcherManager` requires `IndexWriter` to exist first. A read-only
`SearcherManager` uses `DirectoryReader.open(dir)` — completely independent.

### What needs IndexWriter

| Operation | Needs writer? | When called |
|-----------|--------------|-------------|
| `index()`, `deleteById()`, `commit()` | Yes | IndexingLoop (background) |
| `drainSwitchBufferBestEffort()` | Yes | KnowledgeServer.start() after Lucene |
| `validateIndexableFields()` | No (in-memory) | KnowledgeServer.start() after Lucene |
| `latestCommitUserDataBestEffort()` | No (fresh DirectoryReader) | EmbeddingCompatController |
| All search/query operations | No (SearcherManager) | gRPC search service |
| Schema mismatch detection | No (pre-writer check) | ComponentsFactory.build() |

### Existing infrastructure

- `createReadOnlyRuntime()` — proven in Blue/Green migration
- `prebuiltComponents` injection path in `LuceneIndexRuntime` (line 954)
- `injectPrebuiltComponents()` — designed for test pre-wiring but mechanically suitable
- `applyComponents()` — private, does the actual field swap

### Gaps

- No public `upgradeToReadWrite()` API on `LuceneIndexRuntime`
- `applyComponents()` is private and `started` AtomicBoolean gates `startInternal()` to
  run exactly once
- After writer opens, `SearcherManager` must switch from directory-based to NRT-based
  for fresher results
- `drainSwitchBufferBestEffort()` currently runs before gRPC — must be deferred

## Proposed approach

### Normal startup (no migration)

```
Current:
  [IndexWriter open + NRT SearcherManager + CRTRT = 303ms][gRPC][writePort]

Deferred:
  [Read-only DirectoryReader + SearcherManager = ~150ms][gRPC][writePort]
  Background: [IndexWriter open + NRT upgrade + CRTRT + drainSwitchBuffer]
```

1. Open `searchRuntime` as read-only via `createReadOnlyRuntime()`
2. Set `ingestRuntime = searchRuntime` temporarily (search works, writes blocked)
3. Bind gRPC with read-only search runtime
4. Write port, start loop, start sentinel
5. Background: create full read-write `LuceneIndexRuntime`, call `start()`
6. When ready: swap `ingestRuntime` to the new write runtime, wire into `IndexingLoop`,
   call `drainSwitchBufferBestEffort()`

### Blue/Green migration startup

No change needed — already opens read-only search + separate write runtime.

## Open questions

### Q1: Should searchRuntime upgrade to NRT after writer opens?

**Decision: Yes (inside the single runtime).** The revised approach uses a single
`LuceneIndexRuntime` instance. When the background writer opens, it replaces the
directory-based `SearcherManager` with an NRT-based one inside the same instance.
This happens atomically — the `volatile` SearcherManager field is swapped.

### Q2: Does IndexingLoop call write methods immediately on start?

**Answer: No.** Investigated 2026-03-14. The loop polls `jobQueue.pollPending()` first
(line 383). On first start with empty queue, enters idle branch. Idle branch calls only
reads (`countByField` via SearcherManager). All writes (`index()`, `commit()`) are
guarded by conditions (jobs exist, `indexedSinceCommit > 0`, backfill services
available + work pending). No write fires on a fresh first iteration.

### Q3: EmbeddingCompatController interaction?

**Answer: Safe.** `countByField()` uses SearcherManager (read-only).
`latestCommitUserDataBestEffort()` uses `DirectoryReader.open(dir)` (read-only).

### Q4: Thread safety of runtime swap?

**Answer: Swap not needed.** `IndexingLoop.indexRuntime` is `final` (line 88). Cannot
be swapped. The revised approach uses lazy writer init **inside** the single runtime
instance. `LuceneIndexRuntime.writer` is already `volatile` — setting it from a
background thread is immediately visible. `guardWritable()` (line 923) checks
`writer == null` — this is the single chokepoint to modify.

## Revised approach (based on investigation)

The original approach (swap read-only and write runtimes) is blocked by `final` field
constraints. The revised approach uses **lazy IndexWriter inside a single runtime**.

1. Construct `LuceneIndexRuntime` and call `start()` in **read-only mode** — opens
   `DirectoryReader` + directory-based `SearcherManager`. No `IndexWriter`, no CRTRT.
2. Pass this single runtime to `IndexingLoop`, gRPC services, everything — `final`
   fields satisfied.
3. Bind gRPC, write port — search works immediately via read-only SearcherManager.
4. Background: call new `openWriterDeferred()` on the runtime that:
   - Creates `IndexWriter(dir, cfg)`
   - Creates NRT `DirectoryReader.open(writer)`
   - Atomically swaps `SearcherManager` to NRT-based
   - Starts CRTRT thread
   - Sets `this.writer` (volatile) — `guardWritable()` starts passing
   - Counts down a `writerReady` latch
5. `guardWritable()` modified: instead of throwing when `writer == null`, awaits the
   `writerReady` latch with a timeout. First write from IndexingLoop blocks briefly
   (~150ms) until the background writer opens. Reads are unblocked throughout.
6. `drainSwitchBufferBestEffort()` moved into the background task, after writer opens.

### Key design points

- **Single runtime instance** — satisfies `final` field in IndexingLoop
- **`volatile writer`** — already exists, background set is immediately visible
- **`guardWritable()` latch** — converts hard fail to brief block
- **No runtime swap** — avoids thread-safety issues with reference replacement
- **SearcherManager swap** — atomic volatile replacement inside the runtime
- **CRTRT thread** — starts after writer opens, provides NRT refresh

## Implementation items

- [ ] 1. Add `CountDownLatch writerReady` field to `LuceneIndexRuntime`
- [ ] 2. Modify `guardWritable()` to await latch instead of throw when `writer == null`
  and runtime is not `readOnly`
- [ ] 3. Add `openWriterDeferred()` method to `LuceneIndexRuntime` — creates writer,
  NRT SearcherManager, CRTRT, sets writer volatile, counts down latch
- [ ] 4. Modify `ComponentsFactory.build()` or `LuceneIndexRuntime.startInternal()` to
  support deferred-writer mode (read-only open first, writer later)
- [ ] 5. Refactor `KnowledgeServer.start()` to open in deferred-writer mode, move
  writer open + `drainSwitchBufferBestEffort()` into background task (extend existing
  `deferredModelInit` CompletableFuture)
- [ ] 6. Measure improvement

## Measured result (2026-03-14)

Items 1-5 implemented.

**Warm runs (with both SPLADE + embedding models):**

| | Previous (writer sync) | With deferred writer |
|---|------------------------|---------------------|
| Median total (warm) | 945ms | **894ms** |
| lucene phase median | 269ms | **248ms** |
| Improvement | | **-51ms (-5.4%)** |

**Background writer opens ~70ms after writePort (consistently).** The IndexWriter
open itself is only ~20-50ms — much less than the estimated 100-150ms. The bulk of
the `lucene` phase is schema compatibility checking (`DirectoryReader.open(dir)` +
field validation), which runs in both read-only and read-write paths.

**Assessment:** The improvement is real but modest. The original estimate of 100-150ms
was wrong — it assumed IndexWriter open dominated the lucene phase. In reality, the
read-only open (DirectoryReader + SearcherManager + schema check) accounts for most
of the cost. The deferred writer correctly moves the IndexWriter off the critical
path, but the IndexWriter was only ~20-50ms of the ~270ms phase.

## Implementation note

The change adds complexity (CountDownLatch, deferred mode flag, SearcherManager swap)
for ~51ms saving. This is at the margin of what's justifiable. The approach is
architecturally sound and follows the established deferred-init pattern, but the
cost/benefit ratio is weaker than Strategy A (-1116ms for similar complexity).

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 65 days at audit time.

