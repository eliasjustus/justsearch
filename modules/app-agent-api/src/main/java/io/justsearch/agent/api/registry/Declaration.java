/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * The universal cross-cutting base of <b>every</b> declaration in the unified extension substrate
 * (tempdoc 560 §4.1 — the single-authority collapse). It lifts the two axes that <em>every</em>
 * contribution kind genuinely shares — a namespaced {@link #id()} and a {@link #presentation()} —
 * to one structural position, so the model is "shared cross-cutting axes + per-kind payloads"
 * rather than nine records that re-declare the same axes by convention.
 *
 * <p>Implemented by both families:
 *
 * <ul>
 *   <li>the sealed {@link RegistryEntry} primitives ({@link Operation} / {@link Resource} /
 *       {@link Prompt} / {@link DiagnosticChannel}), and
 *   <li>the Manifest tiers ({@link Surface} / {@link ConversationShape} / {@link IntentSource} /
 *       {@link Plugin} / {@link Workflow}).
 * </ul>
 *
 * <p>Narrower shared axes are sub-interfaces that {@code extends Declaration}: provenance via
 * {@link Provenanced}, and audience + consumers via {@link ConsumerDeclaring}. Per the AHA test the
 * tempdoc invokes, only axes that share a reason to change are lifted; per-kind payloads
 * ({@code Operation.policy}, {@code Resource.schema}, {@code IntentSource.extractorId}, …) stay
 * distinct.
 */
public interface Declaration {

  /** The declaration's stable, namespaced id. */
  RegistryRef<?> id();

  /** The declaration's presentation (label/description i18n keys + optional icon/help). */
  Presentation presentation();
}
