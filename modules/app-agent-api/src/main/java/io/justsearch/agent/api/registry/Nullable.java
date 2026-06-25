/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Wire-precision marker: a component of a {@link PreciseWire} record that is <em>present</em> on the
 * wire but whose value may be {@code null} (so the generated schema makes it {@code required} +
 * nullable).
 *
 * <p>Distinguishes the "present-as-null" reference case — e.g. {@link Provenance#identity()} (V1.5.1
 * plugins arrive with {@code identity == null}) and {@link PluginIdentity#signature()} — from the
 * other two cases the precision rule already handles structurally: {@code Optional<>} (also
 * present-as-null, detected from the declared generic type) and {@code @JsonInclude(NON_NULL)}
 * (omitted-when-absent → optional). Runtime-retained so {@code WireSchemaConfig} (victools) reads it
 * via the field/accessor at schema-gen time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.FIELD,
  ElementType.METHOD,
  ElementType.PARAMETER,
  ElementType.RECORD_COMPONENT
})
public @interface Nullable {}
