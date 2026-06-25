package io.justsearch.app.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ipc.logging.MdcContext;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import net.logstash.logback.fieldnames.LogstashFieldNames;
import com.networknt.schema.*;
import net.logstash.logback.marker.Markers;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class LoggingRedactionGoldenTest {
  record SensitiveString(String value) {}

  static String redact(Object x) { return (x instanceof SensitiveString) ? "[REDACTED]" : String.valueOf(x); }

  @Test
  void warnLogMatchesSchemaAndRedacts() throws Exception {
    Path tmp = Files.createTempDirectory("logs");
    Path target = tmp.resolve("app.log");
    System.setProperty("app.data_dir", tmp.toString());

    // Configure Logback programmatically with LogstashEncoder
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    FileAppender<ILoggingEvent> fa = new FileAppender<>();
    fa.setAppend(true);
    fa.setFile(target.toString());
    LogstashEncoder enc = new LogstashEncoder();
    LogstashFieldNames f = new LogstashFieldNames();
    f.setTimestamp("@timestamp");
    f.setLogger("logger");
    f.setThread("thread");
    f.setMessage("message");
    enc.setFieldNames(f);
    enc.addIncludeMdcKeyName("trace_id");
    enc.addIncludeMdcKeyName("pipeline_name");
    // pipeline_hash + budget_profile retired by tempdoc 400 LR2-d (orphan per ADR 0014)
    enc.setCustomFields("{\"service\":\"test\",\"component\":\"app-services.test\"}");
    enc.start();
    fa.setEncoder(enc);
    fa.setContext(root.getLoggerContext());
    fa.start();
    root.addAppender(fa);

    // Populate MDC and emit WARN with reason_code and sensitive value
    try (var req = MdcContext.request("7c0c5c1b0f4f0c1a7f9c0d3e5a6b7c8d", "req-1");
         var p = MdcContext.pipeline("search_default")) {
      org.slf4j.Logger log = LoggerFactory.getLogger("io.justsearch.app.services.test");
      log.warn(Markers.append("reason_code", "rerank_skipped_deadline"),
          "rerank skipped due to budget: {}",
          redact(new SensitiveString("super-secret")));
    }

    // Read last line and validate schema
    String content = Files.readString(target);
    String last = content.lines().reduce((a,b) -> b).orElse("");
    assertFalse(last.contains("super-secret"));
    assertTrue(last.contains("\"reason_code\"")); // presence checked by schema if emitted; keep minimal check

    // Validate with SSOT schema
    Path schemaPath = Path.of("..","..","SSOT","schemas","telemetry","log.schema.json").toAbsolutePath().normalize();
    try (InputStream schemaStream = Files.newInputStream(schemaPath)) {
      tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
      tools.jackson.databind.JsonNode schemaNode = mapper.readTree(schemaStream);
      SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
      var ctx = new SchemaContext(
          registry.getDialect(SpecificationVersion.DRAFT_2020_12.getDialectId()), registry);
      Schema schema = ctx.newSchema(
          SchemaLocation.of(schemaPath.toUri().toString()), schemaNode, null);
      tools.jackson.databind.JsonNode node = mapper.readTree(last);
      var result = schema.validate(node);
      assertTrue(result.isEmpty(), () -> "Schema violations: " + result);
    }
  }
}
