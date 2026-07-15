---
classification: new-rule-registered
tempdoc: 737
---
Introduces the `runtime-state` gate and `governance/runtime-state.v1.json` (tempdoc 737 §12c, the
Phase 1 "governance-register chunk"). §8d diagnosed five independent, zero-derivation vocabularies
answering "what is the AI runtime doing" (Mode enum, the capability algebra, the wire phase/starting
fields, FE aiVerdict/aiStateStore re-derivation, the agent-palette hiding). The design's fork-killer
(§12c) is a `runtime-state` register in the `execution-surfaces` pattern: canonical authority
(RuntimeStatus) + sibling canonical records (RuntimeSpec, RuntimeGpuLease, RuntimeReconciler) +
every registered projection/consumer, with an unregistered new referencer of the runtimestate
package failing the build.

Registered at genesis: the 4 canonical/sibling record sources, 1 carrier (RuntimeSpecStore), 6
consumers (the current bootstrap/wiring importers — RuntimeActivationService, InferenceWiring,
OrchestrationAssembly, OrchestrationPhase, ServicePhase, HeadAssembly), and 5 `projection-pending`
placeholders (Mode, InferenceRuntimeView, BootstrapProjections, InferenceCapability,
InferenceCapabilityWiring) for the pre-migration vocabulary §12c names as Phase-2 migration targets
— registered now so their eventual collapse into RuntimeStatus is a governed row edit, not a silent
fork. The FE projections (aiVerdict.ts/aiStateStore.ts) are out of scope: already governed by the
existing `scripts/ci/check-ai-verdict-derivation.mjs` gate (tempdoc 663), per R7's finding that the
FE single-authority gates already exist.

Also adds `governance/runtime-state.v1.json` to `register-guard-resolution`'s `config.registers[]`
with `requireGuardedKinds: ["projection", "producer"]`, mirroring `execution-surfaces.v1.json`'s
entry exactly — safe because the register's one `producer`-kind row (RuntimeReconciler) already
carries a real `test:` guard (the caller-forcing `RuntimeReconcilerGuardrailsTest`), and there are no
bare `projection`-kind rows (the pre-migration placeholders are `projection-pending`, an exempt
kind).
