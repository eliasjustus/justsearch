/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.api.runtime.Reachability;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Tempdoc 501 Phase 35 (§13.4.2 + F1 fix): single source of truth for the
 * reachability axis. Routes register their transport metadata
 * declaratively as they are wired; the publisher reads the registered set
 * at {@link RuntimeManifestPublisher#publishHead} time instead of
 * hardcoding a list that drifts from actually-registered routes.
 *
 * <p>The pre-Phase-35 shape had {@code composeReachability} hardcoding 8
 * URL strings that mirrored {@code LocalApiServer} route registrations.
 * Adding a route in {@code LocalApiServer} without updating
 * {@code composeReachability} produced a manifest that silently advertised
 * the wrong set of transports. The registry-driven shape removes the
 * drift surface: there is one place to maintain the list, and the
 * publisher cannot diverge.
 *
 * <p>Entries are kind/url/audience triples (see {@link Reachability.Transport}).
 * Registration is order-preserving so the manifest's transport list is
 * deterministic across runs.
 *
 * <p>Thread-safety: registration is expected to happen during boot from a
 * single thread ({@link io.justsearch.ui.api.LocalApiServer}'s route
 * wiring). {@link #snapshot} can be read concurrently; the underlying
 * list is copied on snapshot so readers do not see mid-registration state.
 */
public final class RuntimeTransportRegistry {

  private final List<Reachability.Transport> entries = new ArrayList<>();

  /** Register an HTTP-rest path. URL is composed at snapshot time. */
  public synchronized void registerHttp(String path, String audience) {
    add(Reachability.KIND_HTTP_REST, path, audience);
  }

  /** Register an SSE path. URL is composed at snapshot time. */
  public synchronized void registerSse(String path, String audience) {
    add(Reachability.KIND_SSE, path, audience);
  }

  /** Register a well-known path. URL is composed at snapshot time. */
  public synchronized void registerWellKnown(String path, String audience) {
    add(Reachability.KIND_WELL_KNOWN, path, audience);
  }

  /** Register a probe path. URL is composed at snapshot time. */
  public synchronized void registerProbe(String path, String audience) {
    add(Reachability.KIND_PROBE, path, audience);
  }

  /** Register an MCP tool name. Not URL-composed; kept as-is at snapshot. */
  public synchronized void registerMcpTool(String toolName, String audience) {
    add(Reachability.KIND_MCP, toolName, audience);
  }

  /** Register a filesystem path. Not URL-composed; kept as-is at snapshot. */
  public synchronized void registerFilesystem(String path, String audience) {
    add(Reachability.KIND_FILESYSTEM, path, audience);
  }

  private void add(String kind, String address, String audience) {
    entries.add(new Reachability.Transport(kind, address, audience));
  }

  /**
   * Number of registered transports. Useful for alignment tests and for
   * detecting empty registries before snapshot composition.
   */
  public synchronized int size() {
    return entries.size();
  }

  /**
   * Snapshot the registered transports, applying a URL resolver to
   * HTTP-class kinds (http-rest, sse, well-known, probe). The resolver
   * receives the registered {@code path} (e.g. {@code /api/runtime/ready})
   * and must return the public URL (e.g. {@code http://127.0.0.1:5000/api/runtime/ready}).
   * Non-HTTP kinds (MCP, filesystem) pass their address through unchanged.
   */
  public synchronized Reachability snapshot(Function<String, String> httpUrlResolver) {
    Objects.requireNonNull(httpUrlResolver, "httpUrlResolver");
    List<Reachability.Transport> resolved = new ArrayList<>(entries.size());
    for (Reachability.Transport t : entries) {
      String address =
          switch (t.kind()) {
            case Reachability.KIND_HTTP_REST,
                Reachability.KIND_SSE,
                Reachability.KIND_WELL_KNOWN,
                Reachability.KIND_PROBE -> httpUrlResolver.apply(t.url());
            default -> t.url();
          };
      resolved.add(new Reachability.Transport(t.kind(), address, t.audience()));
    }
    return new Reachability(resolved);
  }
}
