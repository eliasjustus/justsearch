// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 914 D3 — the "N failed" chip must stay REACHABLE while the retry ladder re-queues a
 * permanently failing file.
 *
 * Measured on the live stack (2 files failing with `ExtractionException: Sandbox parser failed`,
 * nothing else pending), 60 samples of `/api/indexing-roots/substrate` at 300ms:
 *
 *   inFlight=0 failed=2  x56      the settled truth
 *   inFlight=2 failed=0  x4       the retry window — the re-queued jobs count as IN-FLIGHT
 *
 * A re-queued FAILED job is PENDING while it waits, so the worker's per-root `GROUP BY` reports it
 * as in-flight and not as failed; the wire carries nothing that distinguishes it from a first
 * attempt. In those windows the seam took its `inFlight > 0` branch, `failed` fell to 0, the chip
 * disappeared and the drill-down was unreachable — on a folder whose files will NEVER succeed, so
 * the flicker is permanent, not transitional.
 *
 * The three sampled states are pinned below, plus the two directions the rule must NOT go: it may
 * never invent a count the substrate never reported, and a settled zero must discharge the memory.
 */

import { describe, it, expect } from 'vitest';
import { folderStatus, rememberFailedCounts, failedChipLabel } from './folderStatus';
import type { IndexedRootView } from '../../api/generated/schema-types/indexed-root-view';

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

const ctx = (lastKnownFailed?: number) => ({
  relativeTime: 'just now',
  verifiedRelativeTime: '',
  provisional: false,
  enrichmentPending: false,
  enrichmentBlocked: false,
  ...(lastKnownFailed === undefined ? {} : { lastKnownFailed }),
});

describe('folderStatus — the failed chip survives a retry re-queue (914 D3)', () => {
  it('sample A (56/60): inFlight=0 failed=2 — the settled truth, chip from this poll', () => {
    const fs = folderStatus(row({ inFlightCount: 0, failedCount: 2 }), ctx(2));
    expect(fs.state).toBe('failed');
    expect(fs.failed).toBe(2);
    // Nothing is being carried: this poll reported the number itself.
    expect(fs.failedIsLastKnown).toBeFalsy();
  });

  it('sample B (4/60): inFlight=2 failed=0 — the chip stays, flagged as last-known', () => {
    const fs = folderStatus(row({ inFlightCount: 2, failedCount: 0 }), ctx(2));
    // The state line stays truthful: the queue IS working on two jobs right now.
    expect(fs.state).toBe('indexing');
    expect(fs.metaText).toBe('default · Indexing · 2 remaining');
    // ...and the drill-down stays reachable, which is the defect this closes.
    expect(fs.failed).toBe(2);
    expect(fs.failedIsLastKnown).toBe(true);
    // The chip says which kind of number it is rather than asserting it as this tick's count.
    expect(failedChipLabel(fs.failed, fs.failedIsLastKnown === true)).toBe(
      'Show 2 failed files (last known — this folder is indexing right now)',
    );
  });

  it('sample C (1/60 in the validator run): inFlight=1 failed=1 — THIS poll wins over the memory', () => {
    const fs = folderStatus(row({ inFlightCount: 1, failedCount: 1 }), ctx(2));
    expect(fs.state).toBe('indexing');
    // 1, not the remembered 2: a sample that reports a count is never overridden by an older one.
    expect(fs.failed).toBe(1);
    expect(fs.failedIsLastKnown).toBeFalsy();
    expect(failedChipLabel(fs.failed, fs.failedIsLastKnown === true)).toBe('Show 1 failed file');
  });

  it('never invents a chip: in-flight with no failure ever observed shows no count', () => {
    const fs = folderStatus(row({ inFlightCount: 2, failedCount: 0 }), ctx());
    expect(fs.state).toBe('indexing');
    expect(fs.failed).toBe(0);
    expect(fs.failedIsLastKnown).toBeFalsy();
  });

  it('the memory is only read inside the in-flight branch — a drained root reports its own truth', () => {
    // Same memory as sample B, but the queue is settled: the substrate has answered, so the answer
    // is 0 failures and the row is ready. A sticky chip here would be the lie 813/906 forbid.
    const fs = folderStatus(row({ inFlightCount: 0, failedCount: 0 }), ctx(2));
    expect(fs.state).toBe('ready');
    expect(fs.failed).toBe(0);
    expect(fs.failedIsLastKnown).toBeFalsy();
  });
});

describe('rememberFailedCounts — the bound on the memory (914 D3)', () => {
  it('remembers a reported failure count', () => {
    expect(rememberFailedCounts({}, [row({ pathHash: 'a', failedCount: 2 })])).toEqual({ a: 2 });
  });

  it('refreshes rather than ages: a new number replaces the old one', () => {
    expect(rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 5 })])).toEqual({ a: 5 });
  });

  it('carries the previous number across an UNSETTLED zero (the retry window)', () => {
    expect(
      rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 0, inFlightCount: 2 })]),
    ).toEqual({ a: 2 });
  });

  it('DISCHARGES on a settled zero — one drained observation ends the memory', () => {
    expect(
      rememberFailedCounts({ a: 2 }, [row({ pathHash: 'a', failedCount: 0, inFlightCount: 0 })]),
    ).toEqual({});
  });

  it('drops a root that left the listing, so the map cannot outgrow the current poll', () => {
    expect(rememberFailedCounts({ a: 2, gone: 7 }, [row({ pathHash: 'a', failedCount: 2 })])).toEqual({
      a: 2,
    });
  });

  it('ignores a row with no pathHash rather than keying the memory on an empty string', () => {
    expect(rememberFailedCounts({}, [row({ pathHash: '', failedCount: 3 })])).toEqual({});
  });
});
