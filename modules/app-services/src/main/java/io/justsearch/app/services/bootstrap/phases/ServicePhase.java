/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.tools.BrowseTool;
import io.justsearch.agent.tools.FileOperationLog;
import io.justsearch.agent.tools.FileOperationsTool;
import io.justsearch.agent.tools.IngestTool;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.DebugStateProvider;
import io.justsearch.app.api.DiagnosticsService;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.ExcludesService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.PackImportService;
import io.justsearch.app.api.PolicyService;
import io.justsearch.app.api.RuntimeVariantService;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.StatusSnapshotProvider;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.OnlineAiServiceImpl;
import io.justsearch.app.services.ai.install.AiInstallService;
import io.justsearch.app.services.ai.pack.AiPackImportService;
import io.justsearch.app.services.ai.pack.PackAllowlistService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.services.bootstrap.BootstrapLateBindings;
import io.justsearch.app.services.braininstall.BrainInstallServiceImpl;
import io.justsearch.app.services.brainruntime.BrainRuntimeServiceImpl;
import io.justsearch.app.services.diagnostics.DiagnosticsServiceImpl;
import io.justsearch.app.services.excludes.ExcludesServiceImpl;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import io.justsearch.app.services.gpl.LambdaMartReranker;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.packimport.PackImportServiceImpl;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.policy.PolicyServiceImpl;
import io.justsearch.app.services.runtimevariant.RuntimeVariantServiceImpl;
import io.justsearch.app.services.settings.SettingsServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import io.justsearch.app.services.worker.WorkerFeatureCache;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.telemetry.Telemetry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Tempdoc 519 §4 Phase 3 — service construction. After §31 Phase 3, this phase constructs ALL
 * production services (the prerequisites + the 7 controller-services that previously lived in
 * LateBoundServices). LocalApiServer no longer constructs services; it only constructs
 * controllers that consume the services from the ServiceGraph.
 *
 * <p>3 bindings have controller back-refs that don't resolve until LocalApiServer constructs
 * its controllers: SettingsController::resetToDefaults, DebugStateController (as
 * DebugStateProvider SPI), StatusLifecycleHandler (as StatusSnapshotProvider SPI). These are
 * threaded via the {@link BootstrapLateBindings} holder — ServicePhase constructs services with
 * supplier/callable wrappers that read from the holder at use-time; LocalApiServer publishes
 * the concrete refs into the holder after constructing the controllers.
 */
public final class ServicePhase {

  private ServicePhase() {}

  /** Bundled inputs (record keeps the parameter surface manageable). */
  public record Input(
      KnowledgeServerBootstrap knowledgeServer,
      RemoteKnowledgeClient knowledgeClient,
      IndexingService indexingService,
      Supplier<IndexingService> indexingServiceSupplier,
      DocumentService documentService,
      LambdaMartReranker lambdaMartReranker,
      Telemetry telemetry,
      java.nio.file.Path dataDir,
      InferenceLifecycleManager inferenceManager,
      InferenceCapability inferenceCapability,
      UiSettingsStore settingsStore,
      BootstrapLateBindings lateBindings) {}

  /** Bundled output — all services every downstream phase consumes. */
  public record Output(
      InferenceLifecycleManager inferenceManager,
      OnlineAiService onlineAiService,
      io.justsearch.app.api.ModeChangeListener gpuBroadcastListener,
      OfflineCoordinator offlineCoordinator,
      KnowledgeHttpApiAdapter agentSearchAdapter,
      FileOperationLog fileOperationLog,
      FileOperationsTool fileOperationsTool,
      SearchTool searchTool,
      BrowseTool browseTool,
      IngestTool ingestTool,
      WorkerFeatureCache workerFeatureCache,
      // §31 Phase 3: 7 controller-services + 1 Worker-dependent ExcludesService.
      ExcludesService excludes,
      EnterprisePolicyService enterprisePolicy,
      SettingsService settings,
      DiagnosticsService diagnostics,
      BrainInstallService brainInstall,
      BrainRuntimeService brainRuntime,
      RuntimeVariantService runtimeVariant,
      PackImportService packImport,
      PolicyService policy,
      // Helpers exposed because LocalApiServer's controllers consume them directly.
      AiInstallService aiInstallHelper,
      AiPackImportService aiPackImportHelper,
      RuntimeActivationService runtimeActivationHelper,
      PackAllowlistService packAllowlistService,
      GpuCapabilitiesService gpuCapabilitiesService,
      // Tempdoc 542 §B Layer 3 — op-lease SPI (no-op when not running under dev-runner).
      OperationLeaseService operationLeaseService) {}

  /**
   * Tempdoc 541 §5.3 + fix-pass Tier 5: sealed-sum entry point. Returns {@link
   * PhaseOutcome.Degraded} with reason {@code "inference.not_configured"} when ILM is absent
   * (lite-mode / AI-disabled paths); {@link PhaseOutcome.Failed} if construction throws;
   * {@link PhaseOutcome.Ready} otherwise.
   */
  public static io.justsearch.app.services.bootstrap.PhaseOutcome<Output> runWithOutcome(Input in) {
    try {
      Output out = runInternal(in);
      if (in.inferenceManager() == null) {
        return new io.justsearch.app.services.bootstrap.PhaseOutcome.Degraded<>(
            out, java.util.Set.of("inference.not_configured"));
      }
      return new io.justsearch.app.services.bootstrap.PhaseOutcome.Ready<>(out);
    } catch (RuntimeException e) {
      return io.justsearch.app.services.bootstrap.PhaseOutcome.Failed.of(e);
    }
  }

  /**
   * §12.F: internal body (formerly public {@code run(Input)}). Visibility narrowed to
   * private; the single entry point is {@link #runWithOutcome(Input)}. No external callers
   * remain since the Tier-5 sealed-sum migration moved every HeadAssembly call site to the
   * sealed-sum entry.
   */
  private static Output runInternal(Input in) {
    OnlineAiService onlineAiService;
    io.justsearch.app.api.ModeChangeListener gpuListener = null;
    OfflineCoordinator offlineCoordinator = null;
    if (in.inferenceManager() != null) {
      onlineAiService = new OnlineAiServiceImpl(in.inferenceManager());
      gpuListener = InferenceWiring.wireGpuStatusBroadcast(in.inferenceManager(), in.knowledgeServer());
      offlineCoordinator =
          OfflineCoordinatorBuilder.build(
              in.inferenceManager(), onlineAiService, in.knowledgeClient(), in.telemetry());
      InferenceCapabilityWiring.attachInferenceModeListener(
          in.inferenceManager(), in.inferenceCapability());
      InferenceWiring.tryStartOnlineMode(in.inferenceManager());
    } else {
      onlineAiService = OnlineAiService.unavailable();
    }

    // §31 Phase 3: GpuCapabilitiesService constructed here (was in LocalApiServer). No deps.
    GpuCapabilitiesService gpuCapabilitiesService = new GpuCapabilitiesService();

    // §31 Phase 3: offlineProcessingTrigger derived from offlineCoordinator (computed above).
    Runnable offlineProcessingTrigger =
        offlineCoordinator != null ? offlineCoordinator::startOfflineProcessing : null;

    AgentToolFactory.Output agentTools =
        AgentToolFactory.build(
            in.dataDir(),
            in.knowledgeServer(),
            in.knowledgeClient(),
            in.indexingService(),
            onlineAiService,
            in.lambdaMartReranker());

    // §31 Step 1.1: ExcludesService constructed via supplier-aware IndexingService.
    ExcludesService excludes = new ExcludesServiceImpl(in.indexingServiceSupplier());

    // §31 Phase 1.A: EnterprisePolicyService impl in app-services — constructible here.
    EnterprisePolicyService enterprisePolicy = new EnterprisePolicyServiceImpl();

    // §31 Phase 1.B-D: helper impls in app-services.
    AiInstallService aiInstallHelper =
        new AiInstallService(
            onlineAiService, in.settingsStore(), in.knowledgeServer(), enterprisePolicy);
    PackAllowlistService packAllowlistService = new PackAllowlistService();
    AiPackImportService aiPackImportHelper =
        new AiPackImportService(
            onlineAiService,
            in.settingsStore(),
            in.knowledgeServer(),
            enterprisePolicy,
            packAllowlistService);
    WorkerFeatureCache workerFeatureCache =
        in.knowledgeClient() != null ? in.knowledgeClient()::getLastKnownOnnxModels : List::of;
    RuntimeActivationService runtimeActivationHelper =
        new RuntimeActivationService(
            onlineAiService,
            in.settingsStore(),
            gpuCapabilitiesService,
            enterprisePolicy,
            workerFeatureCache,
            in.inferenceCapability());

    // §31 Phase 3: 7 controller-services constructed here.
    // SettingsService: callable wraps the late-bound resetFn (set by LocalApiServer after
    // SettingsController exists).
    Callable<Map<String, Object>> deferredResetFn =
        () -> {
          Callable<Map<String, Object>> resetFn = in.lateBindings().settingsResetFn();
          if (resetFn == null) {
            throw new IllegalStateException(
                "Settings reset callback not yet bound (LocalApiServer must publish after"
                    + " constructing SettingsController)");
          }
          return resetFn.call();
        };
    SettingsService settings = new SettingsServiceImpl(deferredResetFn);

    // DiagnosticsService: SPI suppliers read from the late-bindings holder.
    Supplier<DebugStateProvider> debugProviderSupplier = in.lateBindings()::debugStateProvider;
    Supplier<StatusSnapshotProvider> statusProviderSupplier =
        in.lateBindings()::statusSnapshotProvider;
    DiagnosticsService diagnostics =
        new DiagnosticsServiceImpl(
            enterprisePolicy,
            gpuCapabilitiesService,
            debugProviderSupplier,
            statusProviderSupplier);

    // Tempdoc 542: op-lease SPI. Reads JUSTSEARCH_DEV_RUNNER_STATE_ROOT env var; no-op when
    // unset (production / non-dev-runner launch). Single Java writer to op-leases.json.
    OperationLeaseService operationLeaseService = new OperationLeaseServiceImpl();

    BrainInstallService brainInstall = new BrainInstallServiceImpl(aiInstallHelper);
    BrainRuntimeService brainRuntime =
        new BrainRuntimeServiceImpl(
            onlineAiService, in.settingsStore(), enterprisePolicy, offlineProcessingTrigger);
    RuntimeVariantService runtimeVariant =
        new RuntimeVariantServiceImpl(runtimeActivationHelper, enterprisePolicy);
    PackImportService packImport = new PackImportServiceImpl(aiPackImportHelper);
    PolicyService policy = new PolicyServiceImpl(enterprisePolicy);

    return new Output(
        in.inferenceManager(),
        onlineAiService,
        gpuListener,
        offlineCoordinator,
        agentTools.agentSearchAdapter(),
        agentTools.fileOperationLog(),
        agentTools.fileOperationsTool(),
        agentTools.searchTool(),
        agentTools.browseTool(),
        agentTools.ingestTool(),
        workerFeatureCache,
        excludes,
        enterprisePolicy,
        settings,
        diagnostics,
        brainInstall,
        brainRuntime,
        runtimeVariant,
        packImport,
        policy,
        aiInstallHelper,
        aiPackImportHelper,
        runtimeActivationHelper,
        packAllowlistService,
        gpuCapabilitiesService,
        operationLeaseService);
  }
}
