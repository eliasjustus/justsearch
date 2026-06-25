package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit guardrail for tempdoc 397 §7.5 closure property: encoder <em>primary constructors</em>
 * must not perform I/O of any kind. Metadata loading (tokenizer, vocab, pooling config,
 * manifest, label mapping) belongs in the composition root or in each encoder's
 * {@code buildAssembly} static factory.
 *
 * <p>Originally added by §14.26 T1-F as an allowlist of three specific APIs
 * ({@code HuggingFaceTokenizer.newInstance}, {@code Files.read*}, {@code ModelManifest.load*}).
 * §14.28 Issue #10 critique: an allowlist only catches the specific APIs a bug was previously
 * written against; new I/O forms (e.g., {@code Files.newBufferedReader}, {@code Files.lines},
 * {@code ObjectMapper.readTree}) slip through. U8 converts the rule to a <strong>denylist
 * over owner packages and classes</strong> — encoder primary ctors may not call anything in
 * {@code java.nio.file.*}, {@code java.io.*} (readers/streams), or {@code java.nio.channels.*}
 * (whole-package bans), or in a specific list of I/O-bearing classes
 * ({@link #BANNED_OWNER_CLASSES} — JSON readers, {@code ModelManifest}, and the DJL loader
 * types {@code HuggingFaceTokenizer} / {@code DefaultVocabulary} / {@code Model} / {@code ModelZoo}).
 * The {@code ai.djl} package as a whole is <em>not</em> banned because it contains pure-inference
 * types (e.g., {@code Vocabulary}) that encoders legitimately call from their ctors. New I/O
 * APIs added to the banned packages are caught automatically.
 *
 * <p><strong>Identification discipline:</strong> encoder classes are listed by
 * <em>fully-qualified name</em>, not simple name. A rename (e.g.,
 * {@code OnnxEmbeddingEncoder} → {@code GteEmbeddingEncoder}) forces a deliberate edit of
 * this list and its size-control assertion; a simple-name collision elsewhere in the
 * codebase is no longer accidentally covered.
 *
 * <p><strong>Constructor-scope discipline:</strong> ArchUnit does not expose a public API
 * for "method call from a constructor body" as a single predicate. The rule walks every
 * {@link JavaMethodCall} in each encoder class and filters on
 * {@link JavaMethodCall#getOrigin()}{@code .getName().equals("<init>")}. Static factories
 * (e.g., {@code buildAssembly}, {@code detectPoolingStrategy}) have method-name origins and
 * pass — they remain the intended I/O location.
 *
 * <p><strong>Permitted exceptions</strong> (not considered I/O even though package-matched):
 * <ul>
 *   <li>{@code java.nio.file.Path} — a path factory + manipulation API, not I/O. Calls to
 *       {@code Path.of}, {@code Path.resolve}, {@code Path.toAbsolutePath}, etc. are permitted.
 *   <li>{@code java.io.IOException} / {@code UncheckedIOException} constructors — error wrappers
 *       are not I/O. Permitted by class simple-name.
 * </ul>
 *
 * <p>Cross-module reach: {@code app-launcher}'s test scope cannot import {@code worker-core}
 * or {@code reranker} types directly (no dependency edge). The rule identifies encoder
 * classes by FQN strings and banned APIs by package-owner FQNs. Mirrors the
 * {@code EnvRegistryDirectReadTest} pattern.
 */
@AnalyzeClasses(
    packages = "io.justsearch",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class ClosurePropertyTest {

  /**
   * Encoder classes whose primary constructors must be pure. FQN-based (§14.28 U8 upgrade from
   * the pre-U8 simple-name matching). Adding an encoder requires updating this list <strong>and</strong>
   * {@link #encoderAllowlistSizeIsControlled()}.
   */
  private static final Set<String> ENCODER_CLASS_FQNS =
      Set.of(
          "io.justsearch.indexerworker.ner.BertNerInference",
          "io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder",
          "io.justsearch.indexerworker.splade.SpladeEncoder",
          "io.justsearch.indexerworker.bgem3.BgeM3Encoder",
          "io.justsearch.reranker.CrossEncoderReranker",
          "io.justsearch.reranker.CitationScorer");

  /**
   * Owner packages that indicate I/O. A call whose target owner resides in one of these
   * packages (or sub-packages) is a violation when made from an encoder primary ctor.
   *
   * <p>{@code java.nio.file} covers {@code Files}, {@code FileSystems}, {@code StandardOpenOption}
   * (accessing all of these in a ctor is the bug shape). {@code java.io} covers
   * {@code FileReader}, {@code BufferedReader}, {@code FileInputStream}, etc.
   * {@code java.nio.channels} covers NIO channel APIs. DJL-specific I/O-bearing types are
   * listed individually in {@link #BANNED_OWNER_CLASSES} (the {@code ai.djl} package contains
   * many pure-inference types like {@code Vocabulary} that must not be blanket-banned).
   */
  private static final Set<String> BANNED_OWNER_PACKAGE_PREFIXES =
      Set.of("java.nio.file", "java.io", "java.nio.channels");

  /**
   * Specific classes outside the banned packages whose methods are I/O. Covers JSON readers,
   * project-internal manifest loaders, and DJL loader types (HuggingFaceTokenizer loads
   * tokenizer.json; DefaultVocabulary.Builder loads vocab.txt; Model.load loads an ONNX model).
   * A call to any method on one of these classes is a violation when made from an encoder
   * primary ctor.
   */
  private static final Set<String> BANNED_OWNER_CLASSES =
      Set.of(
          "io.justsearch.ort.ModelManifest",
          "com.fasterxml.jackson.databind.ObjectMapper",
          "tools.jackson.databind.ObjectMapper",
          "com.fasterxml.jackson.core.JsonParser",
          "tools.jackson.core.JsonParser",
          "ai.djl.huggingface.tokenizers.HuggingFaceTokenizer",
          "ai.djl.modality.nlp.DefaultVocabulary",
          "ai.djl.modality.nlp.DefaultVocabulary$Builder",
          "ai.djl.Model",
          "ai.djl.ModelZoo");

  /**
   * Permitted exception: {@link java.nio.file.Path} is a path-manipulation API, not I/O. Calls
   * to {@code Path.of}, {@code Path.resolve}, etc. are permitted in encoder ctors (a common
   * pattern — construct paths from strings for later use).
   */
  private static final String PATH_FQN = "java.nio.file.Path";

  /**
   * Permitted exception: exception wrappers. An encoder ctor calling
   * {@code new UncheckedIOException(...)} is wrapping an error, not doing I/O.
   */
  private static final Set<String> PERMITTED_EXCEPTION_CLASS_SIMPLE_NAMES =
      Set.of("IOException", "UncheckedIOException", "FileNotFoundException");

  private static boolean originatesFromConstructor(JavaMethodCall call) {
    return "<init>".equals(call.getOrigin().getName());
  }

  private static String targetOwnerFqn(JavaMethodCall call) {
    return call.getTargetOwner().getFullName();
  }

  private static String targetOwnerSimpleName(JavaMethodCall call) {
    return call.getTargetOwner().getSimpleName();
  }

  /** Tests whether a call to this target owner is considered I/O under the denylist. */
  private static boolean isBannedIoCall(JavaMethodCall call) {
    String ownerFqn = targetOwnerFqn(call);

    // Exception 1: Path is a manipulation API, not I/O.
    if (PATH_FQN.equals(ownerFqn)) {
      return false;
    }
    // Exception 2: exception wrappers live in java.io but are not I/O.
    if (PERMITTED_EXCEPTION_CLASS_SIMPLE_NAMES.contains(targetOwnerSimpleName(call))) {
      return false;
    }
    // Specific-class denylist hit.
    if (BANNED_OWNER_CLASSES.contains(ownerFqn)) {
      return true;
    }
    // Package-prefix denylist hit.
    for (String prefix : BANNED_OWNER_PACKAGE_PREFIXES) {
      if (ownerFqn.equals(prefix) || ownerFqn.startsWith(prefix + ".")) {
        return true;
      }
    }
    return false;
  }

  private static final DescribedPredicate<JavaClass> IS_ENCODER_CLASS =
      new DescribedPredicate<>("is an ORT-backed encoder class") {
        @Override
        public boolean test(JavaClass javaClass) {
          return ENCODER_CLASS_FQNS.contains(javaClass.getFullName());
        }
      };

  private static final ArchCondition<JavaClass> encoderCtorsHaveNoIo =
      new ArchCondition<>("encoder primary constructors perform no I/O") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
          for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
            if (!originatesFromConstructor(call)) {
              continue;
            }
            if (isBannedIoCall(call)) {
              events.add(
                  SimpleConditionEvent.violated(
                      item,
                      item.getSimpleName()
                          + "'s primary constructor calls "
                          + targetOwnerFqn(call)
                          + "#"
                          + call.getName()
                          + " at "
                          + call.getSourceCodeLocation()
                          + " — I/O belongs in the composition root or buildAssembly static"
                          + " factory, not in the encoder ctor (tempdoc 397 §7.5 / §14.28 U8)."));
            }
          }
        }
      };

  @ArchTest
  static final ArchRule encoderPrimaryCtorsMustBePure =
      classes()
          .that(IS_ENCODER_CLASS)
          .should(encoderCtorsHaveNoIo)
          .as(
              "encoder primary constructors must perform no I/O — denylist over owner packages"
                  + " (java.nio.file.*, java.io.*, java.nio.channels.*) and specific classes"
                  + " (ModelManifest, ObjectMapper, JsonParser, HuggingFaceTokenizer,"
                  + " DefaultVocabulary, Model, ModelZoo). See §14.28 U8.");

  @Test
  void encoderAllowlistSizeIsControlled() {
    assertEquals(
        6,
        ENCODER_CLASS_FQNS.size(),
        "Adding or removing an encoder requires updating this assertion and documenting why");
  }

  @Test
  void bannedOwnerPackagesSizeIsControlled() {
    assertEquals(
        3,
        BANNED_OWNER_PACKAGE_PREFIXES.size(),
        "Adding a banned owner package requires updating this assertion and documenting why"
            + " (new I/O form surfaced by a critical review, probably).");
  }
}
