/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * v3: persisted status for runtime variant activation (GPU Booster Pack).
 *
 * <p>Moved from {@code io.justsearch.ui.ai.runtime} to {@code app-api} as part of tempdoc 519 §9
 * Block B2. {@link RuntimeActivationService} returns this type.
 */
public final class AiRuntimeActivationStatus {
  public String state = "idle"; // idle | running | failed | completed
  public String phase = ""; // validate | self_test | apply | rollback | done
  public String message = "";
  public String errorCode = "";

  public String variantId = "";
  public String result = ""; // passed | failed | inconclusive

  public Long vramUsedBeforeBytes = null;
  public Long vramUsedAfterBytes = null;
  public Long vramUsedDeltaBytes = null;

  public Long selfTestPort = null;

  public long startedAtEpochMs = 0;
  public long updatedAtEpochMs = 0;
}
