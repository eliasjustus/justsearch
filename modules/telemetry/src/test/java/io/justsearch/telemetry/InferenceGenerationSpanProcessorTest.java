package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 518 Appendix F W2.2 — end-to-end: span emitted while a supplier is registered
 * acquires the {@code justsearch.inference.generation} attribute; with no supplier (sentinel
 * -1), the attribute is absent.
 */
@DisplayName("InferenceGenerationSpanProcessor — span attribute end-to-end")
final class InferenceGenerationSpanProcessorTest {

  @AfterEach
  void cleanup() {
    InferenceGenerationContext.resetForTest();
    GlobalOpenTelemetry.resetForTest();
  }

  @Test
  @DisplayName("registered supplier: span carries justsearch.inference.generation")
  void registeredSupplierTagsSpan() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-gen-tag");
    GlobalOpenTelemetry.resetForTest();
    AtomicLong gen = new AtomicLong(7L);
    InferenceGenerationContext.set(gen::get);

    try (var bootstrap = new TracingBootstrap(tmp)) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
      var span = tracer.spanBuilder("test.span").setSpanKind(SpanKind.INTERNAL).startSpan();
      span.end();
      bootstrap.flush();
    }

    String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    assertTrue(
        content.contains("\"justsearch.inference.generation\":\"7\""),
        "expected inference generation attr; got: " + content);
  }

  @Test
  @DisplayName("no supplier registered (sentinel -1): attribute is absent")
  void noSupplierOmitsAttribute() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-gen-nosup");
    GlobalOpenTelemetry.resetForTest();
    InferenceGenerationContext.resetForTest();

    try (var bootstrap = new TracingBootstrap(tmp)) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
      var span = tracer.spanBuilder("test.span").setSpanKind(SpanKind.INTERNAL).startSpan();
      span.end();
      bootstrap.flush();
    }

    String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    assertFalse(
        content.contains("justsearch.inference.generation"),
        "expected no inference generation attr; got: " + content);
  }
}
