/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/**
 * Operating modes for the inference runtime. Tempdoc 518 P4 — promoted from
 * {@code InferenceLifecycleManager.Mode} so the role-typed interfaces
 * ({@link OnlineAiService} / {@link OnlineAiRuntimeControl} /
 * {@link OnlineAiRuntimeIntrospection} / {@link OnlineAiLifecycleControl}) can name it without
 * importing the implementation package. The {@code app-inference} module no longer holds the
 * canonical enum; the nested {@code InferenceLifecycleManager.Mode} type was deleted as part
 * of the Slice 3 module-boundary cleanup.
 *
 * <ul>
 *   <li>{@link #ONLINE} — llama-server running; Chat / Vision / Summary / Q&amp;A available.
 *   <li>{@link #INDEXING} — FFM embedding backend active; bulk embeddings only.
 *   <li>{@link #TRANSITIONING} — switching between modes. Suppressed in typed
 *       {@code onTransition} events; only the legacy listener path observes it.
 *   <li>{@link #OFFLINE} — GPU features unavailable (crash recovery failed, insufficient VRAM,
 *       or initial state before first transition).
 * </ul>
 */
public enum Mode {
  ONLINE,
  INDEXING,
  TRANSITIONING,
  OFFLINE
}
