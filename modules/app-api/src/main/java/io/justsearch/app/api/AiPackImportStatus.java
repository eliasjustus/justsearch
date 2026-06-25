/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * v2: persisted/import status for offline AI Pack imports.
 *
 * <p>Moved from {@code io.justsearch.ui.ai.pack} to {@code app-api} as part of tempdoc 519 §9
 * Block B2. {@link AiPackImportService} returns this type.
 */
public final class AiPackImportStatus {
  public String state = "idle"; // idle | running | failed | completed
  public String phase = ""; // preflight | validate | stage | install | apply_settings | done
  public String message = "";
  public String errorCode = "";

  public String packId = "";
  public String packVersion = "";
  public String manifestSha256 = "";

  public long bytesTotal = 0;
  public long bytesDone = 0;

  public long startedAtEpochMs = 0;
  public long updatedAtEpochMs = 0;
}
