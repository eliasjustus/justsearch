/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.ui.api.RouteManifestController.RouteEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/* Shared digest authority for the complete route descriptor carried by both live projections. */
final class RouteDescriptorDigest {
  static final Comparator<RouteEntry> ROUTE_ORDER =
      Comparator.comparing(RouteEntry::cohort)
          .thenComparing(RouteEntry::path)
          .thenComparing(RouteEntry::method);

  private RouteDescriptorDigest() {}

  static String sha256(List<RouteEntry> routes) {
    StringBuilder canonical = new StringBuilder();
    routes.stream().sorted(ROUTE_ORDER).forEach(route -> appendRoute(canonical, route));
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void appendRoute(StringBuilder out, RouteEntry route) {
    appendField(out, route.method());
    appendField(out, route.path());
    appendField(out, route.cohort());
    appendField(out, route.owningModule());
    out.append(route.requiredCapabilities().size()).append('[');
    route.requiredCapabilities().forEach(capability -> appendField(out, capability));
    out.append(']');
    appendField(out, route.responseSchema());
    if (hasExtendedMetadata(route)) {
      out.append("v2|");
      appendField(out, route.stability());
      appendField(out, route.sdkOperationId());
      appendField(out, route.requestSchema());
      appendQueryParameters(out, route.queryParameters());
      appendResponseSchemas(out, route.responseSchemas());
      appendLifecycle(out, route.lifecycle());
    }
    out.append('\n');
  }

  private static boolean hasExtendedMetadata(RouteEntry route) {
    return route.stability() != null
        || route.sdkOperationId() != null
        || route.requestSchema() != null
        || route.queryParameters() != null
        || route.responseSchemas() != null
        || route.lifecycle() != null;
  }

  private static void appendQueryParameters(
      StringBuilder out, List<RouteContractPolicy.QueryParameter> parameters) {
    if (parameters == null) {
      out.append("-1:");
      return;
    }
    out.append(parameters.size()).append('[');
    for (var parameter : parameters) {
      appendField(out, parameter.name());
      out.append(parameter.required()).append('|');
      appendField(out, parameter.schemaReference());
    }
    out.append(']');
  }

  private static void appendResponseSchemas(StringBuilder out, Map<Integer, String> schemas) {
    if (schemas == null) {
      out.append("-1:");
      return;
    }
    out.append(schemas.size()).append('[');
    schemas.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              out.append(entry.getKey()).append('|');
              appendField(out, entry.getValue());
            });
    out.append(']');
  }

  private static void appendLifecycle(
      StringBuilder out, RouteManifestController.LifecycleEntry lifecycle) {
    if (lifecycle == null) {
      out.append("-1:");
      return;
    }
    appendField(out, lifecycle.deprecatedSince());
    appendField(out, lifecycle.sunsetAt());
    appendField(out, lifecycle.replacement());
    appendField(out, lifecycle.documentationUri());
  }

  private static void appendField(StringBuilder out, String value) {
    if (value == null) {
      out.append("-1:");
      return;
    }
    out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value).append('|');
  }
}
