package io.justsearch.telemetry;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.fieldnames.LogstashFieldNames;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;

import java.nio.file.Files;
import java.nio.file.Path;

class LogTraceCorrelationTest {
  @Test
  void logsContainSameTraceIdAsTracesNdjson() throws Exception {
    Path tmp = Files.createTempDirectory("telemetry-corr");

    // Configure logback to write to tmp/logs/app.log with MDC trace_id
    Path logsDir = tmp.resolve("logs");
    Files.createDirectories(logsDir);
    Path logFile = logsDir.resolve("app.log");
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    FileAppender<ILoggingEvent> fa = new FileAppender<>();
    fa.setAppend(true);
    fa.setFile(logFile.toString());
    LogstashEncoder enc = new LogstashEncoder();
    LogstashFieldNames f = new LogstashFieldNames();
    f.setTimestamp("@timestamp");
    f.setLogger("logger");
    f.setThread("thread");
    f.setMessage("message");
    enc.setFieldNames(f);
    enc.addIncludeMdcKeyName("trace_id");
    enc.start();
    fa.setEncoder(enc);
    fa.setContext(root.getLoggerContext());
    fa.start();
    root.addAppender(fa);

    // Bootstrap tracing and start a span
    GlobalOpenTelemetry.resetForTest();
    try (var ignored = new TracingBootstrap(tmp)) {
      Tracer tracer = GlobalOpenTelemetry.get().getTracer("test");
      var span = tracer.spanBuilder("corr.test").startSpan();
      try (var scope = span.makeCurrent()) {
        String traceId = span.getSpanContext().getTraceId();
        MDC.put("trace_id", traceId);
        LoggerFactory.getLogger("io.justsearch.test").info("hello");
      } finally {
        span.end();
      }
      ignored.flush();
    }

    String traceContent = Files.readString(tmp.resolve("telemetry").resolve("traces.ndjson"));
    String logs = Files.readString(logFile);
    // Extract a trace_id from traces and assert it appears in logs
    String firstTraceId = traceContent.lines()
        .filter(l -> l.contains("\"trace_id\":\""))
        .findFirst().orElse("");
    int idx = firstTraceId.indexOf("\"trace_id\":\"");
    String tid = idx >= 0 ? firstTraceId.substring(idx+13, idx+13+32) : "";
    assertTrue(!tid.isEmpty());
    assertTrue(logs.contains(tid));
  }
}
