package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.justsearch.telemetry.grpc.TraceClientInterceptor;
import io.justsearch.telemetry.grpc.TraceServerInterceptor;
import org.junit.jupiter.api.Test;
import java.io.InputStream;

class GrpcTracePropagationIT {
  @Test
  void clientToServerTraceIdPropagates() throws Exception {
    GlobalOpenTelemetry.resetForTest();
    String name = InProcessServerBuilder.generateName();
    MethodDescriptor<byte[], byte[]> method = MethodDescriptor.<byte[], byte[]>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(MethodDescriptor.generateFullMethodName("svc", "unary"))
        .setRequestMarshaller(new ByteArrayMarshaller())
        .setResponseMarshaller(new ByteArrayMarshaller())
        .build();

    ServerServiceDefinition svc = ServerServiceDefinition.builder("svc")
        .addMethod(method, ServerCalls.asyncUnaryCall((req, obs) -> {
          obs.onNext(new byte[0]);
          obs.onCompleted();
        }))
        .build();

    Server server = InProcessServerBuilder.forName(name)
        .addService(svc)
        .intercept(new TraceServerInterceptor())
        .build()
        .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(name).intercept(new TraceClientInterceptor()).build();
    try {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
      var span = tracer.spanBuilder("root").setSpanKind(SpanKind.INTERNAL).startSpan();
      try (var scope = span.makeCurrent()) {
        ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT, new byte[0]);
      } finally {
        span.end();
      }
      assertEquals(span.getSpanContext().getTraceId(), TraceServerInterceptor.lastObservedTraceId);
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
    }
  }

  static final class ByteArrayMarshaller implements MethodDescriptor.Marshaller<byte[]> {
    @Override public InputStream stream(byte[] value) { return new java.io.ByteArrayInputStream(value); }
    @Override public byte[] parse(InputStream stream) { try { return stream.readAllBytes(); } catch (Exception e) { return new byte[0]; } }
  }
}
