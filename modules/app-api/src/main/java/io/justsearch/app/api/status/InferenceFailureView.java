/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.status;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Status-record projection of {@code InferenceFailure}. Carries the canonical wire-form code
 * + detail for surfacing the most recent failure on {@code /api/status}.
 *
 * <p>Tempdoc 412 Phase 3: replaces the prior free-form {@code LlmStatusView.error} string with
 * a typed (code + detail) shape. The {@code code} field is the canonical snake_case wire form
 * from one of the four failure-category code enums (StartupCode / HealthCode / ConfigCode /
 * TransitionCode); operators can map it back to a category by inspecting which metric carries
 * it.
 *
 * <p>Stability: stable (API contract)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InferenceFailureView(String code, String detail) {

  public InferenceFailureView {
    code = code == null ? "" : code;
    detail = detail == null ? "" : detail;
  }
}
