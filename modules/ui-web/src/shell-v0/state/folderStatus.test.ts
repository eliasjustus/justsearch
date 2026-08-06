import { describe, it, expect } from 'vitest';
import { folderStatus } from './folderStatus';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view';

// Tempdoc 599 §9.1/§9.5 — the seam's invariant + precedence. The load-bearing regression is
// "ready ⟹ inFlight === 0 && failed === 0" and "a walk timestamp alone never yields ready"
// (the §8.1 false-terminal fix).
//
// Tempdoc 813 §4 adds the SECOND tier: the drained outcome splits into `keyword-ready` (coverage
// still climbing) and `ready` (complete). The base `row()` below deliberately carries NO coverage
// fields, so every pre-813 case above keeps exercising the no-denominator fallback wording it
// always pinned; the two-tier cases opt in explicitly.

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
  over: Partial<{
    relativeTime: string;
    verifiedRelativeTime: string;
    provisional: boolean;
    enrichmentStages: { embedding: boolean; splade: boolean; ner: boolean } | null;
  }> = {},
) => ({
  relativeTime: 'just now',
  verifiedRelativeTime: '',
  provisional: false,
  ...over,
});

/** All three parent stages on — the ordinary deployment (813 §4 applicability). */
const ALL_STAGES = { embedding: true, splade: true, ner: true };

describe('folderStatus', () => {
  it('a searchable tier ⟹ inFlight === 0 && failed === 0 (the core invariant)', () => {
    // Exhaustive small grid: the searchable outcomes — 813 §4 made that two states, `ready` and
    // `keyword-ready` — are the ONLY drained, failure-free, scanned ones. Both tiers are checked so
    // the new state cannot become a back door around the §8.1 drain requirement.
    for (const inFlight of [0, 1, 5]) {
      for (const failed of [0, 1]) {
        for (const coverage of [{}, { parentDocsTotal: 10, parentDocsSettledEmbedding: 4 }]) {
          const fs = folderStatus(
            row({ inFlightCount: inFlight, failedCount: failed, ...coverage }),
            ctx({ enrichmentStages: ALL_STAGES }),
          );
          if (fs.state === 'ready' || fs.state === 'keyword-ready') {
            expect(inFlight).toBe(0);
            expect(failed).toBe(0);
          }
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

  it('ready: drained + scanned + no failures → ✓ glyph + "indexed" meta (no coverage on the wire)', () => {
    const fs = folderStatus(row(), ctx({ relativeTime: '2 minutes ago' }));
    expect(fs.state).toBe('ready');
    expect(fs.glyph).toBe('indexed');
    expect(fs.metaText).toContain('indexed 2 minutes ago');
    // 813 §4 — with no faithful denominator the row asserts neither tier, it just stays as it was.
    expect(fs.metaText).not.toContain('enriching');
    expect(fs.metaText).not.toContain('fully searchable');
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

// Tempdoc 813 §4 — the two-tier split of the drained outcome. The regression these pin: a folder
// whose jobs drained but whose semantic layers are 40% done must SAY so ("keyword-ready · enriching
// 40%") instead of the pre-813 unqualified "indexed", and a folder with no faithful coverage
// denominator must NOT gain a fabricated percent.
describe('folderStatus — per-root enrichment tier (813 §4)', () => {
  const covered = (over: Partial<IndexedRootView> = {}): IndexedRootView =>
    row({
      fileCount: 312,
      parentDocsTotal: 100,
      parentDocsSettledEmbedding: 100,
      parentDocsSettledSplade: 100,
      parentDocsSettledNer: 100,
      chunkDocsTotal: 100,
      chunkDocsSettled: 100,
      ...over,
    });

  it('keyword-ready: drained but coverage incomplete → "keyword-ready · enriching N%"', () => {
    // Four applicable stages of 100 docs each, 40 settled in every one: 160/400 = 40%.
    const fs = folderStatus(
      covered({
        parentDocsSettledEmbedding: 40,
        parentDocsSettledSplade: 40,
        parentDocsSettledNer: 40,
        chunkDocsSettled: 40,
      }),
      ctx({ relativeTime: '2 minutes ago', enrichmentStages: ALL_STAGES }),
    );
    expect(fs.state).toBe('keyword-ready');
    expect(fs.metaText).toBe('default · 312 files · keyword-ready · enriching 40%');
  });

  it('ready: coverage complete → "fully searchable", keeping the indexed + Verified suffixes', () => {
    const fs = folderStatus(
      covered(),
      ctx({
        relativeTime: '2 minutes ago',
        verifiedRelativeTime: 'just now',
        enrichmentStages: ALL_STAGES,
      }),
    );
    expect(fs.state).toBe('ready');
    expect(fs.glyph).toBe('indexed');
    expect(fs.metaText).toBe(
      'default · 312 files · fully searchable · indexed 2 minutes ago · Verified just now',
    );
  });

  it('zero denominator: coverage fields absent/zero → no percent, pre-813 wording', () => {
    const absent = folderStatus(row(), ctx({ enrichmentStages: ALL_STAGES }));
    expect(absent.state).toBe('ready');
    expect(absent.metaText).not.toContain('%');
    // Explicit zeros are the "index runtime unavailable" shape (IndexedRootView: all-zero) — the
    // same withdrawal, never "enriching 0%".
    const zeros = folderStatus(
      row({ parentDocsTotal: 0, chunkDocsTotal: 0, parentDocsSettledEmbedding: 0 }),
      ctx({ enrichmentStages: ALL_STAGES }),
    );
    expect(zeros.state).toBe('ready');
    expect(zeros.metaText).not.toContain('enriching');
  });

  it('applicability unknown (no snapshot yet) → no percent even with coverage on the row', () => {
    const fs = folderStatus(covered({ parentDocsSettledSplade: 0 }), ctx({ enrichmentStages: null }));
    expect(fs.state).toBe('ready');
    expect(fs.metaText).not.toContain('enriching');
  });

  it('a disabled stage leaves the denominator — it cannot pin a folder below 100% forever', () => {
    // SPLADE off with zero SPLADE-settled docs: with the stage counted the root would sit at 75%.
    const row0 = covered({ parentDocsSettledSplade: 0 });
    const spladeOff = folderStatus(
      row0,
      ctx({ enrichmentStages: { embedding: true, splade: false, ner: true } }),
    );
    expect(spladeOff.state).toBe('ready');
    expect(spladeOff.metaText).toContain('fully searchable');
    const spladeOn = folderStatus(row0, ctx({ enrichmentStages: ALL_STAGES }));
    expect(spladeOn.state).toBe('keyword-ready');
    expect(spladeOn.metaText).toContain('enriching 75%');
  });

  it('coverage never overrides the active/failed/unverified states (precedence unchanged)', () => {
    const stages = ctx({ enrichmentStages: ALL_STAGES });
    expect(folderStatus(covered({ inFlightCount: 3 }), stages).state).toBe('indexing');
    expect(folderStatus(covered({ failedCount: 2 }), stages).state).toBe('failed');
    expect(folderStatus(covered({ deleteDetectionUnverified: true }), stages).state).toBe(
      'unverified',
    );
  });

  it('999 of 1000 is NOT "fully searchable" — completeness is exact, never a rounded 100%', () => {
    // The §8.1 false-terminal in a new costume: Math.round would report 100% with a document still
    // pending. Completeness gates on settled >= total, and the displayed percent caps at 99.
    const fs = folderStatus(
      row({
        fileCount: 1000,
        parentDocsTotal: 1000,
        parentDocsSettledEmbedding: 999,
        parentDocsSettledSplade: 1000,
        parentDocsSettledNer: 1000,
        chunkDocsTotal: 0,
        chunkDocsSettled: 0,
      }),
      ctx({ enrichmentStages: ALL_STAGES }),
    );
    expect(fs.state).toBe('keyword-ready');
    expect(fs.metaText).toContain('enriching 99%');
    expect(fs.metaText).not.toContain('fully searchable');
  });

  it('settled counts are clamped to their total — a stale count can never exceed 100%', () => {
    const fs = folderStatus(
      covered({ parentDocsSettledEmbedding: 500, chunkDocsSettled: 500 }),
      ctx({ enrichmentStages: ALL_STAGES }),
    );
    expect(fs.state).toBe('ready');
    expect(fs.metaText).toContain('fully searchable');
  });
});
