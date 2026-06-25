/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.vdu.ImagePreparer;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.services.vdu.VduBatchProcessor;
import io.justsearch.app.services.vdu.VduMetricCatalog;
import io.justsearch.app.services.vdu.VduProcessor;
import io.justsearch.app.services.worker.RemoteKnowledgeClient;
import io.justsearch.app.util.TempFileManager;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: VDU OfflineCoordinator builder extracted from
 * {@code HeadAssembly#createOfflineCoordinator}. Returns null when the manager or client
 * is null, or when construction fails (logged + non-fatal).
 */
public final class OfflineCoordinatorBuilder {

  private static final Logger log = LoggerFactory.getLogger(OfflineCoordinatorBuilder.class);

  private OfflineCoordinatorBuilder() {}

  public static OfflineCoordinator build(
      InferenceLifecycleManager manager,
      io.justsearch.app.api.OnlineAiService onlineAiService,
      RemoteKnowledgeClient client,
      Telemetry telemetry) {
    if (manager == null || client == null) {
      log.debug("OfflineCoordinator not created: manager or client unavailable");
      return null;
    }
    try {
      Path tempDir = BootstrapHelpers.getJustSearchHome().resolve("temp");
      TempFileManager tempFileManager = new TempFileManager(tempDir);
      ImagePreparer imagePreparer = new ImagePreparer();
      VduMetricCatalog vduCatalog =
          telemetry instanceof LocalTelemetry lt
              ? new VduMetricCatalog(lt.registry())
              : VduMetricCatalog.noop();
      var vduCapabilityState = new io.justsearch.app.services.vdu.VduCapabilityState();
      // Tempdoc 518 Appendix G W4.2 (ported from main 4e1d1c456): VduProcessor migrated to
      // three role-typed handles. Production OnlineAiServiceImpl implements all three role
      // interfaces; cast at this composition-root site.
      var introspectionRole =
          (io.justsearch.app.api.OnlineAiRuntimeIntrospection) onlineAiService;
      var lifecycleRole =
          (io.justsearch.app.api.OnlineAiLifecycleControl) onlineAiService;
      VduProcessor vduProcessor =
          new VduProcessor(
              introspectionRole,
              lifecycleRole,
              onlineAiService,
              tempFileManager,
              imagePreparer,
              vduCatalog);
      GpuCapabilitiesService gpuCapabilitiesService = new GpuCapabilitiesService();
      VduBatchProcessor batchProcessor =
          new VduBatchProcessor(
              vduProcessor, gpuCapabilitiesService, client, vduCatalog, vduCapabilityState);
      OfflineCoordinator coordinator =
          new OfflineCoordinator(manager, batchProcessor, client, vduCapabilityState);
      log.info("OfflineCoordinator created for VDU batch processing");
      return coordinator;
    } catch (Exception e) {
      log.warn("Failed to create OfflineCoordinator; VDU features unavailable", e);
      return null;
    }
  }
}
