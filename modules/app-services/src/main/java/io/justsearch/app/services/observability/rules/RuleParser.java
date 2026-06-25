/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import io.justsearch.app.observability.health.Severity;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

/**
 * Parses operational-signal rule YAML files into {@link Rule} records.
 *
 * <p>Per tempdoc 430 §A.3: rule YAML schema is fixed and documented; this parser performs
 * structural validation (required fields present, durations parseable, severity recognized).
 * CEL expression compilation is deferred to {@code CelEvaluator} — parsing only validates
 * the shape, not the predicate's semantic correctness.
 *
 * <p>Mirrors the Jackson YAMLFactory pattern from {@code ComponentsFactoryTest} in
 * {@code adapters-lucene} (per tempdoc 430 rev 3.11 §B.X investigation).
 */
public final class RuleParser {

  private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

  private RuleParser() {}

  /**
   * Parses a YAML rule file from an input stream.
   *
   * @param in the input stream (closed by the caller)
   * @param sourceLabel a human-readable label used in error messages (e.g., the file name)
   * @return the parsed {@link Rule}
   * @throws IllegalArgumentException if a required field is missing or unparsable
   * @throws IOException if the stream cannot be read
   */
  public static Rule parse(InputStream in, String sourceLabel) throws IOException {
    Objects.requireNonNull(in, "in");
    Objects.requireNonNull(sourceLabel, "sourceLabel");
    JsonNode root = YAML_MAPPER.readTree(in);
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException(
          "RuleParser: " + sourceLabel + " is not a YAML object root");
    }

    String name = requireString(root, "rule", sourceLabel);
    Rule.Kind kind = parseKind(requireString(root, "kind", sourceLabel), sourceLabel);
    Rule.Emits emits = parseEmits(root.get("emits"), sourceLabel);
    String exprCel = requireString(root, "expr_cel", sourceLabel);
    Duration forDuration = parseDuration(requireString(root, "for", sourceLabel), sourceLabel, "for");
    Duration keepFiringFor =
        parseDuration(
            requireString(root, "keep_firing_for", sourceLabel), sourceLabel, "keep_firing_for");
    Map<String, String> magnitudesCel = parseMagnitudes(root.get("magnitudes_cel"), sourceLabel);

    return new Rule(name, kind, emits, exprCel, forDuration, keepFiringFor, magnitudesCel);
  }

  // ----- helpers -----

  private static String requireString(JsonNode node, String field, String sourceLabel) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException(
          "RuleParser: " + sourceLabel + " missing required field '" + field + "'");
    }
    if (!value.isTextual()) {
      throw new IllegalArgumentException(
          "RuleParser: " + sourceLabel + " field '" + field + "' must be a string");
    }
    return value.asText();
  }

  private static Rule.Kind parseKind(String raw, String sourceLabel) {
    String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
    try {
      return Rule.Kind.valueOf(upper);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "RuleParser: " + sourceLabel + " kind='" + raw + "' is not one of CONDITION/THRESHOLD");
    }
  }

  private static Rule.Emits parseEmits(JsonNode emitsNode, String sourceLabel) {
    if (emitsNode == null || !emitsNode.isObject()) {
      throw new IllegalArgumentException(
          "RuleParser: " + sourceLabel + " missing 'emits' object");
    }
    String id = requireString(emitsNode, "id", sourceLabel + " emits");
    String subject = requireString(emitsNode, "subject", sourceLabel + " emits");
    String reason = requireString(emitsNode, "reason", sourceLabel + " emits");
    String severityRaw = requireString(emitsNode, "severity", sourceLabel + " emits");
    Severity severity;
    try {
      severity = Severity.valueOf(severityRaw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "RuleParser: "
              + sourceLabel
              + " emits.severity='"
              + severityRaw
              + "' is not one of INFO/WARNING/ERROR");
    }
    return new Rule.Emits(id, subject, reason, severity);
  }

  /**
   * Parses Prometheus-style duration strings: {@code 60s}, {@code 5m}, {@code 1h}, {@code 2d}.
   * Empty/whitespace-only values map to {@link Duration#ZERO}. Unrecognized suffixes throw.
   */
  static Duration parseDuration(String raw, String sourceLabel, String fieldName) {
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return Duration.ZERO;
    }
    if (trimmed.length() < 2) {
      throw new IllegalArgumentException(
          "RuleParser: "
              + sourceLabel
              + " "
              + fieldName
              + "='"
              + raw
              + "' is too short (expected like '60s', '5m', '1h', '2d')");
    }
    char suffix = trimmed.charAt(trimmed.length() - 1);
    String numericPart = trimmed.substring(0, trimmed.length() - 1);
    long magnitude;
    try {
      magnitude = Long.parseLong(numericPart);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "RuleParser: "
              + sourceLabel
              + " "
              + fieldName
              + "='"
              + raw
              + "' has unparsable magnitude '"
              + numericPart
              + "'");
    }
    if (magnitude < 0) {
      throw new IllegalArgumentException(
          "RuleParser: " + sourceLabel + " " + fieldName + "='" + raw + "' must be >= 0");
    }
    return switch (suffix) {
      case 's' -> Duration.ofSeconds(magnitude);
      case 'm' -> Duration.ofMinutes(magnitude);
      case 'h' -> Duration.ofHours(magnitude);
      case 'd' -> Duration.ofDays(magnitude);
      default ->
          throw new IllegalArgumentException(
              "RuleParser: "
                  + sourceLabel
                  + " "
                  + fieldName
                  + "='"
                  + raw
                  + "' has unrecognized suffix '"
                  + suffix
                  + "' (expected s/m/h/d)");
    };
  }

  private static Map<String, String> parseMagnitudes(JsonNode node, String sourceLabel) {
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException(
          "RuleParser: " + sourceLabel + " magnitudes_cel must be an object");
    }
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    for (String field : node.propertyNames()) {
      JsonNode value = node.get(field);
      if (value == null || !value.isTextual()) {
        throw new IllegalArgumentException(
            "RuleParser: "
                + sourceLabel
                + " magnitudes_cel."
                + field
                + " must be a CEL expression string");
      }
      result.put(field, value.asText());
    }
    return Map.copyOf(result);
  }

  // Suppress unused-import warning on DateTimeParseException — retained for future Prometheus
  // ISO-8601 duration support if we ever extend the syntax.
  @SuppressWarnings("unused")
  private static final Class<DateTimeParseException> RESERVED = DateTimeParseException.class;
}
