/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.lease;

import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLease;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Production impl of {@link OperationLeaseService}. Tempdoc 542 §B Layer 2 + Layer 3.
 *
 * <p>Persists op-leases to {@code <state-root>/op-leases.json} using the same {@code tmp+rename}
 * atomic-write pattern as {@code scripts/dev/dev-runner.cjs}. Different file from {@code
 * active.json}, so per-file atomic rename is contention-free even when the dev-runner supervisor
 * renews the routine lease concurrently.
 *
 * <p>The state root is resolved from the {@code JUSTSEARCH_DEV_RUNNER_STATE_ROOT} environment
 * variable, set by {@code dev-runner.cjs} when it spawns Head. Absent env var → no-op service:
 * register returns a trivial handle that does nothing. This means production / non-dev-runner
 * launches behave identically to today.
 *
 * <p>Thread-safe via an in-process {@link ReentrantLock} that serializes reads + writes of the
 * op-leases file. Cross-process safety is guaranteed by atomic rename (each writer renames its
 * own tmp file; the rename is atomic on POSIX and on Windows for the common case).
 */
public final class OperationLeaseServiceImpl implements OperationLeaseService {

  private static final Logger log = LoggerFactory.getLogger(OperationLeaseServiceImpl.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCHEMA = "op-leases.v1";
  private static final String ENV_STATE_ROOT = "JUSTSEARCH_DEV_RUNNER_STATE_ROOT";
  private static final String ENV_AGENT_SESSION_ID = "JUSTSEARCH_AGENT_SESSION_ID";

  /** Renewal extends expiresAt by this much past the renewal time. */
  static final long RENEWAL_EXTENSION_SEC = 60;

  private final Path leasesFile;
  private final ReentrantLock lock = new ReentrantLock();
  private final boolean enabled;

  public OperationLeaseServiceImpl() {
    String stateRoot = System.getenv(ENV_STATE_ROOT);
    if (stateRoot == null || stateRoot.isBlank()) {
      this.enabled = false;
      this.leasesFile = null;
      log.debug(
          "OperationLeaseService: {} unset; service is no-op (production / non-dev-runner launch).",
          ENV_STATE_ROOT);
    } else {
      this.enabled = true;
      this.leasesFile = Path.of(stateRoot).resolve("op-leases.json");
      log.info("OperationLeaseService: persisting to {}", this.leasesFile);
    }
  }

  /** Visible for testing — explicit file path. */
  OperationLeaseServiceImpl(Path leasesFile) {
    this.enabled = leasesFile != null;
    this.leasesFile = leasesFile;
  }

  @Override
  public OperationLeaseHandle register(
      String opClass,
      OpCriticality criticality,
      long expectedDurationSec,
      Map<String, Object> metadata) {
    if (opClass == null || opClass.isBlank()) {
      throw new IllegalArgumentException("opClass must be non-blank");
    }
    if (criticality == null) {
      throw new IllegalArgumentException("criticality must be non-null");
    }
    if (expectedDurationSec <= 0) {
      throw new IllegalArgumentException("expectedDurationSec must be positive");
    }
    if (!enabled) {
      return NoOpHandle.INSTANCE;
    }
    String opId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    long capSec = Math.max(expectedDurationSec * 2L, 60L);
    long safeCap = Math.min(capSec, 3600L); // hard 1-hour ceiling per §A.6
    Instant expiresAt = now.plusSeconds(safeCap);

    Map<String, Object> holder = currentHolder();
    Map<String, Object> safeMeta = metadata == null ? Map.of() : metadata;

    OperationLease lease =
        new OperationLease(
            opId,
            opClass,
            criticality,
            now,
            expectedDurationSec,
            expiresAt,
            null,
            "head",
            holder,
            safeMeta);
    rewrite(existing -> appendActive(existing, lease, now));
    log.info(
        "OperationLease registered: opId={} opClass={} criticality={} expiresAt={}",
        opId, opClass, criticality, expiresAt);
    return new Handle(opId, opClass);
  }

  private void renew(String opId) {
    if (!enabled) return;
    Instant now = Instant.now();
    rewrite(existing -> renewActive(existing, opId, now));
  }

  private void release(String opId, OpLeaseOutcome outcome) {
    if (!enabled) return;
    rewrite(existing -> removeActive(existing, opId, outcome));
    log.info("OperationLease released: opId={} outcome={}", opId, outcome);
  }

  // ----- File I/O (lock-serialized) -----

  private void rewrite(java.util.function.Function<Map<String, Object>, Map<String, Object>> mut) {
    lock.lock();
    try {
      Map<String, Object> existing = readOrEmpty();
      Map<String, Object> next = mut.apply(existing);
      next = expireStale(next, Instant.now());
      writeAtomic(next);
    } catch (IOException e) {
      log.warn("OperationLease: failed to rewrite leases file at {}: {}", leasesFile, e.toString());
    } finally {
      lock.unlock();
    }
  }

  Map<String, Object> readOrEmpty() throws IOException {
    if (!Files.exists(leasesFile)) {
      return defaultDoc();
    }
    String raw = Files.readString(leasesFile, StandardCharsets.UTF_8);
    if (raw.isBlank()) return defaultDoc();
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = MAPPER.readValue(raw, Map.class);
      if (parsed == null) return defaultDoc();
      parsed.putIfAbsent("schema", SCHEMA);
      parsed.putIfAbsent("opLeases", new ArrayList<>());
      return parsed;
    } catch (Exception e) {
      log.warn("OperationLease: malformed leases file ({}); rewriting empty.", e.toString());
      return defaultDoc();
    }
  }

  private void writeAtomic(Map<String, Object> doc) throws IOException {
    Files.createDirectories(leasesFile.getParent());
    Path tmp = leasesFile.resolveSibling(leasesFile.getFileName() + ".tmp");
    byte[] bytes = MAPPER.writeValueAsBytes(doc);
    Files.write(tmp, bytes);
    try {
      Files.move(tmp, leasesFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(tmp, leasesFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  // ----- Pure helpers -----

  private static Map<String, Object> defaultDoc() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("schema", SCHEMA);
    m.put("opLeases", new ArrayList<>());
    return m;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> appendActive(
      Map<String, Object> existing, OperationLease lease, Instant now) {
    List<Map<String, Object>> entries = (List<Map<String, Object>>) existing.get("opLeases");
    if (entries == null) {
      entries = new ArrayList<>();
      existing.put("opLeases", entries);
    }
    entries.add(toMap(lease));
    existing.put("updatedAt", now.toString());
    return existing;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> renewActive(
      Map<String, Object> existing, String opId, Instant heartbeat) {
    List<Map<String, Object>> entries = (List<Map<String, Object>>) existing.get("opLeases");
    if (entries == null) return existing;
    Instant minExtended = heartbeat.plusSeconds(RENEWAL_EXTENSION_SEC);
    for (Map<String, Object> entry : entries) {
      if (opId.equals(entry.get("opId"))) {
        entry.put("heartbeatAt", heartbeat.toString());
        Object cur = entry.get("expiresAt");
        Instant currentExpiry = null;
        if (cur instanceof String s) {
          try {
            currentExpiry = Instant.parse(s);
          } catch (Exception e) {
            // Malformed expiresAt — leave currentExpiry null so the renewal below extends to minExtended.
            log.debug("OperationLease: unparseable expiresAt {}; extending to minimum.", s);
          }
        }
        Instant newExpiry = currentExpiry == null || currentExpiry.isBefore(minExtended)
            ? minExtended
            : currentExpiry;
        entry.put("expiresAt", newExpiry.toString());
        break;
      }
    }
    existing.put("updatedAt", heartbeat.toString());
    return existing;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> removeActive(
      Map<String, Object> existing, String opId, OpLeaseOutcome outcome) {
    List<Map<String, Object>> entries = (List<Map<String, Object>>) existing.get("opLeases");
    if (entries == null) return existing;
    entries.removeIf(e -> opId.equals(e.get("opId")));
    existing.put("updatedAt", Instant.now().toString());
    existing.put("lastReleaseOutcome", outcome.name());
    return existing;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> expireStale(Map<String, Object> existing, Instant now) {
    List<Map<String, Object>> entries = (List<Map<String, Object>>) existing.get("opLeases");
    if (entries == null) return existing;
    int before = entries.size();
    entries.removeIf(
        e -> {
          Object exp = e.get("expiresAt");
          if (!(exp instanceof String s)) return false;
          try {
            return Instant.parse(s).isBefore(now);
          } catch (Exception ignored) {
            return false;
          }
        });
    if (entries.size() < before) {
      log.info("OperationLease: expired {} stale entries", before - entries.size());
    }
    return existing;
  }

  private static Map<String, Object> toMap(OperationLease lease) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("opId", lease.opId());
    m.put("opClass", lease.opClass());
    m.put("criticality", lease.criticality().name());
    m.put("startedAt", lease.startedAt().toString());
    m.put("expectedDurationSec", lease.expectedDurationSec());
    m.put("expiresAt", lease.expiresAt().toString());
    m.put("heartbeatAt", lease.heartbeatAt() == null ? null : lease.heartbeatAt().toString());
    m.put("originProcess", lease.originProcess());
    m.put("holder", lease.holder());
    m.put("metadata", lease.metadata());
    return m;
  }

  private static Map<String, Object> currentHolder() {
    Map<String, Object> h = new LinkedHashMap<>();
    String sessionId = System.getenv(ENV_AGENT_SESSION_ID);
    h.put("source", sessionId == null || sessionId.isBlank() ? "head" : "head-via-agent");
    h.put("agentSessionId", sessionId == null || sessionId.isBlank() ? null : sessionId);
    return h;
  }

  // ----- Handle inner class -----

  private final class Handle implements OperationLeaseHandle {
    private final String opId;
    private final String opClass;
    private volatile boolean released = false;

    Handle(String opId, String opClass) {
      this.opId = opId;
      this.opClass = opClass;
    }

    @Override
    public String opId() {
      return opId;
    }

    @Override
    public String opClass() {
      return opClass;
    }

    @Override
    public void renew() {
      if (released) return;
      OperationLeaseServiceImpl.this.renew(opId);
    }

    @Override
    public synchronized void release(OpLeaseOutcome outcome) {
      if (released) return;
      released = true;
      OperationLeaseServiceImpl.this.release(opId, outcome);
    }

    @Override
    public void close() {
      release(OpLeaseOutcome.SUCCESS);
    }
  }

  private enum NoOpHandle implements OperationLeaseHandle {
    INSTANCE;

    @Override
    public String opId() {
      return "noop";
    }

    @Override
    public String opClass() {
      return "noop";
    }

    @Override
    public void renew() {}

    @Override
    public void release(OpLeaseOutcome outcome) {}

    @Override
    public void close() {}
  }
}
