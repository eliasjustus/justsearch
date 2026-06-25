/**
 * Rule descriptions for the stage-completeness gate (tempdoc 549 Phase F / principle 9).
 * Keyed by ruleId; surfaced in SARIF + `--explain`.
 */
export const STAGE_COMPLETENESS_RULE_DESCRIPTIONS = {
  'stage-completeness/renderer-missing-stage':
    'A SearchTrace.StageId has no entry in the FE STAGE_LABELS renderer map. Every stage must be ' +
    'renderable (principle 9). Add the wireId→label entry in searchTraceExplain.ts.',
  'stage-completeness/renderer-phantom-stage':
    'STAGE_LABELS declares a label for a wireId that is not a SearchTrace.StageId. Remove the stale ' +
    'entry or correct the wireId.',
  'stage-completeness/orphan-stage':
    'A SearchTrace.StageId is not emitted by any producer (worker SearchTraceProjector / head ' +
    'HeadStage). A declared stage must be produced (principle 9) — wire it in or remove it.',
  'stage-completeness/source-missing':
    'A file required to evaluate stage-completeness (the StageId enum, a producer, or the FE renderer) ' +
    'was not found at its expected path.',
  'stage-completeness/renderer-complete':
    'The FE renderer covers the closed StageId vocabulary exactly (healthy).',
  'stage-completeness/producers-cover-vocabulary':
    'Every StageId is emitted by at least one producer (healthy).',
};
