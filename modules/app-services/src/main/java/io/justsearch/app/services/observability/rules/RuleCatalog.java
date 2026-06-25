/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability.rules;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads operational-signal rules from a fixed classpath directory at boot.
 *
 * <p>Per tempdoc 430 §A.3 + §B.M (rev 3.1): rules live in classpath resources at
 * {@code /rules/operational-signals/*.yaml}, not on the filesystem. Production-safe;
 * hot reload is V1.5 work.
 *
 * <p>V1 ships exactly one rule (`memory-pressure.yaml`); the catalog API supports loading
 * multiple to forward-compat for V1.5 plugin-contributed rules.
 */
public final class RuleCatalog {

  private static final Logger log = LoggerFactory.getLogger(RuleCatalog.class);

  /** Classpath directory holding rule YAML files. */
  public static final String CLASSPATH_DIR = "rules/operational-signals";

  private final Map<String, Rule> rulesByName;

  private RuleCatalog(Map<String, Rule> rulesByName) {
    this.rulesByName = Map.copyOf(rulesByName);
  }

  /**
   * Constructs a catalog directly from a list of {@link Rule} records. Used by tests that
   * need to exercise the rule engine without going through classpath resource loading.
   */
  public static RuleCatalog ofRules(List<Rule> rules) {
    Objects.requireNonNull(rules, "rules");
    java.util.LinkedHashMap<String, Rule> byName = new java.util.LinkedHashMap<>();
    for (Rule rule : rules) {
      Rule prior = byName.put(rule.name(), rule);
      if (prior != null) {
        throw new IllegalArgumentException(
            "RuleCatalog.ofRules: duplicate rule name '" + rule.name() + "'");
      }
    }
    return new RuleCatalog(byName);
  }

  /** Returns the rules in deterministic insertion order. */
  public List<Rule> rules() {
    return List.copyOf(rulesByName.values());
  }

  /** Returns the rule with the given name, or {@code null}. */
  public Rule findByName(String name) {
    return rulesByName.get(Objects.requireNonNull(name, "name"));
  }

  /** Returns the count of loaded rules. */
  public int size() {
    return rulesByName.size();
  }

  /**
   * Loads all {@code *.yaml} files from the classpath directory {@link #CLASSPATH_DIR}.
   *
   * <p>If the directory is absent or empty, the catalog is empty (no error). This matches
   * the YAGNI shape: a deployment without rules still boots. Individual file parse failures
   * abort loading (a malformed rule is a build-time bug, not a graceful runtime degradation).
   */
  public static RuleCatalog fromClasspath() {
    return fromClasspath(CLASSPATH_DIR, RuleCatalog.class.getClassLoader());
  }

  static RuleCatalog fromClasspath(String dir, ClassLoader loader) {
    Objects.requireNonNull(dir, "dir");
    Objects.requireNonNull(loader, "loader");
    Map<String, Rule> rules = new java.util.LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    for (String resourcePath : listResources(dir, loader)) {
      if (!seen.add(resourcePath)) {
        continue;
      }
      try (InputStream in = loader.getResourceAsStream(resourcePath)) {
        if (in == null) {
          continue; // Resource enumeration sometimes returns paths whose stream is null.
        }
        Rule rule = RuleParser.parse(in, resourcePath);
        Rule prior = rules.put(rule.name(), rule);
        if (prior != null) {
          throw new IllegalStateException(
              "RuleCatalog: duplicate rule name '"
                  + rule.name()
                  + "' (sources: "
                  + resourcePath
                  + ")");
        }
      } catch (IOException e) {
        throw new IllegalStateException(
            "RuleCatalog: failed to read rule file " + resourcePath, e);
      }
    }
    return new RuleCatalog(rules);
  }

  /**
   * Enumerates {@code *.yaml} resources under the classpath directory. Handles both
   * filesystem-backed classloaders (development) and JAR-backed classloaders (production).
   */
  private static List<String> listResources(String dir, ClassLoader loader) {
    List<String> result = new ArrayList<>();
    Enumeration<URL> urls;
    try {
      urls = loader.getResources(dir);
    } catch (IOException e) {
      log.warn("RuleCatalog: failed to enumerate {}: {}", dir, e.getMessage());
      return List.of();
    }
    Set<String> alreadyAdded = new HashSet<>();
    while (urls.hasMoreElements()) {
      URL url = urls.nextElement();
      List<String> fromUrl = listResourcesAtUrl(url, dir);
      for (String r : fromUrl) {
        if (alreadyAdded.add(r)) {
          result.add(r);
        }
      }
    }
    Collections.sort(result);
    return result;
  }

  private static List<String> listResourcesAtUrl(URL url, String dir) {
    URI uri;
    try {
      uri = url.toURI();
    } catch (URISyntaxException e) {
      log.warn("RuleCatalog: malformed URL {}: {}", url, e.getMessage());
      return List.of();
    }
    Path basePath = pathFor(uri);
    if (basePath == null) {
      return List.of();
    }
    try (Stream<Path> stream = Files.walk(basePath)) {
      List<String> out = new ArrayList<>();
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".yaml"))
          .forEach(p -> out.add(dir + "/" + basePath.relativize(p).toString().replace('\\', '/')));
      return out;
    } catch (IOException e) {
      log.warn("RuleCatalog: failed to walk {}: {}", basePath, e.getMessage());
      return List.of();
    }
  }

  /** Returns the {@link Path} for a classpath URI, opening a JAR FileSystem if needed. */
  private static Path pathFor(URI uri) {
    String scheme = uri.getScheme();
    if ("file".equals(scheme)) {
      return Paths.get(uri);
    }
    if ("jar".equals(scheme)) {
      try {
        return tryGetJarPath(uri);
      } catch (Exception e) {
        log.warn("RuleCatalog: failed to open JAR FS for {}: {}", uri, e.getMessage());
        return null;
      }
    }
    log.debug("RuleCatalog: unsupported URI scheme '{}' for {}", scheme, uri);
    return null;
  }

  private static Path tryGetJarPath(URI jarUri) throws IOException {
    Map<String, ?> env = new HashMap<>();
    FileSystem fs;
    try {
      fs = FileSystems.newFileSystem(jarUri, env);
    } catch (FileSystemAlreadyExistsException already) {
      fs = FileSystems.getFileSystem(jarUri);
    }
    String specific = jarUri.getSchemeSpecificPart();
    int sep = specific.indexOf("!/");
    String inner = sep >= 0 ? specific.substring(sep + 1) : "/";
    return fs.getPath(inner);
  }
}
