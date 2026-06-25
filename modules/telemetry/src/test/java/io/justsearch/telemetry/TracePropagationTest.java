package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracePropagationTest {
  @Test
  void w3cTraceContextInjectExtractMaintainsTraceId() {
    // Reset global to a fresh SDK to avoid prior registrations
    GlobalOpenTelemetry.resetForTest();
    OpenTelemetrySdk.builder().setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).buildAndRegisterGlobal();
    Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
    var span = tracer.spanBuilder("root").setSpanKind(SpanKind.INTERNAL).startSpan();
    try (var scope = span.makeCurrent()) {
      Map<String, String> carrier = new HashMap<>();
      var setter = new TextMapSetter<Map<String, String>>() { @Override public void set(Map<String, String> c, String k, String v) { c.put(k, v); } };
      var getter = new TextMapGetter<Map<String, String>>() { @Override public String get(Map<String, String> c, String k) { return c.get(k); } @Override public Iterable<String> keys(Map<String, String> c) { return c.keySet(); } };
      W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, setter);
      Context extracted = W3CTraceContextPropagator.getInstance().extract(Context.current(), carrier, getter);
      var extractedSpan = Span.fromContext(extracted);
      assertEquals(span.getSpanContext().getTraceId(), extractedSpan.getSpanContext().getTraceId());
    } finally {
      span.end();
    }
  }
}
