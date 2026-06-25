/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

/**
 * SPI for composition-root-time invariant checks (tempdoc 402 §3.2).
 *
 * <p>Implementations must be registered via
 * {@code META-INF/services/io.justsearch.contracts.BootContractValidator} so
 * {@link BootContractRegistry} can discover them through
 * {@link java.util.ServiceLoader}.
 *
 * <p>Implementations must be:
 *
 * <ul>
 *   <li><b>Idempotent</b> — {@link #validate()} may be re-invoked (e.g.
 *       after test reset) and must produce the same outcome for the same
 *       observable state.
 *   <li><b>Side-effect-free</b> — a validator observes state; it does not
 *       mutate, cache, or repair it. Repair belongs in the composition
 *       root, not in the invariant check.
 *   <li><b>Actionable on failure</b> — the {@link ContractViolation}
 *       message must name the invariant, the discovered state, and the
 *       tempdoc reference so the operator can diagnose without reading the
 *       validator source.
 * </ul>
 *
 * <p>Implementations must declare a public no-arg constructor so
 * {@code ServiceLoader} can instantiate them.
 */
public interface BootContractValidator {

  /**
   * Run the invariant check. Throws {@link ContractViolation} if the
   * invariant is not satisfied. Any unchecked exception propagated from
   * this method is treated by {@link BootContractRunner} as a contract
   * violation as well (wrapped into {@code ContractViolation}).
   */
  void validate() throws ContractViolation;

  /**
   * Tempdoc reference (e.g. {@code "397 §14.27 U1"}) identifying the source
   * discussion that justifies this invariant. Included in the fail-fast log
   * line so operators can navigate from a boot-time error straight to the
   * design rationale without grepping the codebase. Empty string by default
   * — implementations should override to point at their source tempdoc.
   */
  default String tempdoc() {
    return "";
  }
}
