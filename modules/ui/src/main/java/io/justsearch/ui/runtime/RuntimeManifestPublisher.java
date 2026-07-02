/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.api.runtime.Reachability;
import io.justsearch.app.api.runtime.RuntimeManifest;
import io.justsearch.app.api.runtime.RuntimeManifestBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestHeadInfoBuilder;
import io.justsearch.app.api.runtime.RuntimeManifestWorkerInfoBuilder;
// imports kept for builders; from() pattern not used (the @RecordBuilder generates with*()
// methods on the record itself).
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Producer-side runtime manifest writer (tempdoc 501).
 *
 * <p>Lifecycle:
 *
 * <ol>
 *   <li>Construct once at boot (auto-generates {@code instanceId}, captures PID + dataDir).
 *   <li>Call {@link #publishHead} immediately after the Local API server binds. This writes the
 *       first manifest with head-only readiness and acquires the {@code manifest.lock}.
 *   <li>Call {@link #publishWorkerReady} (or {@link #publishWorkerFailed}) after
 *       {@code connectKnowledgeServer} resolves.
 *       This rewrites the manifest with the {@code worker} sub-record.
 *   <li>Call {@link #close} in shutdown finally to remove the manifest + lock so consumers that
 *       read-and-find-nothing know the producer has cleanly torn down. Crashed producers
 *       (SIGKILL, OOM) leave both files behind; consumers defend against that case via the
 *       {@code manifest.lock} PID-alive check.
 * </ol>
 *
 * <p>Writes are atomic (write-to-temp + atomic rename). The publisher notifies registered
 * listeners on every manifest change so HTTP/SSE transports can re-broadcast without needing a
 * filesystem watch.
 *
 * <p>Multi-instance enforcement (refuse-to-start when another live producer holds the
 * dataDir) is layered in Phase 3 of the tempdoc by reusing the existing {@code
 * AppInstanceLock} ({@code <dataDir>/app.lock}, OS-level {@code FileChannel.tryLock} +
 * PID metadata + stale recovery via {@code ProcessHandle.of()}). This publisher does
 * not write its own lock file: the manifest's {@code pid} field is the consumer's
 * freshness signal, and the OS-level lock is the producer's mutex. Two locks would
 * violate the design's own closure rule (one mechanism per concern).
 */
public final class RuntimeManifestPublisher implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RuntimeManifestPublisher.class);
  private static final String MANIFEST_FILENAME = "manifest.json";
  private static final String INSTANCE_LOG_FILENAME = "manifest.log.ndjson";
  private static final String START_LOG_FILENAME = "start.log";
  // Tempdoc 501 §13.4.4: by-count retention default. The full
  // <dataDir>/runtime/instances/ directory grows by one subdirectory per
  // process start; without retention it grows unbounded. Fifty is enough
  // for postmortem traversal across a few weeks of normal use.
  private static final int INSTANCE_RETENTION_COUNT = 50;

  private final Path runtimeDir;
  private final Path manifestPath;
  private final Path historyDir;
  private final Path dataDir;
  private final String instanceId;
  private final long pid;
  private final String startedAt;
  private final ObjectMapper mapper;
  private final AtomicReference<RuntimeManifest> current = new AtomicReference<>();
  private final CopyOnWriteArrayList<Consumer<RuntimeManifest>> listeners =
      new CopyOnWriteArrayList<>();
  /**
   * Tempdoc 501 Phase 35 (F1): late-bound transport registry. When set,
   * {@link #composeReachability} reads from it instead of building a
   * hardcoded list. Null on the test-only path that constructs the
   * publisher without a LocalApiServer wiring it.
   */
  private volatile RuntimeTransportRegistry transportRegistry;

  /**
   * Tempdoc 627 (N1): whether the *previous* Head session ended uncleanly (a leftover manifest with a
   * now-dead PID), and that crashed predecessor's PID. Computed once at construction; surfaced to the
   * Head so it can narrate the recovery as a calm occurrence once the health substrate is up.
   */
  private final boolean uncleanPreviousShutdown;

  private final OptionalLong previousInstancePid;

  public RuntimeManifestPublisher(Path dataDir) {
    if (dataDir == null) {
      throw new IllegalArgumentException("dataDir must be non-null");
    }
    this.dataDir = dataDir;
    this.runtimeDir = dataDir.resolve("runtime");
    this.manifestPath = runtimeDir.resolve(MANIFEST_FILENAME);
    this.instanceId = UUID.randomUUID().toString();
    // Tempdoc 501 §3.7: per-instance history mirroring tmp/dev-runner/runs/
    // pattern. Each publish writes a snapshot at
    // <dataDir>/runtime/instances/<instanceId>/manifest.json so postmortem of
    // "what did instance X look like?" stays available after the canonical
    // manifest is overwritten by a later run.
    this.historyDir = runtimeDir.resolve("instances").resolve(this.instanceId);
    this.pid = ProcessHandle.current().pid();
    this.startedAt = Instant.now().toString();
    this.mapper = new ObjectMapper();
    // Tempdoc 627 (N1): classify the *previous* session BEFORE pruneInstanceHistory / the first
    // publishHead overwrite touch the runtime dir. A leftover manifest with a dead PID is the
    // reliable cross-session crash signal (clean shutdown deletes the manifest in close()).
    PreviousShutdown previous =
        classifyPreviousShutdown(
            manifestPath,
            mapper,
            pid,
            p ->
                ProcessHandle.of(p)
                    .flatMap(h -> h.info().startInstant())
                    .map(i -> OptionalLong.of(i.toEpochMilli()))
                    .orElse(OptionalLong.empty()));
    this.uncleanPreviousShutdown = previous.unclean();
    this.previousInstancePid = previous.pid();
    if (previous.unclean()) {
      log.info(
          "Previous Head session ended uncleanly (leftover manifest pid={} is dead); the app will"
              + " narrate an unclean-shutdown recovery on the health surface",
          previous.pid().orElse(-1));
    }
    // Tempdoc 501 §13.4.4: prune old instance directories on startup. The
    // current instance's own directory does not yet exist (writeManifest
    // creates it on first publish), so it can't be accidentally pruned.
    pruneInstanceHistory();
    // Tempdoc 501 §13.4.4 / Phase 25: open the per-instance start log
    // immediately. The first line records construction so postmortem
    // readers can establish wall-clock zero for the publish timeline.
    appendStartLog("publisher-constructed pid=" + pid + " startedAt=" + startedAt);
  }

  /**
   * Tempdoc 627 (N1): true if a leftover runtime manifest with a dead PID indicates the previous app
   * session crashed (the Head cannot observe its own death in-life; this is the successor signal).
   */
  public boolean detectedUncleanPreviousShutdown() {
    return uncleanPreviousShutdown;
  }

  /** Tempdoc 627 (N1): the crashed predecessor's PID, present iff {@link #detectedUncleanPreviousShutdown()}. */
  public OptionalLong previousInstancePid() {
    return previousInstancePid;
  }

  /** Verdict on the previous session's shutdown. Package-private for direct branch testing. */
  record PreviousShutdown(boolean unclean, OptionalLong pid) {
    static PreviousShutdown clean() {
      return new PreviousShutdown(false, OptionalLong.empty());
    }
  }

  /**
   * Tempdoc 627 (N1): classify whether the previous Head session ended cleanly. The publisher deletes
   * the manifest on graceful {@link #close()}, so a leftover manifest whose owning process is gone is
   * the reliable cross-session crash signal (the {@code app.lock} is NOT — {@code close()} releases the
   * OS lock but never deletes the file, and the OS lock evaporates on any death).
   *
   * <p>Reads only {@code pid}/{@code startedAt} via a JSON tree, NOT a full {@code readValue} into
   * {@link RuntimeManifest} — the manifest's polymorphic {@code reachability} block would otherwise make
   * deserialization fragile, and a parse failure here silently classifies clean (the live-validation
   * miss this fix resolves). The crashed predecessor's PID is all this needs.
   *
   * <p><b>Conservative + reuse-aware (mirrors {@code AppInstanceLock.tryRecoverStaleLock}):</b> flag
   * unclean when the PID is dead, OR alive but its start-instant differs from the manifest's
   * {@code startedAt} (the PID was recycled — the predecessor is gone). A genuinely-alive same process,
   * our own PID, an absent manifest, or any read failure all classify clean, so no coincidental PID
   * match raises a false "crashed" claim. {@code pidStartMillis} (live process start epoch-ms, empty if
   * dead) is injected so every branch is deterministically testable without spawning real processes.
   */
  static PreviousShutdown classifyPreviousShutdown(
      Path manifestPath,
      ObjectMapper mapper,
      long currentPid,
      LongFunction<OptionalLong> pidStartMillis) {
    try {
      if (!Files.exists(manifestPath)) {
        return PreviousShutdown.clean();
      }
      JsonNode root = mapper.readTree(manifestPath.toFile());
      JsonNode pidNode = root.get("pid");
      if (pidNode == null) {
        return PreviousShutdown.clean();
      }
      long leftoverPid = pidNode.asLong();
      if (leftoverPid <= 0L || leftoverPid == currentPid) {
        return PreviousShutdown.clean(); // defensive — cannot occur before the first publishHead
      }
      OptionalLong liveStart = pidStartMillis.apply(leftoverPid);
      if (liveStart.isEmpty()) {
        return new PreviousShutdown(true, OptionalLong.of(leftoverPid)); // PID dead → predecessor crashed
      }
      // PID alive: only a *reused* PID means the predecessor is gone — compare start instants.
      OptionalLong manifestStart = parseEpochMillis(root.get("startedAt"));
      if (manifestStart.isPresent()
          && Math.abs(liveStart.getAsLong() - manifestStart.getAsLong()) > 1000L) {
        return new PreviousShutdown(true, OptionalLong.of(leftoverPid)); // PID recycled → crashed
      }
      return PreviousShutdown.clean(); // same live process (shouldn't outlive the lock) — no false alarm
    } catch (Exception e) {
      log.debug("Previous-shutdown classification failed (treating as clean): {}", e.getMessage());
      return PreviousShutdown.clean();
    }
  }

  private static OptionalLong parseEpochMillis(JsonNode node) {
    if (node == null) {
      return OptionalLong.empty();
    }
    try {
      return OptionalLong.of(Instant.parse(node.asText()).toEpochMilli());
    } catch (RuntimeException e) {
      return OptionalLong.empty();
    }
  }

  /**
   * Append one line to {@code instances/<instanceId>/start.log} (tempdoc
   * 501 Phase 25). Used to record what the publisher did during this run —
   * construction, each publish call's intent, and shutdown. Best-effort:
   * failures log at debug and continue.
   */
  private void appendStartLog(String message) {
    try {
      Files.createDirectories(historyDir);
      Path startLog = historyDir.resolve(START_LOG_FILENAME);
      String line = Instant.now() + " " + message + "\n";
      Files.writeString(
          startLog,
          line,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      log.debug("start.log append failed (non-fatal)", e);
    }
  }

  /**
   * Tempdoc 501 §13.4.4: by-count retention. Walks {@code
   * <dataDir>/runtime/instances/} and deletes the oldest directories
   * beyond {@link #INSTANCE_RETENTION_COUNT}. Sorted by directory
   * last-modified time. Best-effort: failures log a debug and continue.
   */
  private void pruneInstanceHistory() {
    Path instancesRoot = runtimeDir.resolve("instances");
    if (!Files.isDirectory(instancesRoot)) {
      return;
    }
    try (Stream<Path> entries = Files.list(instancesRoot)) {
      List<Path> dirs = new ArrayList<>();
      entries.filter(Files::isDirectory).forEach(dirs::add);
      if (dirs.size() <= INSTANCE_RETENTION_COUNT) {
        return;
      }
      dirs.sort(
          Comparator.comparing(
              p -> {
                try {
                  return Files.getLastModifiedTime(p);
                } catch (IOException e) {
                  return java.nio.file.attribute.FileTime.fromMillis(0L);
                }
              }));
      int toRemove = dirs.size() - INSTANCE_RETENTION_COUNT;
      for (int i = 0; i < toRemove; i++) {
        deleteRecursively(dirs.get(i));
      }
    } catch (IOException e) {
      log.debug("Instance history prune failed (non-fatal)", e);
    }
  }

  private static void deleteRecursively(Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException ignore) {
          // best-effort
        }
      });
    } catch (IOException e) {
      log.debug("Instance dir delete failed: {}", root, e);
    }
  }

  /** UUID generated at construction; constant for the lifetime of this process. */
  public String instanceId() {
    return instanceId;
  }

  /**
   * Tempdoc 501 Phase 35 (F1): bind the transport registry. Called by
   * {@link io.justsearch.ui.api.LocalApiServer} after routes are
   * registered but before {@link #publishHead}. Subsequent
   * {@code publishHead} calls (only one is allowed) read the registered
   * transports instead of the hardcoded fallback list.
   */
  public void setTransportRegistry(RuntimeTransportRegistry registry) {
    this.transportRegistry = registry;
  }

  /** Filesystem path the manifest is written to. */
  public Path manifestPath() {
    return manifestPath;
  }

  /**
   * Root directory of per-instance history (tempdoc 501 §13.4.4). Each
   * instance owns a subdirectory containing a terminal snapshot
   * ({@code manifest.json}) and an append-only ndjson log
   * ({@code manifest.log.ndjson}).
   */
  public Path instancesRoot() {
    return runtimeDir.resolve("instances");
  }

  /** Most-recently-published manifest, or {@code null} if none yet. */
  public RuntimeManifest current() {
    return current.get();
  }

  /**
   * Register a listener fired synchronously on every <em>future</em> successful publish. Does
   * not replay the current snapshot — callers that need the present state should call
   * {@link #current} explicitly. Mixing "current state" and "change event" semantics on the
   * same callback path produced a spurious UPDATE frame at SSE-controller-init time during
   * Phase 2 live verification.
   */
  public void addListener(Consumer<RuntimeManifest> listener) {
    if (listener == null) {
      throw new IllegalArgumentException("listener must be non-null");
    }
    listeners.add(listener);
  }

  public void removeListener(Consumer<RuntimeManifest> listener) {
    listeners.remove(listener);
  }

  /**
   * Initial publish — call once the Local API server has bound its port. Writes the manifest
   * with head-only readiness ({@code worker} null). Multi-instance mutex is owned by
   * {@code AppInstanceLock} (acquired separately by the producer); the manifest's
   * {@code pid} carries the liveness signal consumers check.
   *
   * <p>The {@code lifecycle} projection is initialized to {@code STARTING} because head-bind
   * precedes Worker connect by definition. {@link #publishWorkerReady} and
   * {@link #publishWorkerFailed} carry the projected state from then on.
   */
  public synchronized RuntimeManifest publishHead(
      int apiPort, String sessionToken) throws IOException {
    if (current.get() != null) {
      throw new IllegalStateException("publishHead called twice");
    }
    Files.createDirectories(runtimeDir);

    String apiBaseUrl = "http://127.0.0.1:" + apiPort;
    String readyAt = Instant.now().toString();
    // Tempdoc 606 Piece 2b: self-report the dist content stamp the dev-runner injected
    // (-Djustsearch.head.stamp). Null in production / non-dev-runner launches (sysprop
    // absent) → omitted by @JsonInclude(NON_NULL). Lets a dev tool detect a stale old
    // Head answering on a reused port (its stamp won't match the current lease's).
    String headStampProp =
        io.justsearch.configuration.EnvRegistry.HEAD_BUILD_STAMP.get().orElse(null);
    String headBuildStamp =
        (headStampProp == null || headStampProp.isBlank()) ? null : headStampProp.trim();
    RuntimeManifest.HeadInfo headInfo =
        RuntimeManifestHeadInfoBuilder.builder()
            .apiPort(apiPort)
            .apiBaseUrl(apiBaseUrl)
            .sessionToken(sessionToken)
            .readyAt(readyAt)
            .buildStamp(headBuildStamp)
            .build();
    Reachability reachability = composeReachability(apiBaseUrl);
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder()
            .schemaVersion(RuntimeManifest.CURRENT_SCHEMA_VERSION)
            .instanceId(instanceId)
            .pid(pid)
            .startedAt(startedAt)
            .dataDir(dataDir.toString())
            .lifecycle(io.justsearch.contract.wire.LifecycleState.LIFECYCLE_STATE_STARTING.name())
            .head(headInfo)
            .reachability(reachability)
            // Tempdoc 654: advertise the Runtime Contract descriptor. Set once at head-publish;
            // subsequent publishes use RuntimeManifestBuilder.builder(previous) so it carries forward.
            .runtimeContract(io.justsearch.app.api.runtime.RuntimeContract.current())
            .build();
    log.info(
        "Runtime manifest published (head-ready): instanceId={}, apiPort={}, dataDir={}",
        instanceId,
        apiPort,
        dataDir);
    return commit(manifest, "publishHead apiPort=" + apiPort);
  }

  /**
   * Worker-ready rewrite — call once {@code connectKnowledgeServer} resolves successfully
   * (a Worker bootstrap exists and is connected). Tempdoc 501 §12.1 projection.
   *
   * @param grpcPort the worker's gRPC port (nullable if not yet read from the signal bus)
   * @param indexBasePath the resolved index path (nullable if not yet known)
   * @param lifecycle current overall lifecycle projection — from
   *     {@code LifecycleProjection.derive(workerCap, inferenceCap)}
   */
  public synchronized RuntimeManifest publishWorkerReady(
      Integer grpcPort, String indexBasePath, String lifecycle) throws IOException {
    RuntimeManifest previous = current.get();
    if (previous == null) {
      throw new IllegalStateException("publishWorkerReady called before publishHead");
    }
    String readyAt = Instant.now().toString();
    RuntimeManifest.WorkerInfo workerInfo =
        RuntimeManifestWorkerInfoBuilder.builder()
            .state("ready")
            .grpcPort(grpcPort)
            .indexBasePath(indexBasePath)
            .readyAt(readyAt)
            .build();
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder(previous)
            .worker(workerInfo)
            .lifecycle(lifecycle != null ? lifecycle : previous.lifecycle())
            .build();
    log.info(
        "Runtime manifest updated (worker-ready): grpcPort={}, indexBasePath={}, lifecycle={}",
        grpcPort,
        indexBasePath,
        lifecycle);
    return commit(
        manifest, "publishWorkerReady grpcPort=" + grpcPort + " lifecycle=" + lifecycle);
  }

  /**
   * Worker-failed rewrite — call when {@code connectKnowledgeServer} returns a startup
   * error or the Worker bootstrap never produces a connected handle. Tempdoc 501 §12.1
   * worker-failed signal: the manifest's {@code worker.state} discriminator carries
   * {@code "failed"}; the {@code worker.spawnError} field carries the reason.
   *
   * @param reason the failure reason (non-null; "unknown" if no specific cause is known)
   * @param lifecycle current overall lifecycle projection
   */
  public synchronized RuntimeManifest publishWorkerFailed(
      String reason, String lifecycle) throws IOException {
    RuntimeManifest previous = current.get();
    if (previous == null) {
      throw new IllegalStateException("publishWorkerFailed called before publishHead");
    }
    RuntimeManifest.WorkerInfo workerInfo =
        RuntimeManifestWorkerInfoBuilder.builder()
            .state("failed")
            .spawnError(reason != null && !reason.isBlank() ? reason : "unknown")
            .build();
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder(previous)
            .worker(workerInfo)
            .lifecycle(lifecycle != null ? lifecycle : previous.lifecycle())
            .build();
    log.info(
        "Runtime manifest updated (worker-failed): reason={}, lifecycle={}",
        reason,
        lifecycle);
    return commit(
        manifest, "publishWorkerFailed reason=" + reason + " lifecycle=" + lifecycle);
  }

  /**
   * AI projection rewrite — call on each `InferenceCapability` transition. Tempdoc 501
   * §12.1: projects from the capability's {@code health()} / {@code required()} /
   * {@code pendingReason()} surface. The lifecycle field is recomputed by the caller via
   * {@code LifecycleProjection.derive} (because inference health feeds the projection).
   */
  public synchronized RuntimeManifest publishAi(
      String phase,
      boolean required,
      String pendingReason,
      boolean readyNow,
      String lifecycle)
      throws IOException {
    RuntimeManifest previous = current.get();
    if (previous == null) {
      throw new IllegalStateException("publishAi called before publishHead");
    }
    String readyAt =
        readyNow
            ? Instant.now().toString()
            : previous.ai() == null ? null : previous.ai().readyAt();
    RuntimeManifest.AiInfo aiInfo =
        new RuntimeManifest.AiInfo(phase, required, pendingReason, readyAt);
    RuntimeManifest manifest =
        RuntimeManifestBuilder.builder(previous)
            .ai(aiInfo)
            .lifecycle(lifecycle != null ? lifecycle : previous.lifecycle())
            .build();
    log.info(
        "Runtime manifest updated (ai): phase={}, required={}, lifecycle={}",
        phase,
        required,
        lifecycle);
    return commit(
        manifest,
        "publishAi phase=" + phase + " required=" + required + " lifecycle=" + lifecycle);
  }

  /**
   * Mode projection publish (tempdoc 657) — the install/runtime mode. {@code intent} is the
   * configured product shape ({@code full-desktop} | {@code headless} | {@code mcp-lite}); {@code
   * realized} is the coarse capability actually up ({@code full} | {@code retrieval-only} |
   * {@code degraded}). Call once at startup with the configured intent, then again whenever the
   * realized capability changes (worker / inference transition). No-op when unchanged.
   */
  public synchronized RuntimeManifest publishMode(String intent, String realized)
      throws IOException {
    RuntimeManifest previous = current.get();
    if (previous == null) {
      throw new IllegalStateException("publishMode called before publishHead");
    }
    RuntimeManifest.ModeInfo modeInfo = new RuntimeManifest.ModeInfo(intent, realized);
    if (modeInfo.equals(previous.mode())) {
      return previous; // no-op — mode unchanged
    }
    RuntimeManifest manifest = RuntimeManifestBuilder.builder(previous).mode(modeInfo).build();
    log.info("Runtime manifest updated (mode): intent={}, realized={}", intent, realized);
    return commit(manifest, "publishMode intent=" + intent + " realized=" + realized);
  }

  /**
   * Lifecycle refresh — call when the overall lifecycle projection changes (e.g., inference
   * becomes ready) without a worker transition. Tempdoc 501 §12.1.
   */
  public synchronized RuntimeManifest publishLifecycle(String lifecycle) throws IOException {
    RuntimeManifest previous = current.get();
    if (previous == null) {
      throw new IllegalStateException("publishLifecycle called before publishHead");
    }
    if (lifecycle == null || lifecycle.isBlank()) return previous;
    if (lifecycle.equals(previous.lifecycle())) return previous; // no-op
    RuntimeManifest manifest = RuntimeManifestBuilder.from(previous).withLifecycle(lifecycle);
    log.info("Runtime manifest updated (lifecycle): {}", lifecycle);
    return commit(manifest, "publishLifecycle lifecycle=" + lifecycle);
  }

  /** Remove the manifest on clean shutdown. Best-effort. */
  @Override
  public synchronized void close() {
    appendStartLog("publisher-close (clean shutdown)");
    try {
      Files.deleteIfExists(manifestPath);
    } catch (IOException e) {
      log.debug("Manifest file delete failed (non-fatal)", e);
    }
    current.set(null);
  }

  /**
   * Tempdoc 501 Phase 28: shared commit path. Every public {@code publish*}
   * method reduces to (a) building the mutated manifest with its
   * axis-specific semantics, then (b) handing the result here. This helper
   * owns the write + current.set + listener notify + start-log append
   * sequence so adding a new axis means adding one public method whose
   * body is a one-line delegation, not five lines of plumbing duplicated
   * across siblings.
   *
   * <p>The shape is intentionally NOT a generic {@code update(diff)} —
   * each public method retains its semantic surface ({@code publishHead}
   * validates pre-state differently from {@code publishWorkerReady}, the
   * field shapes differ per axis). The duplication eliminated here was in
   * the *body*, not the *surface*.
   */
  /**
   * Tempdoc 501 Phase 30 (§13.4.2) + Phase 35 (F1): compose the typed
   * transports list at publishHead time.
   *
   * <p>If {@link #transportRegistry} has been wired (production path via
   * {@link io.justsearch.ui.api.LocalApiServer}), the list is read from
   * the registry — the routes themselves declared what to advertise, so
   * there is no drift surface between the registered routes and the
   * advertised transports.
   *
   * <p>If the registry is unwired (test path; no LocalApiServer), an
   * empty Reachability is returned. The publisher never builds a
   * hardcoded fallback list — that was the §13.2 drift defect.
   *
   * <p>Filesystem entries declare audience={@code full}; the
   * audience-axis projection (§13.4.5 / {@link Reachability#publicProjection})
   * strips them from HTTP-served records.
   */
  private Reachability composeReachability(String apiBaseUrl) {
    RuntimeTransportRegistry registry = this.transportRegistry;
    if (registry == null || registry.size() == 0) {
      return new Reachability(List.of());
    }
    return registry.snapshot(path -> apiBaseUrl + path);
  }

  /**
   * Tempdoc 501 Phase 28 + Phase 34: shared commit path.
   *
   * <p>Order is intentional and load-bearing:
   *
   * <ol>
   *   <li>{@code writeManifest} — persistent step. If this throws, current
   *       state stays as-is (no half-published event). Caller sees the
   *       IOException; current still reflects the previous successful
   *       publish.
   *   <li>{@code current.set} — in-memory snapshot updates to match disk.
   *   <li>{@code appendStartLog} — postmortem record fires BEFORE listener
   *       notifications. If a future synchronous listener throws or
   *       blocks, start.log still carries the event. Postmortem-resilient
   *       (§13.4.4): the start log records what happened regardless of
   *       downstream consumer health.
   *   <li>{@code notifyListeners} — each listener is wrapped in try/catch
   *       per {@link #notifyListeners}; one failing listener does not
   *       block others. Listener failures do not affect persisted state.
   * </ol>
   *
   * <p>Phase 34 reordered steps 3 and 4 (previously {@code notifyListeners}
   * preceded {@code appendStartLog}). The original order silently lost
   * postmortem entries for events whose listeners threw before reaching
   * the start-log append.
   */
  private RuntimeManifest commit(RuntimeManifest manifest, String startLogMessage)
      throws IOException {
    writeManifest(manifest);
    current.set(manifest);
    appendStartLog(startLogMessage);
    notifyListeners(manifest);
    return manifest;
  }

  private void writeManifest(RuntimeManifest manifest) throws IOException {
    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
    // Canonical last-writer-wins path.
    Path tmp = manifestPath.resolveSibling(MANIFEST_FILENAME + ".tmp");
    Files.writeString(tmp, json + "\n", StandardCharsets.UTF_8);
    try {
      Files.move(tmp, manifestPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException atomicFailed) {
      // Fallback for filesystems that don't support atomic move (very rare on Win/Mac/Linux for
      // intra-filesystem moves). Non-atomic is acceptable because consumers are tolerant.
      Files.move(tmp, manifestPath, StandardCopyOption.REPLACE_EXISTING);
    }

    // Tempdoc 501 §13.4.4: per-instance time-axis substrate. Two files
    // per instance:
    //   - manifest.json (overwritten on each publish) — terminal snapshot
    //     for one-shot postmortem readers.
    //   - manifest.log.ndjson (appended on each publish) — full publish
    //     series; the canonical history of what this instance went
    //     through. One JSON object per line, no trailing comma.
    // Both writes best-effort: filesystem failures here do not block the
    // canonical manifest write.
    try {
      Files.createDirectories(historyDir);
      Path histPath = historyDir.resolve(MANIFEST_FILENAME);
      Files.writeString(histPath, json + "\n", StandardCharsets.UTF_8);
      Path logPath = historyDir.resolve(INSTANCE_LOG_FILENAME);
      // ndjson: pretty-printed JSON would break the one-object-per-line
      // invariant; use the compact writer here.
      String compact = mapper.writeValueAsString(manifest);
      Files.writeString(
          logPath,
          compact + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      log.debug("Per-instance history write failed (non-fatal)", e);
    }
  }

  private void notifyListeners(RuntimeManifest manifest) {
    for (Consumer<RuntimeManifest> listener : listeners) {
      try {
        listener.accept(manifest);
      } catch (RuntimeException e) {
        log.warn("Manifest listener threw", e);
      }
    }
  }
}
