// @vitest-environment happy-dom

/**
 * Tempdoc 549 (Phase D1) — (SearchTrace, search-explain) strategy.
 * Tempdoc 577 Goal 1 Phase 3 (Ext I + Move D) — the altitude cut.
 *
 * These tests pin:
 *  - the per-facet altitude authority is exhaustive over the closed facet list;
 *  - user-tier facets word themselves (mode legibility, expansion honesty,
 *    correction echo, degradation-in-words) in the summary line;
 *  - diagnostic-tier facets (QPP, decision kind, the stage list) render ONLY
 *    inside the collapsed disclosure — never in the user line;
 *  - the stage list renders one labeled row per stage (status / reason / ms /
 *    detail), fixing the run-on form;
 *  - STAGE_LABELS covers the closed StageId vocabulary verbatim (the FE half of
 *    the Phase F stage-completeness invariant). The Pass-8 FieldRoles
 *    exhaustiveness is enforced at import time by assertFieldRoles.
 */

import { describe, it, expect } from 'vitest';
import { render } from 'lit';
import {
  searchTraceExplainStrategy,
  userSummaryParts,
  STAGE_LABELS,
  TRACE_FACETS,
  TRACE_FACET_ALTITUDE,
  DEGRADATION_REASON_WORDING,
} from './searchTraceExplain.js';
import type { SearchTrace } from '../../../api/generated/index.js';
// Tempdoc 564 Phase 3: SearchTrace is the generated Zod projection; build instances via parse().
import { searchTraceSchema } from '../../../api/generated/schema-types/search-trace.js';

function renderToHost(trace: SearchTrace): HTMLElement {
  const host = document.createElement('div');
  const result = searchTraceExplainStrategy(trace, {} as never, { apiBase: '' });
  render(result as never, host);
  return host;
}

const STAGE_WIRE_IDS = [
  'query-understanding',
  'expansion',
  'correction',
  'sparse-retrieval',
  'dense-retrieval',
  'splade-retrieval',
  'fusion',
  'chunk-merge',
  'branch-fusion',
  'lambdamart',
  'cross-encoder',
  'freshness',
];

describe('searchTraceExplain strategy (549 D1 + 577 Phase 3 altitude cut)', () => {
  it('TRACE_FACET_ALTITUDE is exhaustive over the closed facet list', () => {
    expect(Object.keys(TRACE_FACET_ALTITUDE).sort()).toEqual([...TRACE_FACETS].sort());
    for (const facet of TRACE_FACETS) {
      expect(['user', 'diagnostic']).toContain(TRACE_FACET_ALTITUDE[facet]);
    }
  });

  it('words the user line: mode legibility + expansion honesty + degradation-in-words', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'sparse_shortcut',
      effectiveMode: 'TEXT',
      degradation: { vectorBlocked: true, vectorBlockedReason: 'ENCODER_OOM' },
      stages: [{ id: 'expansion', status: 'skipped', reason: 'TIMEOUT' }],
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain('Keyword search');
    expect(parts).toContain('AI expansion timed out — keyword results only');
    expect(parts).toContain('semantic ranking blocked (ENCODER_OOM)');
    const user = renderToHost(trace).querySelector('[data-testid="search-explain-user"]');
    expect(user?.textContent).toContain('Keyword search');
  });

  it('echoes a correction and an executed expansion in the user grammar', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'multi_leg',
      effectiveMode: 'HYBRID',
      stages: [
        { id: 'expansion', status: 'executed' },
        { id: 'correction', status: 'executed', detail: 'pipeline' },
      ],
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain('Hybrid search');
    expect(parts).toContain('AI-expanded query');
    expect(parts.some((p) => p.includes('pipeline') && p.includes('instead'))).toBe(true);
  });

  it('diagnostic facets render only inside the collapsed disclosure, never the user line', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'sparse_shortcut',
      effectiveMode: 'TEXT',
      qpp: { maxIdf: 5.91, avgIctf: 8.07, queryScope: 0.13 },
      stages: [{ id: 'fusion', status: 'executed', ms: 42 }],
    });
    const host = renderToHost(trace);
    const user = host.querySelector('[data-altitude="user"]');
    const diag = host.querySelector('details[data-altitude="diagnostic"]');
    expect(diag).not.toBeNull();
    // QPP, decision kind, and stages live in the diagnostic tier...
    expect(diag?.textContent).toContain('QPP: maxIdf=5.91');
    expect(diag?.textContent).toContain('decision: sparse_shortcut');
    expect(diag?.textContent).toContain('Fusion');
    // ...and never leak into the user line.
    expect(user?.textContent ?? '').not.toContain('QPP');
    expect(user?.textContent ?? '').not.toContain('sparse_shortcut');
    // The disclosure is collapsed by default (no `open` attribute).
    expect((diag as HTMLDetailsElement).open).toBe(false);
  });

  it('renders one labeled stage row per stage with status, reason, ms and detail', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'multi_leg',
      effectiveMode: 'HYBRID',
      stages: [
        { id: 'fusion', status: 'executed', detail: 'cc', ms: 42 },
        { id: 'dense-retrieval', status: 'skipped', reason: 'dense-only' },
        { id: 'cross-encoder', status: 'executed', ms: 8 },
      ],
    });
    const host = renderToHost(trace);
    const rows = host.querySelectorAll('.search-explain-stage');
    expect(rows.length).toBe(3);
    expect(rows[0]?.textContent?.replace(/\s+/g, ' ')).toContain('Fusion');
    expect(rows[0]?.textContent).toContain('executed (cc) · 42ms');
    expect(rows[1]?.textContent).toContain('skipped (dense-only)');
    expect(rows[2]?.textContent).toContain('executed · 8ms');
  });

  it('STAGE_LABELS covers the closed StageId vocabulary exactly', () => {
    // The FE half of the stage-completeness invariant (principle 9): every wireId in the
    // Java SearchTrace.StageId enum must have a label. A drift here is what Phase F's gate
    // mechanizes; this test is the local backstop.
    expect(Object.keys(STAGE_LABELS).sort()).toEqual([...STAGE_WIRE_IDS].sort());
  });

  // Tempdoc 602 R6 — the user-tier degradation reason is worded, never the raw code.
  it('602 R6 — words a known degradation reason code (no raw code in the user line)', () => {
    const trace: SearchTrace = searchTraceSchema.parse({
      version: 1,
      decisionKind: 'multi_leg',
      effectiveMode: 'TEXT',
      degradation: {
        vectorBlocked: true,
        vectorBlockedReason: 'LEGACY_INDEX_NO_FINGERPRINT',
      },
    });
    const parts = userSummaryParts(trace);
    expect(parts).toContain('semantic ranking blocked — the index needs a one-time rebuild');
    // The raw enum code never appears in the user line.
    expect(parts.join(' · ')).not.toContain('LEGACY_INDEX_NO_FINGERPRINT');
  });

  // The FE half of the search-degradation-reason-codes correspondence (602 R6): every
  // SearchReasonCode member that can populate a user-tier degradation field is worded.
  // The 11 chunk-merge codes are declared noWordingExempt in the gate register; this
  // list is the FE backstop the check-search-degradation-reason-codes gate mechanizes.
  const DEGRADATION_WORDED_CODES = [
    'INITIALIZING',
    'NO_EMBEDDING_MODEL',
    'NEW_INDEX_NO_FINGERPRINT',
    'LEGACY_INDEX_NO_FINGERPRINT',
    'FINGERPRINT_MATCH',
    'FINGERPRINT_MISMATCH',
    'REBUILD_IN_PROGRESS',
    'REBUILD_COMPLETED',
    'EMBEDDING_COMPATIBILITY_UNKNOWN',
    'UNKNOWN',
    'EMBEDDING_COMPATIBILITY_BLOCKED',
    'NO_EMBEDDING_SERVICE',
    'EMBEDDING_GENERATION_FAILED',
    'EMBEDDING_EXCEPTION',
  ];

  it('602 R6 — DEGRADATION_REASON_WORDING covers the user-tier reason vocabulary exactly', () => {
    expect(Object.keys(DEGRADATION_REASON_WORDING).sort()).toEqual(
      [...DEGRADATION_WORDED_CODES].sort(),
    );
  });
});
