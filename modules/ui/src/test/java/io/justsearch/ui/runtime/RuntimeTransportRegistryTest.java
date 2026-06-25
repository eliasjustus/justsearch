package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.Reachability;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 501 Phase 35 (F1): RuntimeTransportRegistry — single source of
 * truth for the reachability axis. Verifies registration order is
 * preserved, HTTP-class kinds get URL-resolved at snapshot time, and
 * MCP/filesystem kinds pass through unchanged.
 */
class RuntimeTransportRegistryTest {

  @Test
  void snapshotResolvesHttpKindsAndPreservesOrder() {
    RuntimeTransportRegistry r = new RuntimeTransportRegistry();
    r.registerHttp("/api/runtime/manifest", Reachability.AUDIENCE_PUBLIC);
    r.registerSse("/api/runtime/manifest/stream", Reachability.AUDIENCE_PUBLIC);
    r.registerProbe("/api/runtime/ready", Reachability.AUDIENCE_PUBLIC);
    r.registerWellKnown(
        "/.well-known/justsearch/manifest.json", Reachability.AUDIENCE_PUBLIC);
    r.registerMcpTool("justsearch_runtime_manifest", Reachability.AUDIENCE_PUBLIC);
    r.registerFilesystem("/data/runtime/manifest.json", Reachability.AUDIENCE_FULL);

    Reachability snap = r.snapshot(p -> "http://127.0.0.1:5000" + p);

    assertEquals(6, snap.transports().size());
    assertEquals("http://127.0.0.1:5000/api/runtime/manifest", snap.transports().get(0).url());
    assertEquals(Reachability.KIND_HTTP_REST, snap.transports().get(0).kind());
    assertEquals(
        "http://127.0.0.1:5000/api/runtime/manifest/stream", snap.transports().get(1).url());
    assertEquals(Reachability.KIND_SSE, snap.transports().get(1).kind());
    assertEquals("http://127.0.0.1:5000/api/runtime/ready", snap.transports().get(2).url());
    assertEquals(Reachability.KIND_PROBE, snap.transports().get(2).kind());
    assertEquals(
        "http://127.0.0.1:5000/.well-known/justsearch/manifest.json",
        snap.transports().get(3).url());
    assertEquals(Reachability.KIND_WELL_KNOWN, snap.transports().get(3).kind());
    // MCP + filesystem pass through unchanged — the resolver is HTTP-only.
    assertEquals("justsearch_runtime_manifest", snap.transports().get(4).url());
    assertEquals(Reachability.KIND_MCP, snap.transports().get(4).kind());
    assertEquals("/data/runtime/manifest.json", snap.transports().get(5).url());
    assertEquals(Reachability.KIND_FILESYSTEM, snap.transports().get(5).kind());
  }

  @Test
  void emptyRegistrySnapshotIsEmpty() {
    RuntimeTransportRegistry r = new RuntimeTransportRegistry();
    Reachability snap = r.snapshot(p -> "http://127.0.0.1:5000" + p);
    assertTrue(snap.transports().isEmpty());
  }

  @Test
  void publicProjectionStripsAudienceFullEntries() {
    RuntimeTransportRegistry r = new RuntimeTransportRegistry();
    r.registerHttp("/api/runtime/manifest", Reachability.AUDIENCE_PUBLIC);
    r.registerFilesystem("/data/runtime/manifest.json", Reachability.AUDIENCE_FULL);

    Reachability snap = r.snapshot(p -> "http://127.0.0.1:5000" + p);
    Reachability pub = snap.publicProjection();

    assertEquals(1, pub.transports().size(), "filesystem transport must be stripped");
    assertEquals(Reachability.AUDIENCE_PUBLIC, pub.transports().get(0).audience());
  }
}
