/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.util.List;

/**
 * Tempdoc 583 §D.3a — the single authority for "which HTTP routes require which runtime capability".
 *
 * <p>Before this, the {path-prefix → required-capability} mapping lived only as hand-written {@code
 * app.before(...)} filters inside {@link ApiSecurityFilters} (Tempdoc 502 §4.2.1). That made the rule
 * un-introspectable: a route manifest could not *describe* what the filter *enforces* without copying
 * it (and drifting). This class holds the rules as DATA; {@link ApiSecurityFilters} installs the
 * enforcement gates from them, and {@code RouteManifestController} reads the same rules to advertise
 * each route's requirement — so enforced and advertised can never drift.
 *
 * <p>Behaviour is identical to the prior hard-coded gates: {@code /api/knowledge/*} and {@code
 * /api/indexing/*} require Worker for non-GET requests (GET degrades gracefully); {@code
 * /api/chat/agent} requires Worker then Inference (no GET exemption). Order within {@link
 * Rule#required()} is the 503-precedence order.
 */
final class RouteCapabilityPolicy {
  private RouteCapabilityPolicy() {}

  /** A runtime capability a route may require. */
  enum Capability {
    WORKER("Worker capability unavailable", "Worker unavailable"),
    INFERENCE("Inference capability unavailable", "Inference unavailable");

    /** Body {@code error} string + the {@code HttpResponseException} message for a 503 on this cap. */
    final String errorLabel;

    final String haltMessage;

    Capability(String errorLabel, String haltMessage) {
      this.errorLabel = errorLabel;
      this.haltMessage = haltMessage;
    }
  }

  /**
   * One path-scoped capability requirement.
   *
   * @param pathPattern Javalin path pattern — either an exact path ({@code /api/chat/agent}) or a
   *     prefix wildcard ({@code /api/knowledge/*}).
   * @param getExempt when true, GET requests bypass the requirement (read paths degrade gracefully).
   * @param required the capabilities required, in 503-precedence order.
   */
  record Rule(String pathPattern, boolean getExempt, List<Capability> required) {}

  /** The capability rules — the one place {path → capability} lives. */
  static final List<Rule> RULES =
      List.of(
          new Rule("/api/knowledge/*", true, List.of(Capability.WORKER)),
          new Rule("/api/indexing/*", true, List.of(Capability.WORKER)),
          new Rule("/api/chat/agent", false, List.of(Capability.WORKER, Capability.INFERENCE)));

  /** True if {@code path} falls under {@code pattern} (exact match, or prefix for a {@code /*} pattern). */
  static boolean matches(String pattern, String path) {
    if (pattern.endsWith("/*")) {
      String prefix = pattern.substring(0, pattern.length() - 1); // keep trailing slash: "/api/knowledge/"
      return path.startsWith(prefix);
    }
    return path.equals(pattern);
  }

  /**
   * The capabilities a concrete {@code (method, path)} requires — the manifest's per-route view.
   * Honors {@link Rule#getExempt()} (a GET under a get-exempt rule requires nothing).
   */
  static List<Capability> requiredFor(String method, String path) {
    boolean isGet = "GET".equalsIgnoreCase(method);
    for (Rule rule : RULES) {
      if (!matches(rule.pathPattern(), path)) {
        continue;
      }
      if (rule.getExempt() && isGet) {
        return List.of();
      }
      return rule.required();
    }
    return List.of();
  }
}
