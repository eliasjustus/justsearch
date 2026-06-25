/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.List;
import java.util.Objects;

/** Worker-side OCR routing configuration derived from the resolved runtime config. */
public record OcrRoutingConfig(
    boolean enabled,
    List<String> languages,
    Integer perFileTimeoutMs,
    Integer maxPages,
    Integer maxImageDimension,
    Integer maxImagePixels) {
  public static final String PARSER_ID = "tika-policy-ocr";
  public static final String ENGINE = "tesseract";
  private static final List<String> DEFAULT_LANGUAGES = List.of("eng");

  public OcrRoutingConfig {
    languages =
        languages == null || languages.isEmpty()
            ? DEFAULT_LANGUAGES
            : languages.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    if (languages.isEmpty()) {
      languages = DEFAULT_LANGUAGES;
    }
  }

  public static OcrRoutingConfig disabled() {
    return new OcrRoutingConfig(false, DEFAULT_LANGUAGES, null, null, null, null);
  }

  public static OcrRoutingConfig defaults() {
    return new OcrRoutingConfig(true, DEFAULT_LANGUAGES, 30_000, 50, 4096, 40_000_000);
  }

  public static OcrRoutingConfig from(ResolvedConfig.Ocr ocr) {
    if (ocr == null) {
      return defaults();
    }
    boolean enabled = !Boolean.FALSE.equals(ocr.enabled());
    return new OcrRoutingConfig(
        enabled,
        ocr.languages(),
        ocr.perFileTimeoutMs(),
        ocr.maxPages(),
        ocr.maxImageDimension(),
        ocr.maxImagePixels());
  }

  public int tikaTimeoutSeconds() {
    int timeoutMs = perFileTimeoutMs == null || perFileTimeoutMs <= 0 ? 30_000 : perFileTimeoutMs;
    return Math.max(1, (int) Math.ceil(timeoutMs / 1000.0d));
  }

  public String tikaLanguage() {
    return String.join("+", languages);
  }
}
