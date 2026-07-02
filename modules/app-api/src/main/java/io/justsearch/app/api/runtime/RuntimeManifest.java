/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.soabase.recordbuilder.core.RecordBuilder;

/**
 * Producer-published runtime manifest (tempdoc 501).
 *
 * <p>A self-describing, versioned document the JustSearch backend writes to
 * {@code <dataDir>/runtime/manifest.json} and serves at {@code GET /api/runtime/manifest}.
 * The single source of truth for non-JVM consumers (Vite proxy, prod MCP,
 * dev-runner, Tauri shell, browser) answering "what is the currently-running
 * JustSearch instance and how do I reach it?"
 *
 * <p>Two transports, one document — the filesystem path is for tools that
 * don't yet know the port; the HTTP endpoint is for browsers and remote tools.
 * Change notification rides {@code /api/runtime/manifest/stream} (SSE) with
 * the same payload shape.
 *
 * <p>Readiness is phased: the first write happens at HTTP bind and carries
 * only head fields ({@code head.readyAt} populated, {@code worker} null);
 * a rewrite happens when the Worker connects, adding the {@code worker}
 * sub-record and {@code worker.readyAt}. Consumers choose their own bar
 * by checking which optional fields are non-null.
 *
 * <p>Identity over liveness: every process generates a fresh {@code
 * instanceId} (UUID) at boot. Consumers cache by identity — when
 * {@code instanceId} changes, caches invalidate by definition. Liveness is
 * a separate concern handled via the companion {@code manifest.lock} file
 * carrying PID.
 *
 * <p>Stability: stable (manifest contract). New fields are added with
 * {@code @JsonInclude(NON_NULL)}; schema bumps require incrementing
 * {@link #schemaVersion}.
 */
@RecordBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeManifest(
    int schemaVersion,
    String instanceId,
    long pid,
    String startedAt,
    String dataDir,
    /**
     * Tempdoc 501 §12.1 projection from {@code LifecycleProjection.derive(WorkerCapability,
     * InferenceCapability)}. Single string discriminator over the canonical
     * {@code LifecycleState} enum ({@code STARTING} | {@code READY} | {@code DEGRADED} |
     * {@code ERROR}). Consumers get the overall state without composing sub-records.
     * Null only during the head-only initial publish before the head is fully bound;
     * present on every subsequent rewrite.
     */
    String lifecycle,
    HeadInfo head,
    WorkerInfo worker,
    AiInfo ai,
    /**
     * Tempdoc 501 Phase 30 (§13.4.2 reachability axis): typed transports
     * list. Each entry names a transport this producer exposes (HTTP REST,
     * SSE, filesystem path, well-known mirror, MCP tool, probes) with a
     * {@code kind} discriminator, the URL or filesystem path, and an
     * audience tag. Replaces the per-field encoding inside {@link HeadInfo}
     * (where {@code apiBaseUrl} carried reachability while
     * {@code sessionToken} carried a credential — two axes in one record).
     * {@code HeadInfo.apiBaseUrl} stays for one schema cycle for
     * back-compat; new consumers should read {@code reachability}.
     * Schema-version stays at 1: {@code @JsonInclude(NON_NULL)} keeps the
     * field optional for older readers.
     */
    Reachability reachability,
    /**
     * Install/runtime mode (tempdoc 657). {@code intent} is the configured product shape
     * ({@code full-desktop} | {@code headless} | {@code mcp-lite}, from {@code -Djustsearch.mode});
     * {@code realized} is the coarse capability actually up ({@code full} | {@code retrieval-only} |
     * {@code degraded}), projected from {@code WorkerCapability} + {@code InferenceCapability}. So the
     * advertised mode never outruns what is actually loaded. Nullable — a manifest written before the
     * first mode publish omits it; {@code @JsonInclude(NON_NULL)} keeps it optional and the schema
     * version stays 1 (older readers unaffected).
     */
    ModeInfo mode) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  public RuntimeManifest {
    if (schemaVersion <= 0) {
      throw new IllegalArgumentException("schemaVersion must be positive");
    }
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must be non-blank");
    }
    if (startedAt == null || startedAt.isBlank()) {
      throw new IllegalArgumentException("startedAt must be non-blank");
    }
    if (dataDir == null || dataDir.isBlank()) {
      throw new IllegalArgumentException("dataDir must be non-blank");
    }
    if (head == null) {
      throw new IllegalArgumentException("head must be non-null");
    }
  }

  /**
   * Head process surface — always non-null. Present from the first manifest
   * write (at HTTP bind).
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HeadInfo(
      int apiPort, String apiBaseUrl, String sessionToken, String readyAt, String buildStamp) {
    public HeadInfo {
      if (apiPort <= 0 || apiPort > 65535) {
        throw new IllegalArgumentException("apiPort out of range: " + apiPort);
      }
      if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
        throw new IllegalArgumentException("apiBaseUrl must be non-blank");
      }
      if (readyAt == null || readyAt.isBlank()) {
        throw new IllegalArgumentException("readyAt must be non-blank");
      }
    }

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis): strips the
     * session token credential. HTTP / SSE / MCP / well-known transports
     * call this; the filesystem transport carries the full record.
     */
    public HeadInfo publicProjection() {
      return sessionToken == null ? this : new HeadInfo(apiPort, apiBaseUrl, null, readyAt, buildStamp);
    }
  }

  /**
   * Worker process surface — nullable. Becomes non-null on the second manifest write,
   * after {@code connectKnowledgeServer} resolves (either successfully or with a
   * spawn failure).
   *
   * <p>Tempdoc 501 §12.1: the {@code state} discriminator carries the worker-projection's
   * tri-state surface — {@code "pending"} (Worker still booting, not yet known if it'll
   * connect), {@code "ready"} (connected and serving), {@code "failed"} (spawn or
   * connect failed; {@code spawnError} carries the human-readable reason). The previous
   * null-vs-populated distinction conflated "still trying" with "gave up" — the
   * discriminator separates them.
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record WorkerInfo(
      /** {@code "pending"} | {@code "ready"} | {@code "failed"}. Always present. */
      String state,
      Integer grpcPort,
      String indexBasePath,
      String readyAt,
      /** Populated when {@link #state} is {@code "failed"}; null otherwise. */
      String spawnError) {

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis). Worker carries
     * no credentials today; the public view is identity. Override here is
     * the structural commitment — adding a future credential-class field
     * must also add it to the redaction branch in this method.
     */
    public WorkerInfo publicProjection() {
      return this;
    }
  }

  /**
   * Inference (AI) runtime surface — nullable until the producer first observes the
   * {@code InferenceCapability}. Projects from
   * {@code io.justsearch.app.services.lifecycle.InferenceCapability} (tempdoc 501 §12.1).
   *
   * <p>The {@code phase} discriminator carries the upstream {@code CapabilityHealth} name
   * ({@code PENDING}, {@code READY}, {@code DEGRADED}, {@code OFFLINE}, {@code RECOVERING}).
   * {@code required} reports whether inference is configured for this stack at all —
   * consumers should treat {@code required=false} + {@code phase=OFFLINE} as the expected
   * state, not a failure.
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AiInfo(
      String phase, boolean required, String pendingReason, String readyAt) {

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis). AI carries no
     * credentials today; the public view is identity. Same structural
     * commitment as {@link WorkerInfo#publicProjection}.
     */
    public AiInfo publicProjection() {
      return this;
    }
  }

  /**
   * Install/runtime mode surface (tempdoc 657) — nullable until the first mode publish.
   *
   * <p>{@code intent} is the producer-owned primitive: the configured product shape from
   * {@code -Djustsearch.mode} ({@code full-desktop} | {@code headless} | {@code mcp-lite}; defaults to
   * {@code full-desktop}). {@code realized} is a coarse projection of what is actually up —
   * {@code full} (retrieval + LLM ready), {@code retrieval-only} (retrieval up, LLM not required/offline),
   * or {@code degraded} — derived from {@code WorkerCapability} + {@code InferenceCapability}. Consumers
   * branch on {@code realized}; when it diverges from {@code intent} the manifest tells the honest truth.
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ModeInfo(String intent, String realized) {

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis). Mode carries no credentials; the public
     * view is identity. Same structural commitment as {@link AiInfo#publicProjection}.
     */
    public ModeInfo publicProjection() {
      return this;
    }
  }

  /**
   * Public projection of the whole manifest (tempdoc 501 §13.4.5 audience
   * axis). Each sub-record declares its own {@code publicProjection()}; this
   * method composes them. The type system enforces that new sensitive
   * fields declare their public projection at the record level — there is
   * no static helper to forget to update.
   *
   * <p>Used by every HTTP-class transport: REST controller, SSE controller,
   * MCP tool result, well-known mirror. The filesystem transport keeps the
   * full record (FS-permission gated).
   */
  public RuntimeManifest publicProjection() {
    HeadInfo publicHead = head == null ? null : head.publicProjection();
    WorkerInfo publicWorker = worker == null ? null : worker.publicProjection();
    AiInfo publicAi = ai == null ? null : ai.publicProjection();
    Reachability publicReach = reachability == null ? null : reachability.publicProjection();
    ModeInfo publicMode = mode == null ? null : mode.publicProjection();
    if (publicHead == head
        && publicWorker == worker
        && publicAi == ai
        && publicReach == reachability
        && publicMode == mode) {
      return this;
    }
    return new RuntimeManifest(
        schemaVersion, instanceId, pid, startedAt, dataDir, lifecycle,
        publicHead, publicWorker, publicAi, publicReach, publicMode);
  }
}
