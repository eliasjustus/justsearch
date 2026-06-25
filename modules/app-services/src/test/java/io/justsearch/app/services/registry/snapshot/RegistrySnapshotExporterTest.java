package io.justsearch.app.services.registry.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.services.registry.snapshot.RegistrySnapshotExporter.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The in-JVM half of the NonEmpty&lt;ConsumerHook&gt; keystone (tempdoc 560 §5/§6): asserts every
 * static Operation declaration has ≥1 merged consumer, and emits the snapshot the Node {@code
 * consumer-presence} gate reads. Running this test (part of {@code :modules:app-services:test})
 * regenerates the snapshot, so it is always fresh for the gate.
 */
class RegistrySnapshotExporterTest {

  @Test
  void everyStaticOperationHasAtLeastOneConsumer() {
    List<Entry> entries = RegistrySnapshotExporter.buildOperationEntries();
    assertFalse(entries.isEmpty(), "expected core + agent-tools operations");
    List<String> orphaned =
        entries.stream().filter(e -> e.consumerCount() == 0).map(Entry::id).toList();
    assertTrue(
        orphaned.isEmpty(),
        "Operations with zero consumers (NonEmpty<ConsumerHook> violation): " + orphaned);
  }

  @Test
  void agentExecutorOperationsAreWitnessedByTheAgentLoop() {
    long withAgentLoop =
        RegistrySnapshotExporter.buildOperationEntries().stream()
            .filter(e -> e.executors().contains("AGENT"))
            .filter(e -> e.consumers().contains("agent-loop"))
            .count();
    assertTrue(withAgentLoop > 0, "AGENT-executor ops must resolve to the agent-loop consumer");
  }

  @Test
  void runtimeWitness_agentOfferingChannelDeliversExactlyTheDeclaredAgentConsumableOps() {
    // §5 runtime-witness (AGENT = prompt-construction): the agent's tool list (the real
    // AgentOperationEmitter output) must deliver EXACTLY the operations that (a) declare an
    // "agent-loop" consumer and (b) are agent-audience-eligible (USER/AGENT). This is the
    // bidirectional consistency check between the declarations and the actual delivery channel:
    //  - an op offered but not declared-agent-consumable would be a phantom offering;
    //  - an op declared-agent-consumable + eligible but NOT offered would be an over-claim
    //    (the static gate sees a consumer the prompt-construction channel never delivers).
    // OPERATOR-audience ops with ExecutorTag.AGENT (e.g. bulk-reindex) are execution-consumed by the
    // agent loop but correctly NOT offered — so they are excluded from both sides and do not appear,
    // which is the truthful result. (Tempdoc 598 B-2: core.rebuild-index is now USER + UI-only — no
    // AGENT executor and no agent-loop consumer — so it is excluded from both sides too.)
    java.util.Set<String> delivered = RegistrySnapshotExporter.agentWitnessDeliveredIds();
    java.util.Set<String> declaredAgentEligible =
        RegistrySnapshotExporter.buildOperationEntries().stream()
            .filter(e -> e.consumers().contains("agent-loop"))
            .filter(e -> e.audience().equals("USER") || e.audience().equals("AGENT"))
            .map(Entry::id)
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    org.junit.jupiter.api.Assertions.assertEquals(
        declaredAgentEligible,
        delivered,
        "Agent offering channel must deliver exactly the declared agent-consumable, "
            + "agent-audience-eligible operations (runtime-witness consistency)");
  }

  @Test
  void snapshotCoversOperationResourcePromptAndManifestAxes() {
    List<Entry> all = RegistrySnapshotExporter.buildEntries();
    assertTrue(all.stream().anyMatch(e -> e.kind().equals("operation")), "operation axis");
    assertTrue(all.stream().anyMatch(e -> e.kind().equals("resource")), "resource axis must be covered");
    // Tempdoc 560 Fix A: the keystone gate now also polices the Manifest types the substrate added —
    // the core workflow catalog ships the demo workflow, so the workflow axis is non-empty.
    assertTrue(all.stream().anyMatch(e -> e.kind().equals("workflow")), "workflow axis must be covered");
    // Prompt + Plugin axes: empty static core catalogs today (0 rows expected); structurally covered,
    // so a future unconsumed prompt/plugin would fail the gate.
    assertFalse(all.stream().anyMatch(e -> e.kind().equals("prompt")));
  }

  @Test
  void everyStaticWorkflowHasAtLeastOneConsumer() {
    List<Entry> entries = RegistrySnapshotExporter.buildWorkflowEntries();
    assertFalse(entries.isEmpty(), "expected the core workflow catalog (demo-compose)");
    List<String> orphaned =
        entries.stream().filter(e -> e.consumerCount() == 0).map(Entry::id).toList();
    assertTrue(
        orphaned.isEmpty(),
        "Workflows with zero consumers (NonEmpty<ConsumerHook> violation): " + orphaned);
  }

  @Test
  void resourcesComputeDeclaredConsumers() {
    // Fix D: consumers are inline ConsumerHooks ∪ surface-derived — NOT inferred from an SSE endpoint.
    // indexing-jobs + server-capabilities now declare their real FE-bridge consumers inline.
    List<Entry> resources = RegistrySnapshotExporter.buildResourceEntries();
    assertFalse(resources.isEmpty(), "expected core resources");
    assertTrue(
        resources.stream()
            .anyMatch(e -> e.id().equals("core.indexing-jobs") && e.consumerCount() > 0),
        "core.indexing-jobs must declare its real consumer");
    // core.runtime-context has no live subscriber → 0 consumers (honestly frozen-at-reserved in exemptions).
    assertTrue(
        resources.stream()
            .anyMatch(e -> e.id().equals("core.runtime-context") && e.consumerCount() == 0),
        "core.runtime-context must show 0 consumers (no subscriber), not a fabricated one");
  }

  @Test
  void writesSnapshotForTheGate() throws IOException {
    Path out = RegistrySnapshotExporter.writeDefault();
    assertTrue(Files.isRegularFile(out), "snapshot not written at " + out);
    String json = Files.readString(out);
    assertTrue(json.contains("\"entries\""), "snapshot missing entries array");
    assertTrue(json.contains("\"consumerCount\""), "snapshot missing consumerCount");
  }
}
