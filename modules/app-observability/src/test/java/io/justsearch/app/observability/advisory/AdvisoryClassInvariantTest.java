package io.justsearch.app.observability.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Per slice 494 §6.3: ArchUnit-style 1:1 invariant between registered
 * {@link AdvisoryClassId} values and {@link AdvisoryProjector} implementations.
 *
 * <p>This test constructs the production {@link AdvisoryClassRegistry} (same
 * construction as both {@code HeadAssembly} paths) and verifies:
 * <ul>
 *   <li>Every known advisory class ID has exactly one registered projector.</li>
 *   <li>Every registered projector declares a class ID in the known set.</li>
 *   <li>No duplicate class IDs.</li>
 *   <li>The projector's {@code classId()} matches its registration key.</li>
 * </ul>
 *
 * <p>Follows the {@code ArchitectureRulesTest.RECORD_BUILDER_TYPES} idiom
 * (explicit enumeration + predicate audit) established at
 * {@code app-api/ArchitectureRulesTest.java:48–94}.
 */
@DisplayName("AdvisoryClassInvariant — 1:1 classId ↔ projector")
final class AdvisoryClassInvariantTest {

  /**
   * The canonical set of advisory class IDs that must have projectors.
   * Update this set when adding a new advisory class.
   */
  private static final Set<AdvisoryClassId> EXPECTED_CLASS_IDS =
      Set.of(OperationCompletionProjector.CLASS_ID, HealthRecoveryProjector.CLASS_ID);

  /**
   * Builds the production registry — same construction as HeadAssembly.
   */
  private static AdvisoryClassRegistry productionRegistry() {
    return AdvisoryClassRegistry.builder()
        .register(new OperationCompletionProjector())
        .register(new HealthRecoveryProjector())
        .build();
  }

  @Test
  @DisplayName("every expected class ID has a registered projector")
  void everyClassIdHasProjector() {
    AdvisoryClassRegistry registry = productionRegistry();
    for (AdvisoryClassId expected : EXPECTED_CLASS_IDS) {
      assertTrue(
          registry.contains(expected),
          "Missing projector for advisory class: " + expected.value());
    }
  }

  @Test
  @DisplayName("every registered projector declares an expected class ID")
  void everyProjectorDeclaresExpectedClassId() {
    AdvisoryClassRegistry registry = productionRegistry();
    for (AdvisoryClassId registered : registry.classIds()) {
      assertTrue(
          EXPECTED_CLASS_IDS.contains(registered),
          "Projector registered for unknown class ID: "
              + registered.value()
              + " — add it to EXPECTED_CLASS_IDS if intentional");
    }
  }

  @Test
  @DisplayName("registry size matches expected count (no duplicates, no orphans)")
  void registrySizeMatchesExpected() {
    AdvisoryClassRegistry registry = productionRegistry();
    assertEquals(
        EXPECTED_CLASS_IDS.size(),
        registry.size(),
        "Registry size mismatch — expected "
            + EXPECTED_CLASS_IDS.size()
            + " projectors, got "
            + registry.size());
  }

  @Test
  @DisplayName("each projector's classId() matches its registration key")
  void projectorClassIdMatchesKey() {
    AdvisoryClassRegistry registry = productionRegistry();
    Map<AdvisoryClassId, String> mismatches = new LinkedHashMap<>();
    for (AdvisoryClassId classId : registry.classIds()) {
      AdvisoryProjector<?> projector = registry.projector(classId);
      assertNotNull(projector, "null projector for " + classId.value());
      if (!projector.classId().equals(classId)) {
        mismatches.put(
            classId,
            "registered as "
                + classId.value()
                + " but projector.classId() returns "
                + projector.classId().value());
      }
    }
    assertTrue(
        mismatches.isEmpty(),
        "Projector classId mismatches: "
            + mismatches.entrySet().stream()
                .map(e -> e.getKey().value() + " → " + e.getValue())
                .collect(Collectors.joining("; ")));
  }
}
