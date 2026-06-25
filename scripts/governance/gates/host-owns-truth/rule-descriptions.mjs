// Rule descriptions for the host-owns-truth gate (merged into the SARIF report by run.mjs).

export const HOST_OWNS_TRUTH_RULE_DESCRIPTIONS = {
  'host-owns-truth/core-namespace-forked':
    'A declaration in the reserved `core` namespace carries non-CORE provenance. Tempdoc 560 §4.5 / ' +
    'kernel decision 05: the host owns truth — the `core` namespace is the host\'s; a contribution ' +
    '(TRUSTED/UNTRUSTED_PLUGIN) may never mint or shadow a `core.*` primitive (fork core truth). ' +
    'Contributions live in `vendor.*`. Enforced at construction by ContributionRegistry.install; this ' +
    'gate is the static backstop over the serialized registry snapshot.',
  'host-owns-truth/vendor-namespace-mislabeled':
    'A declaration in the `vendor.*` namespace carries CORE provenance — a mislabeled contribution. ' +
    'Core primitives must live in `core.*`; vendor namespace is for non-core contributions.',
  'host-owns-truth/snapshot-missing':
    'The registry snapshot was not generated before the gate ran. Run :modules:app-services:test ' +
    '(RegistrySnapshotExporterTest) or the exportRegistrySnapshot task first. Soft-passes (warning).',
};
