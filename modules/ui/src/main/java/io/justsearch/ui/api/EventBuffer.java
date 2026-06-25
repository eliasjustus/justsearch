/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import java.time.Instant;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory ring buffer for recent events.
 *
 * <p>Stores events for debugging and observability. Events are automatically
 * pruned when the buffer exceeds MAX_EVENTS.
 *
 * <p>Thread-safe for concurrent access.
 */
public class EventBuffer {
    private static final int MAX_EVENTS = 50;

    private final Deque<Event> events = new ConcurrentLinkedDeque<>();

    /**
     * Represents a single event.
     */
    public record Event(
        Instant timestamp,
        String level,
        String source,
        String message,
        Map<String, Object> data
    ) {
        public Event(String level, String source, String message) {
            this(Instant.now(), level, source, message, Map.of());
        }

        public Event(String level, String source, String message, Map<String, Object> data) {
            this(Instant.now(), level, source, message, data != null ? data : Map.of());
        }

        /**
         * Convert to map for JSON serialization.
         */
        public Map<String, Object> toMap() {
            return Map.of(
                "timestamp", timestamp.toString(),
                "level", level,
                "source", source,
                "message", message,
                "data", data
            );
        }
    }

    /**
     * Add an info-level event.
     */
    public void info(String source, String message) {
        add(new Event("info", source, message));
    }

    /**
     * Add an info-level event with data.
     */
    public void info(String source, String message, Map<String, Object> data) {
        add(new Event("info", source, message, data));
    }

    /**
     * Add a warning-level event.
     */
    public void warn(String source, String message) {
        add(new Event("warn", source, message));
    }

    /**
     * Add a warning-level event with data.
     */
    public void warn(String source, String message, Map<String, Object> data) {
        add(new Event("warn", source, message, data));
    }

    /**
     * Add an error-level event.
     */
    public void error(String source, String message) {
        add(new Event("error", source, message));
    }

    /**
     * Add an error-level event with data.
     */
    public void error(String source, String message, Map<String, Object> data) {
        add(new Event("error", source, message, data));
    }

    /**
     * Add a debug-level event.
     */
    public void debug(String source, String message) {
        add(new Event("debug", source, message));
    }

    /**
     * Add an event to the buffer.
     */
    public void add(Event event) {
        events.addFirst(event);

        // Prune old events
        while (events.size() > MAX_EVENTS) {
            events.removeLast();
        }
    }

    /**
     * Get recent events.
     *
     * @param limit maximum number of events to return (0 for all)
     * @return list of events, newest first
     */
    public List<Event> recent(int limit) {
        if (limit <= 0) {
            return List.copyOf(events);
        }
        return events.stream().limit(limit).toList();
    }

    /**
     * Get all events.
     */
    public List<Event> all() {
        return List.copyOf(events);
    }

    /**
     * Get event count.
     */
    public int size() {
        return events.size();
    }

    /**
     * Clear all events.
     */
    public void clear() {
        events.clear();
    }
}
