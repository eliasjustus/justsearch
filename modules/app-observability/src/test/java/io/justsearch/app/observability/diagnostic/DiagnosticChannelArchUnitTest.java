/*
 * Slice 448 closure-pass D5 (2026-05-07): ArchUnit governance test for the
 * DiagnosticChannel substrate's recursion carve-out discipline.
 *
 * Per slice 448 phase 3: the substrate's SSE-emit code must mark log calls
 * with DiagnosticChannelInternalMarker so the appender's recursion carve-out
 * can drop them before they re-enter the channel. The runtime tests
 * (DiagnosticChannelAppenderTest#recursionMarkerDropsEvent +
 * #compoundMarkerAlsoDrops) cover the actual runtime drop behavior.
 *
 * <p>This ArchUnit test adds defense-in-depth at the import boundary: any
 * class within {@code io.justsearch.app.observability.diagnostic..} that
 * directly depends on {@code org.slf4j.Logger} (the API callers use to emit
 * log events) must also depend on the
 * {@code DiagnosticChannelInternalMarker} class. Forces a future contributor
 * who adds a {@code Logger} field to the diagnostic package to consciously
 * import the marker, raising the discipline floor without claiming
 * complete per-call enforcement.
 *
 * <p>Carve-out: the marker class itself is excluded by name (it defines the
 * marker via SLF4J's MarkerFactory but doesn't transitively depend on
 * org.slf4j.Logger).
 *
 * <p>Scope limitation (documented for future-substrate-hardening): this
 * rule does NOT enforce that every Logger.info/.warn/.error/.debug/.trace
 * call carries the marker as its first argument. The strong form requires
 * AST-level argument inspection that ArchUnit doesn't support cleanly.
 * Combined with the runtime drop tests + Logback's per-thread
 * AppenderBase.doAppend() recursion guard, the weaker class-level rule
 * here covers the realistic risk surface.
 *
 * <p>As of 2026-05-07 closure-pass, no class in the diagnostic package
 * directly depends on org.slf4j.Logger (only DiagnosticChannelAppenderInstaller
 * imports org.slf4j.LoggerFactory for {@code getILoggerFactory()} cast — not
 * org.slf4j.Logger itself). The rule fires the moment a future contributor
 * adds a Logger field to any class in the diagnostic package without also
 * importing the marker.
 */
package io.justsearch.app.observability.diagnostic;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

@AnalyzeClasses(
    packages = "io.justsearch.app.observability.diagnostic",
    importOptions = ImportOption.DoNotIncludeTests.class)
final class DiagnosticChannelArchUnitTest {

  private static final String LOGGER_FQN = "org.slf4j.Logger";
  private static final String MARKER_FQN =
      "io.justsearch.app.observability.diagnostic.DiagnosticChannelInternalMarker";

  /**
   * Custom condition: a class that directly depends on {@code org.slf4j.Logger} must
   * also directly depend on {@link DiagnosticChannelInternalMarker}. Trivially true for
   * classes that don't depend on Logger at all (the implication's antecedent fails).
   */
  private static final ArchCondition<JavaClass> DEPEND_ON_LOGGER_IMPLIES_DEPEND_ON_MARKER =
      new ArchCondition<>(
          "if depending on " + LOGGER_FQN + ", depend on " + MARKER_FQN) {
        @Override
        public void check(JavaClass clazz, ConditionEvents events) {
          final boolean usesLogger =
              clazz.getDirectDependenciesFromSelf().stream()
                  .anyMatch(d -> d.getTargetClass().getFullName().equals(LOGGER_FQN));
          if (!usesLogger) {
            return;
          }
          final boolean importsMarker =
              clazz.getDirectDependenciesFromSelf().stream()
                  .anyMatch(d -> d.getTargetClass().getFullName().equals(MARKER_FQN));
          if (!importsMarker) {
            events.add(
                SimpleConditionEvent.violated(
                    clazz,
                    clazz.getName()
                        + " depends on "
                        + LOGGER_FQN
                        + " but does not import "
                        + MARKER_FQN
                        + " — every Logger user in the diagnostic package must opt into"
                        + " the recursion carve-out by importing the internal marker."));
          }
        }
      };

  /**
   * Every class in {@code io.justsearch.app.observability.diagnostic..} (except the
   * marker class itself) that depends on {@code org.slf4j.Logger} must also depend on
   * {@link DiagnosticChannelInternalMarker}.
   *
   * <p>Catches the "added a Logger, forgot the marker exists" failure mode at import
   * time. Defense-in-depth complement to the runtime recursion-drop tests in
   * {@code DiagnosticChannelAppenderTest}.
   */
  @ArchTest
  static final ArchRule loggerCallersInDiagnosticPackageMustImportInternalMarker =
      classes()
          .that()
          .resideInAPackage("io.justsearch.app.observability.diagnostic..")
          .and()
          .doNotHaveFullyQualifiedName(MARKER_FQN)
          .should(DEPEND_ON_LOGGER_IMPLIES_DEPEND_ON_MARKER)
          .allowEmptyShould(true);
}
