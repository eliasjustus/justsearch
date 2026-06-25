/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.runtime;

import io.javalin.http.Context;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Tempdoc 501 Phase 27 (§13.7 Q6) — readiness / liveness probes.
 *
 * <ul>
 *   <li>{@code GET /api/runtime/ready} returns 200 with a JSON body when the
 *       overall lifecycle projection on the runtime manifest is {@code
 *       READY}. Returns 503 with the current discriminator otherwise.
 *       Containerized deployments (k8s-style readinessProbe) can use this
 *       endpoint directly without learning the manifest's JSON shape.
 *   <li>{@code GET /api/runtime/live} returns 200 if the request reaches
 *       the handler. The reactivity short-circuit §13.7 Q6 names: the
 *       manifest cannot detect its own producer dying from inside the
 *       dying process; an external probe at the HTTP layer is the
 *       canonical liveness signal. Matches the k8s livenessProbe
 *       convention.
 * </ul>
 *
 * <p>Both endpoints return JSON bodies (not bare status) so a future
 * consumer that wants additional diagnostic info can read it without
 * needing a second round-trip to {@code /api/runtime/manifest}.
 */
public final class RuntimeProbeController {

  /**
   * 548 §4.1: the manifest lifecycle string is proto-canonical (the single authority is the proto
   * enum). The readiness probe compares against the proto-canonical READY name, not the legacy
   * short {@code "READY"} (which would make this k8s-style probe return 503 forever).
   */
  private static final String READY_WIRE =
      io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_READY.name();

  private final RuntimeManifestPublisher publisher;

  public RuntimeProbeController(RuntimeManifestPublisher publisher) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  public void handleReady(Context ctx) {
    RuntimeManifest manifest = publisher.current();
    String lifecycle = manifest == null ? null : manifest.lifecycle();
    boolean ready = READY_WIRE.equals(lifecycle);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ready", ready);
    body.put("lifecycle", lifecycle);
    body.put("instanceId", manifest == null ? null : manifest.instanceId());
    ctx.status(ready ? 200 : 503).json(body);
  }

  public void handleLive(Context ctx) {
    RuntimeManifest manifest = publisher.current();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("alive", true);
    body.put("pid", manifest == null ? null : manifest.pid());
    body.put("instanceId", manifest == null ? null : manifest.instanceId());
    ctx.status(200).json(body);
  }

  /**
   * Tempdoc 501 Phase 38 (F11): HEAD method support for k8s-style
   * probe systems that issue HEAD to save bandwidth. Status code only;
   * no body. Mirrors the GET handler's status logic.
   */
  public void handleReadyHead(Context ctx) {
    RuntimeManifest manifest = publisher.current();
    String lifecycle = manifest == null ? null : manifest.lifecycle();
    boolean ready = READY_WIRE.equals(lifecycle);
    ctx.status(ready ? 200 : 503);
  }

  /** Tempdoc 501 Phase 38 (F11): HEAD always returns 200 (matches GET). */
  public void handleLiveHead(Context ctx) {
    ctx.status(200);
  }
}
