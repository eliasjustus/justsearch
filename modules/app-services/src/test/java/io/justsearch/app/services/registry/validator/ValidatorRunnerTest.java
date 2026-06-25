package io.justsearch.app.services.registry.validator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.Severity;
import io.justsearch.app.services.registry.operations.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.BulkReindexHandler;
import io.justsearch.app.services.registry.operations.handlers.ClearFailedJobsHandler;
import io.justsearch.app.services.registry.operations.handlers.PingBackendHandler;
import io.justsearch.app.services.registry.operations.handlers.RestartWorkerHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Parameterized harness over the six {@link RegistryShapeValidator} instances per
 * tempdoc 429 §A.7 (revision-5 trim from 10 to 6) + §"Acceptance criteria".
 *
 * <p>Constructs the same {@link CoreOperationCatalog} + {@link HandlerRegistry} pairing
 * that {@code HeadAssembly.Phase 4} does at boot time, plus the loaded
 * {@code registry-operation.en.properties} keys, then runs each validator and asserts
 * no ERROR findings.
 */
final class ValidatorRunnerTest {

  private static ValidationContext context;

  private static ValidationContext agentToolsContext;

  @BeforeAll
  static void loadFixture() {
    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(CoreOperationCatalog.RESTART_WORKER, new RestartWorkerHandler());
    // Slice 429 follow-up: BulkReindex now requires an IndexingService supplier.
    // For validator tests (structural checks of the catalog), pass an unavailable
    // service — the validator doesn't invoke handlers.
    handlers.register(
        CoreOperationCatalog.BULK_REINDEX,
        new BulkReindexHandler(
            io.justsearch.app.api.IndexingService::unavailable,
            io.justsearch.app.api.OperationLeaseService.noOp()));
    // Slice 447-followup §X.11.5 Phase 7: rebuild-index parameterless wrapper handler.
    handlers.register(
        CoreOperationCatalog.REBUILD_INDEX,
        new io.justsearch.app.services.registry.operations.handlers.RebuildIndexHandler(
            io.justsearch.app.api.IndexingService::unavailable,
            io.justsearch.app.api.OperationLeaseService.noOp()));
    handlers.register(CoreOperationCatalog.PING_BACKEND, new PingBackendHandler());
    // Slice 3a-2-c precondition: ClearFailedJobs handler. Same supplier pattern as
    // BulkReindex.
    handlers.register(
        CoreOperationCatalog.CLEAR_FAILED_JOBS,
        new ClearFailedJobsHandler(io.justsearch.app.api.IndexingService::unavailable));
    // Slice 3a-1-2 closure: core.reindex registration. Same supplier pattern.
    handlers.register(
        CoreOperationCatalog.REINDEX,
        new io.justsearch.app.services.registry.operations.handlers.ReindexHandler(
            io.justsearch.app.api.IndexingService::unavailable));
    // Tempdoc 626 §Recency added core.reconcile-root to CoreOperationCatalog; register its handler so
    // ExecutorBindingValidator resolves the binding (the validator does not invoke handlers). Mirrors
    // the production wiring in OperationHandlerRegistrations.
    handlers.register(
        CoreOperationCatalog.RECONCILE_ROOT,
        new io.justsearch.app.services.registry.operations.handlers.ReconcileRootHandler(
            io.justsearch.app.api.IndexingService::unavailable));
    // Slice 3a-1-2 closure: core.export-diagnostics registration.
    handlers.register(
        CoreOperationCatalog.EXPORT_DIAGNOSTICS,
        new io.justsearch.app.services.registry.operations.handlers.ExportDiagnosticsHandler(
            () -> null));
    // Tempdoc 561 P-E added core.remember to AgentToolsOperationCatalog; register its handler so the
    // ExecutorBindingValidator resolves it (the no-op MemoryStore suffices — the validator does not
    // invoke handlers). Absorbed via the tempdoc-554 merge of main.
    handlers.register(
        io.justsearch.app.services.registry.operations.AgentToolsOperationCatalog.REMEMBER,
        new io.justsearch.app.services.registry.operations.handlers.RememberFactHandler(
            io.justsearch.agent.api.memory.MemoryStore.noop()));
    // Slice 3a-2-c LibraryView Add/Remove cluster: core.add-watched-root +
    // core.remove-watched-root registration. Same lazy supplier pattern.
    handlers.register(
        CoreOperationCatalog.ADD_WATCHED_ROOT,
        new io.justsearch.app.services.registry.operations.handlers.AddWatchedRootHandler(
            io.justsearch.app.api.IndexingService::unavailable));
    handlers.register(
        CoreOperationCatalog.REMOVE_WATCHED_ROOT,
        new io.justsearch.app.services.registry.operations.handlers.RemoveWatchedRootHandler(
            io.justsearch.app.api.IndexingService::unavailable));
    // Slice 3a-2-c LibraryView Preview/Apply Excludes cluster.
    handlers.register(
        CoreOperationCatalog.PREVIEW_EXCLUDES,
        new io.justsearch.app.services.registry.operations.handlers.PreviewExcludesHandler(
            () -> null));
    handlers.register(
        CoreOperationCatalog.APPLY_EXCLUDES,
        new io.justsearch.app.services.registry.operations.handlers.ApplyExcludesHandler(
            () -> null));
    // Slice 3a-2-c BrainRuntime cluster (reload-inference + switch-inference-mode).
    handlers.register(
        CoreOperationCatalog.RELOAD_INFERENCE,
        new io.justsearch.app.services.registry.operations.handlers.ReloadInferenceHandler(
            () -> null));
    handlers.register(
        CoreOperationCatalog.SWITCH_INFERENCE_MODE,
        new io.justsearch.app.services.registry.operations.handlers.SwitchInferenceModeHandler(
            () -> null));
    handlers.register(
        CoreOperationCatalog.TRIGGER_OFFLINE_PROCESSING,
        new io.justsearch.app.services.registry.operations.handlers.TriggerOfflineProcessingHandler(
            () -> null));
    // Slice 3a-2-c BrainRuntime variant cluster.
    handlers.register(
        CoreOperationCatalog.ACTIVATE_RUNTIME_VARIANT,
        new io.justsearch.app.services.registry.operations.handlers.ActivateRuntimeVariantHandler(
            () -> null));
    handlers.register(
        CoreOperationCatalog.DEACTIVATE_RUNTIME_VARIANT,
        new io.justsearch.app.services.registry.operations.handlers.DeactivateRuntimeVariantHandler(
            () -> null));
    // Slice 3a-2-c BrainPackImport cluster.
    handlers.register(
        CoreOperationCatalog.PREFLIGHT_AI_PACK,
        new io.justsearch.app.services.registry.operations.handlers.PreflightAiPackHandler(
            () -> null));
    handlers.register(
        CoreOperationCatalog.IMPORT_AI_PACK,
        new io.justsearch.app.services.registry.operations.handlers.ImportAiPackHandler(
            () -> null));
    // Slice 3a-2-c BrainInstall cluster.
    handlers.register(
        CoreOperationCatalog.START_AI_INSTALL,
        new io.justsearch.app.services.registry.operations.handlers.StartAiInstallHandler(
            () -> null));
    handlers.register(
        CoreOperationCatalog.CANCEL_AI_INSTALL,
        new io.justsearch.app.services.registry.operations.handlers.CancelAiInstallHandler(
            () -> null));
    handlers.register(
        CoreOperationCatalog.REPAIR_AI_INSTALL,
        new io.justsearch.app.services.registry.operations.handlers.RepairAiInstallHandler(
            () -> null));
    // Slice 3a-2-c BrainPackImport policy cluster.
    handlers.register(
        CoreOperationCatalog.CREATE_USER_POLICY,
        new io.justsearch.app.services.registry.operations.handlers.CreateUserPolicyHandler(
            () -> null));
    handlers.register(
        CoreOperationCatalog.ALLOWLIST_ADD_DIGEST,
        new io.justsearch.app.services.registry.operations.handlers.AllowlistAddDigestHandler(
            () -> null));
    // Slice 3a-2-c Settings reset cluster.
    handlers.register(
        CoreOperationCatalog.RESET_SETTINGS,
        new io.justsearch.app.services.registry.operations.handlers.ResetSettingsHandler(
            () -> null));
    // Slice 445: TABULAR Resource item Operations + privacy resolver. Same
    // unavailable-supplier pattern (validator only checks structural shape).
    handlers.register(
        CoreOperationCatalog.CANCEL_INDEXING_JOB,
        new io.justsearch.app.services.registry.operations.handlers.CancelIndexingJobHandler(
            io.justsearch.app.api.IndexingService::unavailable));
    handlers.register(
        CoreOperationCatalog.RETRY_INDEXING_JOB,
        new io.justsearch.app.services.registry.operations.handlers.RetryIndexingJobHandler(
            io.justsearch.app.api.IndexingService::unavailable));
    handlers.register(
        CoreOperationCatalog.RESOLVE_PATH_HASH,
        new io.justsearch.app.services.registry.operations.handlers.ResolvePathHashHandler(
            io.justsearch.app.api.IndexingService::unavailable));
    // Slice 484 §3.6 / observations.md core.index-gc closure.
    handlers.register(
        CoreOperationCatalog.INDEX_GC,
        new io.justsearch.app.services.registry.operations.handlers.IndexGcHandler(
            io.justsearch.app.api.IndexingService::unavailable,
            io.justsearch.app.api.OperationLeaseService.noOp()));
    // Slice 491 §9.D Phase E (C4 / E3): agent navigation tool. Validator only checks
    // structural shape — pass a no-op supplier returning null; the handler's
    // Slice 491 E17: navigate-to-surface moved from CoreOperationCatalog-only
    // registration to AgentToolsOperationCatalog (below) so the LLM sees it. The
    // handler registration for the validator uses the stub (below), not the real
    // NavigateToSurfaceHandler — avoids the duplicate-registration error since
    // both catalogs share the same OperationRef id.
    // Tempdoc 429 §F.11 closure: register synthetic handlers for the 4+1 tool
    // Operations so ExecutorBindingValidator passes when validating the agent-tools
    // catalog. Production registers wrappers that invoke real tools; here we use
    // a no-op handler since the test only verifies the catalog declarations are
    // structurally consistent.
    OperationHandler stubHandler =
        new OperationHandler() {
          @Override
          public OperationResult execute(String argumentsJson) {
            return OperationResult.success("stub");
          }
        };
    handlers.register(AgentToolsOperationCatalog.SEARCH_INDEX, stubHandler);
    handlers.register(AgentToolsOperationCatalog.BROWSE_FOLDERS, stubHandler);
    handlers.register(AgentToolsOperationCatalog.INGEST_FILES, stubHandler);
    handlers.register(AgentToolsOperationCatalog.FILE_OPERATIONS, stubHandler);
    // Slice 491 E17: navigate-to-surface added to agent palette.
    handlers.register(AgentToolsOperationCatalog.NAVIGATE_TO_SURFACE, stubHandler);

    Set<String> validI18nKeys = loadValidI18nKeys();

    OperationCatalog coreCatalog = new CoreOperationCatalog();
    context = new ValidationContext(coreCatalog, handlers, validI18nKeys, Set.of());

    OperationCatalog agentToolsCatalog = new AgentToolsOperationCatalog();
    agentToolsContext =
        new ValidationContext(agentToolsCatalog, handlers, validI18nKeys, Set.of());
  }

  @ParameterizedTest
  @MethodSource("registeredValidators")
  void runValidatorAgainstCoreCatalog(RegistryShapeValidator validator) {
    List<ValidationFinding> errors =
        validator
            .validate(context)
            .filter(f -> f.severity() == Severity.ERROR)
            .toList();
    assertEquals(
        List.of(),
        errors,
        () ->
            "Validator "
                + validator.name()
                + " produced ERROR findings against CoreOperationCatalog: "
                + errors);
  }

  @ParameterizedTest
  @MethodSource("registeredValidators")
  void runValidatorAgainstAgentToolsCatalog(RegistryShapeValidator validator) {
    List<ValidationFinding> errors =
        validator
            .validate(agentToolsContext)
            .filter(f -> f.severity() == Severity.ERROR)
            .toList();
    assertEquals(
        List.of(),
        errors,
        () ->
            "Validator "
                + validator.name()
                + " produced ERROR findings against AgentToolsOperationCatalog: "
                + errors);
  }

  static Stream<RegistryShapeValidator> registeredValidators() {
    return Stream.of(
        new RiskAuditValidator(),
        new ConfirmValidator(),
        new RetryRateLimitValidator(),
        new ExecutorBindingValidator(),
        new NamespacingValidator(),
        // Tempdoc 564 facet 4c: i18n keys are now DERIVED from the operation id by construction
        // (Presentation.forId / ConfirmStrategy.typedForId → ops.<id-suffix>.{label,description,
        // confirm}); they cannot drift from the id, so the former I18nKeyConventionValidator (which
        // gated the keys *matching* the convention) is deleted — the duplication is gone, not patrolled.
        // I18nKeyValidator stays as the coverage gate: the generated keys must resolve in the catalog.
        new I18nKeyValidator());
  }

  /**
   * Loads i18n keys from the {@code registry-operation.en.properties} classpath
   * resource. Mirrors the {@code MessageCatalogController.loadProperties()} pattern.
   */
  static Set<String> loadValidI18nKeys() {
    Properties props = new Properties();
    try (InputStream is =
            ValidatorRunnerTest.class.getResourceAsStream(
                "/messages/registry-operation.en.properties");
        InputStreamReader reader =
            new InputStreamReader(
                Objects.requireNonNull(is, "registry-operation.en.properties missing"),
                StandardCharsets.UTF_8)) {
      props.load(reader);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return props.stringPropertyNames();
  }
}
