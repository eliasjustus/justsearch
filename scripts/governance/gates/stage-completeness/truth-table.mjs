/**
 * Stage-completeness truth table (tempdoc 549 Phase F).
 *
 * Mechanizes principle 9 ("a new pipeline stage must register a trace stage node AND a
 * renderer, or the build fails") as a cross-file structural invariant over the closed
 * `SearchTrace.StageId` vocabulary:
 *
 *   - renderer completeness: the FE `STAGE_LABELS` map (the single generic search-explain
 *     renderer's stage→label table) must cover EXACTLY the Java `StageId` wireIds — a new
 *     stage with no label, or a phantom label for no stage, fails.
 *   - producer validity: every stage wireId a producer emits (the worker `SearchTraceProjector`
 *     constants + the head `HeadStage` enum) must be a member of the closed vocabulary — a
 *     typo'd / unknown wireId fails (it would otherwise throw at runtime in `StageId.fromWireId`).
 *
 * Conforms to the kernel's truth-table contract: (input) → { ruleId, status, reason }.
 */

/** Verdict for the FE renderer-completeness check (STAGE_LABELS keys vs the Java StageId set). */
export function verdictForRendererCompleteness({ missing, extra }) {
  if (missing.length > 0) {
    return {
      ruleId: 'stage-completeness/renderer-missing-stage',
      status: 'fail',
      reason:
        `The FE search-explain renderer (STAGE_LABELS in searchTraceExplain.ts) is missing a label ` +
        `for stage(s): ${missing.join(', ')}. Every SearchTrace.StageId must have a renderer label ` +
        `(principle 9). Add the entries to STAGE_LABELS.`,
    };
  }
  if (extra.length > 0) {
    return {
      ruleId: 'stage-completeness/renderer-phantom-stage',
      status: 'fail',
      reason:
        `STAGE_LABELS declares label(s) for unknown stage wireId(s): ${extra.join(', ')}. ` +
        `Every key must match a SearchTrace.StageId wireId. Remove the stale entries or fix the wireId.`,
    };
  }
  return {
    ruleId: 'stage-completeness/renderer-complete',
    status: 'pass',
    reason: 'The FE renderer covers the closed StageId vocabulary exactly.',
  };
}

/**
 * Verdict for producer coverage: every declared StageId must actually be emitted by some producer
 * (the worker SearchTraceProjector or the head HeadStage). A stage in the enum that no producer
 * references is an orphan — declared in the vocabulary but never put on the trace.
 */
export function verdictForProducedCoverage({ orphans }) {
  if (orphans.length > 0) {
    return {
      ruleId: 'stage-completeness/orphan-stage',
      status: 'fail',
      reason:
        `StageId vocabulary member(s) ${orphans.join(', ')} are not emitted by any producer ` +
        `(SearchTraceProjector / HeadStage). A declared stage must be produced (principle 9) — ` +
        `wire it into a producer or remove it from the enum.`,
    };
  }
  return {
    ruleId: 'stage-completeness/producers-cover-vocabulary',
    status: 'pass',
    reason: 'Every StageId is emitted by at least one producer.',
  };
}

/** Verdict when a required source file (the vocabulary or a producer/renderer) is absent. */
export function verdictForMissingSource({ label, path }) {
  return {
    ruleId: 'stage-completeness/source-missing',
    status: 'fail',
    reason: `Cannot run the stage-completeness check: ${label} not found at ${path}.`,
  };
}
