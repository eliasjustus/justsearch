/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.ui.api.RouteManifestController.RouteEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

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
    out.append('\n');
  }

  private static void appendField(StringBuilder out, String value) {
    if (value == null) {
      out.append("-1:");
      return;
    }
    out.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value).append('|');
  }
}
