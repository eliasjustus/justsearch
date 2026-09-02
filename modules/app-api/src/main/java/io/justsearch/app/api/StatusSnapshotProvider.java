/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * SPI for snapshotting head-side status into a Jackson-serializable form.
 *
 * <p>Tempdoc 519 §9 Step 3 (Diagnostics extraction): {@code DiagnosticsServiceImpl} (in
 * app-services) takes this interface so it can capture status into the diagnostics zip
 * without depending on the ui-side {@code StatusLifecycleHandler}.
 *
 * <p>Production impl: {@code io.justsearch.ui.api.StatusLifecycleHandler.buildStatusSnapshot()},
 * which serves the internal health sampler's last observation (tempdoc 885 item 6) rather than
 * calling the Worker on the caller's thread.
 *
 * <p>Stability: stable (API contract).
 */
public interface StatusSnapshotProvider {
  /** Build a Jackson-serializable status snapshot (typically a record or map). */
  Object buildStatusSnapshot();
}
