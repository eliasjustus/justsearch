package io.justsearch.ui.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.RuntimeManifestBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestHeadInfoBuilder;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 501 Phase 27 (§13.7 Q6) — readiness / liveness probes.
 */
class RuntimeProbeControllerTest {

  @Test
  void readyReturns200WhenLifecycleIsREADY() {
    RuntimeManifest manifest = manifestWithLifecycle("LIFECYCLE_STATE_READY");
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(manifest);

    Context ctx = mock(Context.class);
    when(ctx.status(200)).thenReturn(ctx);
    when(ctx.status(503)).thenReturn(ctx);

    new RuntimeProbeController(publisher).handleReady(ctx);

    verify(ctx).status(200);
    ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(bodyCap.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) bodyCap.getValue();
    assertEquals(Boolean.TRUE, body.get("ready"));
    assertEquals("LIFECYCLE_STATE_READY", body.get("lifecycle"));
  }

  @Test
  void readyReturns503WhenLifecycleIsNotREADY() {
    RuntimeManifest manifest = manifestWithLifecycle("LIFECYCLE_STATE_STARTING");
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(manifest);

    Context ctx = mock(Context.class);
    when(ctx.status(503)).thenReturn(ctx);

    new RuntimeProbeController(publisher).handleReady(ctx);

    verify(ctx).status(503);
    ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(bodyCap.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) bodyCap.getValue();
    assertEquals(Boolean.FALSE, body.get("ready"));
    assertEquals("LIFECYCLE_STATE_STARTING", body.get("lifecycle"));
  }

  @Test
  void readyReturns503ForLegacyShortReadyName() {
    // 548 §4.1 regression guard: the wire is now proto-canonical, so the readiness probe must
    // NOT accept the legacy short "READY" (the manifest is written as LIFECYCLE_STATE_READY).
    RuntimeManifest manifest = manifestWithLifecycle("READY");
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(manifest);

    Context ctx = mock(Context.class);
    when(ctx.status(503)).thenReturn(ctx);

    new RuntimeProbeController(publisher).handleReady(ctx);

    verify(ctx).status(503);
  }

  @Test
  void readyReturns503WhenManifestAbsent() {
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(null);

    Context ctx = mock(Context.class);
    when(ctx.status(503)).thenReturn(ctx);

    new RuntimeProbeController(publisher).handleReady(ctx);

    verify(ctx).status(503);
  }

  @Test
  void readyHeadReturnsStatusOnlyMatchingGet() {
    RuntimeManifest manifest = manifestWithLifecycle("LIFECYCLE_STATE_READY");
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(manifest);

    Context ctx = mock(Context.class);
    when(ctx.status(200)).thenReturn(ctx);

    new RuntimeProbeController(publisher).handleReadyHead(ctx);

    verify(ctx).status(200);
    // HEAD must NOT call json() — body-less response is the convention.
    org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never()).json(any());
  }

  @Test
  void readyHeadReturns503WhenNotReady() {
    RuntimeManifest manifest = manifestWithLifecycle("LIFECYCLE_STATE_STARTING");
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(manifest);

    Context ctx = mock(Context.class);
    when(ctx.status(503)).thenReturn(ctx);

    new RuntimeProbeController(publisher).handleReadyHead(ctx);

    verify(ctx).status(503);
    org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never()).json(any());
  }

  @Test
  void liveHeadAlwaysReturns200() {
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    Context ctx = mock(Context.class);
    when(ctx.status(200)).thenReturn(ctx);

    new RuntimeProbeController(publisher).handleLiveHead(ctx);

    verify(ctx).status(200);
    org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never()).json(any());
  }

  @Test
  void liveAlwaysReturns200() {
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(null);

    Context ctx = mock(Context.class);
    when(ctx.status(200)).thenReturn(ctx);

    new RuntimeProbeController(publisher).handleLive(ctx);

    verify(ctx).status(200);
    ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(bodyCap.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) bodyCap.getValue();
    assertEquals(Boolean.TRUE, body.get("alive"));
  }

  private static RuntimeManifest manifestWithLifecycle(String lifecycle) {
    RuntimeManifest.HeadInfo head =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(54321)
            .apiBaseUrl("http://127.0.0.1:54321")
            .readyAt("2026-05-21T00:00:00Z")
            .build();
    return RuntimeManifestBuilder.builder()
        .schemaVersion(1)
        .instanceId("inst-probe")
        .pid(1234L)
        .startedAt("2026-05-21T00:00:00Z")
        .dataDir("/tmp")
        .lifecycle(lifecycle)
        .head(head)
        .build();
  }
}
