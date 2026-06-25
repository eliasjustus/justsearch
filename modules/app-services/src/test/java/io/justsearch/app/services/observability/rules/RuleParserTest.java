package io.justsearch.app.services.observability.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.observability.health.Severity;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuleParser")
final class RuleParserTest {

  private static InputStream input(String yaml) {
    return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
  }

  private static final String VALID_RULE =
      """
      rule: memory-pressure
      kind: threshold
      emits:
        id: memory.pressure
        subject: head.memory
        reason: MemoryPressureHigh
        severity: WARNING
      expr_cel: |
        signals['head.jvm.memory.heap.used_bytes'].latest()
          / signals['head.jvm.memory.heap.max_bytes'].latest() > 0.9
      for: 60s
      keep_firing_for: 30s
      magnitudes_cel:
        used_bytes: signals['head.jvm.memory.heap.used_bytes'].latest()
        max_bytes: signals['head.jvm.memory.heap.max_bytes'].latest()
      """;

  @Test
  @DisplayName("valid rule parses with all fields populated")
  void validRuleParses() throws IOException {
    Rule rule = RuleParser.parse(input(VALID_RULE), "memory-pressure.yaml");

    assertEquals("memory-pressure", rule.name());
    assertEquals(Rule.Kind.THRESHOLD, rule.kind());
    assertEquals("memory.pressure", rule.emits().id());
    assertEquals("head.memory", rule.emits().subject());
    assertEquals("MemoryPressureHigh", rule.emits().reason());
    assertEquals(Severity.WARNING, rule.emits().severity());
    assertTrue(rule.exprCel().contains("signals['head.jvm.memory.heap.used_bytes']"));
    assertEquals(Duration.ofSeconds(60), rule.forDuration());
    assertEquals(Duration.ofSeconds(30), rule.keepFiringFor());
    assertEquals(2, rule.magnitudesCel().size());
    assertTrue(rule.magnitudesCel().containsKey("used_bytes"));
    assertTrue(rule.magnitudesCel().containsKey("max_bytes"));
  }

  @Test
  @DisplayName("missing 'rule' field rejected with clear message")
  void missingRuleNameRejected() {
    String yaml =
        """
        kind: threshold
        emits:
          id: foo
          subject: bar
          reason: Bar
          severity: INFO
        expr_cel: 'true'
        for: 0s
        keep_firing_for: 0s
        """;
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> RuleParser.parse(input(yaml), "broken.yaml"));
    assertTrue(ex.getMessage().contains("missing required field 'rule'"));
  }

  @Test
  @DisplayName("missing 'emits' object rejected")
  void missingEmitsRejected() {
    String yaml =
        """
        rule: x
        kind: condition
        expr_cel: 'true'
        for: 0s
        keep_firing_for: 0s
        """;
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> RuleParser.parse(input(yaml), "broken.yaml"));
    assertTrue(ex.getMessage().contains("missing 'emits'"));
  }

  @Test
  @DisplayName("unknown kind rejected with clear message")
  void unknownKindRejected() {
    String yaml =
        """
        rule: x
        kind: bogus
        emits:
          id: a
          subject: b
          reason: C
          severity: INFO
        expr_cel: 'true'
        for: 0s
        keep_firing_for: 0s
        """;
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> RuleParser.parse(input(yaml), "bogus-kind.yaml"));
    assertTrue(ex.getMessage().contains("kind='bogus'"));
  }

  @Test
  @DisplayName("unknown severity rejected with clear message")
  void unknownSeverityRejected() {
    String yaml =
        """
        rule: x
        kind: condition
        emits:
          id: a
          subject: b
          reason: C
          severity: CRITICAL
        expr_cel: 'true'
        for: 0s
        keep_firing_for: 0s
        """;
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> RuleParser.parse(input(yaml), "bogus-severity.yaml"));
    assertTrue(ex.getMessage().contains("severity='CRITICAL'"));
  }

  @Test
  @DisplayName("duration parses '60s', '5m', '1h', '2d'")
  void durationParsing() {
    assertEquals(Duration.ofSeconds(60), RuleParser.parseDuration("60s", "x", "for"));
    assertEquals(Duration.ofMinutes(5), RuleParser.parseDuration("5m", "x", "for"));
    assertEquals(Duration.ofHours(1), RuleParser.parseDuration("1h", "x", "for"));
    assertEquals(Duration.ofDays(2), RuleParser.parseDuration("2d", "x", "for"));
    assertEquals(Duration.ZERO, RuleParser.parseDuration("0s", "x", "for"));
    assertEquals(Duration.ZERO, RuleParser.parseDuration("  ", "x", "for"));
  }

  @Test
  @DisplayName("malformed duration rejected")
  void malformedDurationRejected() {
    IllegalArgumentException badSuffix =
        assertThrows(
            IllegalArgumentException.class,
            () -> RuleParser.parseDuration("60x", "rule.yaml", "for"));
    assertTrue(badSuffix.getMessage().contains("unrecognized suffix"));

    IllegalArgumentException badMagnitude =
        assertThrows(
            IllegalArgumentException.class,
            () -> RuleParser.parseDuration("abcs", "rule.yaml", "for"));
    assertTrue(badMagnitude.getMessage().contains("unparsable magnitude"));

    IllegalArgumentException tooShort =
        assertThrows(
            IllegalArgumentException.class,
            () -> RuleParser.parseDuration("s", "rule.yaml", "for"));
    assertTrue(tooShort.getMessage().contains("too short"));
  }

  @Test
  @DisplayName("magnitudes_cel optional; absent → empty map")
  void magnitudesOptional() throws IOException {
    String yaml =
        """
        rule: simple
        kind: condition
        emits:
          id: a.b
          subject: a
          reason: B
          severity: INFO
        expr_cel: 'true'
        for: 0s
        keep_firing_for: 0s
        """;
    Rule rule = RuleParser.parse(input(yaml), "simple.yaml");
    assertTrue(rule.magnitudesCel().isEmpty());
  }

  @Test
  @DisplayName("magnitudes_cel non-string value rejected")
  void magnitudesValueMustBeString() {
    String yaml =
        """
        rule: x
        kind: threshold
        emits:
          id: a.b
          subject: a
          reason: B
          severity: WARNING
        expr_cel: 'true'
        for: 0s
        keep_firing_for: 0s
        magnitudes_cel:
          good: 'signals.x.latest()'
          bad: 123
        """;
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> RuleParser.parse(input(yaml), "magnitudes.yaml"));
    assertTrue(ex.getMessage().contains("magnitudes_cel.bad"));
  }

  @Test
  @DisplayName("non-object root rejected")
  void nonObjectRootRejected() {
    String yaml = "[1, 2, 3]\n";
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> RuleParser.parse(input(yaml), "list.yaml"));
    assertTrue(ex.getMessage().contains("not a YAML object root"));
  }
}
