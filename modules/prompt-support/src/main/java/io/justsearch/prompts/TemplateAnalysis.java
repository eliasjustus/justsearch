/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.prompts;

import com.google.common.base.Splitter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class TemplateAnalysis {
  private static final Splitter WHITESPACE =
      Splitter.onPattern("\\s+").omitEmptyStrings().trimResults();
  private static final Splitter LIMITED_WHITESPACE =
      Splitter.onPattern("\\s+").omitEmptyStrings().trimResults().limit(2);
  private final Set<String> variables = new LinkedHashSet<>();
  private final Set<String> sections = new LinkedHashSet<>();
  private final Set<String> partials = new LinkedHashSet<>();

  Set<String> variables() {
    return variables;
  }

  Set<String> sections() {
    return sections;
  }

  Set<String> partials() {
    return partials;
  }

  static TemplateAnalysis analyze(String source) {
    TemplateAnalysis analysis = new TemplateAnalysis();
    if (source == null || source.isBlank()) {
      return analysis;
    }
    int idx = 0;
    while (idx < source.length()) {
      int start = source.indexOf("{{", idx);
      if (start < 0) {
        break;
      }
      int end = findEnd(source, start + 2);
      if (end < 0) {
        break;
      }
      String token = source.substring(start + 2, end).trim();
      if (token.isEmpty()) {
        idx = end + 2;
        continue;
      }
      if (token.startsWith("!")) {
        idx = end + 2;
        continue;
      }
      if (token.startsWith(">")) {
        String partial = token.substring(1).trim();
        if (!partial.isEmpty()) {
          analysis.partials.add(partial);
        }
        idx = end + 2;
        continue;
      }
      if (token.startsWith("#") || token.startsWith("^")) {
        String sectionBody = token.substring(1).trim();
        if (!sectionBody.isBlank()) {
          List<String> nameParts = WHITESPACE.splitToList(sectionBody);
          if (!nameParts.isEmpty()) {
            String name = nameParts.get(0);
            if ("if".equals(name) || "unless".equals(name) || "with".equals(name)) {
              List<String> parts = LIMITED_WHITESPACE.splitToList(sectionBody);
              if (parts.size() == 2) {
                analysis.sections.add(parts.get(1));
              }
            } else if (!name.isBlank()) {
              analysis.sections.add(name.trim());
            }
          }
        }
        idx = end + 2;
        continue;
      }
      if (token.startsWith("/")) {
        idx = end + 2;
        continue;
      }
      String clean = token;
      boolean triple = false;
      if (clean.startsWith("{") && source.startsWith("{{{", start)) {
        triple = true;
      }
      if (triple) {
        clean = clean.substring(1);
      }
      List<String> parts = WHITESPACE.splitToList(clean);
      if (parts.isEmpty()) {
        idx = end + 2;
        continue;
      }
      if (parts.size() == 1) {
        analysis.variables.add(stripSpecial(parts.get(0)));
      } else {
        for (int i = 1; i < parts.size(); i++) {
          String part = parts.get(i);
          if (part.isBlank()) {
            continue;
          }
          if (part.startsWith("\"") && part.endsWith("\"")) {
            continue;
          }
          if (part.matches("-?\\d+(\\.\\d+)?")) {
            continue;
          }
          analysis.variables.add(stripSpecial(part));
        }
      }
      idx = end + 2;
    }
    return analysis;
  }

  private static String stripSpecial(String value) {
    if (value == null) {
      return "";
    }
    String stripped = value.strip();
    stripped = stripped.replaceAll("^[#.>/]+", "");
    if (stripped.startsWith("\"") && stripped.endsWith("\"") && stripped.length() >= 2) {
      stripped = stripped.substring(1, stripped.length() - 1);
    }
    if (stripped.endsWith("}}")) {
      stripped = stripped.substring(0, stripped.length() - 2);
    }
    return stripped.toLowerCase(Locale.ROOT);
  }

  private static int findEnd(String source, int fromIndex) {
    int braces = 0;
    for (int i = fromIndex; i < source.length() - 1; i++) {
      if (source.charAt(i) == '}' && source.charAt(i + 1) == '}') {
        if (braces == 0) {
          return i;
        }
        braces--;
      } else if (source.charAt(i) == '{' && source.charAt(i + 1) == '{') {
        braces++;
      }
    }
    return -1;
  }
}
