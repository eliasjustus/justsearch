package io.justsearch.app.services.registry.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Placement;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.ShellAddress;
import io.justsearch.agent.api.registry.StateSnapshot;
import io.justsearch.agent.api.registry.Surface;
import io.justsearch.agent.api.registry.SurfaceConsumes;
import io.justsearch.agent.api.registry.SurfaceRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("URLSurfaceEmitter (slice 489 §5.5 / §12)")
final class URLSurfaceEmitterTest {

  private final URLSurfaceEmitter emitter = new URLSurfaceEmitter();

  private static Surface surfaceOf(String idValue) {
    return new Surface(
        new SurfaceRef(idValue),
        Presentation.of(
            new I18nKey("registry-surface.example.label"),
            new I18nKey("registry-surface.example.description")),
        Audience.USER,
        Placement.RAIL,
        SurfaceConsumes.empty(),
        "jf-example-surface",
        Provenance.core("1.0"));
  }

  @Test
  @DisplayName("empty state yields bare surface URL")
  void emptyStateBareUrl() {
    String url = emitter.toUrl(new SurfaceRef("core.library-surface"), StateSnapshot.empty());
    assertEquals("justsearch://surface/core.library-surface", url);
  }

  @Test
  @DisplayName("single-key state yields ?key=value")
  void singleKeyStateProducesQuery() {
    StateSnapshot s = new StateSnapshot(Map.of("query", "rust"));
    String url = emitter.toUrl(new SurfaceRef("core.search-surface"), s);
    assertEquals("justsearch://surface/core.search-surface?query=rust", url);
  }

  @Test
  @DisplayName("multi-key state sorts keys alphabetically (canonicalization)")
  void multiKeyStateSortsKeys() {
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    values.put("z", "last");
    values.put("a", "first");
    values.put("m", "middle");
    StateSnapshot s = new StateSnapshot(values);
    String url = emitter.toUrl(new SurfaceRef("core.search-surface"), s);
    assertEquals(
        "justsearch://surface/core.search-surface?a=first&m=middle&z=last",
        url,
        "keys must be sorted alphabetically for canonical URL form");
  }

  @Test
  @DisplayName("list value produces repeated keys (matches scorer parser convention)")
  void listValueProducesRepeatedKeys() {
    StateSnapshot s = new StateSnapshot(Map.of("ids", List.of("a", "b", "c")));
    String url = emitter.toUrl(new SurfaceRef("core.library-surface"), s);
    assertEquals("justsearch://surface/core.library-surface?ids=a&ids=b&ids=c", url);
  }

  @Test
  @DisplayName("special characters in values are URL-encoded")
  void specialCharactersEncoded() {
    StateSnapshot s = new StateSnapshot(Map.of("query", "rust ownership & lifetimes"));
    String url = emitter.toUrl(new SurfaceRef("core.search-surface"), s);
    assertEquals(
        "justsearch://surface/core.search-surface?query=rust+ownership+%26+lifetimes", url);
  }

  @Test
  @DisplayName("special characters in keys are URL-encoded")
  void specialCharactersInKeysEncoded() {
    StateSnapshot s = new StateSnapshot(Map.of("filter.range", "30d"));
    String url = emitter.toUrl(new SurfaceRef("core.search-surface"), s);
    assertEquals("justsearch://surface/core.search-surface?filter.range=30d", url);
  }

  @Test
  @DisplayName("null values rejected by StateSnapshot contract (cannot reach emitter)")
  void nullValuesRejectedAtSnapshotConstruction() {
    LinkedHashMap<String, Object> values = new LinkedHashMap<>();
    values.put("query", "rust");
    values.put("dropped", null);
    // StateSnapshot.values uses Map.copyOf which forbids null values — the URL grammar
    // has no representation for "key with null value" anyway. Verifying the contract
    // here ensures the emitter never receives a snapshot with nulls.
    assertThrows(NullPointerException.class, () -> new StateSnapshot(values));
  }

  @Test
  @DisplayName("numeric values stringified")
  void numericValuesStringified() {
    StateSnapshot s = new StateSnapshot(Map.of("limit", 25));
    String url = emitter.toUrl(new SurfaceRef("core.search-surface"), s);
    assertEquals("justsearch://surface/core.search-surface?limit=25", url);
  }

  @Test
  @DisplayName("toUrl(Navigation) overload matches toUrl(target, state)")
  void navigationOverloadMatches() {
    SurfaceRef target = new SurfaceRef("core.library-surface");
    StateSnapshot state = new StateSnapshot(Map.of("folder", "docs"));
    String fromAddress = emitter.toUrl(new ShellAddress.Navigation(target, state));
    String fromTuple = emitter.toUrl(target, state);
    assertEquals(fromTuple, fromAddress);
  }

  @Test
  @DisplayName("toUrlTemplate yields bare surface URL for any Surface")
  void toUrlTemplateBare() {
    Surface s = surfaceOf("core.example-surface");
    assertEquals("justsearch://surface/core.example-surface", emitter.toUrlTemplate(s));
  }

  @Test
  @DisplayName("null arguments rejected")
  void nullArgumentsRejected() {
    assertThrows(
        NullPointerException.class,
        () -> emitter.toUrl((SurfaceRef) null, StateSnapshot.empty()));
    assertThrows(
        NullPointerException.class,
        () -> emitter.toUrl(new SurfaceRef("core.library-surface"), null));
    assertThrows(NullPointerException.class, () -> emitter.toUrl((ShellAddress.Navigation) null));
    assertThrows(NullPointerException.class, () -> emitter.toUrlTemplate(null));
  }

  @Test
  @DisplayName("canonical form is round-trip-stable (sorted re-emission matches first emission)")
  void canonicalFormIsRoundTripStable() {
    StateSnapshot s1 = new StateSnapshot(Map.of("a", "1", "b", "2", "c", "3"));
    StateSnapshot s2 = new StateSnapshot(Map.of("c", "3", "a", "1", "b", "2"));
    SurfaceRef ref = new SurfaceRef("core.search-surface");
    String url1 = emitter.toUrl(ref, s1);
    String url2 = emitter.toUrl(ref, s2);
    assertEquals(url1, url2, "canonical URL must not depend on map insertion order");
    assertTrue(url1.contains("?a=1&b=2&c=3"));
  }
}
