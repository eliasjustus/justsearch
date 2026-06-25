package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

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
import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import java.util.Set;

/**
 * ArchUnit rule: production code outside {@code io.justsearch.telemetry..} must not directly
 * construct OTel {@link Attributes} or {@link AttributeKey}. Use
 * {@code io.justsearch.telemetry.catalog.TagSchema} instead.
 *
 * <p>This rule enforces the metric catalog substrate's primary invariant: every metric tag set
 * is declared by a typed {@code TagSchema} subtype, so non-allowlisted keys can't be silently
 * stripped and typo'd values can't drift across emit callsites.
 *
 * <p>Span attribute construction is out of scope (the catalog refactor covers metrics, not
 * traces). Span-instrumentation files are exempted by simple name; a future "trace catalog"
 * effort can shrink the exemption list.
 *
 * <p>Modeled on {@link EnvRegistryDirectReadTest} (tempdoc 347 D5). Added by tempdoc 417 Phase 0.
 */
@AnalyzeClasses(packages = "io.justsearch", importOptions = ImportOption.DoNotIncludeTests.class)
class AttributesUseRuleTest {

  /** Classes exempt from the rule. Span-instrumentation files only. */
  private static final Set<String> EXEMPT_SIMPLE_NAMES =
      Set.of(
          // Span attributes for OTel ORT-encoder fallback events (tracing, not metrics).
          "EncoderOrtRunSpans",
          // Span attributes for native session lifecycle (tracing, not metrics).
          "NativeSessionHandle",
          // Span attributes for contract event emission (tracing, not metrics).
          "ContractEmitter");

  /** Static methods that construct {@code Attributes} or {@code AttributeKey}. */
  private static final Set<String> BANNED_METHODS =
      Set.of(
          // Attributes
          "of",
          "builder",
          // AttributeKey static factories
          "stringKey",
          "longKey",
          "doubleKey",
          "booleanKey",
          "stringArrayKey",
          "longArrayKey",
          "doubleArrayKey",
          "booleanArrayKey");

  private static final ArchCondition<JavaClass> callAttributesConstructor =
      new ArchCondition<>("call OTel Attributes / AttributeKey constructors") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
          for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
            JavaClass owner = call.getTargetOwner();
            boolean isAttributesOwner =
                owner.isEquivalentTo(Attributes.class)
                    || owner.isEquivalentTo(AttributeKey.class)
                    || owner.isEquivalentTo(AttributesBuilder.class);
            if (isAttributesOwner && BANNED_METHODS.contains(call.getName())) {
              events.add(
                  SimpleConditionEvent.violated(
                      item,
                      item.getSimpleName()
                          + " calls "
                          + owner.getSimpleName()
                          + "."
                          + call.getName()
                          + "() at "
                          + call.getSourceCodeLocation()));
            }
          }
        }
      };

  @ArchTest
  static final ArchRule noDirectAttributesUseOutsideTelemetry =
      noClasses()
          .that()
          .resideOutsideOfPackages("io.justsearch.telemetry..")
          .and(
              DescribedPredicate.describe(
                  "are not exempt",
                  (JavaClass c) -> !EXEMPT_SIMPLE_NAMES.contains(c.getSimpleName())))
          .and(
              DescribedPredicate.describe(
                  "do not implement TagSchema",
                  (JavaClass c) -> !c.isAssignableTo(TagSchema.class)))
          .should(callAttributesConstructor)
          .as(
              "Production code should declare metric tags via TagSchema, not construct OTel"
                  + " Attributes directly. TagSchema implementations are exempt because they ARE"
                  + " the typed bridge to OTel Attributes. Add to EXEMPT_SIMPLE_NAMES if the"
                  + " class is span-instrumentation (out of scope for metric catalog refactor).");
}
