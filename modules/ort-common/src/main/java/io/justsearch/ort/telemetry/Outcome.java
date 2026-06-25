/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort.telemetry;

import java.util.Locale;

/**
 * Tag value for binary success/failure metrics in the {@code ort.session.*} catalog. Lives
 * alongside its peers ({@link FailureCause}, {@link CpuRecreateCause}, etc.) in
 * {@code modules/ort-common}'s telemetry package — relocated from {@code worker-services}
 * in tempdoc 414 v2 (B4 fix).
 */
public enum Outcome {
  SUCCESS,
  FAILURE;

  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
