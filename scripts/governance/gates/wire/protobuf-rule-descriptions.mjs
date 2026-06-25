/**
 * Protobuf-evolution rule descriptions for the SARIF emitter.
 *
 * Factored out of `lib/sarif-emitter.mjs` per tempdoc 530's substrate
 * extraction. The substrate emitter (`scripts/governance/lib/sarif-emitter.mjs`)
 * accepts a `ruleDescriptions` map at emit time; this module supplies the
 * contract-governance kernel's protobuf-specific strings.
 *
 * When a new ruleId is introduced (truth-table verdict or buf-breaking
 * classification), add a corresponding entry here so SARIF's
 * `tool.driver.rules[].shortDescription` is meaningful in the Security tab.
 */

export const CONTRACT_GOVERNANCE_RULE_DESCRIPTIONS = {
  'contract-governance/noop-pr': 'No contract changes; no classification or version bump',
  'contract-governance/phantom-version': 'VERSION bumped without contract changes',
  'contract-governance/undeclared-break': 'Structural break without classification declaration',
  'contract-governance/declared-without-diff':
    'Major-tier classification declared but no structural break observed',
  'contract-governance/declared-additive': 'Additive classification matches structural state',
  'contract-governance/declared-breaking': 'Major-tier classification matches structural break',
  'contract-governance/insufficient-bump':
    'VERSION bump insufficient for declared classification',
  'contract-governance/misclassification':
    'Classification severity insufficient for observed structural break',
  'contract-governance/unhandled-case': 'Truth-table did not match any verdict row',
  'contract-governance/baseline-introduction':
    'First commit introducing the spec; no baseline VERSION to compare against',
  'contract-governance/downgrade-with-breaks':
    'VERSION downgraded with structural breaks; explicit revert classification required',
  'contract-governance/phantom-downgrade':
    'VERSION downgraded with no spec change and no classification',
  'contract-governance/buf-cli-missing': 'buf CLI not installed at the expected path',
  'contract-governance/buf-runner-error': 'buf breaking exited with a runner-level error',
  'contract-governance/fixture-baseline-missing': 'Fixture baseline directory missing',
  'protobuf-buf-breaking/field-deleted':
    'Field deleted (covers explicit removes and reserve+rename pattern)',
  'protobuf-buf-breaking/field-type-changed': 'Field type changed; binary-incompatible',
  'protobuf-buf-breaking/field-renamed': 'Field renamed (same number, different name)',
  'protobuf-buf-breaking/enum-value-deleted': 'Enum value deleted',
  'protobuf-buf-breaking/enum-value-renamed':
    'Enum value renamed (same number; FILE-level break)',
  'protobuf-buf-breaking/scope-reference-missing':
    'Cross-reference cannot be resolved (often signals package rename)',
  'protobuf-buf-breaking/package-rename-detected':
    'Cascading scope errors collapsed into a single package-rename hint',
  'protobuf-buf-breaking/break-detected': 'Generic structural break detected',
  'protobuf-buf-breaking/unparsed': 'buf output line did not match expected format',
};
