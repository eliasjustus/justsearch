package io.justsearch.ui.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.RuntimeManifestBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestHeadInfoBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestWorkerInfoBuilder;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 501 §13.4.5 audience axis: HTTP-class transports (REST + SSE +
 * MCP + well-known) must serve {@code manifest.publicProjection()}. The
 * filesystem transport keeps the full record (FS-permission gated). This
 * test exercises {@link RuntimeManifest#publicProjection} directly so the
 * live-stack test (which runs prodMode=false and never produces a token)
 * cannot silently regress the projection.
 */
class RuntimeManifestControllerRedactionTest {

  @Test
  void publicProjectionStripsSessionTokenWhenPresent() {
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(54321)
            .apiBaseUrl("http://127.0.0.1:54321")
            .sessionToken("super-secret-prod-token")
            .readyAt("2026-05-20T20:00:00Z")
            .build();
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("ddd-eee-fff")
            .pid(1234L)
            .startedAt("2026-05-20T19:59:00Z")
            .dataDir("/tmp/whatever")
            .head(head)
            .build();

    RuntimeManifest publicView = manifest.publicProjection();

    assertNotNull(publicView.head(), "head sub-record must remain present");
    assertEquals(54321, publicView.head().apiPort(), "non-sensitive fields must survive");
    assertEquals("http://127.0.0.1:54321", publicView.head().apiBaseUrl());
    assertNull(publicView.head().sessionToken(), "sessionToken must be stripped");
  }

  @Test
  void publicProjectionIsIdentityWhenNoTokenPresent() {
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(54321)
            .apiBaseUrl("http://127.0.0.1:54321")
            .readyAt("2026-05-20T20:00:00Z")
            .build();
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("ddd-eee-fff")
            .pid(1234L)
            .startedAt("2026-05-20T19:59:00Z")
            .dataDir("/tmp/whatever")
            .head(head)
            .build();

    RuntimeManifest publicView = manifest.publicProjection();

    assertEquals(manifest, publicView, "identity projection when nothing to redact");
  }

  @Test
  void publicProjectionPreservesWorkerAndAi() {
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(54321)
            .apiBaseUrl("http://127.0.0.1:54321")
            .sessionToken("token")
            .readyAt("2026-05-20T20:00:00Z")
            .build();
    RuntimeManifest.WorkerInfo worker =
        RuntimeManifestWorkerInfoBuilder.builder()
            .state("ready")
            .grpcPort(9000)
            .indexBasePath("/data/idx")
            .readyAt("2026-05-20T20:01:00Z")
            .build();
    RuntimeManifest.AiInfo ai =
        new RuntimeManifest.AiInfo("READY", true, null, "2026-05-20T20:02:00Z");
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("ddd-eee-fff")
            .pid(1234L)
            .startedAt("2026-05-20T19:59:00Z")
            .dataDir("/tmp/whatever")
            .head(head)
            .worker(worker)
            .ai(ai)
            .build();

    RuntimeManifest publicView = manifest.publicProjection();

    assertNotNull(publicView.worker(), "worker sub-record must survive projection");
    assertEquals(9000, publicView.worker().grpcPort());
    assertNotNull(publicView.ai(), "ai sub-record must survive projection");
    assertEquals("READY", publicView.ai().phase());
    assertNull(publicView.head().sessionToken());
  }
}
