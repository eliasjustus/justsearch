/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * The shared <b>provenance / trust</b> cross-cutting axis (tempdoc 560 §4.1), lifted to one
 * structural position above {@link Declaration}. Every declaration kind that carries a
 * declaration-side {@link Provenance} (the {@code CORE / TRUSTED_PLUGIN / UNTRUSTED_PLUGIN} origin +
 * trust tier) implements it — the sealed {@link RegistryEntry} primitives and the Manifest tiers
 * alike — so "what authority declared this, at what trust?" is one axis, not nine conventions.
 *
 * <p>{@link IntentSource} names its component {@code declarationProvenance} for historical reasons;
 * it conforms by exposing {@link #provenance()} as a delegating accessor.
 */
public interface Provenanced extends Declaration {

  /** The declaration-side provenance/trust of this declaration. */
  Provenance provenance();
}
