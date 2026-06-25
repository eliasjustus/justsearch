/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Read-side enterprise policy surface: computes the {@link EffectivePolicy} snapshot from the
 * machine + user policy file pair plus any restrictive runtime overrides.
 *
 * <p>This interface exists so consumers (services in {@code app-services}, controllers in
 * {@code ui}, the launcher) can depend on the read-side contract without depending on the
 * implementation's home module. The implementation lives in {@code ui} for historical reasons
 * (the policy file paths are resolved against UI-managed roots) and will move to {@code app-services}
 * when tempdoc 519 §9 Block B3 lands.
 *
 * <p>Stability: stable (API contract).
 *
 * <p>Tempdoc 519 §9 / §18 Block B1.
 */
public interface EnterprisePolicyService {

  /**
   * Compute and return the current effective policy. Reads the machine and user policy files,
   * applies defaults and restrictive-only merge rules, and returns the snapshot.
   *
   * <p>The implementation may have side effects (e.g., propagating policy flags to system
   * properties for runtime enforcement points). Callers should treat the returned snapshot as
   * immutable.
   *
   * @return the current effective policy; never null
   */
  EffectivePolicy snapshot();
}
