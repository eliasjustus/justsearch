package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import java.util.Locale;

class CollectorSmokeIT {
  @Test
  void emitsSignalsWhenCollectorPresent() throws Exception {
    String otelcol = System.getenv("OTELCOL_BIN");
    assumeTrue(otelcol != null && !otelcol.isBlank(), "OTELCOL_BIN not provided; skipping CollectorSmokeIT");

    Path dataDir = Files.createTempDirectory("otelcol-smoke");
    var pb = new ProcessBuilder(otelcol, "--config", "scripts/otel-collector.blessed.yaml");
    pb.redirectErrorStream(true);
    pb.environment().put("JUSTSEARCH_DATA_DIR", dataDir.toString());
    Process proc = pb.start();
    try {
      // Give the collector a moment to start
      Thread.sleep(1500);

      // Configure OTLP exporters to default endpoints
      OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder().setTimeout(Duration.ofSeconds(5)).build();
      SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
          .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build()).build();
      OpenTelemetrySdk otel = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
      GlobalOpenTelemetry.resetForTest();
      GlobalOpenTelemetry.set(otel);

      Tracer tracer = GlobalOpenTelemetry.get().getTracer("smoke");
      var span = tracer.spanBuilder("smoke.test").startSpan();
      span.end();

      // Metrics: wire OTLP exporter via periodic reader and emit a counter
      var metricExporter = OtlpGrpcMetricExporter.builder().setTimeout(Duration.ofSeconds(5)).build();
      var reader = PeriodicMetricReader.builder(metricExporter).setInterval(Duration.ofMillis(250)).build();
      SdkMeterProvider mp = SdkMeterProvider.builder().registerMetricReader(reader).build();
      Meter m = mp.get("smoke");
      m.counterBuilder("smoke.counter").build().add(1, Attributes.empty());

      // Logs path: write a JSON line to be tailed by filelog
      Files.createDirectories(dataDir.resolve("logs"));
      Files.writeString(dataDir.resolve("logs").resolve("app.log"), "{\"message\":\"smoke-log\"}\n");

      // Allow export/ingestion windows
      Thread.sleep(1500);

      // Drain some collector stdout for simple verification
      StringBuilder out = new StringBuilder();
      var in = proc.getInputStream();
      long start = System.currentTimeMillis();
      while (System.currentTimeMillis() - start < 3000) {
        while (in.available() > 0) {
          out.append((char) in.read());
        }
        Thread.sleep(50);
      }

      // If we reach here, also ensure graceful shutdown of SDKs
      mp.close();
      tracerProvider.close();

      String stdout = out.toString();
      assertTrue(stdout.contains("smoke.test"), "collector output should include trace name");
      assertTrue(stdout.contains("smoke.counter") || stdout.toLowerCase(Locale.ROOT).contains("metric"), "collector output should include metrics");
      assertTrue(stdout.contains("smoke-log"), "collector output should include log body");
    } finally {
      proc.destroy();
    }
  }
}
