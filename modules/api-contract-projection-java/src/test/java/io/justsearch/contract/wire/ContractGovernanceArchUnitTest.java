/*
 * Phase 5b of slice 3a-1-8: ArchUnit governance test for the contract
 * substrate's wire-Category projection.
 *
 * Per ADR-09 §"Governance Posture" + ADR-09a §"Decision": V1 ships static
 * structural checks. Mechanical structural-diff (`buf breaking`) is the
 * V1.5 follow-up. The substrate's invariant — wire types are declared in
 * the contract spec only — is V1-enforced via:
 *   1. The codegen path (this module's `generateProto` is the ONLY way to
 *      produce `io.justsearch.contract.wire.*` classes).
 *   2. This ArchUnit test (verifies the projection layer's structural
 *      invariants).
 *   3. Reviewer discipline on PRs touching `contracts/wire/`.
 *
 * The full "build refuses Jackson wire annotations outside the projection"
 * rule (per the substrate's kernel doc §"Governance Posture") requires a
 * permit list of modules where Jackson wire annotations are acceptable
 * (the facade records in `app-api/` and `app-observability/health/`).
 * Authoring that permit list is incremental Phase 5b follow-up work as
 * additional governance rules are surfaced.
 */
package io.justsearch.contract.wire;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
    packages = "io.justsearch.contract.wire",
    importOptions = ImportOption.DoNotIncludeTests.class)
final class ContractGovernanceArchUnitTest {

  /**
   * Smoke check: known wire-Category top-level messages exist in the projection
   * package and inherit the protobuf {@code Message} base. If codegen drift
   * removes one of these classes, the test fails — the substrate's "contract
   * is the only declaration site" rule remains intact.
   *
   * <p>Note: this is a *positive* assertion (these types exist + are messages),
   * not the broader negative governance check (no Jackson wire annotations
   * outside permit-listed packages). Per Phase 5b §B.x, the broader check
   * requires authoring a permit list of facade-record packages
   * (`io.justsearch.app.api.**`, `io.justsearch.app.observability.health.**`)
   * which is incremental Phase 5b follow-up.
   */
  @ArchTest
  static final ArchRule wireCategoryHealthEventIsMessage =
      classes()
          .that()
          .haveFullyQualifiedName("io.justsearch.contract.wire.HealthEvent")
          .should()
          .beAssignableTo(com.google.protobuf.Message.class);

  @ArchTest
  static final ArchRule wireCategoryHealthEventBodyIsMessage =
      classes()
          .that()
          .haveFullyQualifiedName("io.justsearch.contract.wire.HealthEventBody")
          .should()
          .beAssignableTo(com.google.protobuf.Message.class);

  @ArchTest
  static final ArchRule wireCategoryKnowledgeStatusViewIsMessage =
      classes()
          .that()
          .haveFullyQualifiedName("io.justsearch.contract.wire.KnowledgeStatusView")
          .should()
          .beAssignableTo(com.google.protobuf.Message.class);

  @ArchTest
  static final ArchRule wireCategoryStatusResponseIsMessage =
      classes()
          .that()
          .haveFullyQualifiedName("io.justsearch.contract.wire.StatusResponse")
          .should()
          .beAssignableTo(com.google.protobuf.Message.class);

  @ArchTest
  static final ArchRule wireCategorySseEnvelopeIsMessage =
      classes()
          .that()
          .haveFullyQualifiedName("io.justsearch.contract.wire.SseEnvelope")
          .should()
          .beAssignableTo(com.google.protobuf.Message.class);

  /**
   * Permit-list governance template: documents the rule shape per ADR-09
   * §"Future Agents Must Not". Permit list enumerated by the slice 3a-1-8
   * §A.11.1 census (22 wire-format files + 5 internal-serialization).
   *
   * <p><strong>Enforcement scope</strong>: this rule only fires on classes
   * within {@code io.justsearch.contract.wire..} (the projection module's
   * own package per {@code @AnalyzeClasses}). Inside that scope it
   * trivially passes — generated proto messages don't carry Jackson
   * annotations; it remains here as the canonical in-projection smoke.
   * <strong>Broader enforcement</strong> across the whole codebase (catching
   * new {@code @JsonTypeInfo}/{@code @JsonSubTypes} hierarchies in unforeseen
   * packages) is now SHIPPED — tempdoc 564 facet 4d lifted this rule to
   * {@code io.justsearch}-wide scope in
   * {@code io.justsearch.app.launcher.WireShapeMandateTest} (app-launcher is
   * the top assembly, so its test classpath imports every module). The
   * permit list below is mirrored there as the frozen ratchet baseline.
   *
   * <p>Uses string-FQN overload to avoid forcing a Jackson compile dep on
   * modules outside the permit list. Per ArchUnit gotcha #277, this does
   * not detect inherited annotations — base-class {@code @JsonTypeInfo} on
   * a sealed interface IS detected because the annotation is class-level
   * on the interface itself.
   */
  @ArchTest
  static final ArchRule jacksonWireSubTypesOnlyInPermitList =
      com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
          .that()
          .resideOutsideOfPackages(
              // Permit list per slice 3a-1-8 §A.11.1 census (22 wire-format files):
              "io.justsearch.app.api..",
              "io.justsearch.app.observability.health..",
              "io.justsearch.app.observability.metrics..",
              "io.justsearch.app.observability.operations..",
              "io.justsearch.app.observability.runtime..",
              "io.justsearch.agent.api.registry..",
              "io.justsearch.contract.wire..",
              // Permit list — internal serialization (5 files; non-wire usage):
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
          // Within api-contract-projection-java's analysis scope, no classes
          // outside the permit list match the `that()` clause (the projection
          // package IS in the permit list). Allow empty `should` so the rule
          // template is documented + future broader-scope placement preserves
          // its semantic.
          .allowEmptyShould(true);
}
