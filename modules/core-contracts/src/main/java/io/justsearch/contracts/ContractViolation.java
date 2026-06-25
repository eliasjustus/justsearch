/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

/**
 * Thrown by a {@link BootContractValidator} when a composition-root invariant
 * fails at startup (tempdoc 402 §3.2). Checked so validator implementations
 * must declare it on their signature; caught by {@link BootContractRunner},
 * logged at {@code ERROR}, and followed by a fail-fast exit.
 */
public class ContractViolation extends Exception {

  private static final long serialVersionUID = 1L;

  public ContractViolation(String message) {
    super(message);
  }

  public ContractViolation(String message, Throwable cause) {
    super(message, cause);
  }
}
