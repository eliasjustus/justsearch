import { describe, it, expect } from 'vitest';
import { folderStatus } from './folderStatus';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view';

// Tempdoc 599 §9.1/§9.5 — the seam's invariant + precedence. The load-bearing regression is
// "ready ⟹ inFlight === 0 && failed === 0" and "a walk timestamp alone never yields ready"
// (the §8.1 false-terminal fix).

const row = (over: Partial<IndexedRootView> = {}): IndexedRootView => ({
  pathHash: 'h',
  collection: 'default',
  fileCount: 10,
  lastIndexedIsoTime: '2026-06-17T00:00:00Z',
  status: 'indexed',
  walkError: '',
  inFlightCount: 0,
  failedCount: 0,
  walkCompleted: true,
  ...over,
});

const ctx = (
  over: Partial<{ relativeTime: string; verifiedRelativeTime: string; provisional: boolean }> = {},
) => ({
  relativeTime: 'just now',
  verifiedRelativeTime: '',
  provisional: false,
  ...over,
});

describe('folderStatus', () => {
  it('ready ⟹ inFlight === 0 && failed === 0 (the core invariant)', () => {
    // Exhaustive small grid: ready is the ONLY drained, failure-free, scanned outcome.
    for (const inFlight of [0, 1, 5]) {
      for (const failed of [0, 1]) {
        const fs = folderStatus(row({ inFlightCount: inFlight, failedCount: failed }), ctx());
        if (fs.state === 'ready') {
          expect(inFlight).toBe(0);
          expect(failed).toBe(0);
        }
      }
    }
  });

  it('a walk timestamp alone never yields ready while jobs are in flight (the §8.1 fix)', () => {
    // status='indexed' + lastIndexed set (walk done) BUT 5 jobs still draining → indexing, not ready.
    const fs = folderStatus(row({ inFlightCount: 5 }), ctx());
    expect(fs.state).toBe('indexing');
    expect(fs.glyph).toBe('pending');
    expect(fs.metaText).toContain('5 remaining');
  });

  it('ready: drained + scanned + no failures → ✓ glyph + "indexed" meta', () => {
    const fs = folderStatus(row(), ctx({ relativeTime: '2 minutes ago' }));
    expect(fs.state).toBe('ready');
    expect(fs.glyph).toBe('indexed');
    expect(fs.metaText).toContain('indexed 2 minutes ago');
  });

  it('ready: shows the §Recency "Verified" heartbeat when lastVerified is known', () => {
    // Tempdoc 626 §Recency — a calm ✓ proves it is fresh by showing WHEN it was last verified.
    const fs = folderStatus(row(), ctx({ relativeTime: '2 minutes ago', verifiedRelativeTime: 'just now' }));
    expect(fs.state).toBe('ready');
    expect(fs.metaText).toContain('Verified just now');
  });

  it('ready: omits the "Verified" suffix when never verified (empty time)', () => {
    const fs = folderStatus(row(), ctx({ verifiedRelativeTime: '' }));
    expect(fs.state).toBe('ready');
    expect(fs.metaText).not.toContain('Verified');
  });

  it('unverified: indexed but deletions unverified → caution glyph, NEVER the green ✓ (tempdoc 626 §Axis-C)', () => {
    // An otherwise-ready folder whose reconcile cap-skipped delete-detection must NOT show "indexed".
    const fs = folderStatus(row({ deleteDetectionUnverified: true }), ctx());
    expect(fs.state).toBe('unverified');
    expect(fs.glyph).toBe('unverified');
    expect(fs.glyph).not.toBe('indexed');
    expect(fs.metaText).toContain('reindex to be sure');
  });

  it('unverified is a caveat on READY only — active states outrank it', () => {
    // While indexing (jobs in flight), the unverified flag does not override the indexing state.
    const indexing = folderStatus(row({ deleteDetectionUnverified: true, inFlightCount: 3 }), ctx());
    expect(indexing.state).toBe('indexing');
    // A path-missing folder stays 'unavailable' regardless of the unverified flag.
    const gone = folderStatus(row({ deleteDetectionUnverified: true, status: 'unavailable' }), ctx());
    expect(gone.state).toBe('unavailable');
  });

  it('scanning: walk in progress (no lastIndexed, walk NOT completed) → Scanning…', () => {
    const fs = folderStatus(
      row({ status: 'pending', lastIndexedIsoTime: '', walkCompleted: false, fileCount: -1 }),
      ctx({ relativeTime: '' }),
    );
    expect(fs.state).toBe('scanning');
    expect(fs.glyph).toBe('pending');
    expect(fs.metaText).toContain('Scanning');
  });

  it('empty: walk COMPLETED but admitted zero files (the Fix 1 distinction vs scanning)', () => {
    const fs = folderStatus(
      row({ status: 'pending', lastIndexedIsoTime: '', walkCompleted: true, fileCount: 0, inFlightCount: 0 }),
      ctx({ relativeTime: '' }),
    );
    expect(fs.state).toBe('empty');
    expect(fs.metaText).toContain('No indexable files');
  });

  it('Fix 1: same wire fields, walkCompleted flips scanning↔empty', () => {
    const base = { status: 'pending', lastIndexedIsoTime: '', inFlightCount: 0, fileCount: -1 } as const;
    expect(folderStatus(row({ ...base, walkCompleted: false }), ctx({ relativeTime: '' })).state).toBe('scanning');
    expect(folderStatus(row({ ...base, walkCompleted: true }), ctx({ relativeTime: '' })).state).toBe('empty');
  });

  it('failed: drained but terminal failures → error glyph + failed count on the structured field', () => {
    // tempdoc 599 §16/B1: the failed count rides `failed` (rendered as a clickable chip), not metaText.
    const fs = folderStatus(row({ inFlightCount: 0, failedCount: 3 }), ctx());
    expect(fs.state).toBe('failed');
    expect(fs.glyph).toBe('error');
    expect(fs.failed).toBe(3);
  });

  it('failed takes the walkError path with the error message', () => {
    const fs = folderStatus(row({ walkError: 'Access denied' }), ctx());
    expect(fs.state).toBe('failed');
    expect(fs.glyph).toBe('error');
    expect(fs.metaText).toContain('Access denied');
  });

  it('indexing surfaces a failed sub-count on the structured field while still in flight', () => {
    const fs = folderStatus(row({ inFlightCount: 4, failedCount: 2 }), ctx());
    expect(fs.state).toBe('indexing');
    expect(fs.metaText).toContain('4 remaining');
    expect(fs.failed).toBe(2); // surfaced via the clickable chip, not the prose meta
  });

  it('indexed folder with fileCount 0 (files deleted post-index) is still ready, not empty', () => {
    // status='indexed' (had files at walk time) → ready even if the live FS count is now 0.
    const fs = folderStatus(row({ fileCount: 0 }), ctx());
    expect(fs.state).toBe('ready');
  });

  it('unavailable: path-missing status → muted glyph + reconnect remedy + last-known count (A1, §17.3)', () => {
    const fs = folderStatus(
      row({
        status: 'unavailable',
        walkError: 'No such file or directory',
        lastIndexedIsoTime: '',
        fileCount: 10,
      }),
      ctx({ relativeTime: '' }),
    );
    expect(fs.state).toBe('unavailable');
    expect(fs.glyph).toBe('unavailable');
    expect(fs.metaText).toContain('Folder not found');
    // §17.3 — keep the last-known count (last-known, not "0"); the folder is disconnected, not empty.
    expect(fs.metaText).toContain('last known 10 files');
  });

  it('unavailable: count unknown (-1) → remedy only, never a "0 files" (A1, §17.3)', () => {
    const fs = folderStatus(
      row({ status: 'unavailable', walkError: 'ROOT_NOT_DIRECTORY', fileCount: -1 }),
      ctx({ relativeTime: '' }),
    );
    expect(fs.state).toBe('unavailable');
    expect(fs.metaText).toContain('Folder not found');
    expect(fs.metaText).not.toContain('last known');
    expect(fs.metaText).not.toContain('0 files');
  });

  it('provisional (global rebuild) overrides everything → unknown + last-known', () => {
    const fs = folderStatus(row({ inFlightCount: 5 }), ctx({ provisional: true }));
    expect(fs.state).toBe('unknown');
    expect(fs.glyph).toBe('pending');
    expect(fs.metaText).toContain('Rebuilding');
  });
});
