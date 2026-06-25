/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.prompts;

import com.google.common.base.Splitter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Parsed representation of a template URI such as {@code ssot://prompts/en/intent/foo.mustache#stage}. */
public final class PromptTemplateUri {
  private static final Splitter PATH_SPLITTER = Splitter.on('/').omitEmptyStrings().trimResults();
  private final String raw;
  private final String localeSegment;
  private final List<String> featureSegments;
  private final String fileName;
  private final String fragment;

  private PromptTemplateUri(
      String raw, String localeSegment, List<String> featureSegments, String fileName, String fragment) {
    this.raw = raw;
    this.localeSegment = localeSegment;
    this.featureSegments = List.copyOf(featureSegments);
    this.fileName = fileName;
    this.fragment = fragment == null ? "" : fragment;
  }

  public static PromptTemplateUri parse(String raw) throws PromptTemplateException {
    Objects.requireNonNull(raw, "uri");
    if (!raw.startsWith("ssot://")) {
      throw new PromptTemplateException("Template URI must start with ssot://: " + raw);
    }
    String withoutScheme = raw.substring("ssot://".length());
    String fragment = "";
    int fragIndex = withoutScheme.indexOf('#');
    if (fragIndex >= 0) {
      fragment = withoutScheme.substring(fragIndex + 1);
      withoutScheme = withoutScheme.substring(0, fragIndex);
    }
    List<String> segments = PATH_SPLITTER.splitToList(withoutScheme);
    if (segments.size() < 4) {
      throw new PromptTemplateException(
          "Template URI must include prompts/<locale>/<feature>/<file>: " + raw);
    }
    if (!"prompts".equals(segments.get(0))) {
      throw new PromptTemplateException("Template URI must target prompts/: " + raw);
    }
    String localeSegment = segments.get(1);
    if (localeSegment.isBlank()) {
      throw new PromptTemplateException("Template URI locale segment is blank: " + raw);
    }
    String fileName = segments.get(segments.size() - 1);
    if (!fileName.endsWith(".mustache")) {
      throw new PromptTemplateException(
          "Template URI must reference a .mustache file: " + raw);
    }
    List<String> featureSegments = segments.subList(2, segments.size() - 1);
    return new PromptTemplateUri(raw, localeSegment, featureSegments, fileName, fragment);
  }

  public String raw() {
    return raw;
  }

  public String localeSegment() {
    return localeSegment;
  }

  public List<String> featureSegments() {
    return featureSegments;
  }

  public String fileName() {
    return fileName;
  }

  public String fragment() {
    return fragment;
  }

  public Path relativePath() {
    Path path = Path.of("SSOT").resolve("prompts").resolve(localeSegment);
    for (String segment : featureSegments) {
      path = path.resolve(segment);
    }
    return path.resolve(fileName);
  }

  public String blockOrDefault() {
    return fragment == null || fragment.isBlank() ? "default" : fragment;
  }

  public String normalizedLocale() {
    Locale locale = Locale.forLanguageTag(localeSegment.replace('_', '-'));
    return locale.toLanguageTag();
  }
}
