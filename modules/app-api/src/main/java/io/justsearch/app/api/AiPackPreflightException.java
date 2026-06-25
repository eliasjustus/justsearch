/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * v2: Structured error for AI Pack preflight.
 *
 * <p>Moved from {@code io.justsearch.ui.ai.pack} to {@code app-api} as part of tempdoc 519 §9
 * Block B2 so {@link AiPackImportService} can declare it in its contract.
 */
public final class AiPackPreflightException extends RuntimeException {
  private final ApiErrorCode errorCode;

  public AiPackPreflightException(ApiErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ApiErrorCode errorCode() {
    return errorCode;
  }
}
