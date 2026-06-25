package io.justsearch.observable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 518 Appendix F W4.1 — pin the substrate contract that ConfigStore +
 * TransitionRunner (and the additional-listeners branch of IndexingLoop, post-W4.3)
 * collapse onto.
 */
@DisplayName("ObservableNotifier — shared listener-list substrate")
final class ObservableNotifierTest {

  @Test
  @DisplayName("register + notifyAll dispatches in registration order")
  void registrationOrderDispatch() {
    var notifier = new ObservableNotifier<String>("Test");
    List<String> calls = new ArrayList<>();
    notifier.register(e -> calls.add("A:" + e));
    notifier.register(e -> calls.add("B:" + e));
    notifier.register(e -> calls.add("C:" + e));

    notifier.notifyAll("event");

    assertEquals(List.of("A:event", "B:event", "C:event"), calls);
  }

  @Test
  @DisplayName("duplicate registration is permitted; each invocation receives the event")
  void duplicateRegistrationPermitted() {
    var notifier = new ObservableNotifier<String>("Test");
    AtomicInteger count = new AtomicInteger();
    java.util.function.Consumer<String> listener = e -> count.incrementAndGet();
    notifier.register(listener);
    notifier.register(listener);

    notifier.notifyAll("e");

    assertEquals(2, count.get(), "both registrations receive the event");
    assertEquals(2, notifier.size());
  }

  @Test
  @DisplayName("unregister removes the first matching instance; returns true on removal")
  void unregisterRemovesFirstMatching() {
    var notifier = new ObservableNotifier<String>("Test");
    java.util.function.Consumer<String> listener = e -> {};
    notifier.register(listener);
    notifier.register(listener);

    assertTrue(notifier.unregister(listener), "first remove returns true");
    assertEquals(1, notifier.size());
    assertTrue(notifier.unregister(listener), "second remove returns true");
    assertEquals(0, notifier.size());
    assertFalse(notifier.unregister(listener), "third remove returns false");
  }

  @Test
  @DisplayName("listener throwing does not break the iteration — other listeners still fire")
  void throwingListenerDoesNotBreakIteration() {
    var notifier = new ObservableNotifier<String>("Test");
    List<String> got = new ArrayList<>();
    notifier.register(e -> got.add("first:" + e));
    notifier.register(
        e -> {
          throw new RuntimeException("intentional");
        });
    notifier.register(e -> got.add("third:" + e));

    notifier.notifyAll("e");

    assertEquals(List.of("first:e", "third:e"), got);
  }
}
