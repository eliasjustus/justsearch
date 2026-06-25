/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Opt-in marker: a wire-projection record whose generated JSON Schema is <em>precise</em> — its
 * non-{@code Optional}, non-{@link Nullable} components are emitted as {@code required} + non-null,
 * rather than the permissive all-optional/all-nullable default ({@link
 * io.justsearch.app.api.schema.WireSchemaConfig} closes the 564 §7.2 precision gap only for the
 * types that carry this marker).
 *
 * <p>Tempdoc 560 §4c: precision is scoped opt-in (not global) so the ~12 non-registry wire surfaces
 * keep their committed baselines byte-identical — a global flip would turn every surface's
 * fail-open parse boundary into a hard reject in one commit, and some surfaces legitimately omit /
 * null-out non-{@code Optional} fields. The registry wire views ({@link UIResourceView}, {@link
 * UIOperationView} + its nested views, {@link ConsumerView}) and the registry value types they
 * project ({@link Presentation}, {@link Provenance}, {@link HistoryPolicy}, {@link Privacy}, {@link
 * EmissionPolicy}, {@link PluginIdentity}) opt in.
 *
 * <p>The precision rule {@code WireSchemaConfig} applies to a {@code PreciseWire} type's component:
 *
 * <ul>
 *   <li><b>required</b> iff the component is NOT omitted-when-null (i.e. carries no field/class
 *       {@code @JsonInclude(NON_NULL)}). A plain reference or an {@code Optional<>} with no
 *       {@code NON_NULL} is serialized present (an empty {@code Optional} becomes {@code null}, not
 *       absent), so it is wire-required.
 *   <li><b>nullable</b> iff the component is an {@code Optional<>} or carries {@link Nullable}
 *       (present-as-null). A non-{@code Optional}, non-{@link Nullable} reference is asserted
 *       non-null; primitives are never null.
 * </ul>
 */
public interface PreciseWire {}
