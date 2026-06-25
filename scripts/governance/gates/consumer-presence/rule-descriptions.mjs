// Rule descriptions for the consumer-presence gate (merged into the SARIF report by run.mjs).

export const CONSUMER_PRESENCE_RULE_DESCRIPTIONS = {
  'consumer-presence/no-consumers':
    'A registry declaration has zero consumers. Tempdoc 560 §5/§6 (P1): substrate-without-a-consumer ' +
    'is forbidden — the NonEmpty<ConsumerHook> keystone makes it fail the build. Give the declaration ' +
    'an executor (AGENT/UI/CLI), a consuming surface, or an inline ConsumerHook; or, if it is genuine ' +
    'reserved substrate, grandfather it in gates/consumer-presence/exemptions.json with justification.',
  'consumer-presence/exempt-orphan':
    'A zero-consumer declaration that is currently grandfathered in exemptions.json. Surfaced as a note ' +
    'so the standing debt stays visible until a real consumer pulls it.',
  'consumer-presence/stale-exemption':
    'An exemption that no longer corresponds to an orphaned declaration (the declaration gained a ' +
    'consumer or was removed). Remove it so the baseline ratchets down.',
  'consumer-presence/snapshot-missing':
    'The in-JVM registry snapshot was not generated before the gate ran. Run :modules:app-services:test ' +
    '(RegistrySnapshotExporterTest) or the RegistrySnapshotExporter main first. Soft-passes (warning).',
  'consumer-presence/snapshot-unreadable':
    'The registry snapshot artifact could not be parsed as JSON. Soft-passes (warning).',
};
