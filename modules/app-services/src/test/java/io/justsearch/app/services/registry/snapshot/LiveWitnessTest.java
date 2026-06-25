package io.justsearch.app.services.registry.snapshot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.Plugin;
import io.justsearch.agent.api.registry.PluginContributions;
import io.justsearch.agent.api.registry.PluginRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.services.bootstrap.phases.OperationCatalogComposition;
import io.justsearch.app.services.conversation.CoreWorkflowCatalog;
import io.justsearch.app.services.conversation.WorkflowOperationProjection;
import io.justsearch.app.services.registry.operations.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.snapshot.LiveWitness.Orphan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The live-registry witness (ADR-0042 / tempdoc 560 §4b/§5). Composes the live {@link
 * ContributionRegistry} exactly as {@code SubstratePhase} does (static base catalogs + the
 * runtime-projected workflow ops), then asserts {@link LiveWitness#orphanedDeliveries} over it. This is
 * the only tier that sees runtime composition — both the static {@code consumer-presence} snapshot and
 * the static {@code runtime-witness} (AGENT-channel) gate are structurally blind to it (DR-A), so a
 * runtime-composed contribution with no consumer is invisible to them but caught here.
 */
class LiveWitnessTest {

  /** Compose the live registry as SubstratePhase does: static base catalogs + runtime workflow ops. */
  private static ContributionRegistry composed() {
    ContributionRegistry registry = new ContributionRegistry();
    OperationCatalogComposition.installBaseCatalogs(
        registry, new CoreOperationCatalog(), new AgentToolsOperationCatalog());
    OperationCatalogComposition.installWorkflowOps(
        registry, WorkflowOperationProjection.project(CoreWorkflowCatalog.catalog()));
    return registry;
  }

  @Test
  void liveRegistryCoversRuntimeComposedOpsTheStaticSnapshotMisses() {
    ContributionRegistry live = composed();
    // A projected workflow op (core.workflow-*) is composed at RUNTIME and present in the live registry...
    List<String> liveWorkflowOps =
        live.operations().stream()
            .map(o -> o.id().value())
            .filter(id -> id.startsWith(WorkflowOperationProjection.OP_PREFIX))
            .toList();
    assertFalse(
        liveWorkflowOps.isEmpty(),
        "expected runtime-composed core.workflow-* ops in the live registry");
    // ...but ABSENT from the static snapshot the build-tier consumer-presence gate reads (the DR-D gap).
    Set<String> staticOpIds =
        RegistrySnapshotExporter.buildOperationEntries().stream()
            .map(RegistrySnapshotExporter.Entry::id)
            .collect(Collectors.toSet());
    for (String id : liveWorkflowOps) {
      assertFalse(
          staticOpIds.contains(id),
          "runtime-composed op "
              + id
              + " must be absent from the static snapshot (the DR-D blind spot the live-registry witness covers)");
    }
  }

  @Test
  void cleanComposedRegistryHasNoOrphanedDeliveries() {
    List<Orphan> orphans = LiveWitness.orphanedDeliveries(composed());
    assertTrue(
        orphans.isEmpty(),
        "Delivered contributions with zero consumers (live consumer-presence violation): " + orphans);
  }

  @Test
  void runtimeComposedZeroConsumerOpIsFlaggedAsOrphan() {
    ContributionRegistry live = composed();
    // A runtime-composed op with NO executor and NO inline consumer — a genuine orphan. (An op with any
    // executor derives a consumer, so it would NOT be an orphan; the executor-derivation is reused from
    // the static snapshot verbatim, so this witness cannot over-report.) Installed via the same
    // install(Installation) path MCP tools / plugins use, so it is absent from the static snapshot.
    OperationRef orphanRef = new OperationRef("core.runtime-orphan-op");
    Operation orphanOp =
        new Operation(
            orphanRef,
            Presentation.of(new I18nKey("op.orphan.label"), new I18nKey("op.orphan.description")),
            Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Optional.empty(),
                Set.of(),
                false),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(orphanRef),
            Provenance.core("1.0"),
            Set.of()); // zero executors → backward-compat ctor defaults consumers to empty
    PluginRef owner = new PluginRef("core.runtime-source");
    Plugin ownerPlugin =
        new Plugin(
            owner,
            Presentation.of(
                new I18nKey("plugin." + owner.value() + ".label"),
                new I18nKey("plugin." + owner.value() + ".description")),
            Provenance.core("1.0"),
            Audience.OPERATOR,
            PluginContributions.empty(),
            List.of(new ConsumerHook.Realized(owner.value(), Audience.OPERATOR)));
    live.install(new ContributionRegistry.Installation(ownerPlugin, List.of(orphanOp), Map.of()));

    List<Orphan> orphans = LiveWitness.orphanedDeliveries(live);
    assertTrue(
        orphans.stream()
            .anyMatch(o -> o.id().equals("core.runtime-orphan-op") && o.kind().equals("operation")),
        "the runtime-composed zero-consumer op must be flagged as an orphaned delivery; got " + orphans);
    // The clean composition has no orphans, so the planted op is the ONLY one — the witness neither
    // misses it nor over-reports the well-formed runtime-composed ops (e.g. the core.workflow-* ops).
    assertTrue(
        orphans.stream().allMatch(o -> o.id().equals("core.runtime-orphan-op")),
        "the witness must flag ONLY the planted orphan, not well-formed deliveries; got " + orphans);
  }

  @Test
  void nullRegistryYieldsEmptyWitness() {
    assertTrue(LiveWitness.orphanedDeliveries(null).isEmpty());
  }
}
