package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.stub.StreamObserver;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.reranker.WorkerModelDiscovery;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GrpcHealthServiceTest {

  @Test
  void checkReturnsServingWithVersion() {
    GrpcHealthService service = new GrpcHealthService("1.0.0-test");
    CapturingObserver<HealthCheckResponse> observer = new CapturingObserver<>();

    service.check(HealthCheckRequest.getDefaultInstance(), observer);

    HealthCheckResponse response = observer.single();
    assertTrue(response.getServing());
    assertEquals("1.0.0-test", response.getVersion());
    assertTrue(observer.completed);
  }

  @Test
  void checkSurfacesOrtVersionInEffectiveConfig() {
    // tempdoc 623 U7: the ORT library version rides the existing effective_config map (no new
    // proto field) so it reaches the Head's /api/debug/state for the benchmark-release projection.
    GrpcHealthService service = new GrpcHealthService("1.0.0-test");
    CapturingObserver<HealthCheckResponse> observer = new CapturingObserver<>();

    service.check(HealthCheckRequest.getDefaultInstance(), observer);

    HealthCheckResponse response = observer.single();
    assertTrue(response.getEffectiveConfigMap().containsKey("ort.version"));
    assertNotNull(response.getEffectiveConfigMap().get("ort.version"));
  }

  @Test
  void checkHandlesNullVersion() {
    GrpcHealthService service = new GrpcHealthService(null);
    CapturingObserver<HealthCheckResponse> observer = new CapturingObserver<>();

    service.check(HealthCheckRequest.getDefaultInstance(), observer);

    HealthCheckResponse response = observer.single();
    assertTrue(response.getServing());
    assertEquals("unknown", response.getVersion());
  }

  @Test
  void deepCheckPassesWithNullComponents() {
    // Test that deep checks with null components still return serving
    GrpcHealthService service = new GrpcHealthService("2.0.0", null, null, null);
    CapturingObserver<HealthCheckResponse> observer = new CapturingObserver<>();

    service.check(HealthCheckRequest.getDefaultInstance(), observer);

    HealthCheckResponse response = observer.single();
    assertTrue(response.getServing());
    assertEquals("2.0.0", response.getVersion());
    assertFalse(response.getAiReady());
    assertFalse(response.getEmbeddingReady());
  }

  @Test
  void deepCheckReportsEmbeddingAndAiReadyWhenEmbeddingServiceIsAvailable() {
    EmbeddingService embeddingService = mock(EmbeddingService.class);
    when(embeddingService.isAvailable()).thenReturn(true);

    GrpcHealthService service = new GrpcHealthService("2.1.0", null, null, embeddingService);
    CapturingObserver<HealthCheckResponse> observer = new CapturingObserver<>();

    service.check(HealthCheckRequest.getDefaultInstance(), observer);

    HealthCheckResponse response = observer.single();
    assertTrue(response.getServing());
    assertTrue(response.getAiReady());
    assertTrue(response.getEmbeddingReady());
  }

  @Test
  void checkPopulatesOnnxModelsFromDiscovery() {
    var models = List.of(
        new WorkerModelDiscovery.DiscoveredModel("reranker", true, "C:\\models\\reranker", true),
        new WorkerModelDiscovery.DiscoveredModel("citation-scorer", false, null, false));

    GrpcHealthService service = new GrpcHealthService("1.0.0", null, null, null, null, models);
    CapturingObserver<HealthCheckResponse> observer = new CapturingObserver<>();

    service.check(HealthCheckRequest.getDefaultInstance(), observer);

    var onnxModels = observer.single().getOnnxModelsList();
    assertEquals(2, onnxModels.size());
    assertEquals("reranker", onnxModels.get(0).getModelName());
    assertTrue(onnxModels.get(0).getFound());
    assertEquals("C:\\models\\reranker", onnxModels.get(0).getPath());
    assertTrue(onnxModels.get(0).getAutoDiscovered());
    assertEquals("citation-scorer", onnxModels.get(1).getModelName());
    assertFalse(onnxModels.get(1).getFound());
    assertEquals("", onnxModels.get(1).getPath());
    assertFalse(onnxModels.get(1).getAutoDiscovered());
  }

  @Test
  void checkReturnsEmptyOnnxModelsWhenNoneProvided() {
    GrpcHealthService service = new GrpcHealthService("1.0.0");
    CapturingObserver<HealthCheckResponse> observer = new CapturingObserver<>();

    service.check(HealthCheckRequest.getDefaultInstance(), observer);

    assertTrue(observer.single().getOnnxModelsList().isEmpty());
  }

  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private T value;
    boolean completed = false;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable t) {
      throw new AssertionError("unexpected error", t);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    T single() {
      if (value == null) {
        throw new AssertionError("no value captured");
      }
      return value;
    }
  }
}
