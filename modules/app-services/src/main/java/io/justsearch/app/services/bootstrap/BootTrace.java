/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tempdoc 541 §4.2: immutable boot-trace snapshot for one composition-root invocation.
 *
 * <p>Composition substrate observability surface, modeled on tempdoc 529's publication shape but
 * adopting an immutable once-per-boot snapshot (not a ring buffer — per §9.1 A3 retraction).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code process} — {@code "head"} | {@code "worker"} | {@code "brain"}. The {@code
 *       ?process=} discriminator on {@code /api/boot/phases}.
 *   <li>{@code bootStartedAtMs} — timestamp of composition-root construction entry.
 *   <li>{@code bootCompletedAtMs} — timestamp of composition-root construction exit. Null while
 *       boot is in progress.
 *   <li>{@code phases} — ordered list of {@link PhaseRecord}, one per phase invocation.
 * </ul>
 *
 * <p>Construction pattern: a single {@link Builder} accumulates {@link PhaseRecord}s during
 * composition-root construction, then {@link Builder#seal} produces the immutable {@code
 * BootTrace}. The held trace is a {@code volatile} field on the composition root; reads see
 * either the pre-seal in-progress builder snapshot or the post-seal final trace.
 *
 * <p><b>Thread-safety contract</b> (single-writer, multi-reader):
 *
 * <ul>
 *   <li>The {@link Builder}'s {@code phases} list is an {@link ArrayList} and is <em>not</em>
 *       internally synchronized. It MUST be written from a single thread — by contract, the
 *       composition-root constructor's thread (Head's main thread per tempdoc 519 §31's
 *       source-encoded phase ordering). Calling {@link Builder#record} from any other thread
 *       is undefined behavior.
 *   <li>{@link Builder#seal}, {@link Builder#snapshot}, {@link Builder#phasesSoFar} are safe
 *       from any thread: they read through an {@link AtomicReference} after a happens-before
 *       sealed-state check, and either return the sealed immutable {@code BootTrace} OR a
 *       defensive {@link List#copyOf} snapshot of the in-progress phases list. The defensive
 *       copy is safe under the single-writer contract: while the writer thread may append
 *       during the copy, the underlying ArrayList's structural integrity is preserved as long
 *       as no writer is concurrently resizing — and the writer is the only thread that
 *       writes, by contract.
 *   <li>The sealed {@code BootTrace} record is fully immutable (defensive {@link List#copyOf}
 *       in the compact constructor); it may be freely shared across threads.
 * </ul>
 *
 * <p>If a future caller ever needs to write phases from multiple threads (e.g., parallel
 * phase execution), this contract must be revised — wrap the ArrayList in
 * {@link Collections#synchronizedList} or migrate to a {@code CopyOnWriteArrayList}. Today
 * the composition root is single-threaded and parallel phases are an explicit non-goal.
 *
 * <p>Endpoint shape: {@code GET /api/boot/phases?process=head} returns
 * {@code {"boot": {"process": "head", "bootStartedAtMs": ..., "bootCompletedAtMs": ...,
 * "phases": [{...PhaseRecord...}]}}} — mirrors 529's {@code /api/inference/transitions}
 * envelope.
 */
public record BootTrace(
    String process, long bootStartedAtMs, Long bootCompletedAtMs, List<PhaseRecord> phases) {

  /** Wire values for the process discriminator. */
  public static final String HEAD = "head";

  public static final String WORKER = "worker";
  public static final String BRAIN = "brain";

  /** Compact constructor — defensive copy of the phases list. */
  public BootTrace {
    if (process == null || process.isBlank()) {
      throw new IllegalArgumentException("process required");
    }
    phases = phases == null ? List.of() : List.copyOf(phases);
  }

  /** Convenience: optional bootCompletedAtMs for type-checked consumers. */
  public Optional<Long> bootCompletedAtMsOpt() {
    return Optional.ofNullable(bootCompletedAtMs);
  }

  /** Convenience: total boot duration (only if completed). */
  public Optional<Long> totalDurationMs() {
    if (bootCompletedAtMs == null) {
      return Optional.empty();
    }
    return Optional.of(bootCompletedAtMs - bootStartedAtMs);
  }

  /** Returns the named phase if present, else empty. */
  public Optional<PhaseRecord> phase(String name) {
    if (name == null) {
      return Optional.empty();
    }
    for (PhaseRecord p : phases) {
      if (name.equals(p.name())) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }

  /** Builder for accumulating phase records during composition-root construction. */
  public static final class Builder {
    private final String process;
    private final long bootStartedAtMs;
    private final List<PhaseRecord> phases = new ArrayList<>(8);
    private final AtomicReference<BootTrace> sealed = new AtomicReference<>();

    public Builder(String process) {
      this.process = process;
      this.bootStartedAtMs = System.currentTimeMillis();
    }

    /** Append a phase record. No-op once {@link #seal} has been called. */
    public Builder record(PhaseRecord record) {
      if (sealed.get() == null && record != null) {
        phases.add(record);
      }
      return this;
    }

    /** Seal the trace at composition-root completion. Idempotent; subsequent calls are no-ops. */
    public BootTrace seal() {
      BootTrace existing = sealed.get();
      if (existing != null) {
        return existing;
      }
      BootTrace next =
          new BootTrace(process, bootStartedAtMs, System.currentTimeMillis(), phases);
      if (sealed.compareAndSet(null, next)) {
        return next;
      }
      return sealed.get();
    }

    /**
     * Returns an in-progress snapshot. Mid-boot endpoint reads see this; post-boot reads see the
     * sealed result. Either way, the returned {@code BootTrace} is immutable.
     */
    public BootTrace snapshot() {
      BootTrace existing = sealed.get();
      if (existing != null) {
        return existing;
      }
      return new BootTrace(process, bootStartedAtMs, null, List.copyOf(phases));
    }

    /** Visible for status-handlers / API surface: copy the phases-so-far (defensive). */
    public List<PhaseRecord> phasesSoFar() {
      return Collections.unmodifiableList(new ArrayList<>(phases));
    }
  }
}
