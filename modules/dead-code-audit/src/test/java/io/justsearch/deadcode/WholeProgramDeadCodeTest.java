/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.deadcode;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 638 — whole-program closed-world dead-code report.
 *
 * <p>Unlike {@code UnreferencedCodeTest} (which runs on app-launcher's <em>Head-process</em>
 * classpath and only inspects private/package-private members), this test imports the union of
 * <em>every</em> production module's bytecode (this module depends on all of them) and inspects
 * members of <em>all</em> visibilities. In a closed application a symbol with zero callers across
 * the whole program is dead regardless of visibility — the library-style public exclusion does not
 * apply here.
 *
 * <p>This test is <b>report-only</b>: it never fails. It writes the current set of dead symbols to
 * {@code tmp/dead-code-jvm-report.json}; the governance {@code dead-code-jvm} gate ratchets that
 * report against a baseline. The deliberate <b>reachability roots</b> (the boundary where callers
 * come from outside the analysed bytecode — framework dispatch, serialization, JNI, entry points,
 * published API) are encoded as the {@code isRoot*} skips below, mirroring GraalVM native-image's
 * reachability-metadata categories (reflection / JNI / serialization / proxies / entry points).
 */
class WholeProgramDeadCodeTest {

  /**
   * Packages whose public API is an external contract (modules published to GitHub Packages via a
   * {@code maven-publish} block: app-api → {@code io.justsearch.app.api}, api-contract-projection-java
   * → {@code io.justsearch.contract}) — treated as roots since callers can be out of repo.
   */
  private static final List<String> PUBLISHED_API_PACKAGES =
      List.of("io.justsearch.app.api.", "io.justsearch.contract.");

  /** Coverage floor — the import must see the whole program, not a silently-truncated classpath. */
  private static final int MIN_EXPECTED_CLASSES = 1000;

  private static final List<String> COVERAGE_SENTINELS =
      List.of(
          "io.justsearch.indexerworker.loop.IndexingLoop", // worker-services (Worker process)
          "io.justsearch.ui.HeadlessApp", // ui (Head process)
          "io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes"); // adapters-lucene

  /**
   * The production {@code ServiceLoader} interfaces whose {@code META-INF/services} files declare
   * impls reachable only by name (no bytecode caller). Their listed impls are reachability roots.
   * Both files are on this module's classpath (it depends on ai-backend + adapters-lucene).
   */
  private static final List<String> SPI_SERVICE_INTERFACES =
      List.of(
          "io.justsearch.aibackend.backend.BackendProvider", "org.apache.lucene.codecs.Codec");

  /** FQNs declared in the SPI service files above — read once from the classpath. */
  private static final Set<String> SPI_IMPLS = readSpiImpls();

  private static Set<String> readSpiImpls() {
    Set<String> impls = new HashSet<>();
    ClassLoader cl = WholeProgramDeadCodeTest.class.getClassLoader();
    for (String iface : SPI_SERVICE_INTERFACES) {
      try {
        Enumeration<URL> urls = cl.getResources("META-INF/services/" + iface);
        while (urls.hasMoreElements()) {
          try (BufferedReader r =
              new BufferedReader(
                  new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
              int hash = line.indexOf('#');
              String fqn = (hash >= 0 ? line.substring(0, hash) : line).trim();
              if (!fqn.isEmpty()) {
                impls.add(fqn);
              }
            }
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return impls;
  }

  @Test
  void emit_whole_program_dead_code_report() {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.justsearch");

    assertWholeProgramCoverage(classes);

    // Holder roots: a class whose nested type is referenced is a live namespace shell even though
    // its own outer name has no direct incoming dependency (ArchUnit counts the ref against the
    // nested type). Pre-compute the enclosing names of every referenced nested type.
    Set<String> referencedNestedHolders = new java.util.HashSet<>();
    for (JavaClass c : classes) {
      if (c.getEnclosingClass().isPresent() && !c.getDirectDependenciesToSelf().isEmpty()) {
        referencedNestedHolders.add(c.getEnclosingClass().get().getName());
      }
    }

    List<String[]> dead = new ArrayList<>(); // {kind, symbol, location}

    // Scope: whole-program dead *classes* — the public/cross-module gap UnreferencedCodeTest leaves
    // (it covers private/package-private members on the Head classpath only). Whole-program dead
    // *method* detection was measured at ~6.4k findings, noise-dominated by reflectively-serialized
    // accessors / builders / fluent APIs — that needs GraalVM-metadata-level roots and is the
    // low-value long tail (tempdoc 638 §design); deliberately out of scope here.
    for (JavaClass c : classes) {
      if (!c.getPackageName().startsWith("io.justsearch")) {
        continue;
      }
      if (isRootClass(c) || !isDeadClassCandidate(c) || referencedNestedHolders.contains(c.getName())) {
        continue;
      }
      // Dead class: nothing in the whole program depends on it.
      if (c.getDirectDependenciesToSelf().isEmpty()) {
        dead.add(new String[] {"class", c.getName(), sourceOf(c)});
      }
    }

    writeReport(dead);
  }

  // --- reachability roots (the closed-world boundary) -----------------------------------------

  private static boolean isRootClass(JavaClass c) {
    String name = c.getName();
    if (isPublishedApi(name) || SPI_IMPLS.contains(name)) {
      return true; // external/codegen API, or a ServiceLoader impl reached by name
    }
    if (c.getModifiers().contains(JavaModifier.SYNTHETIC)) {
      return true;
    }
    // Process entry points (main classes) and framework service impls reach in from outside.
    if (hasMainMethod(c) || isGrpcImplBase(c) || hasNativeMethod(c)) {
      return true;
    }
    return false;
  }

  private static boolean isDeadClassCandidate(JavaClass c) {
    // Annotations / enums can be referenced only by name or reflectively; nested classes are noisy.
    // Constant-holder classes (a static-final primitive/String field) are referenced via *inlined*
    // constants — the javac inlining erases the bytecode reference, so they false-flag as dead.
    return c.isTopLevelClass()
        && !c.isAnnotation()
        && !c.isEnum()
        && !c.isInterface()
        && !isConstantHolder(c);
  }

  /**
   * A <em>pure</em> constant holder: a namespace of {@code static final} primitive/String constants
   * with no instance state or behaviour. javac inlines such constants into callers, erasing the
   * bytecode reference, so the holder false-flags as dead. Restricted to PURE holders so a genuinely
   * dead class that merely has one constant field is still flagged (not exempted).
   */
  private static boolean isConstantHolder(JavaClass c) {
    boolean hasInstanceMethod =
        c.getMethods().stream().anyMatch(m -> !m.getModifiers().contains(JavaModifier.STATIC));
    if (hasInstanceMethod) {
      return false;
    }
    var fields =
        c.getFields().stream().filter(f -> !f.getModifiers().contains(JavaModifier.SYNTHETIC)).toList();
    if (fields.isEmpty()) {
      return false;
    }
    return fields.stream()
        .allMatch(
            f ->
                f.getModifiers().contains(JavaModifier.STATIC)
                    && f.getModifiers().contains(JavaModifier.FINAL)
                    && (f.getRawType().isPrimitive()
                        || f.getRawType().getName().equals("java.lang.String")));
  }

  /**
   * Fail loudly if the import did not cover the whole program — a silently-truncated classpath would
   * yield a too-small report and corrupt the ratchet baseline (review finding F2).
   */
  private static void assertWholeProgramCoverage(JavaClasses classes) {
    long n = classes.stream().filter(c -> c.getPackageName().startsWith("io.justsearch")).count();
    if (n < MIN_EXPECTED_CLASSES) {
      throw new IllegalStateException(
          "dead-code-jvm: imported only "
              + n
              + " io.justsearch classes (< "
              + MIN_EXPECTED_CLASSES
              + ") — classpath likely truncated; report would be unsound.");
    }
    for (String sentinel : COVERAGE_SENTINELS) {
      if (!classes.contain(sentinel)) {
        throw new IllegalStateException(
            "dead-code-jvm: coverage sentinel " + sentinel + " absent — a module is off the analysis classpath.");
      }
    }
  }

  private static boolean isGrpcImplBase(JavaClass c) {
    return c.getAllRawSuperclasses().stream()
        .anyMatch(s -> s.getName().contains("Grpc$") && s.getName().contains("ImplBase"));
  }

  private static boolean hasMainMethod(JavaClass c) {
    return c.getMethods().stream()
        .anyMatch(
            m ->
                m.getName().equals("main")
                    && m.getModifiers().contains(JavaModifier.STATIC)
                    && m.getRawParameterTypes().size() == 1);
  }

  private static boolean hasNativeMethod(JavaClass c) {
    return c.getMethods().stream().anyMatch(m -> m.getModifiers().contains(JavaModifier.NATIVE));
  }

  private static boolean isPublishedApi(String className) {
    for (String p : PUBLISHED_API_PACKAGES) {
      if (className.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  // --- report emission ------------------------------------------------------------------------

  private static String sourceOf(JavaClass c) {
    return c.getSource().map(Object::toString).orElse(c.getName());
  }

  private static void writeReport(List<String[]> dead) {
    Set<String> lines = new TreeSet<>(); // sorted, deduped — stable report
    StringBuilder sb = new StringBuilder();
    sb.append("{\n  \"deadSymbols\": [\n");
    List<String> entries = new ArrayList<>();
    for (String[] d : dead) {
      entries.add(
          "    {\"kind\": \""
              + esc(d[0])
              + "\", \"symbol\": \""
              + esc(d[1])
              + "\", \"location\": \""
              + esc(d[2])
              + "\"}");
      lines.add(d[0] + " " + d[1]);
    }
    sb.append(String.join(",\n", new TreeSet<>(entries)));
    sb.append("\n  ],\n  \"count\": ").append(lines.size()).append("\n}\n");

    String reportPath = System.getProperty("deadcode.reportPath", "tmp/dead-code-jvm-report.json");
    try {
      Path out = Path.of(reportPath);
      if (out.getParent() != null) {
        Files.createDirectories(out.getParent());
      }
      Files.writeString(out, sb.toString());
      System.out.println("[dead-code-jvm] wrote " + lines.size() + " dead symbols to " + out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
