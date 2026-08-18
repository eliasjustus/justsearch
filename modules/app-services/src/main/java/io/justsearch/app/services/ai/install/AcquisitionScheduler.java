/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner of the ordered SET of fetches an install run has to get through.
 *
 * <p>Before this, the set had no owner: {@code AiInstallService.runInstallInternal} held a raw
 * {@code for} loop over {@code plan.downloads()} with the accumulators as loop locals, so ordering,
 * per-item retry accounting, and any cross-run resumption of the PLAN (as opposed to of one file)
 * could not be expressed without rewriting the loop. This class decides what runs next, tracks per
 * item state, and aggregates progress. It deliberately does two things it did not do as a loop
 * body: it holds the accumulators as fields rather than locals, and it reports every transition
 * through {@link Listener} instead of writing status inline.
 *
 * <p><b>It knows nothing about {@code AiInstallStatus}.</b> That is the wire DTO and belongs to the
 * service; the service projects these events onto it. <b>And it performs no IO.</b> Transfers go
 * through {@link Fetcher}, promotion to the final path through {@link Placer}, and cross-run
 * transport history through {@link AttemptLedger} — the same seam pattern {@link
 * ResumableFetch.Transfer} and {@code DownloadExecutor.BitsControl} already use in this package, and
 * for the same reason: the interesting logic is exercisable with no network, no filesystem and no
 * Windows.
 *
 * <p><b>Sequential by construction.</b> Exactly one item runs at a time, which is what the current
 * behaviour is. What this class adds is the place a later phase can change that: the accumulators
 * ({@code overallBytes}, {@code packageBytes}) are the scheduler's own state rather than locals
 * captured per iteration, and every seam is item-scoped, so concurrency becomes a change to {@link
 * #run()} instead of a rewrite of everything it touches. Nothing here enables it.
 */
public final class AcquisitionScheduler {

  private static final Logger log = LoggerFactory.getLogger(AcquisitionScheduler.class);

  /**
   * One unit of acquisition work.
   *
   * @param id stable identity of the item across runs — the plan's {@code targetPath}, which is
   *     also the key {@link AttemptLedger} records history under
   * @param packageId the registry package this item belongs to; several items may share one, which
   *     is what makes the per-package byte accounting non-trivial
   * @param sizeBytes the item's full size, credited to the totals only once it is actually placed
   */
  public record Item(String id, String packageId, long sizeBytes) {}

  /**
   * Where one item stands. {@code PENDING} also covers "never reached" — an item after the point a
   * run was cancelled was never started, and saying so is more truthful than inventing a verdict.
   */
  public enum ItemState {
    PENDING,
    RUNNING,
    INSTALLED,
    FAILED,
    CANCELLED
  }

  /** Transfers one item into its staging path. In production, a call to {@link ResumableFetch}. */
  @FunctionalInterface
  public interface Fetcher {
    /**
     * Transfers one item into staging and reports how it went.
     *
     * @param startTier the transport tier this item's history says the run should begin at
     * @param progress fires with (bytesForThisItem, totalForThisItem) as the transfer advances
     */
    ResumableFetch.Outcome fetch(
        Item item, int startTier, DownloadExecutor.ProgressCallback progress);
  }

  /**
   * Promotes one successfully fetched item from staging into its final home.
   *
   * <p>Part of the item's lifecycle rather than a later stage because the byte accounting is gated
   * on it: an item that downloaded but could not be moved has not been acquired, and crediting its
   * bytes would make the aggregate claim progress the disk does not have.
   */
  @FunctionalInterface
  public interface Placer {
    /** Returns the user-facing failure message, or {@code null} when the item was placed. */
    String place(Item item);
  }

  /**
   * Per-item transport history that outlives one run — the escalation input and the bookkeeping
   * sink. Backed in production by {@link InstallAttemptMemory}, which persists to disk; declared as
   * an interface here so the scheduler's handoff is testable without it.
   */
  public interface AttemptLedger {
    /** The transport tier this run should start {@code itemId} at, given earlier runs. */
    int startTierFor(String itemId);

    /** Whether automatic repair has provably stopped working for {@code itemId}. */
    boolean isTerminal(String itemId);

    /**
     * {@code itemId}'s LIFETIME transport-attempt count across every run, not this run's — the
     * number a terminal verdict quotes back to the user as evidence.
     */
    int attemptCount(String itemId);

    /** Records that this run failed {@code itemId} at transport. */
    void recordTransportFailure(String itemId, ResumableFetch.Outcome outcome, int startTier);

    /** Records that {@code itemId} transferred, spending its failure history. */
    void recordSuccess(String itemId);

    /** A ledger with no memory: tier 0 every run, never terminal, records nothing. */
    static AttemptLedger none() {
      return new AttemptLedger() {
        @Override
        public int startTierFor(String itemId) {
          return 0;
        }

        @Override
        public boolean isTerminal(String itemId) {
          return false;
        }

        @Override
        public int attemptCount(String itemId) {
          return 0;
        }

        @Override
        public void recordTransportFailure(
            String itemId, ResumableFetch.Outcome outcome, int startTier) {}

        @Override
        public void recordSuccess(String itemId) {}
      };
    }
  }

  /**
   * Everything an observer needs to project the run onto a surface, with no surface type mentioned.
   *
   * <p>Most events are fired by the scheduler as it drives the item lifecycle. {@link
   * #onItemVerifying} and {@link #onAttempt} are fired from INSIDE a fetch instead — they belong to
   * the transport's own phases, which only the {@link Fetcher} can see — and are declared here so
   * one implementation covers the whole acquisition projection.
   */
  public interface Listener {
    /** The item is about to be fetched. */
    default void onItemStarted(Item item) {}

    /** The transport is verifying the bytes it has. */
    default void onItemVerifying(Item item) {}

    /** A transport attempt is starting; {@code attempt} is 1-based. */
    default void onAttempt(Item item, int attempt, int maxAttempts) {}

    /**
     * The set advanced: cumulative byte counts, plus the rate/ETA they support.
     *
     * @param overallBytes bytes acquired across the whole set, including this item's in-flight bytes
     * @param packageBytes bytes acquired for this item's package, ditto
     */
    default void onProgress(
        Item item, long overallBytes, long packageBytes, AcquisitionRate.Estimate estimate) {}

    /** This item continued an earlier run's bytes instead of restarting from zero. */
    default void onItemResumed(Item item) {}

    /** This item will not converge on its own; {@code attemptCount} is its lifetime total. */
    default void onItemTerminal(Item item, int attemptCount) {}

    /** This item failed — at transport, at verification, or at placement. */
    default void onItemFailed(Item item, String message) {}

    /** This item is on disk at its final path. */
    default void onItemInstalled(Item item) {}
  }

  /**
   * Whether the run may proceed to its next item, blocking while it may not.
   *
   * <p>The seam a pause hangs on (tempdoc 840 Phase 3). Consulted between items only — see {@link
   * AcquisitionPause}, the production implementation, for why halting mid-transfer is deliberately
   * not offered. {@link #open()} is the "nothing can pause this run" default the scheduler falls
   * back to, so every existing construction behaves exactly as it did.
   */
  @FunctionalInterface
  public interface PauseGate {
    /**
     * Blocks while the run is halted.
     *
     * @return true to run the next item; false when the run must stop instead of continuing, which
     *     the scheduler treats as cancellation — the only reason a halted run stops waiting
     */
    boolean awaitRunnable();

    /** A gate that never halts anything. */
    static PauseGate open() {
      return () -> true;
    }
  }

  /**
   * How the run ended.
   *
   * @param cancelled true when the run stopped early because cancellation was requested; the
   *     remaining items were never started
   * @param acquiredBytes bytes credited to fully placed items
   */
  public record Summary(boolean cancelled, int installed, int failed, long acquiredBytes) {}

  private final List<Item> items;
  private final Fetcher fetcher;
  private final Placer placer;
  private final AttemptLedger ledger;
  private final Listener listener;
  private final BooleanSupplier cancelRequested;
  private final PauseGate pauseGate;
  private final AcquisitionRate rate;
  private final long totalBytes;

  private final Map<String, ItemState> states = new LinkedHashMap<>();
  private final Map<String, Long> packageBytes = new LinkedHashMap<>();
  private long overallBytes;

  public AcquisitionScheduler(
      List<Item> items,
      Fetcher fetcher,
      Placer placer,
      AttemptLedger ledger,
      Listener listener,
      BooleanSupplier cancelRequested,
      LongSupplier nanoClock) {
    this(items, fetcher, placer, ledger, listener, cancelRequested, nanoClock, PauseGate.open());
  }

  public AcquisitionScheduler(
      List<Item> items,
      Fetcher fetcher,
      Placer placer,
      AttemptLedger ledger,
      Listener listener,
      BooleanSupplier cancelRequested,
      LongSupplier nanoClock,
      PauseGate pauseGate) {
    this.pauseGate = pauseGate == null ? PauseGate.open() : pauseGate;
    this.items = items == null ? List.of() : List.copyOf(items);
    this.fetcher = fetcher;
    this.placer = placer == null ? item -> null : placer;
    this.ledger = ledger == null ? AttemptLedger.none() : ledger;
    this.listener = listener == null ? new Listener() {} : listener;
    this.cancelRequested = cancelRequested == null ? () -> false : cancelRequested;
    this.rate = AcquisitionRate.withDefaults(nanoClock);
    long sum = 0L;
    for (Item item : this.items) {
      states.put(item.id(), ItemState.PENDING);
      sum += Math.max(0L, item.sizeBytes());
    }
    this.totalBytes = sum;
  }

  /**
   * The live rate/ETA estimate for this scheduler's own slice. Reaches the wire as {@code
   * AiInstallStatus.bytesPerSecond} / {@code remainingSeconds}, re-horizoned onto the whole run by
   * the projection in {@code AiInstallService} (tempdoc 840 Phase 4).
   */
  public AcquisitionRate.Estimate estimate() {
    return rate.estimate(totalBytes);
  }

  /** Where every item stands, in the order the set declared them. */
  public Map<String, ItemState> states() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(states));
  }

  /** Bytes credited per package — cumulative across a package's several files. */
  public Map<String, Long> packageBytes() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(packageBytes));
  }

  /**
   * Runs the whole set, one item at a time, and returns how it ended.
   *
   * <p>Cancellation is checked at the top of each iteration and again after a failed fetch, because
   * cancel only raises a flag and never interrupts this thread. A single item's failure is isolated:
   * the run continues to the next item, exactly as the loop this replaces did — the terminal verdict
   * over the set belongs to the caller, not here.
   *
   * <p>The {@link PauseGate} is consulted in the same place and for a related reason: a halt has to
   * land where the set has no in-flight decision to preserve, which is between items. A gate that
   * refuses to become runnable is a cancelled run, not a stuck one.
   */
  public Summary run() {
    int installed = 0;
    int failed = 0;
    for (Item item : items) {
      if (cancelRequested.getAsBoolean()) {
        return new Summary(true, installed, failed, overallBytes);
      }
      if (!pauseGate.awaitRunnable()) {
        return new Summary(true, installed, failed, overallBytes);
      }
      if (cancelRequested.getAsBoolean()) {
        return new Summary(true, installed, failed, overallBytes);
      }
      states.put(item.id(), ItemState.RUNNING);
      listener.onItemStarted(item);

      // Captured before the fetch so the in-flight progress of THIS item is added to what earlier
      // items already banked, per set and per package. A multi-file package whose second file
      // reported only its own bytes is how the package counter used to go backwards.
      final long overallBase = overallBytes;
      final long packageBase = packageBytes.getOrDefault(item.packageId(), 0L);
      final int startTier = ledger.startTierFor(item.id());

      ResumableFetch.Outcome outcome =
          fetcher.fetch(
              item,
              startTier,
              (bytes, total) -> {
                long overall = overallBase + bytes;
                rate.sample(overall);
                listener.onProgress(item, overall, packageBase + bytes, rate.estimate(totalBytes));
              });
      if (outcome == null) {
        outcome =
            new ResumableFetch.Outcome(
                false, false, "Fetch produced no outcome for " + item.id(), null, 0, null);
      }

      if (outcome.firstAction() == DownloadResume.Action.RESUME_RANGE
          || outcome.firstAction() == DownloadResume.Action.RESUME_BITS) {
        listener.onItemResumed(item);
      }

      if (!outcome.ok()) {
        if (outcome.cancelled() || cancelRequested.getAsBoolean()) {
          states.put(item.id(), ItemState.CANCELLED);
          return new Summary(true, installed, failed, overallBytes);
        }
        if (InstallAttemptMemory.isTransportFailure(outcome)) {
          ledger.recordTransportFailure(item.id(), outcome, startTier);
          if (ledger.isTerminal(item.id())) {
            listener.onItemTerminal(item, ledger.attemptCount(item.id()));
          }
        }
        states.put(item.id(), ItemState.FAILED);
        failed++;
        listener.onItemFailed(item, outcome.error());
        continue;
      }
      ledger.recordSuccess(item.id());

      String placementError = placer.place(item);
      if (placementError != null) {
        states.put(item.id(), ItemState.FAILED);
        failed++;
        listener.onItemFailed(item, placementError);
        continue;
      }

      overallBytes += Math.max(0L, item.sizeBytes());
      packageBytes.merge(item.packageId(), Math.max(0L, item.sizeBytes()), Long::sum);
      states.put(item.id(), ItemState.INSTALLED);
      installed++;
      listener.onItemInstalled(item);
    }
    log.debug("Acquisition finished: {} installed, {} failed, {} bytes", installed, failed, overallBytes);
    return new Summary(false, installed, failed, overallBytes);
  }

  /** The set this scheduler was given, in run order. */
  public List<Item> items() {
    return new ArrayList<>(items);
  }
}
