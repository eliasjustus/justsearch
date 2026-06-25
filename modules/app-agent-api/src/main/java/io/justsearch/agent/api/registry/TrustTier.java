/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Trust tier of an Operation's contributor. Drives the executor's three-branch dispatch.
 *
 * <p>Per tempdoc 429 §A.5 + §B.D: V1's trust model is "you wrote it, or you know who did."
 * TRUSTED_PLUGIN behaves equivalently to CORE in V1 because V1's audience (maintainer +
 * power users) has no plugin sandbox; V1.5+ extends the TRUSTED_PLUGIN branch additively
 * with policy floors.
 */
public enum TrustTier {
  /** Operation defined by core JustSearch code. Fully trusted. */
  CORE,
  /** Operation contributed by a maintainer-installed plugin. V1: same as CORE. V1.5+: policy floor applies. */
  TRUSTED_PLUGIN,
  /** Operation contributed by an unsigned/untrusted plugin. V1: throws (sandbox not implemented). V1.5+: requires iframe sandbox. */
  UNTRUSTED_PLUGIN
}
