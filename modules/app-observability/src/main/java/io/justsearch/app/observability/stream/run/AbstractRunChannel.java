/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.observability.stream.FrameHistoryRingBuffer;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The half of a run channel that is identical for both execution semantics (tempdoc 834 §0): the
 * observation substrate. The two subtypes add only what genuinely differs — park + snapshot.
 *
 * <p>Deliberately does NOT declare {@code implements RunChannel}: {@link RunChannel} is sealed to
 * its two sub-interfaces, so a shared base that implemented it directly would have to be listed in
 * the permits clause — which would make the base itself a third, park-agnostic subtype and hand
 * back exactly the uniform handle §3.4 exists to prevent. The concrete leaves implement the
 * sub-interfaces and inherit these methods; the compiler checks the match.
 */
abstract class AbstractRunChannel {

  private final RunId id;
  private final RunDescriptor descriptor;
  private final RunChannelPolicy policy;
  private final SseStreamChannel channel;
  private final AtomicBoolean retired = new AtomicBoolean(false);
  private final CopyOnWriteArrayList<Runnable> retireListeners = new CopyOnWriteArrayList<>();

  AbstractRunChannel(RunId id, RunDescriptor descriptor, RunChannelPolicy policy) {
    this.id = Objects.requireNonNull(id, "id");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.channel =
        new SseStreamChannel(
            id.streamId(),
            new io.justsearch.app.observability.stream.StreamSequenceTracker(),
            new FrameHistoryRingBuffer(policy.frameRetention()),
            java.time.Clock.systemUTC());
  }

  public final RunId id() {
    return id;
  }

  public final RunDescriptor descriptor() {
    return descriptor;
  }

  public final RunChannelPolicy policy() {
    return policy;
  }

  public final SseStreamChannel channel() {
    return channel;
  }

  public final int observerCount() {
    return channel.listenerCount();
  }

  public final boolean publish(RunFrame frame) {
    Objects.requireNonNull(frame, "frame");
    if (retired.get()) {
      return false;
    }
    channel.publish(SseFrameKind.UPDATE, frame.asPayload());
    return true;
  }

  public final SseEnvelope lifecycle(RunFrame frame) {
    Objects.requireNonNull(frame, "frame");
    return channel.nextEnvelope(SseFrameKind.LIFECYCLE, frame.asPayload());
  }

  public final Optional<SseStreamChannel.Subscription> observe(
      Consumer<SseEnvelope> listener, long sinceSeq) {
    Objects.requireNonNull(listener, "listener");
    if (sinceSeq < 0) {
      throw new IllegalArgumentException("sinceSeq must be >= 0, got " + sinceSeq);
    }
    return channel.subscribeAndReplay(listener, sinceSeq);
  }

  public final boolean retired() {
    return retired.get();
  }

  public final void onRetire(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    if (retired.get()) {
      // Already terminal: run it now rather than registering it on a list that will never fire.
      listener.run();
      return;
    }
    retireListeners.add(listener);
  }

  /**
   * Marks the run terminal and fires the retire listeners exactly once. Package-private: the whole
   * terminal transition is the REGISTRY's to own (§2 — retire is two sites today, and a linger that
   * only half-applies is how "retired-but-readable" and "gone" get conflated).
   */
  final void markRetired() {
    if (!retired.compareAndSet(false, true)) {
      return;
    }
    for (Runnable listener : retireListeners) {
      try {
        listener.run();
      } catch (RuntimeException ignored) {
        // A writer failing to close its own socket must not abort the retirement of the run or of
        // the other observers' connections.
      }
    }
    retireListeners.clear();
  }
}
