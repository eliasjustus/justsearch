package io.justsearch.ui.api.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.http.Context;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * Tempdoc 501 §13.4.4: verifies the time-axis reader endpoints surface
 * per-instance history correctly and apply the public projection (no
 * sessionToken leak via the postmortem reader).
 */
class RuntimeInstancesControllerTest {

  @Test
  void handleListReturnsKnownInstancesSortedNewestFirst(@TempDir Path tempDir) throws Exception {
    Path instancesRoot = tempDir.resolve("runtime").resolve("instances");
    Files.createDirectories(instancesRoot.resolve("aaa-old"));
    Files.createDirectories(instancesRoot.resolve("bbb-new"));
    Files.writeString(
        instancesRoot.resolve("bbb-new").resolve("manifest.json"),
        "{}",
        StandardCharsets.UTF_8);
    Files.writeString(
        instancesRoot.resolve("bbb-new").resolve("manifest.log.ndjson"),
        "{\"a\":1}\n{\"a\":2}\n",
        StandardCharsets.UTF_8);
    // Make "aaa-old" actually older by touching mtime.
    Files.setLastModifiedTime(
        instancesRoot.resolve("aaa-old"),
        java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L));
    Files.setLastModifiedTime(
        instancesRoot.resolve("bbb-new"),
        java.nio.file.attribute.FileTime.fromMillis(1_800_000_000_000L));

    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.instancesRoot()).thenReturn(instancesRoot);

    RuntimeInstancesController controller = new RuntimeInstancesController(publisher);
    Context ctx = mock(Context.class);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);

    controller.handleList(ctx);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) captor.getValue();
    assertEquals(2, body.get("count"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("instances");
    assertEquals("bbb-new", rows.get(0).get("instanceId"), "newest first");
    assertEquals("aaa-old", rows.get(1).get("instanceId"));
    assertEquals(Boolean.TRUE, rows.get(0).get("hasSnapshot"));
    assertEquals(Boolean.TRUE, rows.get(0).get("hasLog"));
    assertEquals(2L, rows.get(0).get("logLines"));
  }

  @Test
  void handleGetOneReturnsTerminalSnapshotAndLogWithRedaction(@TempDir Path tempDir)
      throws Exception {
    Path dir = tempDir.resolve("runtime").resolve("instances").resolve("inst-123");
    Files.createDirectories(dir);
    // Snapshot with sessionToken — projection MUST strip it.
    String snapshot = "{\"schemaVersion\":1,\"instanceId\":\"inst-123\","
        + "\"pid\":1,\"startedAt\":\"2026-05-21T00:00:00Z\","
        + "\"dataDir\":\"/tmp\",\"lifecycle\":\"READY\","
        + "\"head\":{\"apiPort\":1234,\"apiBaseUrl\":\"http://127.0.0.1:1234\","
        + "\"sessionToken\":\"SECRET\",\"readyAt\":\"2026-05-21T00:00:01Z\"}}";
    Files.writeString(dir.resolve("manifest.json"), snapshot, StandardCharsets.UTF_8);
    Files.writeString(
        dir.resolve("manifest.log.ndjson"), snapshot + "\n", StandardCharsets.UTF_8);

    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.instancesRoot()).thenReturn(dir.getParent());

    RuntimeInstancesController controller = new RuntimeInstancesController(publisher);
    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn("inst-123");
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);

    controller.handleGetOne(ctx);

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(captor.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) captor.getValue();
    assertEquals("inst-123", body.get("instanceId"));
    assertNotNull(body.get("snapshot"));
    @SuppressWarnings("unchecked")
    List<Object> logEntries = (List<Object>) body.get("log");
    assertEquals(1, logEntries.size());
    assertEquals(0, body.get("logFromLine"));
    assertEquals(200, body.get("logLimit"));
    assertEquals(1L, body.get("logTotalLines"));
    // Snapshot is a RuntimeManifest; check that .head().sessionToken() is null.
    io.justsearch.app.api.runtime.RuntimeManifest snap =
        (io.justsearch.app.api.runtime.RuntimeManifest) body.get("snapshot");
    assertTrue(snap.head().sessionToken() == null, "sessionToken must be stripped from snapshot");
    io.justsearch.app.api.runtime.RuntimeManifest logged =
        (io.justsearch.app.api.runtime.RuntimeManifest) logEntries.get(0);
    assertTrue(logged.head().sessionToken() == null, "sessionToken must be stripped from log entry");
  }

  /**
   * Tempdoc 501 Phase 36 (F4): pagination — fromLine + limit honored,
   * logTotalLines counts all non-blank entries even when the returned
   * window is bounded.
   */
  @Test
  void handleGetOneHonorsFromLineAndLimit(@TempDir Path tempDir) throws Exception {
    Path dir = tempDir.resolve("runtime").resolve("instances").resolve("inst-paged");
    Files.createDirectories(dir);
    StringBuilder ndjson = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      ndjson
          .append("{\"schemaVersion\":1,\"instanceId\":\"inst-paged\",\"pid\":")
          .append(i)
          .append(",\"startedAt\":\"2026-05-21T00:00:00Z\",\"dataDir\":\"/tmp\",")
          .append("\"lifecycle\":\"STARTING\",\"head\":{\"apiPort\":1234,")
          .append("\"apiBaseUrl\":\"http://127.0.0.1:1234\",")
          .append("\"readyAt\":\"2026-05-21T00:00:01Z\"}}\n");
    }
    Files.writeString(dir.resolve("manifest.log.ndjson"), ndjson.toString());

    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.instancesRoot()).thenReturn(dir.getParent());

    RuntimeInstancesController controller = new RuntimeInstancesController(publisher);
    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn("inst-paged");
    when(ctx.queryParam("fromLine")).thenReturn("3");
    when(ctx.queryParam("limit")).thenReturn("4");
    when(ctx.contentType(anyString())).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);

    controller.handleGetOne(ctx);

    ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
    verify(ctx).json(cap.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) cap.getValue();
    assertEquals(3, body.get("logFromLine"));
    assertEquals(4, body.get("logLimit"));
    assertEquals(10L, body.get("logTotalLines"));
    assertEquals(Boolean.TRUE, body.get("logTruncated"));
    @SuppressWarnings("unchecked")
    List<Object> logEntries = (List<Object>) body.get("log");
    assertEquals(4, logEntries.size(), "limit honored");
    // First returned entry is the 4th log line (0-indexed: pid=3).
    io.justsearch.app.api.runtime.RuntimeManifest first =
        (io.justsearch.app.api.runtime.RuntimeManifest) logEntries.get(0);
    assertEquals(3L, first.pid());
  }

  @Test
  void handleGetOneRejectsInvalidId(@TempDir Path tempDir) {
    RuntimeManifestPublisher publisher = mock(RuntimeManifestPublisher.class);
    when(publisher.instancesRoot()).thenReturn(tempDir);
    RuntimeInstancesController controller = new RuntimeInstancesController(publisher);
    Context ctx = mock(Context.class);
    when(ctx.pathParam("id")).thenReturn("../etc/passwd");
    when(ctx.status(400)).thenReturn(ctx);
    when(ctx.json(any())).thenReturn(ctx);

    controller.handleGetOne(ctx);

    verify(ctx).status(400);
  }
}
