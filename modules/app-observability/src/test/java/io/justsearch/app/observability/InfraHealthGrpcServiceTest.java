package io.justsearch.app.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import io.justsearch.infra.health.InfraHealthAggregator;
import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InfraHealthGrpcServiceTest {

  @Test
  void currentSnapshotConvertsPayloadToProto() {
    InfraDiagnosticsService diagnostics = diagnosticsService();
    InfraHealthGrpcService service = new InfraHealthGrpcService(diagnostics);
    RecordingObserver observer = new RecordingObserver();

    service.currentSnapshot(Empty.getDefaultInstance(), observer);

    assertNotNull(observer.last);
    assertEquals("healthy", observer.last.getStatus());
    assertEquals(3, observer.last.getComponentsCount());
    assertEquals("translator", observer.last.getComponents(1).getComponentId());
    assertEquals("10.000", observer.last.getMetadataOrThrow("lag_seconds"));
  }

  @Test
  void streamSnapshotsEmitsInitialPayload() {
    InfraDiagnosticsService diagnostics = diagnosticsService();
    InfraHealthGrpcService service = new InfraHealthGrpcService(diagnostics);
    RecordingObserver observer = new RecordingObserver();

    service.streamSnapshots(Empty.getDefaultInstance(), observer);

    assertEquals(1, observer.count);
  }

  @Test
  void toProtoConvertsNestedMetrics() throws Exception {
    InfraDiagnosticsService diagnostics = diagnosticsService();
    InfraHealthGrpcService service = new InfraHealthGrpcService(diagnostics);
    Map<String, Object> metricsMap = new HashMap<>();
    metricsMap.put("nullVal", null);
    metricsMap.put("count", 5);
    metricsMap.put("active", true);
    metricsMap.put("nested", Map.of("inner", 7));
    InfraDiagnosticsService.InfraHealthPayload payload =
        new InfraDiagnosticsService.InfraHealthPayload(
            "healthy",
            Instant.now().toString(),
            List.of(
                new InfraDiagnosticsService.ComponentStatus(
                    "custom",
                    "healthy",
                    "ok",
                    metricsMap)),
            Map.of("pi", 3.14159));

    Method toProto =
        InfraHealthGrpcService.class.getDeclaredMethod(
            "toProto", InfraDiagnosticsService.InfraHealthPayload.class);
    toProto.setAccessible(true);
    io.justsearch.ipc.v1.InfraHealthSnapshot snapshot =
        (io.justsearch.ipc.v1.InfraHealthSnapshot) toProto.invoke(service, payload);

    Map<String, com.google.protobuf.Value> metrics =
        snapshot.getComponents(0).getMetrics().getFieldsMap();
    assertEquals(5.0, metrics.get("count").getNumberValue());
    assertTrue(metrics.get("active").getBoolValue());
    assertTrue(metrics.get("nested").hasStructValue());
    assertEquals(
        com.google.protobuf.NullValue.NULL_VALUE, metrics.get("nullVal").getNullValue());
    assertEquals("3.142", snapshot.getMetadataOrThrow("pi"));
  }

  private InfraDiagnosticsService diagnosticsService() {
    InfraHealthAggregator.Config config =
        new InfraHealthAggregator.Config(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20), 50);
    InfraDiagnosticsService diagnostics = new InfraDiagnosticsService(config);
    diagnostics.setNrtLagSupplier(() -> 5L);
    diagnostics.setTranslatorHandshakeSupplier(() -> Instant.now());
    diagnostics.setAnnReadySupplier(() -> 75);
    diagnostics.setMetadataSupplier(() -> Map.of("lag_seconds", 10.0));
    diagnostics.setConfigValidSupplier(() -> true);
    return diagnostics;
  }

  private static final class RecordingObserver implements StreamObserver<io.justsearch.ipc.v1.InfraHealthSnapshot> {
    private io.justsearch.ipc.v1.InfraHealthSnapshot last;
    private int count;

    @Override
    public void onNext(io.justsearch.ipc.v1.InfraHealthSnapshot value) {
      this.last = value;
      this.count++;
    }

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onCompleted() {}
  }

}
