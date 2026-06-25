package io.justsearch.telemetry.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Tempdoc 417 Phase 0: automated proof that mismatched-tag-schema or wrong-instrument-type
 * emission is a compile error, not a runtime check.
 *
 * <p>Catches regressions where a future change weakens the {@code Metric<T extends TagSchema>}
 * generic bound, which would let any production callsite slip past the catalog's compile-time
 * type guarantee. Modeled on
 * {@code modules/adapters-lucene/.../DeferredRuntimeCompileFailTest.java} (tempdoc 406 Gap H).
 */
class CatalogTypeSafetyCompileFailTest {

  @TempDir Path classOutDir;

  @Test
  void counterRecordWithWrongTagSchemaDoesNotCompile() {
    String src =
        "package io.justsearch.compilefail;\n"
            + "import io.justsearch.telemetry.catalog.CounterMetric;\n"
            + "import io.justsearch.telemetry.catalog.EmptyTags;\n"
            + "import io.justsearch.telemetry.catalog.TagSchema;\n"
            + "import io.opentelemetry.api.common.Attributes;\n"
            + "import java.util.Set;\n"
            + "class WrongTagsOnCounter {\n"
            + "  record FooTags() implements TagSchema {\n"
            + "    public Set<String> allowedKeys() { return Set.of(); }\n"
            + "    public Attributes toAttributes() { return Attributes.empty(); }\n"
            + "  }\n"
            + "  void f(CounterMetric<EmptyTags> c) { c.add(1L, new FooTags()); }\n"
            + "}\n";
    String diagnostics = compileExpectingFailure("WrongTagsOnCounter", src);
    assertTrue(
        diagnostics.contains("incompatible types") || diagnostics.contains("cannot be applied"),
        "expected type-mismatch diagnostic; got: " + diagnostics);
  }

  @Test
  void histogramRecordWithStringInsteadOfTagSchemaDoesNotCompile() {
    String src =
        "package io.justsearch.compilefail;\n"
            + "import io.justsearch.telemetry.catalog.HistogramMetric;\n"
            + "import io.justsearch.telemetry.catalog.EmptyTags;\n"
            + "class StringInsteadOfSchema {\n"
            + "  void f(HistogramMetric<EmptyTags> h) { h.record(42L, \"some-string\"); }\n"
            + "}\n";
    String diagnostics = compileExpectingFailure("StringInsteadOfSchema", src);
    assertTrue(
        diagnostics.contains("incompatible types") || diagnostics.contains("cannot be applied"),
        "expected type-mismatch diagnostic; got: " + diagnostics);
  }

  @Test
  void rawCounterMetricDoesNotEnforceTagSchema() {
    // Sanity check: Metric is a sealed interface; subclassing outside the catalog package
    // should fail.
    String src =
        "package io.justsearch.compilefail;\n"
            + "import io.justsearch.telemetry.catalog.Metric;\n"
            + "class CustomMetric implements Metric {\n"
            + "  public io.justsearch.telemetry.catalog.MetricDefinition definition() {\n"
            + "    return null;\n"
            + "  }\n"
            + "}\n";
    String diagnostics = compileExpectingFailure("CustomMetric", src);
    // Sealed-interface violation manifests as one of these messages depending on JDK version.
    assertTrue(
        diagnostics.contains("sealed")
            || diagnostics.contains("not allowed")
            || diagnostics.contains("permitted")
            || diagnostics.contains("cannot inherit"),
        "expected sealed-interface diagnostic; got: " + diagnostics);
  }

  private String compileExpectingFailure(String className, String src) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
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
        "expected compilation to FAIL for "
            + className
            + " but it succeeded; diag: "
            + diagnostics);
    return diagnostics;
  }

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
