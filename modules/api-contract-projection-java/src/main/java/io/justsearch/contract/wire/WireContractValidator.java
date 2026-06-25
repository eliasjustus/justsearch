/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Phase 3 of slice 3a-1-8: runtime invariant validator for the wire-Category
 * contract substrate.
 *
 * Wraps protovalidate-java's validator to enforce CEL-based invariants
 * declared in `contracts/wire/*.proto` symmetrically with the FE side
 * (@bufbuild/protovalidate). Per ADR-09a §"Decision" axis 3 (runtime
 * invariants).
 */
package io.justsearch.contract.wire;

import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidationResult;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.Message;

/**
 * Singleton wrapper around protovalidate-java's validator. The validator instance is
 * thread-safe and immutable — created once, reused across all validate calls.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * HealthEventBody body = HealthEventBody.newBuilder()
 *     .setKind("condition")
 *     .setReason("worker-starting")  // BAD: hyphens not allowed
 *     .build();
 * ValidationResult result = WireContractValidator.validate(body);
 * if (!result.isSuccess()) {
 *   throw new IllegalArgumentException(result.toString());
 * }
 * }</pre>
 *
 * <p>Validation runs on every wire-payload deserialization (REST + SSE) per the
 * substrate's axis-3 commitment. In-process record construction continues to rely
 * on existing compact-constructor checks — protovalidate is the *additional*
 * symmetric-with-FE enforcement layer, not a replacement for in-process
 * invariants.
 */
public final class WireContractValidator {

  private static final Validator INSTANCE = new Validator();

  private WireContractValidator() {}

  /**
   * Validates a wire-format message against its protovalidate constraints.
   *
   * @param message any protovalidate-annotated wire message (e.g.,
   *     {@link HealthEventBody}, {@link HealthEvent}, {@link KnowledgeStatusView}).
   * @return validation result; {@link ValidationResult#isSuccess()} indicates
   *     all constraints satisfied.
   * @throws ValidationException if the validator itself fails (malformed
   *     descriptor; should not happen in production).
   */
  public static ValidationResult validate(Message message) throws ValidationException {
    return INSTANCE.validate(message);
  }

  /**
   * Convenience: throws {@link IllegalArgumentException} on validation failure.
   * Use at deserialization boundaries where validation must be enforced.
   */
  public static void validateOrThrow(Message message) throws ValidationException {
    ValidationResult result = INSTANCE.validate(message);
    if (!result.isSuccess()) {
      throw new IllegalArgumentException("Wire contract validation failed: " + result);
    }
  }

  /** Returns the shared protovalidate validator. Use only for advanced cases. */
  public static Validator instance() {
    return INSTANCE;
  }
}
