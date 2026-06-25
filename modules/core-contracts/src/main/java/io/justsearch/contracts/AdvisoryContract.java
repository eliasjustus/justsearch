/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or method as subject to an advisory invariant
 * (tempdoc 400 §8.6 Layer 6 tier taxonomy).
 *
 * <p>An {@code @AdvisoryContract} is NOT enforced at build or boot; instead,
 * the invariant emits a log-only signal when it would be violated, and that
 * signal feeds drift detection (Layer 4 projection LR6-c, or
 * {@code TelemetryHealthState}-style counters). This tier is appropriate when:
 *
 * <ul>
 *   <li>The invariant is aspirational or under transition (e.g. migrating
 *       a legacy pattern; a strict check would block work).
 *   <li>Violation is tolerable but should be trended (e.g. stale-cache
 *       fallbacks; retry counts above a soft limit).
 *   <li>The detection path is too expensive for per-call enforcement but
 *       cheap to log.
 * </ul>
 *
 * <p>Advisory contracts have an explicit decay failure mode: the signal
 * must be consumed by someone (a dashboard, an LR4 projection, an alert).
 * An advisory contract whose signal nobody reads is noise — delete the
 * annotation or promote it to {@link BuildContract} when the invariant is
 * ready to be enforced.
 *
 * <p>Rationale for being in Phase 1 alongside {@link BuildContract} but
 * excluding {@code @BootContract} + {@code @SampleContract} (deferred): both
 * annotations are inert metadata with no runtime machinery required.
 *
 * <p>Located in the dep-free {@code modules/core-contracts} module since
 * tempdoc 400 §22 Issue A: see {@link BuildContract} rationale.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface AdvisoryContract {

  /** Short human-readable description of what the invariant asserts. */
  String description();

  /**
   * Originating tempdoc reference (e.g. {@code "397 §14.28"}).
   */
  String tempdoc() default "";

  /**
   * Stable signal identifier used by downstream projections / dashboards
   * (e.g. {@code "worker.readiness.stale"}). Optional but recommended:
   * without a named signal, a reader has to guess which metric / log
   * pattern corresponds to the advisory.
   */
  String signal() default "";
}
