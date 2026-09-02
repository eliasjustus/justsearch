/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.telemetry.catalog.EmptyTags;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Out-of-process extraction sandbox backed by a pool of <b>persistent</b> child JVMs (tempdoc 885
 * item 14, design decision 1).
 *
 * <p>Replaces the one-child-JVM-per-file {@code ProcessExtractionSandbox} shipped by tempdoc 410,
 * which was never enabled because a JVM start plus Tika class-loading per file costs hundreds of
 * milliseconds. Here each child is spawned lazily, handles one request at a time, and is reused
 * until it crashes, misses a deadline, or exhausts its request budget.
 *
 * <p><b>The deadline is enforced by killing the child, not by cancelling a thread.</b> That is the
 * property the in-process extractor cannot have: {@code Future.cancel(true)} only sets an interrupt
 * flag, and a wedged PDFBox/POI parse ignores it. {@link Process#destroyForcibly()} closes the
 * child's pipes, which unblocks this sandbox's reader task, so the calling thread returns at the
 * deadline and the next file gets a fresh child.
 *
 * <p><b>Failure classification.</b> A child that dies with {@code OutOfMemoryError} on its stderr
 * is reported as a plain {@link ContentExtractor.ExtractionException}, which
 * {@code JobBatchExtractor} already maps to {@code PARSER_FAILED} with
 * {@code IngestionRetryPolicy.NONE} — a permanent failure, because retrying a file that does not
 * fit in the child heap will exhaust it again. Any other non-zero exit is a
 * {@link SandboxExtractionException} (retryable), and a missed deadline is an
 * {@link TimeboxedContentExtractor.ExtractionTimeoutException} (PARSER_TIMEOUT), exactly as before.
 */
public final class PersistentExtractionSandbox implements ExtractionSandbox {
  private static final Logger log = LoggerFactory.getLogger(PersistentExtractionSandbox.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  static final int DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
  static final int DEFAULT_MAX_STDERR_BYTES = 64 * 1024;
  /** Requests one child handles before it is recycled — the leak guard (design decision 1). */
  static final int DEFAULT_MAX_REQUESTS_PER_CHILD = 500;

  static final String REASON_TIMEOUT = "timeout";
  static final String REASON_CRASH = "crash";
  static final String REASON_OOM = "oom";
  static final String REASON_REQUEST_BUDGET = "request_budget";
  static final String REASON_PROTOCOL = "protocol";

  private final List<String> command;
  private final TikaExtractionPolicy policy;
  private final OcrRoutingConfig ocrConfig;
  private final Duration timeout;
  private final int maxResponseBytes;
  private final int maxStderrBytes;
  private final int maxRequestsPerChild;
  private final ExtractionMetricCatalog catalog;

  private final Slot[] allSlots;
  private final BlockingQueue<Slot> freeSlots;
  private final ExecutorService readers;
  private final AtomicLong spawnCount = new AtomicLong();
  private final AtomicLong restartCount = new AtomicLong();
  private final Thread shutdownHook;
  private volatile boolean closed;

  public PersistentExtractionSandbox(
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      int poolSize,
      int maxRequestsPerChild,
      ExtractionMetricCatalog catalog) {
    this(
        command,
        policy,
        ocrConfig,
        timeout,
        poolSize,
        maxRequestsPerChild,
        catalog,
        DEFAULT_MAX_RESPONSE_BYTES,
        DEFAULT_MAX_STDERR_BYTES);
  }

  PersistentExtractionSandbox(
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      int poolSize,
      int maxRequestsPerChild,
      ExtractionMetricCatalog catalog,
      int maxResponseBytes,
      int maxStderrBytes) {
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("Sandbox command must not be empty");
    }
    this.command = List.copyOf(command);
    this.policy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    this.ocrConfig = ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
    this.timeout = timeout == null ? TimeboxedContentExtractor.DEFAULT_TIMEOUT : timeout;
    this.maxResponseBytes = Math.max(1024, maxResponseBytes);
    this.maxStderrBytes = Math.max(1024, maxStderrBytes);
    this.maxRequestsPerChild =
        maxRequestsPerChild > 0 ? maxRequestsPerChild : DEFAULT_MAX_REQUESTS_PER_CHILD;
    this.catalog = catalog;

    int size = Math.max(1, poolSize);
    this.allSlots = new Slot[size];
    this.freeSlots = new ArrayBlockingQueue<>(size);
    for (int i = 0; i < size; i++) {
      allSlots[i] = new Slot();
      freeSlots.add(allSlots[i]);
    }
    this.readers =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "extraction-sandbox-reader");
              t.setDaemon(true);
              return t;
            });
    // Belt to the child's PID-gate braces: a clean JVM exit that skips close() must not leave a
    // child behind either. Removed in close() so a per-test sandbox does not accumulate hooks.
    this.shutdownHook = new Thread(this::killAll, "extraction-sandbox-shutdown");
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  @Override
  public TikaExtractionPolicy policy() {
    return policy;
  }

  @Override
  public ExtractionArtifact extract(Path file)
      throws IOException, ContentExtractor.ExtractionException {
    Objects.requireNonNull(file, "file");
    if (closed) {
      throw new SandboxExtractionException("Sandbox is closed", null);
    }
    Slot slot;
    try {
      slot = freeSlots.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Interrupted waiting for a sandbox child");
    }
    try {
      return extractOnSlot(slot, file);
    } finally {
      freeSlots.add(slot);
    }
  }

  private ExtractionArtifact extractOnSlot(Slot slot, Path file)
      throws IOException, ContentExtractor.ExtractionException {
    Child child = acquireChild(slot);
    child.requests++;

    byte[] request =
        MAPPER.writeValueAsBytes(
            new SandboxExtractionRequest(
                SandboxExtractionRequest.CURRENT_SCHEMA_VERSION,
                file.toAbsolutePath().toString(),
                policy,
                ocrConfig));
    try {
      SandboxFrames.write(child.stdin, request);
    } catch (IOException e) {
      // The child died between the liveness check and the write (broken pipe).
      throw discardAndClassify(slot, child, REASON_CRASH, e);
    }

    Future<byte[]> pending =
        readers.submit(() -> SandboxFrames.read(child.stdout, maxResponseBytes));
    byte[] responseBytes;
    try {
      responseBytes = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // Kill FIRST: destroying the child closes its stdout, which is what releases the reader
      // task. cancel() alone would only interrupt a read that is not interruptible on Windows.
      discardChild(slot, child, REASON_TIMEOUT);
      pending.cancel(true);
      throw new TimeboxedContentExtractor.ExtractionTimeoutException(
          "Sandbox extraction timed out after " + timeout.toMillis() + "ms for: "
              + file.getFileName(),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      discardChild(slot, child, REASON_CRASH);
      pending.cancel(true);
      throw new InterruptedIOException("Interrupted awaiting sandbox response");
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof SandboxFrames.SandboxProtocolException) {
        throw discardAndClassify(slot, child, REASON_PROTOCOL, (Exception) cause);
      }
      throw discardAndClassify(
          slot, child, REASON_CRASH, cause instanceof Exception ex ? ex : new IOException(cause));
    }

    if (responseBytes == null) {
      // Clean EOF instead of a frame: the child exited while we were waiting for its answer.
      throw discardAndClassify(slot, child, REASON_CRASH, null);
    }

    String stderrTail = child.stderr.tail();
    if (!stderrTail.isEmpty() && log.isDebugEnabled()) {
      log.debug("Sandbox child stderr (success path): {}", stderrTail);
    }
    return decode(responseBytes);
  }

  private ExtractionArtifact decode(byte[] responseBytes)
      throws ContentExtractor.ExtractionException {
    try {
      SandboxExtractionResponse response =
          MAPPER.readValue(
              new String(responseBytes, StandardCharsets.UTF_8), SandboxExtractionResponse.class);
      if (response == null
          || response.schemaVersion() != SandboxExtractionResponse.CURRENT_SCHEMA_VERSION) {
        throw new IllegalArgumentException("Unsupported sandbox response schema");
      }
      ExtractionArtifact artifact = response.toArtifact();
      if (response.status() == ExtractionStatus.BUDGET_EXCEEDED) {
        throw new ContentExtractor.BudgetExceededException(
            "Sandbox extraction budget exceeded",
            response.reasonCode() == null ? "EXTRACTED_TEXT_TOO_LARGE" : response.reasonCode());
      }
      if (response.status() == ExtractionStatus.TIMED_OUT) {
        throw new TimeboxedContentExtractor.ExtractionTimeoutException(
            "Sandbox parser timed out", null);
      }
      if (response.status() == ExtractionStatus.FAILED) {
        throw new ContentExtractor.ExtractionException("Sandbox parser failed");
      }
      return artifact.validateContentBoundsOnly(policy.maxExtractedChars());
    } catch (ContentExtractor.ExtractionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SandboxExtractionException("Malformed sandbox response", e);
    }
  }

  /** Kills the child, records the restart, and turns its exit into the right typed failure. */
  private ContentExtractor.ExtractionException discardAndClassify(
      Slot slot, Child child, String reason, Exception cause) {
    int exitCode = exitCodeAfterKill(child);
    // The OOM signature only reaches the tail once the drain thread has seen EOF on the dead
    // child's stderr; reading it before that classifies a heap exhaustion as an ordinary crash.
    child.stderr.awaitDrain(2000L);
    String tail = child.stderr.tail();
    boolean oom = tail.contains("OutOfMemoryError");
    finishDiscard(slot, child, oom ? REASON_OOM : reason);
    if (oom) {
      // Permanent: the file does not fit in the child heap, so a retry exhausts it again.
      return new ContentExtractor.ExtractionException(
          "Sandbox child exhausted its heap (exit=" + exitCode + "): " + tail, cause);
    }
    return new SandboxExtractionException(
        "Sandbox child exited with code " + exitCode + ": " + tail, cause);
  }

  private void discardChild(Slot slot, Child child, String reason) {
    exitCodeAfterKill(child);
    finishDiscard(slot, child, reason);
  }

  private void finishDiscard(Slot slot, Child child, String reason) {
    child.close();
    if (slot.child == child) {
      slot.child = null;
    }
    restartCount.incrementAndGet();
    if (catalog != null) {
      catalog.sandboxRestartTotal.increment(ExtractionSandboxRestartTags.of(reason));
    }
    log.warn("Extraction sandbox child recycled (reason={}, pid={})", reason, child.pid);
  }

  private static int exitCodeAfterKill(Child child) {
    child.process.destroyForcibly();
    try {
      if (child.process.waitFor(5, TimeUnit.SECONDS)) {
        return child.process.exitValue();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return -1;
  }

  private Child acquireChild(Slot slot) throws IOException {
    Child current = slot.child;
    if (current != null && current.requests >= maxRequestsPerChild) {
      discardChild(slot, current, REASON_REQUEST_BUDGET);
      current = null;
    } else if (current != null && !current.process.isAlive()) {
      discardChild(slot, current, REASON_CRASH);
      current = null;
    }
    if (current == null) {
      current = spawn();
      slot.child = current;
    }
    return current;
  }

  private Child spawn() throws IOException {
    List<String> argv = new ArrayList<>(command);
    argv.add(ExtractionSandboxChild.PARENT_PID_FLAG + ProcessHandle.current().pid());
    Process process = new ProcessBuilder(argv).start();
    spawnCount.incrementAndGet();
    if (catalog != null) {
      catalog.sandboxSpawnTotal.increment(EmptyTags.INSTANCE);
    }
    log.info("Extraction sandbox child spawned (pid={})", process.pid());
    return new Child(process, maxStderrBytes);
  }

  @Override
  public void close() {
    closed = true;
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException e) {
      // Already shutting down — the hook is running or has run.
    }
    killAll();
    readers.shutdownNow();
  }

  private void killAll() {
    for (Slot slot : allSlots) {
      Child child = slot.child;
      if (child != null) {
        slot.child = null;
        child.process.destroyForcibly();
        child.close();
      }
    }
  }

  /** Children spawned since construction (test seam for the pool's reuse property). */
  long spawnCount() {
    return spawnCount.get();
  }

  /** Children discarded since construction (crash, timeout, OOM or request budget). */
  long restartCount() {
    return restartCount.get();
  }

  /** Live PID of the child currently bound to slot 0, or -1. Test seam for "same child reused". */
  long firstChildPid() {
    Child child = allSlots[0].child;
    return child == null ? -1L : child.pid;
  }

  /** One concurrency unit of the pool: at most one request in flight, at most one child. */
  private static final class Slot {
    private volatile Child child;
  }

  private static final class Child {
    private final Process process;
    private final long pid;
    private final OutputStream stdin;
    private final InputStream stdout;
    private final StderrTail stderr;
    private int requests;

    Child(Process process, int maxStderrBytes) {
      this.process = process;
      this.pid = process.pid();
      this.stdin = process.getOutputStream();
      this.stdout = process.getInputStream();
      this.stderr = new StderrTail(process.getErrorStream(), maxStderrBytes);
    }

    void close() {
      stderr.stop();
      try {
        stdin.close();
      } catch (IOException e) {
        // The pipe is already broken when the child is gone; nothing to recover.
      }
    }
  }

  /**
   * Drains the child's stderr on a daemon thread into a bounded buffer. Draining is mandatory, not
   * diagnostic: an undrained stderr pipe fills its OS buffer and wedges the child mid-parse, which
   * would look exactly like the hang this sandbox exists to prevent.
   */
  private static final class StderrTail {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final int maxBytes;
    private final Thread thread;

    StderrTail(InputStream stream, int maxBytes) {
      this.maxBytes = maxBytes;
      this.thread =
          new Thread(
              () -> {
                byte[] chunk = new byte[4096];
                try (InputStream in = stream) {
                  int n;
                  while ((n = in.read(chunk)) >= 0) {
                    append(chunk, n);
                  }
                } catch (IOException e) {
                  // Child gone; whatever was captured is the tail.
                }
              },
              "extraction-sandbox-stderr");
      thread.setDaemon(true);
      thread.start();
    }

    private synchronized void append(byte[] chunk, int n) {
      int room = maxBytes - buffer.size();
      if (room > 0) {
        buffer.write(chunk, 0, Math.min(room, n));
      }
    }

    synchronized String tail() {
      String value =
          new String(buffer.toByteArray(), StandardCharsets.UTF_8)
              .replaceAll("[\\r\\n\\t]+", " ")
              .trim();
      return value.length() <= 512 ? value : value.substring(value.length() - 512);
    }

    void awaitDrain(long millis) {
      try {
        thread.join(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    void stop() {
      thread.interrupt();
    }
  }
}
