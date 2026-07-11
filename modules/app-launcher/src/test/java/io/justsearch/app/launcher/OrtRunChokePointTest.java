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
 * ArchUnit guardrail for tempdoc 710 Move 2: {@code ai.onnxruntime.OrtSession#run} (any overload)
 * must be invoked only from classes inside the {@code io.justsearch.ort} package — the module that
 * owns {@link io.justsearch.ort.SessionHandle.Lease#run} / {@link
 * io.justsearch.ort.SessionHandle.Lease#runPinned}, the choke point every encoder now routes
 * through so ORT-call metrics recording cannot be forgotten at a call site (the shape of two
 * historical blind spots: NER's batched-path gap, tempdoc 691 B-5; embed's {@code runHidden}
 * late-chunking gap, tempdoc 710 S-B3).
 *
 * <p>Mirrors {@link ClosurePropertyTest}'s manual {@link JavaMethodCall} walk pattern (ArchUnit has
 * no single predicate for "any overload of method X on class Y").
 *
 * <p><strong>Named exemption.</strong> {@code io.justsearch.indexerworker.ort.ModelVerifier} is the
 * {@code verifyModel} Gradle task's standalone diagnostic harness: it calls
 * {@link io.justsearch.ort.OrtSessionAssembler#verifyModelSession}, which — by design, per its own
 * javadoc — returns a raw {@link ai.onnxruntime.OrtSession} for ad-hoc inspection, NOT a
 * {@link io.justsearch.ort.SessionHandle}. There is no {@code Lease} to route through; the encoder
 * lane/profiler/metrics concepts this choke point exists for do not apply to a one-shot dev-tool
 * session. Exempted by FQN, not by package, so a future production class in the same package does
 * not silently inherit the exemption.
 */
@AnalyzeClasses(
    packages = "io.justsearch",
    importOptions = {ImportOption.DoNotIncludeTests.class})
class OrtRunChokePointTest {

  private static final String ORT_SESSION_FQN = "ai.onnxruntime.OrtSession";
  private static final String RUN_METHOD_NAME = "run";
  private static final String CHOKE_POINT_PACKAGE = "io.justsearch.ort";

  /**
   * Classes explicitly exempted from the choke point, with the reason each is safe. Adding an
   * entry requires updating {@link #exemptionListSizeIsControlled()} and documenting why — mirrors
   * {@link ClosurePropertyTest}'s FQN-list discipline (a rename forces a deliberate edit, not a
   * silent simple-name collision elsewhere).
   */
  private static final Set<String> EXEMPT_CLASS_FQNS =
      Set.of("io.justsearch.indexerworker.ort.ModelVerifier");

  private static boolean isChokePointClass(JavaClass javaClass) {
    String pkg = javaClass.getPackageName();
    return pkg.equals(CHOKE_POINT_PACKAGE) || pkg.startsWith(CHOKE_POINT_PACKAGE + ".");
  }

  private static final DescribedPredicate<JavaClass> IS_SUBJECT_TO_THE_RULE =
      new DescribedPredicate<>(
          "resides outside io.justsearch.ort and is not an explicitly named exemption") {
        @Override
        public boolean test(JavaClass javaClass) {
          return !isChokePointClass(javaClass) && !EXEMPT_CLASS_FQNS.contains(javaClass.getFullName());
        }
      };

  private static boolean isOrtSessionRunCall(JavaMethodCall call) {
    return ORT_SESSION_FQN.equals(call.getTargetOwner().getFullName())
        && RUN_METHOD_NAME.equals(call.getName());
  }

  private static final ArchCondition<JavaClass> callsNoOrtSessionRun =
      new ArchCondition<>("not call OrtSession#run directly") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
          for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
            if (isOrtSessionRunCall(call)) {
              events.add(
                  SimpleConditionEvent.violated(
                      item,
                      item.getSimpleName()
                          + " calls OrtSession#run directly at "
                          + call.getSourceCodeLocation()
                          + " — route through SessionHandle.Lease#run /"
                          + " SessionHandle.Lease#runPinned (tempdoc 710 Move 2) so the ORT-call"
                          + " metrics choke point cannot be bypassed. If this is a legitimate new"
                          + " raw-session diagnostic use (no SessionHandle/Lease available), add it"
                          + " to OrtRunChokePointTest.EXEMPT_CLASS_FQNS with a documented reason."));
            }
          }
        }
      };

  @ArchTest
  static final ArchRule noClassOutsideOrtCommonCallsOrtSessionRunDirectly =
      classes()
          .that(IS_SUBJECT_TO_THE_RULE)
          .should(callsNoOrtSessionRun)
          .as(
              "no class outside io.justsearch.ort (or the named ModelVerifier exemption) may call"
                  + " OrtSession#run directly — tempdoc 710 Move 2 choke point");

  @Test
  void exemptionListSizeIsControlled() {
    assertEquals(
        1,
        EXEMPT_CLASS_FQNS.size(),
        "Adding or removing a choke-point exemption requires updating this assertion and"
            + " documenting why in EXEMPT_CLASS_FQNS' javadoc.");
  }
}
