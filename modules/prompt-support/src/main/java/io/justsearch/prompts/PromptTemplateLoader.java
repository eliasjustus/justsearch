/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.prompts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.cache.ConcurrentMapTemplateCache;
import com.github.jknack.handlebars.io.TemplateLoader;
import com.github.jknack.handlebars.io.TemplateSource;
import com.google.common.base.Splitter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Loads prompt templates from the SSOT directory structure. */
public final class PromptTemplateLoader {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Splitter BLOCK_HEADER_SPLITTER =
      Splitter.onPattern("\\s+").omitEmptyStrings().trimResults();
  private final Path promptsRoot;
  private final Handlebars handlebars;
  private final ConcurrentMap<Path, PromptTemplate> cache = new ConcurrentHashMap<>();

  public PromptTemplateLoader(Path repoRoot) {
    Objects.requireNonNull(repoRoot, "repoRoot");
    this.promptsRoot = repoRoot.resolve("SSOT").resolve("prompts");
    TemplateLoader partialLoader =
        new TemplateLoader() {
          @Override
          public TemplateSource sourceAt(String location) throws IOException {
            Path resolved = resolvePartial(location);
            if (!Files.exists(resolved)) {
              throw new IOException("Partial not found: " + location);
            }
            return new TemplateSource() {
              @SuppressWarnings("unused")
              public String content() throws IOException {
                return Files.readString(resolved, StandardCharsets.UTF_8);
              }

              @Override
              public String content(Charset charset) throws IOException {
                return Files.readString(resolved, charset == null ? StandardCharsets.UTF_8 : charset);
              }

              @Override
              public String filename() {
                return resolved.toString();
              }

              @Override
              public long lastModified() {
                try {
                  return Files.getLastModifiedTime(resolved).toMillis();
                } catch (IOException e) {
                  return Instant.now().toEpochMilli();
                }
              }
            };
          }

          @Override
          public String resolve(String location) {
            return location;
          }

          @Override
          public String getPrefix() {
            return "";
          }

          @Override
          public void setPrefix(String prefix) {}

          @Override
          public String getSuffix() {
            return "";
          }

          @Override
          public void setSuffix(String suffix) {}

          @Override
          public Charset getCharset() {
            return StandardCharsets.UTF_8;
          }

          @Override
          public void setCharset(Charset charset) {}
        };
    this.handlebars = new Handlebars(partialLoader);
    this.handlebars.with(new ConcurrentMapTemplateCache());
    registerHelpers(this.handlebars);
  }

  public PromptTemplate load(PromptTemplateUri uri, Locale locale) throws PromptTemplateException {
    Objects.requireNonNull(uri, "uri");
    Locale resolvedLocale =
        locale == null ? Locale.forLanguageTag(uri.localeSegment()) : locale;
    List<Path> candidates = candidatePaths(uri, resolvedLocale);
    PromptTemplate template = null;
    for (Path candidate : candidates) {
      if (!Files.exists(candidate)) {
        continue;
      }
      PromptTemplate cached = cache.get(candidate);
      if (cached != null) {
        template = cached;
      } else {
        PromptTemplate loaded = readTemplate(candidate);
        PromptTemplate prior = cache.putIfAbsent(candidate, loaded);
        template = prior == null ? loaded : prior;
      }
      break;
    }
    if (template == null) {
      throw new PromptTemplateException("Template not found for URI " + uri.raw());
    }
    return template;
  }

  private PromptTemplate readTemplate(Path path) throws PromptTemplateException {
    if (!Files.exists(path)) {
      throw new PromptTemplateException("Template file does not exist: " + path);
    }
    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new PromptTemplateException("Failed to read template " + path, e);
    }
    int boundary = content.indexOf("---");
    if (boundary < 0) {
      throw new PromptTemplateException("Template " + path + " missing front matter boundary");
    }
    String frontMatter = content.substring(0, boundary).trim();
    String body = content.substring(boundary + 3).trim();
    JsonNode node;
    try {
      node = JSON.readTree(frontMatter);
    } catch (Exception e) {
      throw new PromptTemplateException("Invalid JSON front matter in " + path, e);
    }
    PromptTemplateMetadata metadata = PromptTemplateMetadata.fromJson(node);
    Map<String, PromptTemplateBlock> blocks = new LinkedHashMap<>();
    Map<String, String> blockSources = extractBlocks(body);
    for (Map.Entry<String, String> entry : blockSources.entrySet()) {
      blocks.put(entry.getKey(), new PromptTemplateBlock(entry.getKey(), entry.getValue(), handlebars, metadata));
    }
    return new PromptTemplate(metadata, blocks);
  }

  /**
   * Load only the front-matter metadata from a template file, skipping block
   * compilation (no Handlebars). Use this when only templateId, taskId, and
   * attributes are needed.
   */
  public PromptTemplateMetadata loadMetadataOnly(Path path) throws PromptTemplateException {
    if (!Files.exists(path)) {
      throw new PromptTemplateException("Template file does not exist: " + path);
    }
    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new PromptTemplateException("Failed to read template " + path, e);
    }
    int boundary = content.indexOf("---");
    if (boundary < 0) {
      throw new PromptTemplateException("Template " + path + " missing front matter boundary");
    }
    String frontMatter = content.substring(0, boundary).trim();
    JsonNode node;
    try {
      node = JSON.readTree(frontMatter);
    } catch (Exception e) {
      throw new PromptTemplateException("Invalid JSON front matter in " + path, e);
    }
    return PromptTemplateMetadata.fromJson(node);
  }

  private Map<String, String> extractBlocks(String body) throws PromptTemplateException {
    Map<String, String> blocks = new LinkedHashMap<>();
    String remaining = body;
    int searchFrom = 0;
    while (true) {
      int blockStart = remaining.indexOf("{{#block", searchFrom);
      if (blockStart < 0) {
        break;
      }
      int startBrace = remaining.indexOf("}}", blockStart);
      if (startBrace < 0) {
        throw new PromptTemplateException("Unterminated block declaration");
      }
      String header = remaining.substring(blockStart + "{{#block".length(), startBrace).trim();
      if (header.isEmpty()) {
        throw new PromptTemplateException("Block declaration missing name");
      }
      List<String> headerParts = BLOCK_HEADER_SPLITTER.splitToList(header);
      if (headerParts.isEmpty()) {
        throw new PromptTemplateException("Block declaration missing name");
      }
      String name = headerParts.get(0);
      int blockEnd = remaining.indexOf("{{/block}}", startBrace);
      if (blockEnd < 0) {
        throw new PromptTemplateException("Block '" + name + "' missing closing tag");
      }
      String content = remaining.substring(startBrace + 2, blockEnd);
      blocks.put(name, content.trim());
      searchFrom = blockEnd + "{{/block}}".length();
    }
    if (blocks.isEmpty()) {
      blocks.put("default", body);
    }
    return blocks;
  }

  private List<Path> candidatePaths(PromptTemplateUri uri, Locale locale) {
    List<Path> paths = new ArrayList<>();
    String baseName = uri.fileName();
    Path localized = resolveFeaturePath(locale.getLanguage(), uri.featureSegments()).resolve(baseName);
    paths.add(localized);
    String nameWithoutExt = baseName.replace(".mustache", "");
    Path localizedSuffix =
        resolveFeaturePath(locale.getLanguage(), uri.featureSegments())
            .resolve(nameWithoutExt + "." + locale.getLanguage() + ".mustache");
    paths.add(localizedSuffix);
    paths.add(resolveFeaturePath(uri.localeSegment(), uri.featureSegments()).resolve(baseName));
    return paths;
  }

  private Path resolveFeaturePath(String locale, List<String> segments) {
    Path path = promptsRoot.resolve(locale);
    for (String segment : segments) {
      path = path.resolve(segment);
    }
    return path;
  }

  private Path resolvePartial(String location) {
    String normalized = location.endsWith(".mustache") ? location : location + ".mustache";
    Path candidate = promptsRoot.resolve(normalized);
    if (Files.exists(candidate)) {
      return candidate;
    }
    return promptsRoot.resolve("shared").resolve(normalized);
  }

  private static void registerHelpers(Handlebars handlebars) {
    handlebars.registerHelper(
        "default",
        (context, options) -> {
          Object[] params = options.params;
          if (params.length == 0) {
            return "";
          }
          Object primary = params[0];
          if (isTruthy(primary)) {
            return primary;
          }
          if (params.length > 1) {
            return params[1];
          }
          return "";
        });
    handlebars.registerHelper(
        "truncate",
        (context, options) -> {
          if (options.params.length == 0) {
            return "";
          }
          Object text = options.params[0];
          int max = 128;
          if (options.params.length > 1 && options.params[1] instanceof Number number) {
            max = number.intValue();
          }
          String value = text == null ? "" : text.toString();
          if (value.length() <= max) {
            return value;
          }
          return value.substring(0, Math.max(0, max - 3)) + "...";
        });
  }

  private static boolean isTruthy(Object value) {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value instanceof Number number) {
      return number.doubleValue() != 0.0d;
    }
    if (value instanceof CharSequence cs) {
      return !cs.toString().isBlank();
    }
    if (value instanceof java.util.Collection<?> collection) {
      return !collection.isEmpty();
    }
    return true;
  }
}
