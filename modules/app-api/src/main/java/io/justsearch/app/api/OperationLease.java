/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.time.Instant;
import java.util.Map;

/**
 * Persisted shape of an operation lease entry in {@code tmp/dev-runner/op-leases.json}.
 * Tempdoc 542 Layer 2.
 *
 * <p>Single document, single writer (Head's {@link OperationLeaseService}). Read by the
 * dev-runner admission gate to decide takeover policy.
 *
 * @param opId               opaque, unique per op invocation
 * @param opClass            stable string identifying the op type (e.g. {@code "indexing.migration"})
 * @param criticality        admission-policy class
 * @param startedAt          when {@code register} was called
 * @param expectedDurationSec optimistic upper bound; informs expiry safety
 * @param expiresAt          hard ceiling; lease is auto-removed when current time exceeds this
 * @param heartbeatAt        last {@code renew} call; null if never renewed since register
 * @param originProcess      "head" | "worker" — for the Amendment-B audit trail
 * @param holder             session identity + source (mirrors active.json.holder)
 * @param metadata           op-class-specific JSON payload (e.g. migration source/target generation)
 */
public record OperationLease(
    String opId,
    String opClass,
    OpCriticality criticality,
    Instant startedAt,
    long expectedDurationSec,
    Instant expiresAt,
    Instant heartbeatAt,
    String originProcess,
    Map<String, Object> holder,
    Map<String, Object> metadata) {}
