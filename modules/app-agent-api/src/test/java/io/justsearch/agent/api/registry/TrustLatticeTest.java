package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 560 WS6 (§4.3) — the genuinely N-dimensional trust lattice. The load-bearing claim WS6
 * corrects is that adding a dimension is a longer coordinate through the SAME lookup, not a forked
 * evaluator. These tests exercise the generic mechanism and demonstrate a third dimension added
 * without touching the lattice class.
 */
final class TrustLatticeTest {

  // A synthetic third axis (corpus sensitivity) — values are plain objects; a coordinate is
  // dimension-agnostic, so a new axis needs no change to TrustLattice.
  private enum Corpus {
    PUBLIC,
    SENSITIVE
  }

  @Test
  void declaredCellResolvesAndUndeclaredFailsSafeToTheDefault() {
    TrustLattice lattice =
        TrustLattice.builder()
            .dimensions("source", "risk")
            .cell(List.of(SourceTier.TRUSTED, RiskTier.LOW), GateBehavior.AUTO)
            .defaultOnMissing(GateBehavior.DENY)
            .build();

    assertEquals(GateBehavior.AUTO, lattice.gate(List.of(SourceTier.TRUSTED, RiskTier.LOW)));
    // Undeclared coordinate → most-restrictive default (adding cells can never silently weaken a gate).
    assertEquals(GateBehavior.DENY, lattice.gate(List.of(SourceTier.UNTRUSTED, RiskTier.HIGH)));
  }

  @Test
  void coordinateArityMustMatchTheDimensionCount() {
    TrustLattice lattice =
        TrustLattice.builder().dimensions("source", "risk").build();
    assertThrows(
        IllegalArgumentException.class,
        () -> lattice.gate(List.of(SourceTier.TRUSTED)),
        "a 1-tuple cannot index a 2-dimension lattice");
  }

  @Test
  void aCellWithWrongArityIsRejectedAtBuild() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            TrustLattice.builder()
                .dimensions("source", "risk")
                .cell(List.of(SourceTier.TRUSTED), GateBehavior.AUTO)
                .build(),
        "an under-specified cell would be unreachable via gate() — reject it loudly at build");
  }

  @Test
  void aThirdDimensionIsAddedWithoutForkingTheLattice() {
    // The SAME TrustLattice class, now with three dimensions — no new switch arm, no subclass. A
    // SENSITIVE corpus escalates a coordinate that the 2D core would AUTO to DENY; PUBLIC keeps it.
    TrustLattice threeAxis =
        TrustLattice.builder()
            .dimensions("source", "risk", "corpus")
            .cell(List.of(SourceTier.TRUSTED, RiskTier.LOW, Corpus.PUBLIC), GateBehavior.AUTO)
            .cell(List.of(SourceTier.TRUSTED, RiskTier.LOW, Corpus.SENSITIVE), GateBehavior.DENY)
            .defaultOnMissing(GateBehavior.DENY)
            .build();

    assertEquals(3, threeAxis.dimensionCount());
    assertEquals(
        GateBehavior.AUTO,
        threeAxis.gate(List.of(SourceTier.TRUSTED, RiskTier.LOW, Corpus.PUBLIC)));
    assertEquals(
        GateBehavior.DENY,
        threeAxis.gate(List.of(SourceTier.TRUSTED, RiskTier.LOW, Corpus.SENSITIVE)),
        "the third dimension genuinely changes the gate — it is not a cosmetic rename");
  }
}
