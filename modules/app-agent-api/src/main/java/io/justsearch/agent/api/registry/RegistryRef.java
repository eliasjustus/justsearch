/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Typed cross-reference to a registry primitive or manifest entry.
 *
 * <p>Per slice 447 §X.3.2 + 447 §X.11.5 follow-up Phase 1: where a field carries a
 * reference to a primitive entry rather than the entry's own id, the field type binds
 * to the target type via this interface's generic parameter. Reading
 * {@code Optional<OperationRef>} unambiguously says "reference to an Operation"; the
 * older {@code Optional<OperationId>} pattern was ambiguous between "id of an Operation"
 * and "reference to an Operation."
 *
 * <p>Sealed permit list covers the four registry primitives (Operation, Resource,
 * Prompt, DiagnosticChannel) plus the Manifest-tier Surface (slice 449). The bound
 * is intentionally unconstrained — Surface is not a {@link RegistryEntry} (it's a
 * Manifest tier) but participates in the same cross-reference shape, so {@code T}
 * is open rather than bounded by {@code RegistryEntry}.
 *
 * <p>Each permitted variant also implements {@link NamespacedId} — the regex +
 * bare-string-wire shape factoring is orthogonal to the typed-reference contract this
 * interface provides. {@code RegistryRef<T>} answers "what does this reference point
 * at?"; {@link NamespacedId} answers "what's the wire shape and namespace regex?".
 *
 * <p><strong>Scope (Pass 4 + Pass 5)</strong>: shape factoring + naming clarity, NOT
 * polymorphism. There are zero polymorphic call sites that take "any registry ref" —
 * every consumer knows the specific ref type. The interface exists so a reader sees
 * {@code Optional<OperationRef>} and immediately knows what's pointed at, and so
 * future framework code that genuinely needs polymorphism (e.g., a generic
 * cross-reference validator) has a single sealed type to dispatch over.
 */
public sealed interface RegistryRef<T>
    extends NamespacedId
    permits OperationRef,
        ResourceRef,
        PromptRef,
        DiagnosticChannelRef,
        SurfaceRef,
        ConversationShapeRef,
        IntentSourceRef,
        PluginRef,
        WorkflowRef {
}
