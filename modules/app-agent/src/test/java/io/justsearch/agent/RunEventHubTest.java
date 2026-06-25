package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.TraceContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tempdoc 577 §2.14 Root I (#13) — the session-local event hub: replay + fan-out + bounded buffer. */
class RunEventHubTest {

  private static AgentEvent ev(String chunk) {
    return new AgentEvent.TextChunk(chunk);
  }

  /** A traced TextChunk whose monotonic span (B1's Last-Event-ID) is {@code seq}. */
  private static AgentEvent evSeq(String chunk, int seq) {
    String spanId = String.format(java.util.Locale.ROOT, "span-%06d", seq);
    return new AgentEvent.TextChunk(chunk, new TraceContext("run-1", "step", spanId, null, "a", null, 0));
  }

  private static List<String> texts(List<AgentEvent> events) {
    List<String> out = new ArrayList<>();
    for (AgentEvent e : events) {
      if (e instanceof AgentEvent.TextChunk tc) {
        out.add(tc.text());
      }
    }
    return out;
  }

  @Test
  void aLateSubscriberReplaysTheBufferOldestFirstThenReceivesOngoing() {
    var hub = new RunEventHub(100);
    hub.publish(ev("a"));
    hub.publish(ev("b"));

    var received = new ArrayList<AgentEvent>();
    var unsub = hub.subscribe(received::add);
    // Replay delivered the two already-published events, oldest-first.
    assertEquals(List.of("a", "b"), texts(received));

    hub.publish(ev("c"));
    assertEquals(List.of("a", "b", "c"), texts(received), "ongoing events reach the subscriber");

    unsub.run();
    hub.publish(ev("d"));
    assertEquals(List.of("a", "b", "c"), texts(received), "no events after unsubscribe");
  }

  @Test
  void nObserversEachSeeTheRun() {
    var hub = new RunEventHub(100);
    var one = new ArrayList<AgentEvent>();
    var two = new ArrayList<AgentEvent>();
    hub.subscribe(one::add);
    hub.publish(ev("x"));
    hub.subscribe(two::add); // a SECOND observer attaches mid-run
    hub.publish(ev("y"));

    assertEquals(List.of("x", "y"), texts(one), "the first observer saw both");
    assertEquals(List.of("x", "y"), texts(two), "the second replayed x, then saw y");
    assertEquals(2, hub.observerCount());
  }

  @Test
  void theBufferIsBoundedToTheCapacityWindow() {
    var hub = new RunEventHub(3);
    for (int i = 0; i < 10; i++) {
      hub.publish(ev("e" + i));
    }
    var received = new ArrayList<AgentEvent>();
    hub.subscribe(received::add);
    // Only the newest 3 are replayed; the older 7 were evicted (the full history is on events.ndjson).
    assertEquals(List.of("e7", "e8", "e9"), texts(received));
  }

  @Test
  void aBrokenObserverIsEvictedAndDoesNotBreakTheRunOrOtherObservers() {
    var hub = new RunEventHub(100);
    var healthy = new ArrayList<AgentEvent>();
    hub.subscribe(
        e -> {
          throw new RuntimeException("socket closed");
        });
    hub.subscribe(healthy::add);
    assertEquals(2, hub.observerCount());
    hub.publish(ev("z")); // must not throw
    assertEquals(List.of("z"), texts(healthy), "the healthy observer still received the event");
    // Tempdoc 577 Root I — the dead-socket observer is EVICTED, so observerCount reflects LIVE
    // observers (the zero-observer policy depends on this; a counted-but-dead observer would mean
    // "no watcher" never registers).
    assertEquals(1, hub.observerCount(), "the throwing observer was evicted");
  }

  @Test
  void evictingTheLastObserverDropsTheCountToZero() {
    // The reachability the §2.14 Root I zero-observer park needs: the SOLE observer's socket dies →
    // its write throws → it is evicted → observerCount 0 → the loop's policy sees "no watcher".
    var hub = new RunEventHub(100);
    hub.subscribe(
        e -> {
          throw new RuntimeException("the only watcher left");
        });
    assertEquals(1, hub.observerCount());
    hub.publish(ev("a"));
    assertEquals(0, hub.observerCount(), "the only observer was evicted ⇒ zero live observers");
  }

  @Test
  void subscribeFromSeqReplaysOnlyEventsNewerThanTheCursor() {
    // Tempdoc 585 §D Phase 2 (B1) — a precise reconnect: the client last saw span 3, so a reattach
    // with fromSeq=3 replays ONLY spans 4 and 5 (no duplicate replay of 1–3).
    var hub = new RunEventHub(100);
    for (int i = 1; i <= 5; i++) {
      hub.publish(evSeq("e" + i, i));
    }
    var received = new ArrayList<AgentEvent>();
    hub.subscribe(received::add, 3L);
    assertEquals(List.of("e4", "e5"), texts(received), "only events with seq > 3 replayed");

    hub.publish(evSeq("e6", 6));
    assertEquals(List.of("e4", "e5", "e6"), texts(received), "ongoing events still stream");
  }

  @Test
  void subscribeFromSeqMinValueReplaysTheWholeWindow() {
    // The default subscribe(observer) delegates with Long.MIN_VALUE — the full window replays,
    // including untraced events (seq == -1) a real cursor could not be compared against.
    var hub = new RunEventHub(100);
    hub.publish(evSeq("a", 1));
    hub.publish(ev("untraced")); // seq == -1
    hub.publish(evSeq("b", 2));
    var received = new ArrayList<AgentEvent>();
    hub.subscribe(received::add, Long.MIN_VALUE);
    assertEquals(List.of("a", "untraced", "b"), texts(received));
  }

  @Test
  void subscribeFromSeqAtOrAboveTheNewestReplaysNothing() {
    // The common case: the client is fully caught up (last seen == newest), so a reattach replays
    // nothing and only streams what comes next.
    var hub = new RunEventHub(100);
    hub.publish(evSeq("a", 1));
    hub.publish(evSeq("b", 2));
    var received = new ArrayList<AgentEvent>();
    hub.subscribe(received::add, 2L);
    assertEquals(List.of(), texts(received), "caught-up reattach replays nothing");
    hub.publish(evSeq("c", 3));
    assertEquals(List.of("c"), texts(received));
  }

  @Test
  void closeDropsObserversAndRefusesFurtherPublishes() {
    var hub = new RunEventHub(100);
    var received = new ArrayList<AgentEvent>();
    hub.subscribe(received::add);
    hub.publish(ev("a"));
    hub.close();
    assertEquals(0, hub.observerCount());
    hub.publish(ev("b")); // ignored after close
    assertEquals(List.of("a"), texts(received));
    assertTrue(texts(received).size() == 1);
  }
}
