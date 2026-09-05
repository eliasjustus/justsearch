package io.justsearch.app.services.registry.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.ExecutorTag;
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
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.snapshot.LiveWitness.Orphan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The live-registry witness (ADR-0042 / tempdoc 560 §4b/§5). Composes the live {@link
 * ContributionRegistry} exactly as {@code SubstratePhase} does (static base catalogs + the
 * runtime-projected workflow ops), then asserts {@link LiveWitness#orphanedDeliveries} over it. This is
 * the only tier that sees what a RUNNING install connects.
 *
 * <p>Tempdoc 876 B.6 narrowed what "blind" means here. The static snapshot now composes what
 * production composes, so the projected {@code core.workflow-*} ops it used to miss are covered by
 * the build tier — they are derivable from a compile-time workflow catalog. What remains structurally
 * invisible to every static tier (DR-A) is what only a running process learns: MCP tools and plugin
 * contributions arriving via {@code ContributionRegistry.install(Installation)}. A contribution of
 * that kind with no consumer is caught here and nowhere else.
 */
class LiveWitnessTest {

  /** Compose the live registry as SubstratePhase does: static base catalogs + runtime workflow ops. */
  private static ContributionRegistry composed() {
    ContributionRegistry registry = new ContributionRegistry();
    OperationCatalogComposition.installBaseCatalogs(
        registry, new CoreOperationCatalog(), new AgentToolsOperationCatalog());
    // Tempdoc 876 B.4: the projection resolves ToolStep refs against the composed registry, so the
    // already-installed operations are the known set (no MCP host here — demo-compose drops out).
    OperationCatalogComposition.installWorkflowOps(
        registry,
        WorkflowOperationProjection.project(CoreWorkflowCatalog.catalog(), registry.operations()));
    return registry;
  }

  @Test
  void liveRegistryCoversRuntimeComposedOpsTheStaticSnapshotMisses() {
    // Tempdoc 876 B.6 MOVED the coverage boundary this test pins, so the exemplar changed while the
    // invariant did not. Before 876 the static snapshot ran a bare emitter over the two static base
    // catalogs, so the projected core.workflow-* ops were the readiest instance of the DR-D blind
    // spot. RegistrySnapshotExporter now builds from the SAME composition production uses, which
    // deliberately pulls those ops into the static tier (they are build-derivable: the workflow
    // catalog is a compile-time constant). What no BUILD can derive is what a running install
    // actually connects — MCP tools and plugin contributions, which arrive through
    // ContributionRegistry.install(Installation). That is the surviving blind spot, and it is what
    // this test must witness if it is to keep meaning anything.
    ContributionRegistry live = composed();
    Set<String> staticOpIds =
        RegistrySnapshotExporter.buildOperationEntries().stream()
            .map(RegistrySnapshotExporter.Entry::id)
            .collect(Collectors.toSet());

    // (a) The 876 B.6 win, pinned so it cannot silently regress: projected workflow ops now ARE in
    // the static snapshot. If the exporter ever reverts to the static base catalogs, this fails.
    List<String> liveWorkflowOps =
        live.operations().stream()
            .map(o -> o.id().value())
            .filter(id -> id.startsWith(WorkflowOperationProjection.OP_PREFIX))
            .toList();
    assertFalse(
        liveWorkflowOps.isEmpty(),
        "expected runtime-composed core.workflow-* ops in the live registry");
    for (String id : liveWorkflowOps) {
      assertTrue(
          staticOpIds.contains(id),
          "tempdoc 876 B.6: the static snapshot composes what production composes, so projected op "
              + id
              + " must be present in it. Its absence means buildOperationEntries() regressed to the"
              + " static base catalogs, which is the drift 876 closed.");
    }

    // (b) The surviving DR-D blind spot: an op installed the way MCP tools and plugins install is
    // in the live registry and cannot be in any static snapshot.
    OperationRef runtimeRef = new OperationRef("vendor.runtime-only.op");
    live.install(
        new ContributionRegistry.Installation(
            runtimeSourcePlugin("vendor.runtime-only.source"), List.of(runtimeOp(runtimeRef)), Map.of()));
    assertTrue(
        live.operations().stream().anyMatch(o -> o.id().equals(runtimeRef)),
        "the runtime-installed op must be in the live registry");
    // Recomputed AFTER the install, deliberately: asserting against the snapshot captured above
    // would be a tautology (the ref is a literal minted in this method, so no production change
    // could put it there). Re-deriving proves the structural property — a build-time composition
    // cannot see a contribution that only a running process installed.
    Set<String> staticOpIdsAfterInstall =
        RegistrySnapshotExporter.buildOperationEntries().stream()
            .map(RegistrySnapshotExporter.Entry::id)
            .collect(Collectors.toSet());
    assertFalse(
        staticOpIdsAfterInstall.contains(runtimeRef.value()),
        "runtime-installed op "
            + runtimeRef.value()
            + " must be absent from the static snapshot even when it is recomputed while the op is"
            + " live (the DR-D blind spot the live-registry witness covers — no build can know what"
            + " a running install connects)");
  }

  /** An Operation with one executor, so it derives a consumer and is not itself an orphan. */
  private static Operation runtimeOp(OperationRef ref) {
    return runtimeOp(ref, Set.of(ExecutorTag.AGENT), List.of());
  }

  /** An Operation with an explicit executor set and inline consumer hooks (the two merge inputs). */
  private static Operation runtimeOp(
      OperationRef ref, Set<ExecutorTag> executors, List<ConsumerHook> consumers) {
    return new Operation(
        ref,
        Presentation.of(
            new I18nKey("op." + ref.value() + ".label"),
            new I18nKey("op." + ref.value() + ".description")),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ref),
        Provenance.core("1.0"),
        executors,
        Audience.OPERATOR,
        consumers);
  }

  private static Operation find(ContributionRegistry live, OperationRef ref) {
    return live.operations().stream()
        .filter(o -> o.id().equals(ref))
        .findFirst()
        .orElseThrow(() -> new AssertionError("fixture op not installed: " + ref.value()));
  }

  /**
   * ADR-0042's no-fork property: this witness REUSES the build-tier consumer merge ({@link
   * RegistrySnapshotExporter#operationConsumerIds}) rather than re-deriving "has a consumer" over the
   * live registry. That is the one thing the rest of this class does not imply — a forked merge would
   * still pass every orphan case above. The retired {@code check-live-witness.mjs} asserted it by
   * scraping the source for the symbol (tempdoc 930 dropped that register + script); it is asserted
   * behaviourally here instead, over a fixture that exercises BOTH merge inputs. A fork reading only
   * inline hooks disagrees on the executor-only row; one reading only executors disagrees on the
   * inline-only row.
   */
  @Test
  void witnessReusesTheBuildTierConsumerMergeForOperations() {
    ContributionRegistry live = composed();
    OperationRef executorOnly = new OperationRef("vendor.merge.executor-only");
    OperationRef inlineOnly = new OperationRef("vendor.merge.inline-only");
    OperationRef neither = new OperationRef("vendor.merge.neither");
    live.install(
        new ContributionRegistry.Installation(
            runtimeSourcePlugin("vendor.merge-fixture.source"),
            List.of(
                runtimeOp(executorOnly, Set.of(ExecutorTag.AGENT), List.of()),
                runtimeOp(
                    inlineOnly,
                    Set.of(),
                    List.of(new ConsumerHook.Realized("inline-consumer", Audience.OPERATOR))),
                runtimeOp(neither, Set.of(), List.of())),
            Map.of()));

    // The fixture must actually exercise both merge inputs, or the agreement below is vacuous.
    assertTrue(
        find(live, executorOnly).consumers().isEmpty()
            && !RegistrySnapshotExporter.operationConsumerIds(find(live, executorOnly)).isEmpty(),
        "executor-only row must get its consumer from executor derivation alone");
    assertTrue(
        find(live, inlineOnly).executors().isEmpty()
            && !RegistrySnapshotExporter.operationConsumerIds(find(live, inlineOnly)).isEmpty(),
        "inline-only row must get its consumer from the inline hook alone");
    assertTrue(
        RegistrySnapshotExporter.operationConsumerIds(find(live, neither)).isEmpty(),
        "the zero-executor, zero-hook row must merge to no consumers");

    Set<String> perBuildTierMerge =
        live.operations().stream()
            .filter(o -> RegistrySnapshotExporter.operationConsumerIds(o).isEmpty())
            .map(o -> o.id().value())
            .collect(Collectors.toSet());
    Set<String> perWitness =
        LiveWitness.orphanedDeliveries(live).stream()
            .filter(o -> "operation".equals(o.kind()))
            .map(Orphan::id)
            .collect(Collectors.toSet());
    assertEquals(
        perBuildTierMerge,
        perWitness,
        "the live witness must classify every delivered operation exactly as the build-tier merge"
            + " does — a divergence means the consumer-presence notion was forked");
    assertEquals(
        Set.of(neither.value()),
        perWitness,
        "fixture guard: only the zero-executor, zero-hook row is an orphan, so the agreement above"
            + " spans all three merge outcomes rather than an all-empty set");
  }

  /** The installing plugin, carrying a realized consumer hook so the installation is not orphaned. */
  private static Plugin runtimeSourcePlugin(String id) {
    PluginRef owner = new PluginRef(id);
    return new Plugin(
        owner,
        Presentation.of(
            new I18nKey("plugin." + owner.value() + ".label"),
            new I18nKey("plugin." + owner.value() + ".description")),
        Provenance.core("1.0"),
        Audience.OPERATOR,
        PluginContributions.empty(),
        List.of(new ConsumerHook.Realized(owner.value(), Audience.OPERATOR)));
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
