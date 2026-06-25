/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tempdoc 560 WS6 (§4.3) — a genuinely <b>N-dimensional</b> trust lattice.
 *
 * <p>§4.3 claimed the trust lattice was dimension-extensible; a measurement (tempdoc 560 confidence
 * pass) found the only implementation, {@link io.justsearch.agent.api.registry.TrustEvaluator}'s core,
 * to be a hardcoded {@code SourceTier × RiskTier} 3×3 {@code switch} — extensible only by editing the
 * switch (a fork), not by adding a dimension. This corrects that: a lattice is a declared ordered list
 * of <em>dimensions</em> plus a sparse map from a <em>coordinate</em> (one value per dimension) to a
 * {@link GateBehavior}, with a <b>most-restrictive default</b> for any coordinate not explicitly
 * declared. A third (or fourth) dimension is a longer coordinate through the <em>same</em> {@link #gate}
 * lookup — not a new {@code switch} arm, not a forked evaluator.
 *
 * <p><b>Fail-safe default.</b> An undeclared coordinate resolves to {@link Builder#defaultOnMissing}
 * (defaulting to {@link GateBehavior#DENY}, the most restrictive cell). Adding a dimension therefore
 * cannot silently <em>weaken</em> a gate: the combinations you don't enumerate deny by default until
 * you declare them. (The 2D {@link io.justsearch.app.services.intent.CoreTrustEvaluator} enumerates all
 * nine {@code source × risk} cells, so its default never fires — behavior is preserved exactly.)
 *
 * <p>Coordinates are {@code List<Object>} of dimension values (typically enum constants); equality is
 * value equality on the list, so enum identity keys the cells. The lattice is immutable after
 * {@link Builder#build}.
 */
public final class TrustLattice {

  private final List<String> dimensions;
  private final Map<List<Object>, GateBehavior> cells;
  private final GateBehavior defaultOnMissing;

  private TrustLattice(
      List<String> dimensions, Map<List<Object>, GateBehavior> cells, GateBehavior defaultOnMissing) {
    this.dimensions = dimensions;
    this.cells = cells;
    this.defaultOnMissing = defaultOnMissing;
  }

  /** The declared dimension names, in coordinate order. */
  public List<String> dimensions() {
    return dimensions;
  }

  /** How many dimensions a coordinate must supply. */
  public int dimensionCount() {
    return dimensions.size();
  }

  /** The fail-safe behavior for a coordinate not explicitly declared. */
  public GateBehavior defaultOnMissing() {
    return defaultOnMissing;
  }

  /**
   * Resolve the {@link GateBehavior} for a coordinate (one value per dimension, in declared order).
   * An undeclared coordinate returns {@link #defaultOnMissing()} — adding a dimension cannot silently
   * weaken any gate.
   *
   * @throws IllegalArgumentException if the coordinate's arity differs from {@link #dimensionCount()}.
   */
  public GateBehavior gate(List<Object> coordinate) {
    Objects.requireNonNull(coordinate, "coordinate");
    if (coordinate.size() != dimensions.size()) {
      throw new IllegalArgumentException(
          "coordinate arity "
              + coordinate.size()
              + " != lattice dimension count "
              + dimensions.size()
              + " "
              + dimensions);
    }
    return cells.getOrDefault(List.copyOf(coordinate), defaultOnMissing);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for an N-dimensional lattice. */
  public static final class Builder {
    private List<String> dimensions = List.of();
    private final Map<List<Object>, GateBehavior> cells = new LinkedHashMap<>();
    private GateBehavior defaultOnMissing = GateBehavior.DENY;

    /** Declare the ordered dimensions (any arity — this is what makes the lattice extensible). */
    public Builder dimensions(String... names) {
      this.dimensions = List.of(names);
      return this;
    }

    /** Declare one cell: a coordinate (one value per dimension, in declared order) → behavior. */
    public Builder cell(List<Object> coordinate, GateBehavior behavior) {
      Objects.requireNonNull(coordinate, "coordinate");
      Objects.requireNonNull(behavior, "behavior");
      cells.put(List.copyOf(coordinate), behavior);
      return this;
    }

    /** The behavior for undeclared coordinates (default {@link GateBehavior#DENY} — most restrictive). */
    public Builder defaultOnMissing(GateBehavior behavior) {
      this.defaultOnMissing = Objects.requireNonNull(behavior, "behavior");
      return this;
    }

    public TrustLattice build() {
      List<String> dims = List.copyOf(dimensions);
      // Every declared cell must match the dimension arity, so a coordinate can never silently
      // under/over-specify (which would make it unreachable via gate()).
      List<List<Object>> bad = new ArrayList<>();
      for (List<Object> coord : cells.keySet()) {
        if (coord.size() != dims.size()) {
          bad.add(coord);
        }
      }
      if (!bad.isEmpty()) {
        throw new IllegalArgumentException(
            "lattice cells with arity != " + dims.size() + " " + dims + ": " + bad);
      }
      return new TrustLattice(dims, Map.copyOf(cells), defaultOnMissing);
    }
  }
}
