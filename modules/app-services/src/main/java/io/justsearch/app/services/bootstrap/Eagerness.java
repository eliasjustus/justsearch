/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

/**
 * Tempdoc 541 §5.2 — first-class typed dimension on Phase invocations.
 *
 * <p>An {@code EAGER} phase runs synchronously at composition-root construction; its Output is
 * materialized before the constructor exits. A {@code LAZY} phase returns a {@link
 * java.util.function.Supplier} (or {@link Memoized}) whose body executes on first {@code
 * get()} and memoizes thereafter.
 *
 * <p>Discipline coupling per tempdoc 541 §4.3 (rule 4e, deferred until this enum ships): a
 * phase declared {@code LAZY} must have an Output type of form {@code Supplier<T>} or
 * {@code Memoized<T>}, and vice versa. The rule is enforceable once §5.2 sites exist.
 *
 * <p>Substrate motivation (per tempdoc 539 cold-start profile): some boot-time work has
 * implicit availability/config gates (LambdaMART training, agent-tool wiring, GPL coordinator,
 * VDU offline coordinator). Reifying those implicit gates as explicit {@code Eagerness.LAZY}
 * declarations with named triggers makes the cold-start cost auditable.
 */
public enum Eagerness {
  /** Phase runs synchronously at composition-root construction. */
  EAGER,
  /** Phase body deferred until first read of its {@link Memoized} / {@link
   * java.util.function.Supplier} output. */
  LAZY;
}
