// Rule descriptions for the runtime-witness gate (merged into the SARIF report by run.mjs).

export const RUNTIME_WITNESS_RULE_DESCRIPTIONS = {
  'runtime-witness/over-claimed-agent-consumer':
    'An operation declares an "agent-loop" consumer and is agent-audience-eligible (USER/AGENT) but ' +
    'the real AgentOperationEmitter does not offer it to the model. The static consumer-presence gate ' +
    'sees a declared consumer; this gate proves the prompt-construction channel never delivers it ' +
    '(tempdoc 560 §5 — AGENT = prompt-construction). The keystone\'s runtime half.',
  'runtime-witness/phantom-agent-offering':
    'The AgentOperationEmitter offers an operation to the model that is NOT a declared, ' +
    'agent-audience-eligible agent consumer in the snapshot — the delivery channel carries a ' +
    'declaration the declarations do not account for. Indicates the emitter and the catalog drifted.',
  'runtime-witness/consistent':
    'The agent offering channel delivers exactly the declared agent-consumable, agent-audience-eligible ' +
    'operations — declared ⟺ delivered. The runtime-witness invariant holds (note level).',
  'runtime-witness/unregistered-kind':
    'A declaration kind appears in the registry snapshot but is absent from the declaration-kinds ' +
    'register (governance/declaration-kinds.v1.json). The keystone projects its coverage from that ' +
    'register, so every kind must be declared there with its witness channel — otherwise a new kind ' +
    'could ship without its consumers being witnessed (tempdoc 560 §5; the 548 §5.2 catalog-projection rule).',
  'runtime-witness/missing-populated-kind':
    'A kind declared staticCatalog=POPULATED in the register produced zero snapshot entries — the catalog ' +
    'wiring or RegistrySnapshotExporter dropped it. Either the kind regressed to empty (fix the catalog) ' +
    'or the register is stale (downgrade it to EMPTY/RUNTIME).',
  'runtime-witness/register-missing':
    'The declaration-kinds register was not found; catalog-projected coverage was skipped. Soft-passes (warning).',
  'runtime-witness/register-unreadable':
    'The declaration-kinds register could not be parsed as JSON. Soft-passes (warning).',
  'runtime-witness/witness-absent':
    'The snapshot has no witness.agentDelivered block — it predates the §5 runtime-witness exporter. ' +
    'Regenerate via RegistrySnapshotExporterTest. Soft-passes (warning).',
  'runtime-witness/snapshot-missing':
    'The in-JVM registry snapshot was not generated before the gate ran. Run :modules:app-services:test ' +
    '(RegistrySnapshotExporterTest) or the RegistrySnapshotExporter main first. Soft-passes (warning).',
  'runtime-witness/snapshot-unreadable':
    'The registry snapshot artifact could not be parsed as JSON. Soft-passes (warning).',
};
