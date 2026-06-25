/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fail-fast runner for {@link BootContractValidator} invariants
 * (tempdoc 402 §3.2). Invoked once per composition root from
 * {@code HeadlessApp.main} / {@code IndexerWorker.main} before any service
 * starts.
 *
 * <p>Semantics: {@link #findViolations()} runs every registered validator
 * and returns every violation found (collect-all, not stop-on-first). The
 * runner deliberately continues past a failure because each validator is
 * required to be idempotent and side-effect-free; collecting all problems
 * gives the operator one boot log with the full diagnostic set instead of
 * a fix-one-retry-discover-next cycle. "Fail-fast" in tempdoc 402 §3.2
 * means "don't start the system with unfixed invariants" — the
 * system-level fail-fast is preserved because {@link #validateAll()} exits
 * the JVM when any violation is found.
 *
 * <p>{@link #validateAll()} is the production entrypoint: it calls
 * {@code findViolations()}, logs a single INFO line with validator count +
 * violation count (so a silent no-op is impossible: operators see "0
 * validators registered" if the SPI file went missing), logs each
 * violation at ERROR, and invokes {@link System#exit(int)} with status 1
 * if any violation was found.
 */
public final class BootContractRunner {

  private static final Logger log = LoggerFactory.getLogger(BootContractRunner.class);

  private BootContractRunner() {}

  /**
   * Run every registered validator and return the collected violations.
   * Unchecked exceptions from a validator are wrapped as
   * {@link ContractViolation} with the validator FQN as context — a
   * validator that throws {@link NullPointerException} is a boot failure,
   * not a silent skip.
   *
   * <p>The returned list is empty iff every validator passed. Callers
   * decide how to report and whether to terminate.
   */
  public static List<ContractViolation> findViolations() {
    return findViolations(BootContractRegistry.validators());
  }

  /**
   * Test-only overload: run the supplied validators instead of consulting
   * {@link BootContractRegistry}. Package-private — production code must
   * use the no-arg variant.
   */
  static List<ContractViolation> findViolations(Iterable<BootContractValidator> validators) {
    List<ContractViolation> violations = new ArrayList<>();
    for (BootContractValidator v : validators) {
      try {
        v.validate();
      } catch (ContractViolation e) {
        violations.add(e);
      } catch (RuntimeException | Error t) {
        violations.add(new ContractViolation("unexpected failure in " + v.getClass().getName(), t));
      }
    }
    return violations;
  }

  /**
   * Production entrypoint. Runs every registered validator; logs the
   * validator + violation counts at INFO (so a classpath regression that
   * drops every SPI file is visible as "0 validators registered" in the
   * boot log); logs each violation at ERROR; invokes {@link System#exit}
   * with status 1 if any violation was found.
   */
  public static void validateAll() {
    List<BootContractValidator> validators = BootContractRegistry.validators();
    List<ContractViolation> violations = findViolations(validators);
    log.info(
        "BootContractRunner: {} validators registered, {} violations",
        validators.size(),
        violations.size());
    if (violations.isEmpty()) {
      return;
    }
    for (ContractViolation v : violations) {
      log.error("Boot contract violation: {}", v.getMessage(), v);
    }
    System.exit(1);
  }
}
