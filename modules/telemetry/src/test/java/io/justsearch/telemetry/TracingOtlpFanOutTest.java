package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TracingOtlpFanOutTest {
  @Test
  void exportsLocallyAndFansOutOverOtlpHttp() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-otlp-fanout");
    AtomicInteger requestCount = new AtomicInteger();
    CountDownLatch latch = new CountDownLatch(1);
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/v1/traces", exchange -> {
      requestCount.incrementAndGet();
      try (InputStream in = exchange.getRequestBody()) {
        while (in.read() != -1) {
          // drain request body
        }
      }
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
      latch.countDown();
    });
    server.start();

    GlobalOpenTelemetry.resetForTest();
    try {
      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
      try (var bootstrap = new TracingBootstrap(tmp, null, Sampler.alwaysOn(), Map.of(
          "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", endpoint,
          "OTEL_EXPORTER_OTLP_TRACES_TIMEOUT", "5000"))) {
        var tracer = GlobalOpenTelemetry.get().getTracer("test");
        var span = tracer.spanBuilder("fanout.test").startSpan();
        span.end();
        bootstrap.flush();
      }

      assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected OTLP HTTP exporter request");
      assertTrue(requestCount.get() > 0, "Expected at least one OTLP export request");
      String content = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
      assertTrue(content.contains("\"name\":\"fanout.test\""));
    } finally {
      server.stop(0);
      GlobalOpenTelemetry.resetForTest();
    }
  }
}
