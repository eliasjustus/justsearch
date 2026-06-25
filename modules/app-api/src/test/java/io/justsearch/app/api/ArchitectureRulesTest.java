package io.justsearch.app.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;

@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {
  @ArchTest
  static final ArchRule appApiMustNotDependOnLuceneOrAiBridge =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.api..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("org.apache.lucene..", "io.justsearch.aibackend..");

  /**
   * 548 §4.1 (tier-2, on top of the tier-1 collapse): the lifecycle-state vocabulary has a single
   * authority — the proto enum {@code io.justsearch.contract.wire.LifecycleState}. app-api must not
   * re-introduce a hand-written {@code LifecycleState} (the deleted second authority). The proto
   * enum lives in {@code io.justsearch.contract.wire}, so it is not matched by this rule; only a
   * regression that re-adds {@code io.justsearch.app.api..LifecycleState} would fail it.
   */
  @ArchTest
  static final ArchRule appApiMustNotRedeclareLifecycleStateVocabulary =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.api..")
          .should()
          .haveSimpleName("LifecycleState")
          .as(
              "the lifecycle vocabulary's single authority is the proto enum"
                  + " io.justsearch.contract.wire.LifecycleState (548 §4.1); app-api must not"
                  + " re-introduce a hand-written LifecycleState");

  @ArchTest
  static final ArchRule appApiMustNotReadEnvOrSystemProperties =
      noClasses()
          .that()
          .resideInAnyPackage("io.justsearch.app.api..")
          .should()
          .callMethod(System.class, "getenv", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class)
          .orShould()
          .callMethod(System.class, "getProperty", String.class, String.class)
          .orShould()
          .callMethod(System.class, "setProperty", String.class, String.class);

  /**
   * Records annotated with {@code @RecordBuilder} must be constructed via their generated builder,
   * not by direct canonical constructor call. This prevents drift back to fragile positional
   * construction (tempdoc 370). The generated builder classes (ending in "Builder") are excluded
   * because their {@code build()} method necessarily calls the canonical constructor.
   *
   * <p>Note: {@code @RecordBuilder} has {@code @Retention(SOURCE)} so it's absent from bytecode.
   * We enumerate the types explicitly instead of using annotation-based detection.
   */
  private static final Set<String> RECORD_BUILDER_TYPES =
      Set.of(
          "io.justsearch.app.api.knowledge.KnowledgeSearchResponse",
          "io.justsearch.app.api.knowledge.KnowledgeSearchResponse$Hit",
          // Tempdoc 549 Phase E3: KnowledgeSearchResponse$PipelineExecution retired.
          "io.justsearch.app.api.status.MigrationGenerationView",
          "io.justsearch.app.api.status.EnrichmentProgressView",
          "io.justsearch.app.api.status.WorkerDebugView",
          "io.justsearch.app.api.status.WorkerOperationalView",
          "io.justsearch.app.api.knowledge.KnowledgeStatusView");

  private static final DescribedPredicate<JavaConstructorCall> TARGETS_RECORD_BUILDER_TYPE =
      new DescribedPredicate<>("constructor of a @RecordBuilder-annotated type") {
        @Override
        public boolean test(JavaConstructorCall call) {
          return RECORD_BUILDER_TYPES.contains(call.getTargetOwner().getName());
        }
      };

  /** Generated builder class names: {@code <Type>Builder} or {@code <Type>Builder$With}. */
  private static final Set<String> GENERATED_BUILDER_PREFIXES =
      RECORD_BUILDER_TYPES.stream()
          .map(t -> t.replace("$", "") + "Builder")
          .collect(java.util.stream.Collectors.toUnmodifiableSet());

  private static final DescribedPredicate<JavaClass> NOT_GENERATED_BUILDER =
      new DescribedPredicate<>("not a generated record-builder class") {
        @Override
        public boolean test(JavaClass javaClass) {
          String name = javaClass.getName();
          for (String prefix : GENERATED_BUILDER_PREFIXES) {
            // Match the builder class itself and its nested types (e.g., $With)
            if (name.equals(prefix) || name.startsWith(prefix + "$")) return false;
          }
          return true;
        }
      };

  @ArchTest
  static final ArchRule recordBuilderTypesMustUseBuilder =
      noClasses()
          .that(NOT_GENERATED_BUILDER)
          .should()
          .callConstructorWhere(TARGETS_RECORD_BUILDER_TYPE)
          .as(
              "@RecordBuilder records must be constructed via their builder,"
                  + " not by direct canonical constructor call (tempdoc 370)");
}
