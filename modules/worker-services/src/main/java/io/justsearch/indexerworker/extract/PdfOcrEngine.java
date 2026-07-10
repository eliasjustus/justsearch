/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one owned OCR engine: JustSearch renders and OCRs; Tika never spawns tesseract. Each document
 * gets a per-document render loop (serial — PDFBox {@link PDFRenderer} is not thread-safe on a
 * shared {@link PDDocument}) that fans page OCR out to a bounded parallel pool, joins in page order,
 * and owns every child process it spawns.
 *
 * <p>Two bounds are enforced honestly: a per-invocation timeout and an aggregate per-document
 * elapsed budget that must sit BELOW the outer {@link TimeboxedContentExtractor} 60s timebox so the
 * budget fires first and returns partial page-ordered text (truncated) rather than the whole
 * document being discarded. Every spawned {@link Process} is registered; interrupt/timeout/close
 * forcibly terminates the registered set (the {@code LlamaServerOps} kill discipline applied to
 * tesseract), fixing the interrupt-path orphan leak the previous {@code OcrConfidenceExtractor}
 * left.
 */
final class PdfOcrEngine {
  private static final Logger DEFAULT_LOG = LoggerFactory.getLogger(PdfOcrEngine.class);
  static final int DEFAULT_RENDER_DPI = 300;
  private static final long DEFAULT_BUDGET_MS = 30_000L;

  /** Substitutable process starter so tests can inject stub children (no real tesseract needed). */
  @FunctionalInterface
  interface ProcessStarter {
    Process start(List<String> command) throws IOException;
  }

  private final OcrRoutingConfig ocrConfig;
  private final Invocation fixedInvocation;
  private final Supplier<TikaOcrRuntime.RuntimePaths> runtimeSupplier;
  private final int renderDpi;
  private final int poolSize;
  private final Logger log;

  PdfOcrEngine(
      OcrRoutingConfig ocrConfig,
      ProcessStarter processStarter,
      String executable,
      int renderDpi,
      int poolSize,
      Logger log) {
    this(
        ocrConfig,
        new Invocation(
            executable == null || executable.isBlank() ? "tesseract" : executable, processStarter),
        null,
        renderDpi,
        poolSize,
        log);
  }

  PdfOcrEngine(
      OcrRoutingConfig ocrConfig,
      Supplier<TikaOcrRuntime.RuntimePaths> runtimeSupplier,
      int renderDpi,
      int poolSize,
      Logger log) {
    this(ocrConfig, null, Objects.requireNonNull(runtimeSupplier, "runtimeSupplier"), renderDpi, poolSize, log);
  }

  private PdfOcrEngine(
      OcrRoutingConfig ocrConfig,
      Invocation fixedInvocation,
      Supplier<TikaOcrRuntime.RuntimePaths> runtimeSupplier,
      int renderDpi,
      int poolSize,
      Logger log) {
    this.ocrConfig = ocrConfig == null ? OcrRoutingConfig.defaults() : ocrConfig;
    this.fixedInvocation = fixedInvocation;
    this.runtimeSupplier = runtimeSupplier;
    this.renderDpi = renderDpi > 0 ? renderDpi : DEFAULT_RENDER_DPI;
    this.poolSize = Math.max(1, poolSize);
    this.log = log == null ? DEFAULT_LOG : log;
  }

  /**
   * Production engine bound to the app-owned tesseract runtime, re-resolved per engine call (per
   * document, not per page): the "Install AI" flow can restore the runtime while the worker is
   * running, and the eligibility gate ({@code TikaOcrRuntime.blockedReason}) resolves per attempt —
   * a construction-time snapshot would leave the engine stuck on a stale executable/tessdata
   * (the {@code standalone-capability-stays-stuck} failure shape).
   */
  static PdfOcrEngine create(OcrRoutingConfig ocrConfig, Logger log) {
    return new PdfOcrEngine(
        ocrConfig, TikaOcrRuntime::resolve, DEFAULT_RENDER_DPI, defaultPoolSize(), log);
  }

  /** Resolves the executable + spawn environment for one engine call from the current runtime. */
  private Invocation resolveInvocation() {
    if (fixedInvocation != null) {
      return fixedInvocation;
    }
    TikaOcrRuntime.RuntimePaths runtime = runtimeSupplier.get();
    String exe =
        runtime != null && runtime.executable() != null ? runtime.executable().toString() : "tesseract";
    return new Invocation(exe, productionStarter(runtime));
  }

  static int defaultPoolSize() {
    return Math.min(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), 8);
  }

  private static ProcessStarter productionStarter(TikaOcrRuntime.RuntimePaths runtime) {
    return command -> {
      ProcessBuilder builder =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD);
      if (runtime != null && runtime.tessdataDirectory() != null) {
        builder.environment().put("TESSDATA_PREFIX", runtime.tessdataDirectory().toString());
      }
      if (runtime != null && runtime.executableDirectory() != null) {
        builder
            .environment()
            .merge(
                "PATH",
                runtime.executableDirectory().toString(),
                (oldValue, newValue) -> newValue + File.pathSeparator + oldValue);
      }
      // Defensive: keep each tesseract child single-threaded so the pool, not OpenMP, owns parallelism.
      builder.environment().put("OMP_THREAD_LIMIT", "1");
      return builder.start();
    };
  }

  /** OCRs every page of a PDF. */
  OcrEngineResult ocrPdf(Path pdf, int maxOcrChars) {
    return renderAndOcr(pdf, null, maxOcrChars);
  }

  /** OCRs a specified subset of PDF page indices (0-based), for the mixed-PDF selective path. */
  OcrEngineResult ocrPdfPages(Path pdf, List<Integer> pageIndices, int maxOcrChars) {
    return renderAndOcr(pdf, pageIndices, maxOcrChars);
  }

  /** OCRs a single pre-existing raster image (no rendering); text carries no page marker. */
  OcrEngineResult ocrImage(Path image, int maxOcrChars) {
    Invocation invocation = resolveInvocation();
    Set<Process> live = ConcurrentHashMap.newKeySet();
    Path tempDir = null;
    long deadlineNanos = System.nanoTime() + budgetMs() * 1_000_000L;
    try {
      tempDir = Files.createTempDirectory("justsearch-ocr-");
      Path base = tempDir.resolve("image");
      PageOcr page = runTesseract(invocation, live, image, base, remainingBudgetMs(deadlineNanos));
      if (page.failureReason() != null) {
        return OcrEngineResult.failed(page.failureReason());
      }
      String text = page.text();
      boolean truncated = false;
      if (maxOcrChars >= 0 && text.length() > maxOcrChars) {
        text = text.substring(0, Math.max(0, maxOcrChars));
        truncated = true;
      }
      if (text.isBlank()) {
        return new OcrEngineResult("", OcrConfidenceExtractor.Summary.empty(), truncated, null, 0);
      }
      return new OcrEngineResult(text, page.confidence(), truncated, null, 1);
    } catch (InterruptedException e) {
      killAll(live);
      Thread.currentThread().interrupt();
      return OcrEngineResult.failed(OcrSkipReason.TIMEOUT);
    } catch (IOException e) {
      log.debug("Single-image OCR failed for {}: {}", image.getFileName(), e.getMessage());
      return OcrEngineResult.failed(OcrSkipReason.UNKNOWN);
    } finally {
      killAll(live);
      deleteRecursively(tempDir);
    }
  }

  private OcrEngineResult renderAndOcr(Path pdf, List<Integer> pageSubset, int maxOcrChars) {
    Invocation invocation = resolveInvocation();
    Set<Process> live = ConcurrentHashMap.newKeySet();
    Path tempDir = null;
    ExecutorService pool = null;
    long deadlineNanos = System.nanoTime() + budgetMs() * 1_000_000L;
    boolean truncated = false;
    boolean interrupted = false;
    boolean sawTimeout = false;
    OcrSkipReason spawnFailure = null;
    List<PageTask> tasks = new ArrayList<>();
    StringBuilder merged = new StringBuilder();
    List<OcrConfidenceExtractor.Summary> confidences = new ArrayList<>();
    int pagesProcessed = 0;
    try {
      tempDir = Files.createTempDirectory("justsearch-ocr-");
      pool = Executors.newFixedThreadPool(poolSize, daemonFactory());
      Semaphore inFlight = new Semaphore(poolSize);
      try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
        PDFRenderer renderer = new PDFRenderer(document);
        List<Integer> targets = resolveTargets(pageSubset, document.getNumberOfPages());
        Integer maxPages = ocrConfig.maxPages();
        if (maxPages != null && maxPages > 0 && targets.size() > maxPages) {
          targets = targets.subList(0, maxPages);
          truncated = true;
        }
        Path renderDir = tempDir;
        for (int pageIndex : targets) {
          if (Thread.interrupted()) {
            interrupted = true;
            break;
          }
          if (System.nanoTime() >= deadlineNanos) {
            truncated = true;
            sawTimeout = true;
            break;
          }
          inFlight.acquire();
          boolean submitted = false;
          try {
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.GRAY);
            Path png = renderDir.resolve("page-" + pageIndex + ".png");
            ImageIO.write(image, "png", png.toFile());
            image = null; // free ~9MB/page promptly; the PNG on disk is the only retained copy
            Path base = renderDir.resolve("page-" + pageIndex);
            Future<PageOcr> future =
                pool.submit(
                    () -> {
                      try {
                        return runTesseract(invocation, live, png, base, remainingBudgetMs(deadlineNanos));
                      } finally {
                        inFlight.release();
                        deleteQuietly(png);
                      }
                    });
            tasks.add(new PageTask(pageIndex, future));
            submitted = true;
          } finally {
            if (!submitted) {
              inFlight.release();
            }
          }
        }
      }

      for (PageTask task : tasks) {
        long remaining = remainingBudgetMs(deadlineNanos);
        if (remaining <= 0) {
          truncated = true;
          sawTimeout = true;
          break;
        }
        try {
          PageOcr page = task.future().get(remaining, TimeUnit.MILLISECONDS);
          if (page.failureReason() == OcrSkipReason.TIMEOUT) {
            sawTimeout = true;
          } else if (page.failureReason() != null) {
            spawnFailure = page.failureReason();
          }
          if (page.text() != null && !page.text().isBlank()) {
            boolean pageTruncated = appendPage(merged, page.text(), task.pageIndex(), maxOcrChars);
            confidences.add(page.confidence());
            pagesProcessed++;
            if (pageTruncated) {
              truncated = true;
              break;
            }
          }
        } catch (TimeoutException e) {
          task.future().cancel(true);
          truncated = true;
          sawTimeout = true;
          break;
        } catch (ExecutionException e) {
          spawnFailure = OcrSkipReason.UNKNOWN;
        }
      }
    } catch (InterruptedException e) {
      interrupted = true;
    } catch (IOException | RuntimeException e) {
      log.debug("PDF OCR failed for {}: {}", pdf.getFileName(), e.getMessage());
      if (pagesProcessed == 0) {
        return finishAfterCleanup(pool, live, tempDir, OcrEngineResult.failed(OcrSkipReason.UNKNOWN));
      }
      spawnFailure = OcrSkipReason.UNKNOWN;
    } finally {
      cancelAll(tasks);
    }
    if (interrupted) {
      cancelAll(tasks);
      truncated = true;
    }

    shutdown(pool);
    killAll(live);
    deleteRecursively(tempDir);
    if (interrupted) {
      Thread.currentThread().interrupt();
    }

    OcrSkipReason failure =
        pagesProcessed > 0
            ? null
            : (sawTimeout || interrupted ? OcrSkipReason.TIMEOUT : spawnFailure);
    return new OcrEngineResult(
        merged.toString(),
        OcrConfidenceExtractor.aggregate(confidences),
        truncated,
        failure,
        pagesProcessed);
  }

  private OcrEngineResult finishAfterCleanup(
      ExecutorService pool, Set<Process> live, Path tempDir, OcrEngineResult result) {
    shutdown(pool);
    killAll(live);
    deleteRecursively(tempDir);
    return result;
  }

  private PageOcr runTesseract(
      Invocation invocation, Set<Process> live, Path image, Path outputBase, long remainingBudgetMs)
      throws InterruptedException {
    long waitMs = Math.min(remainingBudgetMs, perInvocationTimeoutMs());
    if (waitMs <= 0) {
      return PageOcr.failed(OcrSkipReason.TIMEOUT);
    }
    List<String> command =
        List.of(
            invocation.executable(),
            image.toString(),
            outputBase.toString(),
            "--psm",
            "6",
            "-l",
            ocrConfig.tikaLanguage(),
            "txt",
            "tsv");
    Process process = null;
    try {
      process = invocation.starter().start(command);
      live.add(process);
      boolean exited = process.waitFor(waitMs, TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        return PageOcr.failed(OcrSkipReason.TIMEOUT);
      }
      if (process.exitValue() != 0) {
        return PageOcr.failed(OcrSkipReason.UNKNOWN);
      }
      String text = readQuietly(Path.of(outputBase + ".txt"));
      OcrConfidenceExtractor.Summary confidence =
          OcrConfidenceExtractor.parseTsv(readQuietly(Path.of(outputBase + ".tsv")));
      return new PageOcr(text.strip(), confidence, null);
    } catch (InterruptedException e) {
      if (process != null) {
        process.destroyForcibly();
      }
      throw e;
    } catch (IOException | RuntimeException e) {
      log.debug("Tesseract invocation failed for {}: {}", image.getFileName(), e.getMessage());
      return PageOcr.failed(OcrSkipReason.UNKNOWN);
    } finally {
      if (process != null) {
        live.remove(process);
      }
    }
  }

  /** Mirrors {@code PolicyDrivenTikaExtractor.appendOcrPageText}: same marker and truncation shape. */
  private boolean appendPage(StringBuilder target, String pageText, int pageIndex, int maxOcrChars) {
    String block = "\n\n--- OCR page " + (pageIndex + 1) + " ---\n" + pageText.strip() + "\n";
    if (maxOcrChars < 0) {
      target.append(block);
      return false;
    }
    int remaining = maxOcrChars - target.length();
    if (remaining <= 0) {
      return true;
    }
    if (block.length() > remaining) {
      target.append(block, 0, remaining);
      return true;
    }
    target.append(block);
    return false;
  }

  private static List<Integer> resolveTargets(List<Integer> pageSubset, int pageCount) {
    List<Integer> targets = new ArrayList<>();
    if (pageSubset == null) {
      for (int i = 0; i < pageCount; i++) {
        targets.add(i);
      }
      return targets;
    }
    for (Integer page : pageSubset) {
      if (page != null && page >= 0 && page < pageCount) {
        targets.add(page);
      }
    }
    return targets;
  }

  private long budgetMs() {
    Integer perFile = ocrConfig.perFileTimeoutMs();
    return perFile != null && perFile > 0 ? perFile : DEFAULT_BUDGET_MS;
  }

  private long perInvocationTimeoutMs() {
    return Math.max(1L, (long) ocrConfig.tikaTimeoutSeconds() * 1000L);
  }

  private static long remainingBudgetMs(long deadlineNanos) {
    return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
  }

  private void killAll(Set<Process> live) {
    for (Process process : live) {
      process.destroyForcibly();
    }
    live.clear();
  }

  private static void cancelAll(List<PageTask> tasks) {
    for (PageTask task : tasks) {
      task.future().cancel(true);
    }
  }

  private void shutdown(ExecutorService pool) {
    if (pool != null) {
      pool.shutdownNow();
    }
  }

  private static java.util.concurrent.ThreadFactory daemonFactory() {
    return runnable -> {
      Thread thread = new Thread(runnable, "PdfOcrEngine-worker");
      thread.setDaemon(true);
      return thread;
    };
  }

  private static String readQuietly(Path path) {
    try {
      return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    } catch (IOException e) {
      return "";
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort per-page cleanup; the per-document temp directory is swept in finally.
    }
  }

  private static void deleteRecursively(Path dir) {
    if (dir == null) {
      return;
    }
    try (var paths = Files.walk(dir)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Best-effort recursive sweep of the per-document OCR temp directory.
                }
              });
    } catch (IOException ignored) {
      // Directory already gone or unreadable — nothing more to clean.
    }
  }

  /** One engine call's resolved spawn recipe: executable plus environment-carrying starter. */
  private record Invocation(String executable, ProcessStarter starter) {}

  private record PageTask(int pageIndex, Future<PageOcr> future) {}

  private record PageOcr(String text, OcrConfidenceExtractor.Summary confidence, OcrSkipReason failureReason) {
    static PageOcr failed(OcrSkipReason reason) {
      return new PageOcr("", OcrConfidenceExtractor.Summary.empty(), reason);
    }
  }

  /**
   * Engine output. {@code text} carries page markers for PDFs (none for single images) and is
   * already bounded to the caller's max-chars budget; {@code failureReason} is non-null only when
   * zero pages produced text (TIMEOUT for budget/timeout exhaustion, UNKNOWN for spawn failures).
   */
  record OcrEngineResult(
      String text,
      OcrConfidenceExtractor.Summary confidence,
      boolean truncated,
      OcrSkipReason failureReason,
      int pagesProcessed) {
    static OcrEngineResult failed(OcrSkipReason reason) {
      return new OcrEngineResult("", OcrConfidenceExtractor.Summary.empty(), false, reason, 0);
    }
  }
}
