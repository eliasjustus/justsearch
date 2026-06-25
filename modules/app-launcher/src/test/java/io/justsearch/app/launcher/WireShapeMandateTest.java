package io.justsearch.app.launcher;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Tempdoc 564 facet 4d (mandate, not capability) — the <em>whole-codebase</em> enforcement of the
 * wire-shape mandate that {@code ContractGovernanceArchUnitTest} could only document.
 *
 * <p>That test's {@code jacksonWireSubTypesOnlyInPermitList} rule was correct in shape but scoped to
 * {@code io.justsearch.contract.wire..} alone (its module's {@code @AnalyzeClasses} package), so it
 * trivially passed — generated proto messages never carry Jackson annotations, and the rule never
 * saw the rest of the codebase (it explicitly noted: "Broader enforcement … requires placing the
 * rule in a module with broader test classpath access (e.g. system-tests)"). This test is that
 * placement: {@code app-launcher} is the top assembly, so its test classpath imports every
 * {@code io.justsearch} module, and the rule now actually fires across the whole codebase.
 *
 * <p><strong>The mandate.</strong> A new Jackson <em>polymorphic wire hierarchy</em>
 * ({@code @JsonTypeInfo} / {@code @JsonSubTypes}) — the shape that bypasses the gated contract and
 * reintroduces a hand-authored second authority — may exist <em>only</em> inside the permit-listed
 * facade / internal-serialization packages below. Outside them the build refuses it. This makes the
 * "wire types are declared in the contract spec, consumers are projections" rule mechanical (~100%)
 * rather than reviewer-discipline (~70%) for the polymorphic-hierarchy drift class.
 *
 * <p><strong>The permit list is the frozen ratchet baseline.</strong> It is today's census (slice
 * 3a-1-8 §A.11.1: 22 wire-format + 5 internal-serialization files) — the same shape as the repo's
 * {@code class-size-exceptions.txt} / {@code npm-audit-ratchet-baseline.v1.json} ratchets. The
 * intended motion is monotone <em>shrink</em>: as facets 4a/4b migrate a facade package onto a
 * generated projection, its entry is removed here. Adding a package is the explicit, reviewed
 * "refuse the new one" decision — not a silent default.
 *
 * <p><strong>Scope of this mandate vs. the ratification-gated remainder.</strong> This rule
 * mechanizes the <em>polymorphic-hierarchy</em> signal — concrete and unambiguous. The broader
 * "no hand-authored wire-shaped record at all" mandate (564 §4d / §7 tension #3) has no clean
 * mechanical signal until the 4a/4b migrations shrink the hand-authored set, and graduating that
 * axis from capability to mandate is the standing user ratification question (ADR-09
 * §"Capability vs Mandate"). That remainder is named there, not enforced here.
 */
@AnalyzeClasses(
    packages = "io.justsearch",
    importOptions = ImportOption.DoNotIncludeTests.class)
final class WireShapeMandateTest {

  /**
   * No class outside the permit-listed facade / internal-serialization packages may carry a Jackson
   * polymorphic-wire annotation. Measured clean at introduction (2026-05-31): all eight
   * {@code @JsonTypeInfo}/{@code @JsonSubTypes} sites in main sources reside in
   * {@code io.justsearch.agent.api.registry}, {@code io.justsearch.app.api.selection}, and
   * {@code io.justsearch.app.observability.health} — all permit-listed.
   *
   * <p>Uses the string-FQN {@code beAnnotatedWith} overload so this module needs no Jackson compile
   * dependency. Per ArchUnit gotcha #277 this does not detect <em>inherited</em> annotations, but a
   * base-class {@code @JsonTypeInfo} on a sealed wire interface IS detected because the annotation
   * is class-level on the interface itself (which the importer visits directly).
   */
  @ArchTest
  static final ArchRule noNewPolymorphicWireHierarchiesOutsidePermitList =
      noClasses()
          .that()
          .resideOutsideOfPackages(
              // Permit list (frozen ratchet baseline) — wire-format facade packages
              // per slice 3a-1-8 §A.11.1 census (22 wire-format files):
              "io.justsearch.app.api..",
              "io.justsearch.app.observability.health..",
              "io.justsearch.app.observability.metrics..",
              "io.justsearch.app.observability.operations..",
              "io.justsearch.app.observability.runtime..",
              "io.justsearch.agent.api.registry..",
              "io.justsearch.contract.wire..",
              // Permit list — internal serialization (5 files; non-wire JSON usage):
              "io.justsearch.configuration..",
              "io.justsearch.app.services.gpl..",
              "io.justsearch.testsupport.docs..",
              "io.justsearch.ui.api..",
              "io.justsearch.ui.policy..",
              "io.justsearch.ui.ai.pack..",
              "io.justsearch.core.dto..")
          .should()
          .beAnnotatedWith("com.fasterxml.jackson.annotation.JsonTypeInfo")
          .orShould()
          .beAnnotatedWith("com.fasterxml.jackson.annotation.JsonSubTypes")
          .as(
              "no Jackson polymorphic wire hierarchy (@JsonTypeInfo/@JsonSubTypes) may be declared "
                  + "outside the permit-listed facade packages (tempdoc 564 facet 4d) — declare wire "
                  + "types in the contract (contracts/wire/*.proto) and project, or, if this is a "
                  + "legitimate facade, add the package to the permit list in a reviewed change");
}
