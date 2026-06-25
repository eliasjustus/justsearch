/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability;

import com.google.protobuf.Empty;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.justsearch.app.observability.InfraDiagnosticsService.ComponentStatus;
import io.justsearch.ipc.v1.InfraDiagnosticsServiceGrpc;
import io.justsearch.ipc.v1.InfraHealthComponent;
import io.justsearch.ipc.v1.InfraHealthSnapshot;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** gRPC facade that exposes infrastructure health diagnostics snapshots. */
public final class InfraHealthGrpcService
    extends InfraDiagnosticsServiceGrpc.InfraDiagnosticsServiceImplBase {

  private final InfraDiagnosticsService diagnostics;

  public InfraHealthGrpcService(InfraDiagnosticsService diagnostics) {
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  @Override
  public void currentSnapshot(Empty request, StreamObserver<InfraHealthSnapshot> responseObserver) {
    InfraDiagnosticsService.InfraHealthPayload payload = diagnostics.currentPayload();
    responseObserver.onNext(toProto(payload));
    responseObserver.onCompleted();
  }

  @Override
  public void streamSnapshots(
      Empty request, StreamObserver<InfraHealthSnapshot> responseObserver) {
    InfraHealthSnapshot initial = toProto(diagnostics.currentPayload());
    responseObserver.onNext(initial);

    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "infra-health-grpc-stream");
              thread.setDaemon(true);
              return thread;
            });

    Runnable emit =
        () -> {
          try {
            responseObserver.onNext(toProto(diagnostics.currentPayload()));
          } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withCause(e).asRuntimeException());
            scheduler.shutdownNow();
          }
        };

    var unused = scheduler.scheduleAtFixedRate(emit, 5, 5, TimeUnit.SECONDS);

    if (responseObserver instanceof ServerCallStreamObserver<InfraHealthSnapshot> serverObserver) {
      serverObserver.setOnCancelHandler(() -> scheduler.shutdownNow());
      serverObserver.setOnCloseHandler(() -> scheduler.shutdown());
    }
  }

  private static InfraHealthSnapshot toProto(InfraDiagnosticsService.InfraHealthPayload payload) {
    InfraHealthSnapshot.Builder builder =
        InfraHealthSnapshot.newBuilder()
            .setStatus(payload.status())
            .setGeneratedAt(payload.generatedAt());

    for (ComponentStatus component : payload.components()) {
      builder.addComponents(
          InfraHealthComponent.newBuilder()
              .setComponentId(component.componentId())
              .setStatus(component.status())
              .setReasonCode(component.reasonCode() == null ? "" : component.reasonCode())
              .setMetrics(toStruct(component.metrics()))
              .build());
    }

    payload.metadata().forEach((key, value) -> builder.putMetadata(key, stringify(value)));
    return builder.build();
  }

  private static Struct toStruct(Map<String, ?> metrics) {
    Struct.Builder builder = Struct.newBuilder();
    if (metrics == null || metrics.isEmpty()) {
      return builder.build();
    }
    metrics.forEach((key, value) -> builder.putFields(key, toValue(value)));
    return builder.build();
  }

  private static Value toValue(Object value) {
    if (value == null) {
      return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    }
    if (value instanceof Number number) {
      return Value.newBuilder().setNumberValue(number.doubleValue()).build();
    }
    if (value instanceof Boolean bool) {
      return Value.newBuilder().setBoolValue(bool).build();
    }
    if (value instanceof Map<?, ?> nested) {
      return Value.newBuilder().setStructValue(toStruct(castMap(nested))).build();
    }
    return Value.newBuilder().setStringValue(value.toString()).build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ?> castMap(Map<?, ?> raw) {
    return (Map<String, ?>) raw;
  }

  private static String stringify(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Number number) {
      if (number instanceof Float || number instanceof Double) {
        return String.format(Locale.ROOT, "%.3f", number.doubleValue());
      }
      return number.toString();
    }
    return value.toString();
  }
}
