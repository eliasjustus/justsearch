/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.ipc.IndexingJobsDelta;
import io.justsearch.ipc.IndexingJobsFrame;
import io.justsearch.ipc.SubscribeIndexingJobsRequest;
import io.justsearch.ipc.IngestServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slice 445 head-side bridge for the {@code core.indexing-jobs} TABULAR
 * Resource. Owns the long-lived {@code SubscribeIndexingJobs} streaming RPC,
 * translates incoming proto frames into typed {@link Delta} events, and
 * fans them out to subscribed listeners.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #start} — opens the gRPC stream. Returns a future that completes
 *       once the initial snapshot frame has been delivered to listeners.</li>
 *   <li>{@link #subscribe} — registers a listener. New listeners are NOT
 *       replayed the snapshot — they begin receiving deltas from "now". For
 *       initial-state hydration, listeners use {@link #latestSnapshot}.</li>
 *   <li>{@link #stop} — cancels the gRPC stream + clears listeners.</li>
 * </ul>
 *
 * <p>Reconnect handling: if the worker stream errors (worker restarted, network
 * blip), the bridge re-issues the subscribe call; the new stream begins with a
 * fresh snapshot which replaces {@link #latestSnapshot} and emits a
 * {@link Delta.SnapshotReplaced} event so listeners can rebuild their keyed
 * state. Reconnect is currently bounded by the gRPC channel's keepalive +
 * retry policy at the {@code RemoteKnowledgeClient} layer; this class does not
 * own its own backoff schedule for V1.
 *
 * <p>Per slice 445 lean scope (verification commit {@code 044b21ab3}): concrete
 * to {@link IndexingJobView}; not parameterized. A second TABULAR Resource
 * would motivate a generic extraction.
 */
public final class RemoteIndexingJobsBridge {

  private static final Logger log = LoggerFactory.getLogger(RemoteIndexingJobsBridge.class);

  /** Per-row delta event delivered to subscribers. */
  public sealed interface Delta {
    /** Initial snapshot OR replacement after a stream reconnect. */
    record SnapshotReplaced(long snapshotSeq, List<IndexingJobView> items) implements Delta {}

    /** New row inserted into the worker's job queue. */
    record Insert(long seq, IndexingJobView row) implements Delta {}

    /** Existing row mutated. */
    record Update(long seq, IndexingJobView row) implements Delta {}

    /**
     * Row removed from the worker's job queue. Only the {@code pathHash}
     * (primary key) is carried since the row is gone.
     */
    record Delete(long seq, String pathHash) implements Delta {}
  }

  /**
   * Listener registration handle. {@link #close()} unregisters and is safe to
   * call from listener callbacks.
   */
  public interface Subscription extends java.io.Closeable {
    @Override
    void close();
  }

  private final Supplier<IngestServiceGrpc.IngestServiceStub> asyncStubSupplier;
  private final List<java.util.function.Consumer<Delta>> listeners = new CopyOnWriteArrayList<>();
  /**
   * The cached (seq, items) pair, updated ATOMICALLY together (tempdoc 550 §B.2 fix-pass). A new
   * SSE subscriber reads this ONE record (via {@link #latestSnapshotPair()}), so it can never see a
   * torn snapshot — items at one seq with a {@code seq} from a concurrent delta — which would make
   * the client subscribe past an unseen delta and go permanently stale.
   */
  public record CachedSnapshot(long seq, List<IndexingJobView> items) {}

  private final AtomicReference<CachedSnapshot> cached =
      new AtomicReference<>(new CachedSnapshot(0L, List.of()));
  private final AtomicBoolean started = new AtomicBoolean(false);
  private volatile boolean stopped = false;

  /**
   * Constructs the bridge against an asynchronous gRPC stub supplier.
   *
   * <p>The supplier is resolved at {@link #start()} time (not construction
   * time), which lets the bridge be wired into the bootstrap eagerly even
   * though the underlying gRPC channel only comes up after async Worker
   * startup. {@link #start()} returns a future that fails fast when the
   * supplier returns {@code null}; callers retry once the channel is ready.
   *
   * <p>The stub MUST be the async variant — blocking stub doesn't support
   * server-streaming via the {@link StreamObserver} pattern.
   */
  public RemoteIndexingJobsBridge(Supplier<IngestServiceGrpc.IngestServiceStub> asyncStubSupplier) {
    this.asyncStubSupplier = Objects.requireNonNull(asyncStubSupplier, "asyncStubSupplier");
  }

  /**
   * Opens the gRPC stream. Returns a future completed once the snapshot frame
   * has been delivered to listeners. Callers can register listeners before
   * calling start and will receive the snapshot via the regular delta path.
   *
   * <p>Idempotent on second call — a started bridge returns a future that
   * resolves once the in-flight snapshot has been delivered (or never if the
   * connection failed).
   */
  public CompletableFuture<Void> start() {
    CompletableFuture<Void> snapshotDelivered = new CompletableFuture<>();
    if (!started.compareAndSet(false, true)) {
      // Already started; return an already-completed future to avoid hanging callers.
      // Real "is the current snapshot loaded?" check is via latestSnapshotSeq() > 0.
      snapshotDelivered.complete(null);
      return snapshotDelivered;
    }
    openStream(snapshotDelivered);
    return snapshotDelivered;
  }

  /**
   * Subscribes a listener. New listeners do NOT receive a synthetic snapshot
   * — they start receiving deltas from "now". For initial-state hydration use
   * {@link #latestSnapshot}.
   */
  public Subscription subscribe(java.util.function.Consumer<Delta> listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  /**
   * The current (seq, items) pair as ONE atomic read — the correct way for a new SSE subscriber to
   * hydrate, so the seq and items can never be torn apart (tempdoc 550 §B.2 fix-pass).
   */
  public CachedSnapshot latestSnapshotPair() {
    return cached.get();
  }

  /** Most recent snapshot items. Empty list before first connect. */
  public List<IndexingJobView> latestSnapshot() {
    return cached.get().items();
  }

  /** Seq cursor as of the most recent snapshot. {@code 0} before first connect. */
  public long latestSnapshotSeq() {
    return cached.get().seq();
  }

  /**
   * Stops listener fan-out. Subsequent frames arriving on the gRPC stream are
   * dropped without being delivered to listeners or mutating cached state.
   *
   * <p>Note: this does NOT actively cancel the gRPC stream — the
   * {@code asyncStub.subscribeIndexingJobs} server-streaming variant gives the
   * client no handle on the underlying call. In practice the channel shutdown
   * (driven by {@code RemoteKnowledgeClient}) closes the connection and the
   * server stops emitting. For Phase 4 / production, wrap {@link #start()} in
   * a {@code CancelToken}-bound {@code io.grpc.Context} (mirroring the
   * scanRoot pattern) if proactive cancellation is required.
   */
  public void stop() {
    stopped = true;
    listeners.clear();
  }

  private void openStream(CompletableFuture<Void> snapshotDelivered) {
    if (stopped) {
      snapshotDelivered.completeExceptionally(new IllegalStateException("bridge stopped"));
      return;
    }
    IngestServiceGrpc.IngestServiceStub asyncStub = asyncStubSupplier.get();
    if (asyncStub == null) {
      // Allow start() to be retried once the channel is up. Reset the started
      // flag so a follow-up start() will try again.
      started.set(false);
      snapshotDelivered.completeExceptionally(
          new IllegalStateException(
              "Worker channel not connected yet; retry start() after connectKnowledgeServer."));
      return;
    }
    StreamObserver<IndexingJobsFrame> obs =
        new StreamObserver<>() {
          @Override
          public void onNext(IndexingJobsFrame frame) {
            if (stopped) return;
            try {
              switch (frame.getBodyCase()) {
                case SNAPSHOT -> {
                  var items = new ArrayList<IndexingJobView>(frame.getSnapshot().getItemsCount());
                  for (var view : frame.getSnapshot().getItemsList()) {
                    items.add(toView(view));
                  }
                  List<IndexingJobView> immutable = List.copyOf(items);
                  cached.set(new CachedSnapshot(frame.getSeq(), immutable));
                  emit(new Delta.SnapshotReplaced(frame.getSeq(), immutable));
                  if (!snapshotDelivered.isDone()) {
                    snapshotDelivered.complete(null);
                  }
                }
                case DELTA -> {
                  IndexingJobsDelta d = frame.getDelta();
                  // Tempdoc 550 §B.2 keystone fix: keep `latestSnapshot` a FAITHFUL LIVE
                  // projection of the worker's job table by applying every delta to the
                  // cached snapshot — not only emitting it to live listeners. Previously
                  // only the SNAPSHOT frame mutated `latestSnapshot`, so after the worker
                  // drained jobs (PENDING→DONE via UPDATE deltas) the cache stayed frozen
                  // at the subscribe-time snapshot; every NEW SSE subscriber (browser
                  // reload) then received phantom PENDING rows the live `queueDepth()`
                  // reported as 0 — the count-vs-list drift. Applying deltas here makes a
                  // fresh subscriber's snapshot agree with the live worker state.
                  switch (d.getChangeCase()) {
                    case INSERT -> {
                      IndexingJobView view = toView(d.getInsert());
                      upsertSnapshot(view, frame.getSeq());
                      emit(new Delta.Insert(frame.getSeq(), view));
                    }
                    case UPDATE -> {
                      IndexingJobView view = toView(d.getUpdate());
                      upsertSnapshot(view, frame.getSeq());
                      emit(new Delta.Update(frame.getSeq(), view));
                    }
                    case DELETE_PATH_HASH -> {
                      removeFromSnapshot(d.getDeletePathHash(), frame.getSeq());
                      emit(new Delta.Delete(frame.getSeq(), d.getDeletePathHash()));
                    }
                    case CHANGE_NOT_SET -> log.warn(
                        "RemoteIndexingJobsBridge: empty delta change in frame seq={}",
                        frame.getSeq());
                  }
                }
                case BODY_NOT_SET -> log.warn(
                    "RemoteIndexingJobsBridge: empty body in frame seq={}", frame.getSeq());
              }
            } catch (RuntimeException e) {
              log.error("RemoteIndexingJobsBridge.onNext failed", e);
            }
          }

          @Override
          public void onError(Throwable t) {
            if (stopped) return;
            log.warn("RemoteIndexingJobsBridge stream error; will rely on caller-driven reconnect", t);
            if (!snapshotDelivered.isDone()) {
              snapshotDelivered.completeExceptionally(t);
            }
            // V1 lean scope: do not auto-reconnect from the bridge. Higher layers
            // (RemoteKnowledgeClient on reconnect, controller subscribe-on-demand)
            // re-call start(). The bounded reconnect schedule is a Phase 4 concern.
          }

          @Override
          public void onCompleted() {
            log.info("RemoteIndexingJobsBridge stream completed by server");
          }
        };

    asyncStub.subscribeIndexingJobs(SubscribeIndexingJobsRequest.newBuilder().build(), obs);
  }

  /**
   * Apply an Insert/Update to the cached snapshot (tempdoc 550 §B.2). Keyed by
   * {@code pathHash}; preserves insertion order (a re-keyed Update keeps the row's
   * original position). Atomic so concurrent {@link #latestSnapshot()} readers
   * always see a consistent immutable list.
   */
  private void upsertSnapshot(IndexingJobView view, long seq) {
    cached.updateAndGet(
        cur -> {
          var map = new java.util.LinkedHashMap<String, IndexingJobView>(cur.items().size() + 1);
          for (var v : cur.items()) {
            map.put(v.pathHash(), v);
          }
          map.put(view.pathHash(), view);
          return new CachedSnapshot(seq, List.copyOf(map.values()));
        });
  }

  /** Remove a row from the cached snapshot by primary key (tempdoc 550 §B.2). */
  private void removeFromSnapshot(String pathHash, long seq) {
    cached.updateAndGet(
        cur -> {
          boolean present = false;
          for (var v : cur.items()) {
            if (v.pathHash().equals(pathHash)) {
              present = true;
              break;
            }
          }
          // Advance seq even on a no-op remove so the (seq, items) cursor stays current.
          if (!present) {
            return new CachedSnapshot(seq, cur.items());
          }
          var out = new ArrayList<IndexingJobView>(cur.items().size());
          for (var v : cur.items()) {
            if (!v.pathHash().equals(pathHash)) {
              out.add(v);
            }
          }
          return new CachedSnapshot(seq, List.copyOf(out));
        });
  }

  private void emit(Delta delta) {
    for (var listener : listeners) {
      try {
        listener.accept(delta);
      } catch (RuntimeException e) {
        log.warn("RemoteIndexingJobsBridge listener threw on delta delivery; continuing", e);
      }
    }
  }

  private static IndexingJobView toView(io.justsearch.ipc.IndexingJobView proto) {
    return new IndexingJobView(
        proto.getPathHash(),
        proto.getState(),
        proto.getAttempts(),
        proto.getLastUpdatedMs(),
        proto.getErrorMessage(),
        proto.getRetryAfterMs(),
        proto.getCollection());
  }
}
