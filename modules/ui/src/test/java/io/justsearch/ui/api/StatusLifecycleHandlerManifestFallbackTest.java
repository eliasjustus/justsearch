package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.RuntimeManifestBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestHeadInfoBuilder;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 501 Phase 26 + Phase 39 (F9): verifies the three fallback paths
 * of {@link StatusLifecycleHandler#readManifestLifecycle}. The method must
 * return {@code null} (forcing fallback to direct {@code LifecycleProjection.derive})
 * when the publisher is unwired, hasn't published yet, or carries a
 * lifecycle string that cannot be parsed as {@link LifecycleState}.
 *
 * <p>Tests use minimal-construction handlers (most collaborators are
 * irrelevant for this method; we only need the publisher field wired).
 */
class StatusLifecycleHandlerManifestFallbackTest {

  @Test
  void publisherUnwiredReturnsNull() {
    StatusLifecycleHandler handler = newHandler();
    // No setRuntimeManifestPublisher call — runtimeManifestPublisher stays null.
    assertNull(handler.readManifestLifecycle());
  }

  @Test
  void publisherWithoutManifestReturnsNull() {
    StatusLifecycleHandler handler = newHandler();
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(null);
    handler.setRuntimeManifestPublisher(publisher);

    assertNull(handler.readManifestLifecycle());
  }

  @Test
  void manifestWithNullLifecycleReturnsNull() {
    StatusLifecycleHandler handler = newHandler();
    RuntimeManifest m =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("i")
            .pid(1L)
            .startedAt("2026-05-21T00:00:00Z")
            .dataDir("/tmp")
            .head(
                RuntimeManifestHeadInfoBuilder.builder()
                    .apiPort(1234)
                    .apiBaseUrl("http://127.0.0.1:1234")
                    .readyAt("2026-05-21T00:00:01Z")
                    .build())
            .build();
    // lifecycle stays null (no .lifecycle(...) call)
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(m);
    handler.setRuntimeManifestPublisher(publisher);

    assertNull(handler.readManifestLifecycle());
  }

  @Test
  void unrecognizedDiscriminatorReturnsNull() {
    StatusLifecycleHandler handler = newHandler();
    RuntimeManifest m =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("i")
            .pid(1L)
            .startedAt("2026-05-21T00:00:00Z")
            .dataDir("/tmp")
            .lifecycle("THIS_IS_NOT_A_LIFECYCLE_STATE")
            .head(
                RuntimeManifestHeadInfoBuilder.builder()
                    .apiPort(1234)
                    .apiBaseUrl("http://127.0.0.1:1234")
                    .readyAt("2026-05-21T00:00:01Z")
                    .build())
            .build();
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(m);
    handler.setRuntimeManifestPublisher(publisher);

    assertNull(handler.readManifestLifecycle());
  }

  @Test
  void validLifecycleStringReturnsParsedState() {
    StatusLifecycleHandler handler = newHandler();
    RuntimeManifest m =
        RuntimeManifestBuilder.builder()
            .schemaVersion(1)
            .instanceId("i")
            .pid(1L)
            .startedAt("2026-05-21T00:00:00Z")
            .dataDir("/tmp")
            .lifecycle("LIFECYCLE_STATE_READY")
            .head(
                RuntimeManifestHeadInfoBuilder.builder()
                    .apiPort(1234)
                    .apiBaseUrl("http://127.0.0.1:1234")
                    .readyAt("2026-05-21T00:00:01Z")
                    .build())
            .build();
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.current()).thenReturn(m);
    handler.setRuntimeManifestPublisher(publisher);

    assertEquals(LifecycleState.LIFECYCLE_STATE_READY, handler.readManifestLifecycle());
  }

  private static StatusLifecycleHandler newHandler() {
    // Minimal collaborators — readManifestLifecycle uses none of these.
    return new StatusLifecycleHandler(
        mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class),
        () -> null,
        null,
        null,
        null,
        java.time.Instant.now(),
        () -> "OK",
        null,
        null,
        null,
        mock(io.justsearch.app.services.lifecycle.WorkerCapability.class),
        mock(io.justsearch.app.services.lifecycle.InferenceCapability.class));
  }
}
