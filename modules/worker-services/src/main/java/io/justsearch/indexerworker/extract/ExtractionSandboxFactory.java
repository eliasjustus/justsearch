/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.time.Duration;
import java.util.List;

/**
 * Builds selectable extraction sandboxes without coupling callers to sandbox implementation
 * classes. Tempdoc 417 post-merge: takes an {@link ExtractionMetricCatalog} (catalog-substrate)
 * instead of the legacy {@code Telemetry} handle.
 */
public final class ExtractionSandboxFactory {
  private ExtractionSandboxFactory() {}

  public enum Mode {
    IN_PROCESS,
    PROCESS
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
    TikaExtractionPolicy effectivePolicy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    OcrRoutingConfig effectiveOcrConfig =
        ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig;
    Duration effectiveTimeout =
        timeout == null ? TimeboxedContentExtractor.DEFAULT_TIMEOUT : timeout;
    if (mode == Mode.PROCESS) {
      return new TimeboxedContentExtractor(
          new ProcessExtractionSandbox(
              processCommand, effectivePolicy, effectiveOcrConfig, effectiveTimeout),
          effectiveTimeout,
          catalog);
    }
    // Tempdoc 560 §4.4/§6: the in-process extractor is pulled through the Worker's contribution
    // composer (the content extractor as a real first consumer of the substrate). The default
    // composition is a single CORE Tika catch-all, so this is behaviorally identical to the direct
    // delegate — but the extractor now IS a declared, composable contribution.
    return new TimeboxedContentExtractor(
        ExtractorContributionRegistry.withCoreTika(
            new PolicyDrivenTikaExtractor(effectivePolicy, effectiveOcrConfig, ocrMetricCatalog)),
        effectiveTimeout,
        catalog);
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
    return create(
        Mode.IN_PROCESS,
        TikaExtractionPolicy.defaults(),
        ocrConfig,
        TimeboxedContentExtractor.DEFAULT_TIMEOUT,
        catalog,
        ocrMetricCatalog,
        List.of());
  }
}
