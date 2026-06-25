/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Exception thrown during AI install operations, carrying HTTP status and typed error code.
 *
 * <p>Moved from {@code io.justsearch.ui.ai.install} to {@code app-api} as part of tempdoc 519 §9
 * Block B2 so the {@link AiInstallService} interface can declare it in its contract from a
 * cross-module-reachable location.
 */
public class AiInstallException extends RuntimeException {
  private final int httpStatus;
  private final ApiErrorCode errorCode;

  public AiInstallException(int httpStatus, ApiErrorCode errorCode, String message) {
    super(message);
    this.httpStatus = httpStatus;
    this.errorCode = errorCode == null ? ApiErrorCode.AI_INSTALL_ERROR : errorCode;
  }

  public int httpStatus() {
    return httpStatus;
  }

  public ApiErrorCode errorCode() {
    return errorCode;
  }
}
