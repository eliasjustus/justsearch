import { describe, it, expect } from 'vitest';
import { enrichmentProgress } from './enrichmentCoverage.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';

// 809 finding 1 (the gate) + finding 9 (the trap it must not fall into): the DOC-level counters read
// fully healthy during an active PASSAGE-level backfill, so a gate reading only them is the wrong-gate
// mistake with a green light on it.

const status = (enrichment: Record<string, unknown> | null): StatusSnapshot =>
  ({ worker: enrichment === null ? {} : { enrichment } }) as unknown as StatusSnapshot;

/** Everything drained, every tier on — the settled index. */
const settled = {
  embeddingEnabled: true,
  spladeEnabled: true,
  nerEnabled: true,
  embeddingPendingCount: 0,
  spladePendingCount: 0,
  pendingNerCount: 0,
  chunk: { chunkEmbeddingPendingCount: 0, chunkVectorCoveragePercent: 100, chunkVectorsReady: true },
};

describe('enrichmentProgress', () => {
  it('a fully drained index owes nothing', () => {
    expect(enrichmentProgress(status(settled))).toEqual({ pending: false, tiers: [] });
  });

  it('809 finding 9 TRAP: doc-level clean + passage-level backfill running ⇒ still pending', () => {
    // The measured shape: embeddingCoveragePercent 100, queueDepth 0, pendingJobsCount 0 — all clean —
    // while chunkEmbeddingPendingCount was 1554 and chunkVectorsReady false.
    const p = enrichmentProgress(
      status({
        ...settled,
        embeddingCoveragePercent: 100,
        chunk: { chunkEmbeddingPendingCount: 1554, chunkVectorCoveragePercent: 90.1, chunkVectorsReady: false },
      }),
    );
    expect(p.pending).toBe(true);
    expect(p.tiers).toEqual(['passage-embeddings']);
  });

  it('names each tier that still owes work', () => {
    expect(enrichmentProgress(status({ ...settled, embeddingPendingCount: 12 })).tiers).toEqual([
      'document-embeddings',
    ]);
    expect(enrichmentProgress(status({ ...settled, spladePendingCount: 3 })).tiers).toEqual(['sparse-terms']);
    expect(enrichmentProgress(status({ ...settled, pendingNerCount: 7 })).tiers).toEqual(['entities']);
  });

  it('a DISABLED tier never pins a permanent caveat, however large its backlog', () => {
    expect(
      enrichmentProgress(
        status({
          ...settled,
          embeddingEnabled: false,
          spladeEnabled: false,
          nerEnabled: false,
          embeddingPendingCount: 5000,
          spladePendingCount: 5000,
          pendingNerCount: 5000,
          chunk: { chunkEmbeddingPendingCount: 5000, chunkVectorsReady: false },
        }),
      ),
    ).toEqual({ pending: false, tiers: [] });
  });

  it('positive evidence only: an absent snapshot / block / counter is UNKNOWN, never pending', () => {
    expect(enrichmentProgress(null)).toEqual({ pending: false, tiers: [] });
    expect(enrichmentProgress(undefined)).toEqual({ pending: false, tiers: [] });
    expect(enrichmentProgress(status(null))).toEqual({ pending: false, tiers: [] });
    expect(enrichmentProgress(status({}))).toEqual({ pending: false, tiers: [] });
  });

  it('"not ready" with nothing pending is a stalled condition, not "still computing"', () => {
    // chunkVectorsReady false + zero pending: no work is queued, so a completion claim must not be
    // held open forever on it (that would be a caveat that never clears). Health owns that condition.
    const p = enrichmentProgress(
      status({ ...settled, chunk: { chunkEmbeddingPendingCount: 0, chunkVectorsReady: false } }),
    );
    expect(p.pending).toBe(false);
  });
});
