package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 406 Gap H: automated proof that calling write-side ops on
 * {@link DeferredRuntime} or {@link ReadOnlyRuntime} is a compile error, not a runtime check.
 *
 * <p>Phase 4 of tempdoc 406 listed this check as either a Gradle compile-fail source set
 * OR "noted in Javadoc" — both acceptable. This test takes the JavaCompiler-API route:
 * snippet sources that should fail to compile are fed to {@code javac} at test time and
 * the resulting diagnostic is asserted to contain "cannot find symbol".
 *
 * <p>Catches regressions where a future change accidentally exposes
 * {@code indexingCoordinator()} on the read-only or deferred phase types — eliminating
 * the audit-confusion bug-class for write-on-read-only via the type system.
 */
class DeferredRuntimeCompileFailTest {

  @TempDir Path classOutDir;

  @Test
  void deferredRuntimeIndexingCoordinatorDoesNotCompile() {
    String src =
        "package io.justsearch.compilefail;\n"
            + "import io.justsearch.adapters.lucene.runtime.DeferredRuntime;\n"
            + "class WriteOnDeferred {\n"
            + "  void f(DeferredRuntime r) { r.indexingCoordinator(); }\n"
            + "}\n";
    String diagnostics = compileExpectingFailure("WriteOnDeferred", src);
    assertTrue(
        diagnostics.contains("cannot find symbol")
            && diagnostics.contains("indexingCoordinator"),
        "expected 'cannot find symbol' for indexingCoordinator() on DeferredRuntime; got: "
            + diagnostics);
  }

  @Test
  void readOnlyRuntimeIndexingCoordinatorDoesNotCompile() {
    String src =
        "package io.justsearch.compilefail;\n"
            + "import io.justsearch.adapters.lucene.runtime.ReadOnlyRuntime;\n"
            + "class WriteOnReadOnly {\n"
            + "  void f(ReadOnlyRuntime r) { r.indexingCoordinator(); }\n"
            + "}\n";
    String diagnostics = compileExpectingFailure("WriteOnReadOnly", src);
    assertTrue(
        diagnostics.contains("cannot find symbol")
            && diagnostics.contains("indexingCoordinator"),
        "expected 'cannot find symbol' for indexingCoordinator() on ReadOnlyRuntime; got: "
            + diagnostics);
  }

  @Test
  void runningRuntimeIndexingCoordinatorCompiles() {
    // Sanity check: the same call IS valid on RunningRuntime.
    String src =
        "package io.justsearch.compilefail;\n"
            + "import io.justsearch.adapters.lucene.runtime.RunningRuntime;\n"
            + "class WriteOnRunning {\n"
            + "  void f(RunningRuntime r) { r.indexingCoordinator(); }\n"
            + "}\n";
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "JavaCompiler not available — run on a JDK, not a JRE");
    StringWriter diag = new StringWriter();
    boolean ok =
        compiler
            .getTask(
                new PrintWriter(diag),
                compiler.getStandardFileManager(null, Locale.ROOT, null),
                null,
                compileOptions(),
                null,
                List.of(new InMemorySource("WriteOnRunning", src)))
            .call();
    assertTrue(ok, "RunningRuntime.indexingCoordinator() should compile cleanly: " + diag);
  }

  // --- helpers ---

  private String compileExpectingFailure(String className, String src) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "JavaCompiler not available — run on a JDK, not a JRE");
    StringWriter diag = new StringWriter();
    boolean ok =
        compiler
            .getTask(
                new PrintWriter(diag),
                compiler.getStandardFileManager(null, Locale.ROOT, null),
                null,
                compileOptions(),
                null,
                List.of(new InMemorySource(className, src)))
            .call();
    String diagnostics = diag.toString();
    assertFalse(
        ok,
        "expected compilation to FAIL for " + className + " but it succeeded; diag: " + diagnostics);
    return diagnostics;
  }

  /**
   * Common javac options. {@code -d} routes class file output to a per-test tempdir so the
   * success-case test ({@code runningRuntimeIndexingCoordinatorCompiles}) doesn't leak class
   * files into the module root.
   */
  private List<String> compileOptions() {
    return List.of(
        "-classpath", System.getProperty("java.class.path"),
        "-d", classOutDir.toString());
  }

  private static final class InMemorySource extends SimpleJavaFileObject {
    private final String content;

    InMemorySource(String className, String content) {
      super(URI.create("string:///" + className + Kind.SOURCE.extension), Kind.SOURCE);
      this.content = content;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return content;
    }
  }
}
