package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracingLocalExportTest {
  @Test
  void writesRootAndStageSpansWithAttrs() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-trace");
    // reset global before installing bootstrap
    GlobalOpenTelemetry.resetForTest();
    try (var ignored = new TracingBootstrap(tmp)) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
      // pipeline_hash + budget_profile retired by tempdoc 400 LR2-d (orphan per ADR 0014).
      // Tempdoc 400 LR2-d.2: commit.* identity attrs replace the retired slot.
      var root = tracer.spanBuilder("search.request").setSpanKind(SpanKind.INTERNAL)
          .setAttribute("pipeline_name", "search_default")
          .setAttribute("commit.schema_fp", "fp-schema")
          .setAttribute("commit.field_catalog_hash", "fp-fc")
          .setAttribute("commit.analyzer_fp", "fp-analyzer")
          .setAttribute("commit.index_schema_fp", "fp-idx-schema")
          .startSpan();
      try {
        var stage = tracer.spanBuilder("pipeline.stage.rerank").setSpanKind(SpanKind.INTERNAL)
            .setAttribute("stage_id", "rerank").startSpan();
        stage.addEvent("skip", io.opentelemetry.api.common.Attributes.of(AttributeKey.stringKey("reason_code"), "rerank_skipped_deadline"));
        stage.end();
      } finally {
        root.setStatus(StatusCode.OK);
        root.end();
      }
      ignored.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    assertTrue(content.contains("\"name\":\"search.request\""));
    assertTrue(content.contains("\"name\":\"pipeline.stage.rerank\""));
    assertTrue(content.contains("\"parent_span_id\":\""), "Should export parent_span_id field");
    assertTrue(content.contains("\"pipeline_name\":\"search_default\""));
    assertTrue(content.contains("\"stage_id\":\"rerank\""));
    // Tempdoc 400 LR2-d.2: commit.* identity attrs must round-trip through the
    // allowlist filter.
    assertTrue(content.contains("\"commit.schema_fp\":\"fp-schema\""));
    assertTrue(content.contains("\"commit.field_catalog_hash\":\"fp-fc\""));
    assertTrue(content.contains("\"commit.analyzer_fp\":\"fp-analyzer\""));
    assertTrue(content.contains("\"commit.index_schema_fp\":\"fp-idx-schema\""));
  }

  @Test
  void writesEncoderOrtRunAttrsThroughAllowlist() throws Exception {
    // Tempdoc 400 LR2-a: encoder.ort_run span attrs (encoder.name, encoder.gpu,
    // encoder.batch_size, encoder.seq_len) must round-trip through the
    // NdjsonSpanExporter allowlist filter.
    Path tmp = Files.createTempDirectory("telemetry-encoder-ortrun");
    GlobalOpenTelemetry.resetForTest();
    try (var ignored = new TracingBootstrap(tmp)) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("encoder.embed");
      var span = tracer.spanBuilder("encoder.ort_run").setSpanKind(SpanKind.INTERNAL)
          .setAttribute("encoder.name", "embed")
          .setAttribute("encoder.gpu", true)
          .setAttribute("encoder.batch_size", 16L)
          .setAttribute("encoder.seq_len", 512L)
          .startSpan();
      span.end();
      ignored.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    assertTrue(content.contains("\"name\":\"encoder.ort_run\""));
    assertTrue(content.contains("\"encoder.name\":\"embed\""));
    assertTrue(content.contains("\"encoder.gpu\":\"true\""));
    assertTrue(content.contains("\"encoder.batch_size\":\"16\""));
    assertTrue(content.contains("\"encoder.seq_len\":\"512\""));
    // Tempdoc 400 §23.8 D-1: NdjsonSpanExporter emits duration_ms as a
    // structural field (nanosecond-sourced). Consumers that read span
    // durations (LR4-g encoder_drift projection) depend on this field
    // being present without reconstruction from ISO start/end strings.
    assertTrue(
        content.contains("\"duration_ms\":"),
        "duration_ms structural field missing from exported span: " + content);
  }

  @Test
  void writesLeaseAcquireAttrsThroughAllowlist() throws Exception {
    // Tempdoc 400 LR2-b: lease.acquire span attrs (lease.mode,
    // lease.wait_queue_depth) must round-trip through the allowlist filter.
    Path tmp = Files.createTempDirectory("telemetry-lease-acquire");
    GlobalOpenTelemetry.resetForTest();
    try (var ignored = new TracingBootstrap(tmp)) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("ort.lease");
      var span = tracer.spanBuilder("lease.acquire").setSpanKind(SpanKind.INTERNAL)
          .setAttribute("lease.mode", "gpu")
          .setAttribute("lease.wait_queue_depth", 3L)
          .startSpan();
      span.end();
      ignored.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    assertTrue(content.contains("\"name\":\"lease.acquire\""));
    assertTrue(content.contains("\"lease.mode\":\"gpu\""));
    assertTrue(content.contains("\"lease.wait_queue_depth\":\"3\""));
  }

  @Test
  void writesContractViolationEventsThroughAllowlist() throws Exception {
    // Tempdoc 402 P6: contract.violation events with the 3 attrs (tempdoc,
    // tier, description) must round-trip through NdjsonSpanExporter. Consumer
    // lock: scripts/jseval/jseval/projections/contract_violations.py reads
    // span["events"][*].attrs["contract.tempdoc" / "contract.tier" /
    // "contract.description"]. An event whose name is NOT on
    // ALLOWED_EVENT_NAMES must be dropped.
    Path tmp = Files.createTempDirectory("telemetry-contract-violation");
    GlobalOpenTelemetry.resetForTest();
    try (var ignored = new TracingBootstrap(tmp)) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
      var span = tracer.spanBuilder("contract.parent").setSpanKind(SpanKind.INTERNAL).startSpan();
      span.addEvent(
          "contract.violation",
          Attributes.builder()
              .put(AttributeKey.stringKey("contract.tempdoc"), "402 P6")
              .put(AttributeKey.stringKey("contract.tier"), "@SampleContract")
              .put(AttributeKey.stringKey("contract.description"), "test invariant")
              .build());
      // Not on ALLOWED_EVENT_NAMES — must be dropped.
      span.addEvent(
          "other.event",
          Attributes.builder()
              .put(AttributeKey.stringKey("contract.tempdoc"), "should-not-appear")
              .build());
      span.end();
      ignored.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    // Structural: every span now carries an "events" array (possibly empty).
    assertTrue(
        content.contains("\"events\":["),
        "exporter must emit events array: " + content);
    // Allowed event name + all 3 attrs present:
    assertTrue(
        content.contains("\"name\":\"contract.violation\""),
        "contract.violation event missing from export: " + content);
    assertTrue(
        content.contains("\"contract.tempdoc\":\"402 P6\""),
        "contract.tempdoc attr missing: " + content);
    assertTrue(
        content.contains("\"contract.tier\":\"@SampleContract\""),
        "contract.tier attr missing: " + content);
    assertTrue(
        content.contains("\"contract.description\":\"test invariant\""),
        "contract.description attr missing: " + content);
    // Disallowed event name must not appear — ALLOWED_EVENT_NAMES filter.
    assertFalse(
        content.contains("\"name\":\"other.event\""),
        "other.event should be dropped by ALLOWED_EVENT_NAMES: " + content);
    assertFalse(
        content.contains("should-not-appear"),
        "attrs of a filtered-out event must not leak: " + content);
  }

  @Test
  void writesCpuFallbackTriggeredEventsThroughAllowlist() throws Exception {
    // Tempdoc 402 P6 cleanup: cpu_fallback.triggered events produced by
    // NativeSessionHandle#reportCpuSessionFailure and
    // EncoderOrtRunSpans#emitCpuFallbackEvent must round-trip so
    // cpu_fallback_counts.py can aggregate them. This path was silently
    // dropped pre-P6 (the exporter never emitted events); the P6 allowlist
    // now covers both contract.violation and cpu_fallback.triggered.
    Path tmp = Files.createTempDirectory("telemetry-cpu-fallback");
    GlobalOpenTelemetry.resetForTest();
    try (var ignored = new TracingBootstrap(tmp)) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
      var span = tracer.spanBuilder("encoder.ort_run").setSpanKind(SpanKind.INTERNAL).startSpan();
      span.addEvent(
          "cpu_fallback.triggered",
          Attributes.builder()
              .put(AttributeKey.stringKey("fallback.cause"), "cpu_session_failure")
              .put(AttributeKey.stringKey("fallback.encoder"), "splade")
              .build());
      span.end();
      ignored.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    assertTrue(
        content.contains("\"name\":\"cpu_fallback.triggered\""),
        "cpu_fallback.triggered event missing: " + content);
    assertTrue(
        content.contains("\"fallback.cause\":\"cpu_session_failure\""),
        "fallback.cause attr missing: " + content);
    assertTrue(
        content.contains("\"fallback.encoder\":\"splade\""),
        "fallback.encoder attr missing: " + content);
  }

  @Test
  void writesWorkflowRunResourceAttrsToLocalTraceExport() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-workflow-resource");
    GlobalOpenTelemetry.resetForTest();
    try (var ignored = new TracingBootstrap(tmp, null, Sampler.alwaysOn(), Map.of(
        "JUSTSEARCH_WORKFLOW_RUN_ID", "workflow-run-123",
        "JUSTSEARCH_WORKFLOW_FAMILY", "beir-gate"))) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
      var span = tracer.spanBuilder("workflow.resource.test").setSpanKind(SpanKind.INTERNAL).startSpan();
      span.end();
      ignored.flush();
    }
    String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    assertTrue(content.contains("\"name\":\"workflow.resource.test\""));
    assertTrue(content.contains("\"justsearch.workflow.run_id\":\"workflow-run-123\""));
    assertTrue(content.contains("\"justsearch.workflow.family\":\"beir-gate\""));
  }
}
