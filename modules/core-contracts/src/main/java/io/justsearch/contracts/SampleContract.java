/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or constructor as subject to a runtime-sampled invariant
 * (tempdoc 402 §3.3 Layer 6 tier taxonomy).
 *
 * <p>A {@code @SampleContract} is evaluated at call-site under a cost budget
 * enforced by {@link ContractSampler}; when the gate fires, the call site
 * invokes {@link ContractEmitter#emit} to attach a {@code contract.violation}
 * span event. The event is consumed by the Python projection
 * {@code scripts/jseval/jseval/projections/contract_violations.py} and
 * aggregated by {@code (contract.tempdoc, contract.tier)} for Layer 4 drift
 * detection.
 *
 * <p>Shape of an instrumented call site:
 *
 * <pre>{@code
 * private static final SampleKey ENCODER_LEASE_HELD =
 *     new SampleKey("encoder.lease.held", 1000);
 *
 * @SampleContract(
 *     description = "SPLADE session.run must hold the GPU lease",
 *     tempdoc = "400 LR2-b",
 *     every = 1000,
 *     validator = "io.justsearch.checks.LeaseHeldInvariant")
 * public void runSession(Lease lease, ...) {
 *   if (ContractSampler.shouldSample(ENCODER_LEASE_HELD) && !lease.isHeld()) {
 *     ContractEmitter.emit("@SampleContract", "400 LR2-b", "Lease not held on ORT session.run");
 *   }
 *   ...
 * }
 * }</pre>
 *
 * <p>Examples of appropriate invariants for this tier:
 *
 * <ul>
 *   <li>Per-call guards whose violation is only detectable under load
 *       (e.g. "lease held during session.run").
 *   <li>Post-condition checks whose per-call cost is unacceptable but
 *       whose sampled-cost is affordable (e.g. "retrieved doc count
 *       matches expected after fusion").
 *   <li>Invariants that depend on transient runtime state not reachable
 *       at boot time (e.g. "GPU arbiter has at least one active lease
 *       owner while inference is in flight").
 * </ul>
 *
 * <p>This tier is chosen deliberately over sibling tiers:
 *
 * <ul>
 *   <li>{@link BuildContract} is enforced at build time; appropriate when
 *       the invariant is a static structural rule detectable from bytecode.
 *   <li>{@link BootContract} fails startup on violation; appropriate when
 *       the invariant is a composition-root guarantee independent of load.
 *   <li>{@link AdvisoryContract} emits a log-only signal; appropriate when
 *       the invariant is aspirational or degradation-tolerant and a
 *       span-event-per-call is too expensive.
 * </ul>
 *
 * <p>Sample-rate-vs-cost-budget discipline: every {@code @SampleContract}
 * site must justify its {@link #every()} value against a documented cost
 * budget. A site inside a per-token loop needs a higher {@code every}
 * (lower sample rate) than a site at the per-request boundary. Tempdoc 402
 * §3.3 claims the uncontended sampler cost is ~2-5 ns; this is un-measured
 * at time of writing — microbenchmark before placing a site inside a hot
 * inner loop.
 *
 * <p>Located in the dep-free {@code modules/core-contracts} module so
 * every downstream module can annotate sites without pulling a framework
 * chain. The module adds {@code io.opentelemetry:opentelemetry-api} for
 * the emitter's event-attachment path; production emitters run on plain
 * OTel API with no SDK dependency.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface SampleContract {

  /** Short human-readable description of what the contract asserts. */
  String description();

  /**
   * Originating tempdoc reference (e.g. {@code "400 LR2-b"} or
   * {@code "397 §14.25"}). Required — callers must point at the discussion
   * that justifies the invariant. Emitted on every sampled
   * {@code contract.violation} event so the operator can navigate from
   * the aggregated projection output straight to the source design
   * rationale.
   */
  String tempdoc();

  /**
   * Sample rate: fire the invariant check once every {@code every} calls.
   * Default 1000 matches the tempdoc 402 §3.3 cost-budget sketch. A rate
   * of 1 means every call is checked (use only for cold paths); a rate of
   * 10000 means roughly one in ten thousand (use for per-token loops).
   */
  int every() default 1000;

  /**
   * Fully-qualified class name of the check that enforces this invariant
   * (e.g. {@code "io.justsearch.checks.LeaseHeldInvariant"}). Optional —
   * documentation only; unlike {@link BootContract#validator()}, there is
   * no ServiceLoader dispatch through this attribute. Provided so a reader
   * can navigate from the annotation to the check's source.
   */
  String validator() default "";
}
