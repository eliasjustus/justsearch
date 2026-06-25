/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A content extractor wrapper that enforces a timeout on extraction operations.
 *
 * <p>This prevents a single pathological file from hanging the entire indexing loop.
 * When extraction times out:
 * <ul>
 *   <li>The extraction task is cancelled (best-effort; native parsers may not respond)</li>
 *   <li>An {@link ExtractionTimeoutException} is thrown</li>
 *   <li>A timeout counter is incremented for observability</li>
 * </ul>
 *
 * <p>Thread safety: This class is thread-safe. The underlying executor is a single-thread
 * executor to ensure isolation between extraction tasks.
 *
 * @see ContentExtractor
 */
public final class TimeboxedContentExtractor implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(TimeboxedContentExtractor.class);

  /** Default extraction timeout (60 seconds). */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

  /** Minimum allowed timeout (5 seconds). */
  public static final Duration MIN_TIMEOUT = Duration.ofSeconds(5);

  private final ContentExtractorProvider delegate;
  private final ExtractionSandbox sandbox;
  private final Duration timeout;
  private final ExecutorService executor;

  // Observability counters
  private final AtomicLong timeoutCount = new AtomicLong(0);
  private final ExtractionMetricCatalog catalog;

  // Rate-limited logging for timeouts
  private volatile long lastTimeoutLogMs = 0;
  private static final long TIMEOUT_LOG_INTERVAL_MS = 10_000; // 10 seconds

  /**
   * Creates a timeboxed content extractor with default timeout and no telemetry.
   *
   * @param delegate the underlying content extractor provider
   */
  public TimeboxedContentExtractor(ContentExtractorProvider delegate) {
    this(delegate, DEFAULT_TIMEOUT, null);
  }

  /** Creates a timeboxed extractor around an explicit sandbox implementation. */
  public TimeboxedContentExtractor(
      ExtractionSandbox sandbox, Duration timeout, ExtractionMetricCatalog catalog) {
    this(null, Objects.requireNonNull(sandbox, "sandbox"), timeout, catalog, true);
  }

  /**
   * Creates a timeboxed content extractor with specified timeout and optional telemetry.
   *
   * @param delegate the underlying content extractor provider
   * @param timeout extraction timeout (minimum 5 seconds)
   * @param catalog extraction metric catalog (may be null for tests)
   */
  public TimeboxedContentExtractor(
      ContentExtractorProvider delegate, Duration timeout, ExtractionMetricCatalog catalog) {
    this(
        Objects.requireNonNull(delegate, "delegate"),
        new InProcessExtractionSandbox(delegate),
        timeout,
        catalog,
        true);
  }

  /**
   * Internal constructor that allows tests to bypass {@link #MIN_TIMEOUT}.
   */
  TimeboxedContentExtractor(
      ContentExtractorProvider delegate,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      boolean enforceMinTimeout) {
    this(
        Objects.requireNonNull(delegate, "delegate"),
        new InProcessExtractionSandbox(delegate),
        timeout,
        catalog,
        enforceMinTimeout);
  }

  TimeboxedContentExtractor(
      ContentExtractorProvider delegate,
      ExtractionSandbox sandbox,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      boolean enforceMinTimeout) {
    this.delegate = delegate;
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
    if (enforceMinTimeout) {
      this.timeout = timeout == null || timeout.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : timeout;
    } else {
      this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
    }
    // Use a single-thread executor to isolate extraction work
    this.executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "ContentExtractor-Timebox");
      t.setDaemon(true);
      return t;
    });
    this.catalog = catalog;
  }

  /**
   * Extracts text content from a file with timeout enforcement.
   *
   * @param file the file to extract content from
   * @return the extraction result
   * @throws IOException if the file cannot be read
   * @throws ExtractionException if Tika parsing fails
   * @throws ExtractionTimeoutException if extraction exceeds the configured timeout
   */
  public ExtractionResult extract(Path file) throws IOException, ExtractionException {
    return extractArtifact(file).result();
  }

  /** Extracts a bounded artifact with timeout enforcement. */
  public ExtractionArtifact extractArtifact(Path file) throws IOException, ExtractionException {
    Objects.requireNonNull(file, "file");

    Callable<ExtractionArtifact> task = () -> sandbox.extract(file);
    Future<ExtractionArtifact> future = executor.submit(task);

    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      // Best-effort cancellation
      future.cancel(true);
      recordTimeout(file);
      throw new ExtractionTimeoutException(
          "Extraction timed out after " + timeout.toSeconds() + "s for: " + file.getFileName(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new ExtractionException("Extraction interrupted for: " + file.getFileName(), e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException ioe) {
        throw ioe;
      } else if (cause instanceof ExtractionException ee) {
        throw ee;
      } else {
        throw new ExtractionException("Extraction failed for: " + file.getFileName(), cause);
      }
    } catch (CancellationException e) {
      throw new ExtractionException("Extraction was cancelled for: " + file.getFileName(), e);
    }
  }

  /**
   * Attempts to extract content, returning an empty result on failure (including timeout).
   *
   * @param file the file to extract content from
   * @return the extraction result, or empty result if extraction fails
   */
  public ExtractionResult extractSafe(Path file) {
    try {
      return extract(file);
    } catch (IOException | ExtractionException e) {
      log.debug("Safe extraction failed for {}: {}", file, e.getMessage());
      return new ExtractionResult("", null, delegate.detectMimeType(file));
    }
  }

  /**
   * Detects the MIME type of a file without timeout (fast operation).
   *
   * @param file the file to detect
   * @return the detected MIME type
   */
  public String detectMimeType(Path file) {
    return delegate != null ? delegate.detectMimeType(file) : "application/octet-stream";
  }

  public TikaExtractionPolicy extractionPolicy() {
    if (sandbox instanceof InProcessExtractionSandbox inProcess) {
      return inProcess.policy();
    }
    if (sandbox instanceof ProcessExtractionSandbox process) {
      return process.policy();
    }
    return TikaExtractionPolicy.defaults();
  }

  /**
   * Returns the total number of extraction timeouts since this extractor was created.
   */
  public long getTimeoutCount() {
    return timeoutCount.get();
  }

  private void recordTimeout(Path file) {
    long count = timeoutCount.incrementAndGet();
    if (catalog != null) {
      catalog.timeoutTotal.increment(ExtractionTimeoutTags.of());
    }

    // Rate-limited warning log
    long now = System.currentTimeMillis();
    if (now - lastTimeoutLogMs > TIMEOUT_LOG_INTERVAL_MS) {
      lastTimeoutLogMs = now;
      log.warn("Extraction timeout for {} (total timeouts: {})", file.getFileName(), count);
    } else {
      log.debug("Extraction timeout for {} (total timeouts: {})", file.getFileName(), count);
    }
  }

  @Override
  public void close() {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Extraction executor did not terminate cleanly");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while shutting down extraction executor");
    }
  }

  /**
   * Exception thrown when content extraction exceeds the configured timeout.
   */
  public static class ExtractionTimeoutException extends ExtractionException {
    public ExtractionTimeoutException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
