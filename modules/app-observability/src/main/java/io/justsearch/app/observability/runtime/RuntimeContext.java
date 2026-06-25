/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.runtime;

import java.util.Objects;

/**
 * Wire-format snapshot of the head process's current runtime context.
 *
 * <p>Per slice 440 (Runtime mode STATE Resource): the rolled-up "what kind of system are we
 * right now" payload carried by the {@code core.runtime-context} STATE Resource. Replace-only
 * — every change broadcasts a new full snapshot; no append history.
 *
 * <p>v1 carries the two mode-shaped dimensions the head process currently has authoritative
 * sources for:
 *
 * <ul>
 *   <li>{@link #systemMode}: PRODUCTION vs EVAL — read from {@code justsearch.eval.mode}.
 *   <li>{@link #automationEnabled}: UI automation flag — read from {@code Ui.automationEnabled}.
 * </ul>
 *
 * <p>Future dimensions ({@code aiMode}, {@code installMode}, {@code policyMode}) are deferred
 * to follow-up slices per 440's "Mode taxonomy" flag — adding a field requires a clear single
 * source of truth, which the deferred dimensions don't yet have. The Resource catalog entry's
 * schema URL pins the v1 shape; expanding adds a new schema version.
 */
public record RuntimeContext(SystemMode systemMode, boolean automationEnabled) {

  public RuntimeContext {
    Objects.requireNonNull(systemMode, "systemMode");
  }
}
