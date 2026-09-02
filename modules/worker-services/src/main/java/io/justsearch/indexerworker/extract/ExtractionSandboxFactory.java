/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.time.Duration;
import java.util.List;

/**
 * Builds selectable extraction sandboxes without coupling callers to sandbox implementation
 * classes. Tempdoc 417 post-merge: takes an {@link ExtractionMetricCatalog} (catalog-substrate)
 * instead of the legacy {@code Telemetry} handle.
 *
 * <p>Tempdoc 885 item 14 added {@link Mode#AUTO} — per-family routing between the two — and made
 * it the shipped default. {@link Mode#PROCESS} now means the {@link PersistentExtractionSandbox}
 * pool, not a child JVM per file.
 */
public final class ExtractionSandboxFactory {
  private ExtractionSandboxFactory() {}

  public enum Mode {
    IN_PROCESS,
    PROCESS,
    AUTO
  }

  /**
   * How much longer than the sandbox deadline the outer {@link TimeboxedContentExtractor} waits
   * when a child pool is in play.
   *
   * <p>Both layers used to enforce the same duration, and the outer one starts its clock first, so
   * it ALWAYS won: every wedged child was reported as an interrupted wait rather than as a sandbox
   * timeout, and the pool's own kill-at-the-deadline path — the designed mechanism — never ran.
   * The chaos tier caught this (tempdoc 885 §SC-chaos); the unit tests could not, because they
   * drive the pool directly with no timebox around it. The grace has to cover the kill itself:
   * {@code destroyForcibly} + a 5 s {@code waitFor} + a 2 s stderr-drain join, plus slack.
   */
  static final Duration PROCESS_TIMEBOX_GRACE = Duration.ofSeconds(15);

  /** Pool sizing + leak-guard settings for the out-of-process families. */
  public record PoolSettings(int poolSize, int maxRequestsPerChild) {
    public static PoolSettings defaults() {
      return new PoolSettings(1, PersistentExtractionSandbox.DEFAULT_MAX_REQUESTS_PER_CHILD);
    }

    public PoolSettings {
      poolSize = poolSize > 0 ? poolSize : 1;
      maxRequestsPerChild =
          maxRequestsPerChild > 0
              ? maxRequestsPerChild
              : PersistentExtractionSandbox.DEFAULT_MAX_REQUESTS_PER_CHILD;
    }
  }

  public static TimeboxedContentExtractor create(
      Mode mode,
      TikaExtractionPolicy policy,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      List<String> processCommand) {
    return create(mode, policy, OcrRoutingConfig.disabled(), timeout, catalog, OcrMetricCatalog.noop(), processCommand);
  }

  public static TimeboxedContentExtractor create(
      Mode mode,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      List<String> processCommand) {
    return create(mode, policy, ocrConfig, timeout, catalog, OcrMetricCatalog.noop(), processCommand);
  }

  public static TimeboxedContentExtractor create(
      Mode mode,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      OcrMetricCatalog ocrMetricCatalog,
      List<String> processCommand) {
    return create(
        mode,
        policy,
        ocrConfig,
        timeout,
        catalog,
        ocrMetricCatalog,
        processCommand,
        PoolSettings.defaults());
  }

  public static TimeboxedContentExtractor create(
      Mode mode,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      OcrMetricCatalog ocrMetricCatalog,
      List<String> processCommand,
      PoolSettings poolSettings) {
    TikaExtractionPolicy effectivePolicy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    OcrRoutingConfig effectiveOcrConfig =
        ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
    Duration effectiveTimeout =
        timeout == null ? TimeboxedContentExtractor.DEFAULT_TIMEOUT : timeout;
    PoolSettings effectivePool = poolSettings == null ? PoolSettings.defaults() : poolSettings;

    if (mode == Mode.IN_PROCESS) {
      return new TimeboxedContentExtractor(
          inProcessSandbox(effectivePolicy, effectiveOcrConfig, ocrMetricCatalog),
          effectiveTimeout,
          catalog);
    }
    ExtractionSandbox pool =
        new PersistentExtractionSandbox(
            processCommand,
            effectivePolicy,
            effectiveOcrConfig,
            effectiveTimeout,
            effectivePool.poolSize(),
            effectivePool.maxRequestsPerChild(),
            catalog);
    // The sandbox owns the deadline; the timebox is only a backstop for a sandbox that itself
    // wedges. See PROCESS_TIMEBOX_GRACE.
    Duration backstop = effectiveTimeout.plus(PROCESS_TIMEBOX_GRACE);
    if (mode == Mode.PROCESS) {
      return new TimeboxedContentExtractor(pool, backstop, catalog);
    }
    ContentExtractorProvider provider =
        contributionProvider(effectivePolicy, effectiveOcrConfig, ocrMetricCatalog);
    return new TimeboxedContentExtractor(
        new RoutingExtractionSandbox(new InProcessExtractionSandbox(provider), pool, provider),
        backstop,
        catalog);
  }

  private static ExtractionSandbox inProcessSandbox(
      TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig, OcrMetricCatalog ocrMetricCatalog) {
    return new InProcessExtractionSandbox(contributionProvider(policy, ocrConfig, ocrMetricCatalog));
  }

  // Tempdoc 560 §4.4/§6: the in-process extractor is pulled through the Worker's contribution
  // composer (the content extractor as a real first consumer of the substrate). The default
  // composition is a single CORE Tika catch-all, so this is behaviorally identical to the direct
  // delegate — but the extractor now IS a declared, composable contribution.
  private static ContentExtractorProvider contributionProvider(
      TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig, OcrMetricCatalog ocrMetricCatalog) {
    return ExtractorContributionRegistry.withCoreTika(
        new PolicyDrivenTikaExtractor(policy, ocrConfig, ocrMetricCatalog));
  }

  public static TimeboxedContentExtractor inProcessStructured(ExtractionMetricCatalog catalog) {
    return inProcessStructured(catalog, OcrRoutingConfig.disabled());
  }

  public static TimeboxedContentExtractor inProcessStructured(
      ExtractionMetricCatalog catalog, OcrRoutingConfig ocrConfig) {
    return inProcessStructured(catalog, ocrConfig, OcrMetricCatalog.noop());
  }

  public static TimeboxedContentExtractor inProcessStructured(
      ExtractionMetricCatalog catalog, OcrRoutingConfig ocrConfig, OcrMetricCatalog ocrMetricCatalog) {
    return inProcessStructured(catalog, ocrConfig, ocrMetricCatalog, TikaExtractionPolicy.defaults());
  }

  /** As above, with an explicit policy (tempdoc 799 §N.2 — operator worker.limits.*). */
  public static TimeboxedContentExtractor inProcessStructured(
      ExtractionMetricCatalog catalog,
      OcrRoutingConfig ocrConfig,
      OcrMetricCatalog ocrMetricCatalog,
      TikaExtractionPolicy policy) {
    return create(
        Mode.IN_PROCESS,
        policy,
        ocrConfig,
        TimeboxedContentExtractor.DEFAULT_TIMEOUT,
        catalog,
        ocrMetricCatalog,
        List.of());
  }
}
