/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.ui.api.RouteManifestController.RouteEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;

/** Offline exporter for the committed reference-client structural OpenAPI inventory. */
public final class OpenApiSnapshotExporter {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private static final DefaultPrettyPrinter JSON_STRINGIFY_PRETTY_PRINTER =
      new DefaultPrettyPrinter(
              Separators.createDefaultInstance()
                  .withObjectNameValueSpacing(Separators.Spacing.AFTER))
          .withArrayIndenter(new DefaultIndenter("  ", "\n"))
          .withObjectIndenter(new DefaultIndenter("  ", "\n"));

  private OpenApiSnapshotExporter() {}

  /**
   * Usage: {@code OpenApiSnapshotExporter <route-snapshot> <openapi-snapshot> [--check]}.
   *
   * <p>The input is a captured route manifest. This command never reconstructs route registration and
   * never claims that an offline snapshot matches a current Head.
   */
  // Offline CLI entry point invoked by a Gradle task, not a service path: stdout IS this tool's
  // output, so routing it through SLF4J would hide it behind the logging config.
  @SuppressWarnings("PMD.SystemPrintln")
  public static void main(String[] args) throws Exception {
    if (args.length < 2 || args.length > 3 || (args.length == 3 && !"--check".equals(args[2]))) {
      throw new IllegalArgumentException(
          "usage: OpenApiSnapshotExporter <route-snapshot> <openapi-snapshot> [--check]");
    }
    Path input = Path.of(args[0]);
    Path output = Path.of(args[1]);
    String rendered = renderSnapshot(input);
    if (args.length == 3) {
      String current = Files.exists(output) ? normalizeNewlines(Files.readString(output)) : "";
      if (!current.equals(rendered)) {
        throw new IllegalStateException(
            "OpenAPI snapshot drift: run :modules:ui:generateReferenceClientOpenApiSnapshot");
      }
      System.out.println("reference-client OpenAPI snapshot up to date");
      return;
    }
    writeAtomically(output, rendered);
    System.out.println("wrote " + output);
  }

  static String renderSnapshot(Path routeSnapshot) throws IOException {
    @SuppressWarnings("unchecked")
    Map<String, Object> envelope = MAPPER.readValue(Files.readString(routeSnapshot), Map.class);
    Object rawRoutes = envelope.get("routes");
    if (!(rawRoutes instanceof List<?> rows)) {
      throw new IllegalArgumentException("route snapshot has no routes array: " + routeSnapshot);
    }
    Object declaredCount = envelope.get("count");
    if (!(declaredCount instanceof Number count) || count.intValue() != rows.size()) {
      throw new IllegalArgumentException(
          "route snapshot count does not match routes array: " + routeSnapshot);
    }

    List<RouteEntry> routes = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      if (!(rows.get(i) instanceof Map<?, ?> row)) {
        throw new IllegalArgumentException("route snapshot row " + i + " is not an object");
      }
      routes.add(toRoute(row, i));
    }
    Map<String, Object> document = OpenApiRenderer.render(routes);
    validateRouteDigest(envelope.get("routeDigest"), document, routeSnapshot);
    return normalizeNewlines(
            MAPPER.writer().with(JSON_STRINGIFY_PRETTY_PRINTER).writeValueAsString(document))
        + "\n";
  }

  private static RouteEntry toRoute(Map<?, ?> row, int index) {
    return new RouteEntry(
        requiredString(row, "method", index),
        requiredString(row, "path", index),
        requiredString(row, "cohort", index),
        nullableString(row, "owningModule", index),
        stringList(row, "requiredCapabilities", index),
        nullableString(row, "responseSchema", index),
        nullableString(row, "stability", index),
        nullableString(row, "sdkOperationId", index),
        nullableString(row, "requestSchema", index),
        queryParameters(row, index),
        responseSchemas(row, index),
        lifecycle(row, index));
  }

  private static String requiredString(Map<?, ?> row, String field, int index) {
    Object value = row.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("route snapshot row " + index + " has invalid " + field);
    }
    return text;
  }

  private static String nullableString(Map<?, ?> row, String field, int index) {
    Object value = row.get(field);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("route snapshot row " + index + " has invalid " + field);
    }
    return text;
  }

  private static List<String> stringList(Map<?, ?> row, String field, int index) {
    Object value = row.get(field);
    if (!(value instanceof List<?> values)) {
      throw new IllegalArgumentException("route snapshot row " + index + " has invalid " + field);
    }
    List<String> result = new ArrayList<>();
    for (Object item : values) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IllegalArgumentException(
            "route snapshot row " + index + " has invalid " + field + " entry");
      }
      result.add(text);
    }
    return List.copyOf(result);
  }

  private static List<RouteContractPolicy.QueryParameter> queryParameters(
      Map<?, ?> row, int index) {
    Object value = row.get("queryParameters");
    if (value == null) return null;
    if (!(value instanceof List<?> values)) {
      throw new IllegalArgumentException(
          "route snapshot row " + index + " has invalid queryParameters");
    }
    List<RouteContractPolicy.QueryParameter> result = new ArrayList<>();
    for (Object item : values) {
      if (!(item instanceof Map<?, ?> parameter)
          || !(parameter.get("required") instanceof Boolean required)) {
        throw new IllegalArgumentException(
            "route snapshot row " + index + " has invalid queryParameters entry");
      }
      result.add(
          new RouteContractPolicy.QueryParameter(
              requiredString(parameter, "name", index),
              required,
              requiredString(parameter, "schemaReference", index)));
    }
    return List.copyOf(result);
  }

  private static Map<Integer, String> responseSchemas(Map<?, ?> row, int index) {
    Object value = row.get("responseSchemas");
    if (value == null) return null;
    if (!(value instanceof Map<?, ?> schemas)) {
      throw new IllegalArgumentException(
          "route snapshot row " + index + " has invalid responseSchemas");
    }
    Map<Integer, String> result = new java.util.TreeMap<>();
    for (var entry : schemas.entrySet()) {
      int status;
      try {
        status = Integer.parseInt(String.valueOf(entry.getKey()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "route snapshot row " + index + " has invalid responseSchemas status", e);
      }
      if (!(entry.getValue() instanceof String schema) || schema.isBlank()) {
        throw new IllegalArgumentException(
            "route snapshot row " + index + " has invalid responseSchemas entry");
      }
      result.put(status, schema);
    }
    return Map.copyOf(result);
  }

  private static RouteManifestController.LifecycleEntry lifecycle(Map<?, ?> row, int index) {
    Object value = row.get("lifecycle");
    if (value == null) return null;
    if (!(value instanceof Map<?, ?> lifecycle)) {
      throw new IllegalArgumentException(
          "route snapshot row " + index + " has invalid lifecycle");
    }
    return new RouteManifestController.LifecycleEntry(
        requiredString(lifecycle, "deprecatedSince", index),
        nullableString(lifecycle, "sunsetAt", index),
        requiredString(lifecycle, "replacement", index),
        requiredString(lifecycle, "documentationUri", index));
  }

  private static void validateRouteDigest(
      Object routeDigest, Map<String, Object> document, Path routeSnapshot) {
    // Captures predating tempdoc 893 have no digest. The next real Head capture adds it; from then on
    // it is a fail-closed provenance link that catches manual descriptor edits.
    if (routeDigest == null) {
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> source =
        (Map<String, Object>) document.get("x-justsearch-route-source");
    if (!source.get("routeDigest").equals(routeDigest)) {
      throw new IllegalArgumentException(
          "route snapshot digest does not match its descriptors: " + routeSnapshot);
    }
  }

  private static void writeAtomically(Path output, String rendered) throws IOException {
    Files.createDirectories(output.toAbsolutePath().getParent());
    Path temporary =
        Files.createTempFile(
            output.toAbsolutePath().getParent(), output.getFileName().toString(), ".tmp");
    try {
      Files.writeString(temporary, rendered, StandardCharsets.UTF_8);
      try {
        Files.move(
            temporary,
            output,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static String normalizeNewlines(String value) {
    return value.replace("\r\n", "\n");
  }
}
