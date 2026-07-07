/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Exception thrown when a mode transition fails in InferenceLifecycleManager.
 *
 * <p>Each instance carries a {@link Reason} enum and (per tempdoc 518 P3) a typed {@link
 * InferenceFailure} payload accessible via {@link #failure()}. New consumers should
 * pattern-match on {@code failure()} (a sealed sum-type with four sub-records) rather than
 * switch over the flat {@link Reason}; legacy consumers continue to work unchanged.
 *
 * <p>The {@code failure()} accessor is the resolution of the audit's P3 typed-payload-at-the-
 * throw-site requirement: every {@code ModeTransitionException} now carries a typed
 * {@link InferenceFailure} alongside the legacy reason / message. The 75-LOC {@code mapFailure}
 * switch in ILM (pre-518) is gone; the mapping lives in {@link TransitionRunner} as the
 * compat bridge between legacy throw sites that still construct via {@code (Reason, message)}
 * and the typed payload that downstream consumers see.
 */
public class ModeTransitionException extends Exception {

  /** Structured reason codes for mode transition failures. */
  public enum Reason {
    INVALID_CONFIG,
    INSUFFICIENT_VRAM,
    INTERRUPTED,
    ONLINE_START_FAILED,
    INDEXING_START_FAILED,
    CONFIG_APPLY_FAILED,
    PORT_ALLOCATION_FAILED,
    CONFIG_REQUIRED,
    ALREADY_TRANSITIONING,
    EXTERNAL_SERVER_CONFLICT,
    MISSING_DLL,
    // Tempdoc 656 Task 3: distinct from MISSING_DLL (which inspects an already-launched process's
    // exit code) — this fires when the server executable itself does not exist, before a process is
    // ever started.
    EXECUTABLE_NOT_FOUND,
    PROCESS_EXITED,
    HEALTH_CHECK_TIMEOUT,
    HEALTH_CHECK_INTERRUPTED,
    EXTERNAL_SERVER_POLICY_BLOCKED
  }

  private final Reason reason;
  /**
   * Lazily-constructed typed payload. {@code null} until {@link #failure()} is first called;
   * built from {@link Reason} + {@link #getMessage()} + {@link #getCause()} on demand via
   * {@link TransitionRunner#mapExceptionToFailure}. Tempdoc 518 P3.
   */
  private volatile InferenceFailure cachedFailure;

  /**
   * Creates a new ModeTransitionException with the specified reason and message.
   *
   * @param reason the structured failure reason
   * @param message the detail message
   */
  public ModeTransitionException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Creates a new ModeTransitionException with the specified reason, message, and cause.
   *
   * @param reason the structured failure reason
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public ModeTransitionException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  /** Returns the structured failure reason. */
  public Reason reason() {
    return reason;
  }

  /**
   * Returns the typed {@link InferenceFailure} payload. Built lazily from the legacy
   * {@code (Reason, message, cause)} fields the first time this accessor is called; subsequent
   * calls return the cached value.
   *
   * <p>New consumers should pattern-match on the returned sub-record
   * ({@link InferenceFailure.StartupFailure} / {@link InferenceFailure.HealthFailure} /
   * {@link InferenceFailure.ConfigFailure} / {@link InferenceFailure.TransitionFailure}) for
   * typed access to the per-category code enums. The wireCode flows through unchanged from
   * legacy {@code mapFailure} mappings. Tempdoc 518 P3.
   */
  public InferenceFailure failure() {
    InferenceFailure local = cachedFailure;
    if (local == null) {
      local = buildFailure();
      cachedFailure = local;
    }
    return local;
  }

  /**
   * Maps the legacy {@code (Reason, message, cause)} into the appropriate {@link InferenceFailure}
   * sub-record. Tempdoc 518 P3/P4 — mapping moved here when MTE + InferenceFailure both relocated
   * to {@code app-api}, breaking the previous dependency on {@code TransitionRunner}.
   */
  private InferenceFailure buildFailure() {
    String detail = super.getMessage() != null ? super.getMessage() : "unknown";
    Throwable cause = getCause();
    return switch (reason) {
      case INVALID_CONFIG -> new InferenceFailure.ConfigFailure(ConfigCode.INVALID_CONFIG, detail);
      case CONFIG_REQUIRED ->
          new InferenceFailure.ConfigFailure(ConfigCode.CONFIG_REQUIRED, detail);
      case ALREADY_TRANSITIONING ->
          new InferenceFailure.ConfigFailure(ConfigCode.ALREADY_TRANSITIONING, detail);
      case EXTERNAL_SERVER_CONFLICT ->
          new InferenceFailure.ConfigFailure(ConfigCode.EXTERNAL_SERVER_CONFLICT, detail);
      case INSUFFICIENT_VRAM ->
          new InferenceFailure.StartupFailure(StartupCode.INSUFFICIENT_VRAM, detail, cause);
      case MISSING_DLL ->
          new InferenceFailure.StartupFailure(StartupCode.MISSING_DLL, detail, cause);
      case EXECUTABLE_NOT_FOUND ->
          new InferenceFailure.StartupFailure(StartupCode.EXECUTABLE_NOT_FOUND, detail, cause);
      case PROCESS_EXITED ->
          new InferenceFailure.StartupFailure(StartupCode.PROCESS_EXITED, detail, cause);
      case PORT_ALLOCATION_FAILED ->
          new InferenceFailure.StartupFailure(StartupCode.PORT_ALLOCATION_FAILED, detail, cause);
      case EXTERNAL_SERVER_POLICY_BLOCKED ->
          new InferenceFailure.StartupFailure(
              StartupCode.EXTERNAL_SERVER_POLICY_BLOCKED, detail, cause);
      case HEALTH_CHECK_TIMEOUT ->
          new InferenceFailure.HealthFailure(HealthCode.HEALTH_TIMEOUT, detail, cause);
      case HEALTH_CHECK_INTERRUPTED ->
          new InferenceFailure.HealthFailure(HealthCode.HEALTH_INTERRUPTED, detail, cause);
      case ONLINE_START_FAILED ->
          new InferenceFailure.TransitionFailure(TransitionCode.ONLINE_START_FAILED, detail, cause);
      case INDEXING_START_FAILED ->
          new InferenceFailure.TransitionFailure(
              TransitionCode.INDEXING_START_FAILED, detail, cause);
      case CONFIG_APPLY_FAILED ->
          new InferenceFailure.TransitionFailure(TransitionCode.CONFIG_APPLY_FAILED, detail, cause);
      case INTERRUPTED ->
          new InferenceFailure.TransitionFailure(TransitionCode.INTERRUPTED, detail, cause);
    };
  }

  /**
   * Returns the detail message prefixed with the structured reason code.
   *
   * <p>Format: {@code [REASON_NAME] original message}. This ensures callers that log or re-throw
   * using {@code getMessage()} automatically include the machine-readable reason without needing to
   * inspect {@link #reason()} directly.
   */
  @Override
  public String getMessage() {
    return "[" + reason.name() + "] " + super.getMessage();
  }
}
