/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations;

import io.justsearch.agent.api.registry.AliasRegistry;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.CatalogMatcher;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog of agent-tool Operations: mirrors the existing {@code SearchTool},
 * {@code BrowseTool}, {@code IngestTool}, {@code FileOperationsTool} as
 * Operation declarations per tempdoc 429 §"Migration" item 12 + §F.11 closure.
 *
 * <p>Bit-for-bit-preserves the existing {@code SafetyLevel → RiskTier} mapping
 * per §A.2:
 *
 * <ul>
 *   <li>READ_ONLY → LOW (search-index, browse-folders): {@link ConfirmStrategy.None},
 *       {@link AuditPolicy#NONE}, auto-retry idempotent
 *   <li>WRITE → MEDIUM (ingest-files): {@link ConfirmStrategy.Inline},
 *       {@link AuditPolicy#METADATA_ONLY}, no auto-retry
 *   <li>DESTRUCTIVE → HIGH (file-operations): {@link ConfirmStrategy.Inline},
 *       {@link AuditPolicy#METADATA_ONLY}, no auto-retry, undoSupported=true
 * </ul>
 *
 * <p>All four entries declare {@code executors = {AGENT}} (matches current
 * agent-only behavior); UI exposure is a future slice's concern.
 *
 * <p>Namespace is {@code "core"} (these are core JustSearch tools, not plugins).
 * Both {@link CoreOperationCatalog} and this catalog share the namespace; entry
 * IDs are disjoint.
 */
public final class AgentToolsOperationCatalog implements OperationCatalog {

  public static final String NAMESPACE = "core";

  private final AliasRegistry aliasRegistry;
  private final CatalogMatcher catalogMatcher;

  public AgentToolsOperationCatalog() {
    this(AliasRegistry.empty(), CatalogMatcher.defaultMatcher());
  }

  public AgentToolsOperationCatalog(AliasRegistry aliasRegistry, CatalogMatcher catalogMatcher) {
    this.aliasRegistry = aliasRegistry;
    this.catalogMatcher = catalogMatcher;
  }

  @Override
  public AliasRegistry aliasRegistry() {
    return aliasRegistry;
  }

  @Override
  public CatalogMatcher matcher() {
    return catalogMatcher;
  }

  public static final OperationRef SEARCH_INDEX = new OperationRef("core.search-index");
  public static final OperationRef BROWSE_FOLDERS = new OperationRef("core.browse-folders");
  public static final OperationRef INGEST_FILES = new OperationRef("core.ingest-files");
  public static final OperationRef FILE_OPERATIONS = new OperationRef("core.file-operations");

  /**
   * Tempdoc 561 P-E — the agent's learning producer. When the model learns a durable fact or user
   * preference, it calls {@code core_remember} to persist it to the single-authority memory record
   * (inspectable + forgettable via the Memory surface / {@code /api/memory}). LOW risk: a benign
   * local note, not a file/index mutation — read-only-ish; no confirmation, no audit.
   */
  public static final OperationRef REMEMBER = new OperationRef("core.remember");

  /**
   * Slice 491 §9.D Phase E (E3 + E17 probe fix): navigation tool in the agent's palette.
   * Re-declares the same OperationRef as {@link CoreOperationCatalog#NAVIGATE_TO_SURFACE};
   * the handler is registered in HeadAssembly after backendIntentRouter is initialized.
   * The LLM sees this as {@code core_navigate_to_surface} (wire-name transliteration).
   */
  public static final OperationRef NAVIGATE_TO_SURFACE =
      new OperationRef("core.navigate-to-surface");

  private final List<Operation> definitions =
      List.of(
          searchIndex(),
          browseFolders(),
          ingestFiles(),
          fileOperations(),
          navigateToSurface(),
          remember());

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Operation> definitions() {
    return definitions;
  }

  private static Operation searchIndex() {
    return new Operation(
        SEARCH_INDEX,
        Presentation.forId(SEARCH_INDEX),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},"
                + "\"limit\":{\"type\":\"integer\"}},\"required\":[\"query\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.autoRetry(2, "core.search-index"),
            Optional.empty(),
            Set.of(),
            false),
        // Tempdoc 550 Preview face (F3): the first real producer of availability. Search is
        // offered to the agent only when the index is serving. The health model is
        // absence=healthy: "index.unavailable" fires ONLY when INDEX_SERVING is unhealthy
        // (LifecycleSnapshotTap) and is reliably cleared when it returns to READY (reconcileDim
        // → clearPrior). So Not(ConditionMatches("index.unavailable")) = "available unless the
        // index is not serving" — shown when ready, hidden when down, re-shown on recovery. The
        // emitter (AgentOperationEmitter) and the preview endpoint both evaluate this live.
        new OperationAvailability(
            Optional.of(
                new AvailabilityExpression.Not(
                    new AvailabilityExpression.ConditionMatches("index.unavailable"))),
            Optional.empty()),
        OperationLineage.empty(),
        Binding.of(SEARCH_INDEX),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation remember() {
    return new Operation(
        REMEMBER,
        new Presentation(
            new I18nKey("ops.remember.label"),
            new I18nKey("ops.remember.description"),
            Optional.empty(),
            Optional.empty()),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{"
                + "\"content\":{\"type\":\"string\",\"description\":\"The durable fact or user"
                + " preference to remember, in one concise sentence.\"},"
                + "\"kind\":{\"type\":\"string\",\"description\":\"Optional category, e.g."
                + " \\\"fact\\\" or \\\"preference\\\".\"}},\"required\":[\"content\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(REMEMBER),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation browseFolders() {
    return new Operation(
        BROWSE_FOLDERS,
        Presentation.forId(BROWSE_FOLDERS),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"parent_path\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.autoRetry(2, "core.browse-folders"),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(BROWSE_FOLDERS),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation ingestFiles() {
    return new Operation(
        INGEST_FILES,
        Presentation.forId(INGEST_FILES),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"paths\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}}},\"required\":[\"paths\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
                RiskTier.MEDIUM,
                ConfirmStrategy.Inline.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Optional.empty(),
                Set.of(),
                false)
            // Tempdoc 560 §28 (4d): a coherent capability family — a durable allow-always grant for
            // "file-operations" auto-approves both the ingest and the file-mutation tools at once.
            .withCapabilityFamily("file-operations"),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(INGEST_FILES),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation fileOperations() {
    return new Operation(
        FILE_OPERATIONS,
        Presentation.forId(FILE_OPERATIONS, Optional.of("warning"), Optional.of("destructive")),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"operations\":{\"type\":\"array\"}},"
                + "\"required\":[\"operations\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.HIGH,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
                true,
                Optional.of(new io.justsearch.agent.api.registry.ResourceRef(
                    "core.advisory-operation-completed")))
            // Tempdoc 560 §28 (4d): same "file-operations" family as ingest (a HIGH-risk member).
            .withCapabilityFamily("file-operations"),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(FILE_OPERATIONS),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  /**
   * Slice 491 §9.D Phase E (E3 + E17): agent navigation tool. LOW risk (navigation is
   * presentation-layer); no confirm.
   *
   * <p>Tempdoc 560 WS4 (catalog collapse): this is now the <em>single canonical</em>
   * {@code core.navigate-to-surface} declaration. The duplicate that previously lived in
   * {@link CoreOperationCatalog} (executors {@code {UI, AGENT}}, audience {@code USER}) was
   * removed so core + agent-tools can install into the one {@link
   * io.justsearch.agent.api.registry.ContributionRegistry} without a ref collision. This entry
   * carries the <em>superset</em> executor set {@code {UI, AGENT}} + {@code USER} audience, so
   * the UI registry path (UIOperationEmitter filters {@code UI}) emits a byte-identical wire
   * entry while the agent loop (filters {@code AGENT}) still sees it. The handler is the same
   * {@code NavigateToSurfaceHandler} registered in HeadAssembly.
   */
  private static Operation navigateToSurface() {
    return new Operation(
        NAVIGATE_TO_SURFACE,
        Presentation.forId(NAVIGATE_TO_SURFACE),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"surfaceId\":{\"type\":\"string\"}},"
                + "\"required\":[\"surfaceId\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(NAVIGATE_TO_SURFACE),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT),
        Audience.USER);
  }
}
