package io.justsearch.ipc.grpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.grpc.Metadata;
import io.justsearch.ipc.grpc.TraceClientInterceptor;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

final class TraceClientInterceptorTest {

  @Test
  void setterPopulatesTraceHeaders() throws Exception {
    TextMapSetter<Metadata> setter = setter();
    Metadata metadata = new Metadata();

    setter.set(metadata, "traceparent", "00-1234-5678-01");
    setter.set(metadata, "tracestate", "vendor=value");

    Metadata.Key<String> traceparent =
        Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    Metadata.Key<String> tracestate =
        Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);

    assertEquals("00-1234-5678-01", metadata.get(traceparent));
    assertEquals("vendor=value", metadata.get(tracestate));
  }

  @Test
  void setterIgnoresUnknownKeysAndNullValues() throws Exception {
    TextMapSetter<Metadata> setter = setter();
    Metadata metadata = new Metadata();

    setter.set(metadata, "traceparent", null);
    setter.set(metadata, "custom", "ignored");
    setter.set(null, "traceparent", "value");

    Metadata.Key<String> traceparent =
        Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);

    assertNull(metadata.get(traceparent));
  }

  @SuppressWarnings("unchecked")
  private TextMapSetter<Metadata> setter() throws Exception {
    Field field = TraceClientInterceptor.class.getDeclaredField("SETTER");
    field.setAccessible(true);
    return (TextMapSetter<Metadata>) field.get(null);
  }
}
