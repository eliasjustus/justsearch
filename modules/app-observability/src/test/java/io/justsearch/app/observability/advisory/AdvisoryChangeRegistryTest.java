package io.justsearch.app.observability.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.EmissionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AdvisoryChangeRegistry")
final class AdvisoryChangeRegistryTest {

  private static final AdvisoryClassId TEST_CLASS = AdvisoryClassId.of("test.class");
  private static final Duration DEDUPE_WINDOW = Duration.ofMinutes(1);
  private static final Instant T0 = Instant.parse("2026-05-15T10:00:00Z");

  private static AdvisoryProjector<String> testProjector(boolean filter) {
    return new AdvisoryProjector<>() {
      @Override
      public AdvisoryClassId classId() {
        return TEST_CLASS;
      }

      @Override
      public EmissionPolicy emissionPolicy() {
        return EmissionPolicy.persisted().withDedupeWindow(DEDUPE_WINDOW);
      }

      @Override
      public Optional<AdvisoryProjection> project(String source) {
        if (filter) return Optional.empty();
        return Optional.of(
            new AdvisoryProjection(
                T0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("test.key"),
                Map.of("input", source)));
      }

      @Override
      public String dedupKey(String source) {
        return "key:" + source;
      }
    };
  }

  private AdvisoryChangeRegistry registry(Clock clock) {
    var projector = testProjector(false);
    var classRegistry = AdvisoryClassRegistry.builder().register(projector).build();
    return new AdvisoryChangeRegistry(classRegistry, clock);
  }

  @Test
  @DisplayName("project publishes and returns stamped record")
  void projectPublishesAndReturnsStamped() {
    var projector = testProjector(false);
    var classRegistry = AdvisoryClassRegistry.builder().register(projector).build();
    var reg = new AdvisoryChangeRegistry(classRegistry, Clock.fixed(T0, ZoneOffset.UTC));

    AtomicReference<Object> received = new AtomicReference<>();
    reg.subscribe(TEST_CLASS, env -> received.set(env.payload()));

    Optional<AdvisoryRecord> result = reg.project(projector, "hello");

    assertTrue(result.isPresent());
    AdvisoryRecord record = result.get();
    assertEquals("test.class", record.classId());
    assertEquals("test.class:key:hello", record.id());
    assertEquals("PERSISTED", record.renderHint());
    assertNotNull(received.get());
  }

  @Test
  @DisplayName("project returns empty when projector filters")
  void projectReturnsEmptyWhenFiltered() {
    var projector = testProjector(true);
    var classRegistry = AdvisoryClassRegistry.builder().register(projector).build();
    var reg = new AdvisoryChangeRegistry(classRegistry, Clock.fixed(T0, ZoneOffset.UTC));

    Optional<AdvisoryRecord> result = reg.project(projector, "hello");

    assertTrue(result.isEmpty());
  }

  @Test
  @DisplayName("dedup window suppresses repeat within window")
  void dedupSuppressesRepeat() {
    var projector = testProjector(false);
    var classRegistry = AdvisoryClassRegistry.builder().register(projector).build();
    Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    var reg = new AdvisoryChangeRegistry(classRegistry, clock);

    Optional<AdvisoryRecord> first = reg.project(projector, "same");
    Optional<AdvisoryRecord> second = reg.project(projector, "same");

    assertTrue(first.isPresent());
    assertFalse(second.isPresent());
  }

  @Test
  @DisplayName("different dedup keys both publish")
  void differentKeysBothPublish() {
    var projector = testProjector(false);
    var classRegistry = AdvisoryClassRegistry.builder().register(projector).build();
    var reg = new AdvisoryChangeRegistry(classRegistry, Clock.fixed(T0, ZoneOffset.UTC));

    Optional<AdvisoryRecord> first = reg.project(projector, "alpha");
    Optional<AdvisoryRecord> second = reg.project(projector, "beta");

    assertTrue(first.isPresent());
    assertTrue(second.isPresent());
    assertEquals("test.class:key:alpha", first.get().id());
    assertEquals("test.class:key:beta", second.get().id());
  }
}
