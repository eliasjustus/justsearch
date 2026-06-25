package io.justsearch.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 402 §3.3: ContractEmitter.emit attaches events with the consumer-locked shape. */
class ContractEmitterTest {

  private InMemorySpanExporter exporter;
  private SdkTracerProvider tracerProvider;
  private OpenTelemetry otel;

  @BeforeEach
  void setUp() {
    exporter = InMemorySpanExporter.create();
    tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    otel = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
  }

  @AfterEach
  void tearDown() {
    tracerProvider.close();
  }

  @Test
  void emitAttachesEventToCurrentSpan() {
    Tracer tracer = otel.getTracer("test");
    Span span = tracer.spanBuilder("parent").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      ContractEmitter.emit("@SampleContract", "402 P5", "test description");
    } finally {
      span.end();
    }

    List<SpanData> finished = exporter.getFinishedSpanItems();
    assertEquals(1, finished.size());
    List<EventData> events = finished.get(0).getEvents();
    assertEquals(1, events.size(), "emit must produce exactly one event");

    EventData event = events.get(0);
    assertEquals("contract.violation", event.getName());
    assertEquals(
        "402 P5", event.getAttributes().get(ContractEmitter.TEMPDOC_KEY));
    assertEquals(
        "@SampleContract", event.getAttributes().get(ContractEmitter.TIER_KEY));
    assertEquals(
        "test description", event.getAttributes().get(ContractEmitter.DESCRIPTION_KEY));
  }

  @Test
  void emitOnNoopSpanIsNoOp() {
    // No active span context: Span.current() returns a no-op span. emit must
    // not throw; nothing is exported because no SdkTracerProvider is bound to
    // GlobalOpenTelemetry — the local OpenTelemetrySdk in this test is only
    // used when explicitly scoped via makeCurrent().
    ContractEmitter.emit("@SampleContract", "402 P5", "no-op check");

    assertTrue(
        exporter.getFinishedSpanItems().isEmpty(),
        "no span was started, so nothing should be exported");
  }

  @Test
  void eventNameAndAttrKeysMatchConsumerLock() {
    // contract_violations.py reads the event name + 3 attr keys as literal
    // strings (scripts/jseval/jseval/projections/contract_violations.py:44,
    // 108-110). A rename on the Java side silently drops events — the D-1
    // silent-failure class tempdoc 402 exists to eliminate. These literals
    // must stay in lockstep with the Python consumer.
    assertEquals("contract.violation", ContractEmitter.EVENT_NAME);
    assertEquals("contract.tempdoc", ContractEmitter.TEMPDOC_KEY.getKey());
    assertEquals("contract.tier", ContractEmitter.TIER_KEY.getKey());
    assertEquals("contract.description", ContractEmitter.DESCRIPTION_KEY.getKey());
  }

  @Test
  void emitWithoutMakeCurrentScopeEmitsNothing() {
    // Starting a span does not make it current — you need makeCurrent(). If a
    // call site calls emit without establishing scope, Span.current() is a
    // no-op and the event is dropped. Documented behavior; this test pins it.
    Tracer tracer = otel.getTracer("test");
    Span span = tracer.spanBuilder("parent").startSpan();
    try {
      ContractEmitter.emit("@SampleContract", "402 P5", "no scope established");
    } finally {
      span.end();
    }

    List<SpanData> finished = exporter.getFinishedSpanItems();
    assertEquals(1, finished.size());
    assertTrue(
        finished.get(0).getEvents().isEmpty(),
        "emit without makeCurrent attaches to no-op span; the real span has no events");
  }

  @Test
  void emitRejectsNullArgsWithNpe() {
    // Cleanup fix for tempdoc 402 P2-P6 critical-analysis Issue 10: pin the
    // null-rejection contract. OTel's Attributes.of throws NullPointerException
    // before any event is attached; call sites must guard nulls at the source.
    // This test exists so a future null-coercion refactor (e.g. "treat null
    // as empty string") fails loudly instead of silently changing the contract.
    Tracer tracer = otel.getTracer("test");
    Span span = tracer.spanBuilder("parent").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      assertThrows(
          NullPointerException.class, () -> ContractEmitter.emit(null, "402", "desc"));
      assertThrows(
          NullPointerException.class, () -> ContractEmitter.emit("@SampleContract", null, "desc"));
      assertThrows(
          NullPointerException.class, () -> ContractEmitter.emit("@SampleContract", "402", null));
    } finally {
      span.end();
    }
    // No event was attached on any failure — Attributes.of throws before
    // addEvent is called.
    assertEquals(1, exporter.getFinishedSpanItems().size());
    assertTrue(exporter.getFinishedSpanItems().get(0).getEvents().isEmpty());
  }

  @Test
  void emitWithEmptyStringsIsAcceptable() {
    // tempdoc 402 §3.3: empty strings are acceptable for the description;
    // they are not null, so OTel accepts them. Projection consumer falls back
    // to "<unknown>" only on missing keys, not on empty values.
    Tracer tracer = otel.getTracer("test");
    Span span = tracer.spanBuilder("parent").startSpan();
    try (Scope ignored = span.makeCurrent()) {
      ContractEmitter.emit("", "", "");
    } finally {
      span.end();
    }

    List<SpanData> finished = exporter.getFinishedSpanItems();
    assertEquals(1, finished.size());
    List<EventData> events = finished.get(0).getEvents();
    assertEquals(1, events.size());
    assertEquals("", events.get(0).getAttributes().get(ContractEmitter.TEMPDOC_KEY));
    assertFalse(events.get(0).getAttributes().isEmpty());
  }
}
