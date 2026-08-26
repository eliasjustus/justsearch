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

  /**
   * Tempdoc 868 §B.2 — the delegate's one content-bearing READ. Search returns budgeted excerpts;
   * this returns a document's extracted text a page at a time over the Worker's
   * {@code FetchDocumentSlice} RPC, so "read this file and summarize it" (the rank-1 recorded
   * intent, 0 clean completions before this) becomes literally executable. LOW risk, no confirm:
   * the readable universe is exactly the INDEXED documents — the Worker serves nothing else, which
   * is a stronger boundary than any path allowlist and needs no new consent posture.
   */
  public static final OperationRef READ_DOCUMENT = new OperationRef("core.read-document");

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
          readDocument(),
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
        // Tempdoc 868 §B.4 (from §A.2): `path_prefix` is DECLARED here because this Interface — not
        // SearchTool.PARAMETER_SCHEMA — is what AgentOperationEmitter projects to the model
        // (AgentOperationEmitter.java:252 reads op.intf().inputs()). SearchTool honours the key
        // (SearchTool.java:222-236) and the system prompt instructs the model to use it, but the
        // in-app delegate could never see it: across 37 recorded runs it was used 0 times. The
        // outward MCP surface (McpToolSurface) already declared it, so external agents had scoping
        // the delegate did not. `mode`/`pipeline` stay undeclared on purpose — they are internal
        // retrieval levers, not a capability the model should be steering.
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},"
                + "\"limit\":{\"type\":\"integer\"},"
                + "\"path_prefix\":{\"type\":\"string\",\"description\":\"Restrict results to files"
                + " under this absolute folder path (get it from core_browse_folders)\"}},"
                + "\"required\":[\"query\"]}",
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

  /**
   * Tempdoc 868 §B.2 — {@code core.read-document}. Same availability expression as {@link
   * #searchIndex()} and for the same reason: both are served by the Worker's index, so both are
   * offered only while the index is serving ({@code Not(ConditionMatches("index.unavailable"))} —
   * absence=healthy, re-shown on recovery). Declares {@code noRetry}, matching the intent that a
   * paged read should not be transparently repeated; note that {@code RetryPolicy} is DECLARATIVE
   * today — {@code OperationPolicy.retry()} has no reader anywhere in the tree, so nothing
   * auto-retries any agent tool and this declaration documents intent rather than enforcing it.
   *
   * <p>Deliberately absent from the outward MCP surface ({@code McpToolSurface}), and the asymmetry
   * is the point: tempdoc 770 §4 withdrew a `fetch` tool there because an external MCP client is a
   * different consumer with its own context budget and its own retrieval loop, and the search/fetch
   * split it would need is the client's to make. The in-app delegate is the consumer whose 4096-token
   * window and inline-confirm lattice this tool was sized and gated for.
   */
  private static Operation readDocument() {
    return new Operation(
        READ_DOCUMENT,
        Presentation.forId(READ_DOCUMENT),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"Absolute path of the document to"
                + " read, as returned by core_browse_folders or a core_search_index result.\"},"
                + "\"offset_chars\":{\"type\":\"integer\",\"description\":\"Character offset to"
                + " start reading from (default 0). Use the offset the previous page's 'More:' line"
                + " gives you to continue.\"},"
                + "\"max_chars\":{\"type\":\"integer\",\"description\":\"Maximum characters to"
                + " return in this page. Capped server-side so one page always fits the context"
                + " window.\"}},"
                + "\"required\":[\"path\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Optional.empty(),
            Set.of(),
            false),
        new OperationAvailability(
            Optional.of(
                new AvailabilityExpression.Not(
                    new AvailabilityExpression.ConditionMatches("index.unavailable"))),
            Optional.empty()),
        OperationLineage.empty(),
        Binding.of(READ_DOCUMENT),
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
        // Tempdoc 655: list_files added so this declared schema matches the MCP-visible schema
        // for justsearch_browse (McpToolSurface) — the two were independently authored and had
        // drifted (list_files was accepted by MCP callers but never declared here). The
        // underlying BrowseOperationHandler does not yet read list_files (auto-detect only,
        // logged separately as a pre-existing gap) — declaring it here keeps the two schemas in
        // sync without changing today's runtime behavior.
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"parent_path\":{\"type\":\"string\"},"
                + "\"list_files\":{\"type\":\"boolean\"}}}",
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
            // Tempdoc 811 (C-2a): `collection` is an OPTIONAL tag. Omitted → the containing indexed
            // root's collection, or `mcp-ingest` for out-of-root paths. Reserved app-internal names
            // are rejected server-side in IngestTool, not by this schema.
            "{\"type\":\"object\",\"properties\":{\"paths\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}},\"collection\":{\"type\":\"string\"}},"
                + "\"required\":[\"paths\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
                RiskTier.MEDIUM,
                ConfirmStrategy.Inline.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Optional.empty(),
                Set.of(),
                false)
            // Tempdoc 560 §28 (4d): a coherent capability family. A durable allow-always grant for
            // "file-operations" auto-approves THIS (MEDIUM) member. Tempdoc 875 C.2: it does NOT
            // auto-approve the HIGH member `core.file-operations` — DurableGrantStore.isAllowed
            // refuses HIGH outright, so destructive work always costs a fresh, args-bound gesture.
            // Tempdoc 875 C.3: and even for this member the grant only covers invocations whose
            // `paths` canonicalize inside an indexed root (IndexedRootGrantScope); an out-of-root
            // ingest still runs, it just costs an approval that names the path (811 C-2a preserved).
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
            // Tempdoc 560 §28 (4d): same "file-operations" family as ingest (a HIGH-risk member) —
            // the family axis is real, the membership stands. Tempdoc 875 C.2: but NO durable grant
            // (family OR per-operation) can satisfy this operation's gate, because
            // DurableGrantStore.isAllowed refuses RiskTier.HIGH before consulting either grant set.
            // The family membership is therefore about coherence and revocation scope, not about
            // blanket destructive approval — which the product never asked for.
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
