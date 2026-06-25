/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Wire type of an {@link EventField} in a {@link ConversationShape} event payload.
 *
 * <p>Part of tempdoc 564 (facet 4b): the typed event-schema model that lets FE handler payloads
 * be generated from the backend declaration rather than hand-cast off {@code unknown}. Each value
 * maps to a TypeScript type in {@code gen-shape-handlers.mjs}:
 *
 * <ul>
 *   <li>{@link #STRING} → {@code string}
 *   <li>{@link #NUMBER} → {@code number}
 *   <li>{@link #BOOLEAN} → {@code boolean}
 *   <li>{@link #ENUM} → a union of string literals ({@link EventField#enumValues()})
 *   <li>{@link #OBJECT} → the named shared interface ({@link EventField#objectType()})
 *   <li>{@link #ARRAY} → {@code T[]} where {@code T} is {@link EventField#elementType()}
 * </ul>
 */
public enum EventFieldType {
  STRING,
  NUMBER,
  BOOLEAN,
  ENUM,
  OBJECT,
  ARRAY
}
