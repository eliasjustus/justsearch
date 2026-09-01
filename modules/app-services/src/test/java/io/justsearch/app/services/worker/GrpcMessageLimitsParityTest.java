/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.grpc.GrpcMessageLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 882 item 5 regression guard: both ends of the Head-to-Worker gRPC channel - the
 * Worker's server ({@code KnowledgeServerGrpcWiring}) and the Head's client
 * ({@code RemoteKnowledgeClient}) - read the SAME inbound message-size limit from
 * {@link GrpcMessageLimits}, rather than each hardcoding its own value (the two had drifted 32
 * MiB vs grpc-java's 4 MiB default since the first commit).
 */
final class GrpcMessageLimitsParityTest {

  @Test
  @DisplayName("MAX_INBOUND_MESSAGE_BYTES is 32 MiB")
  void maxInboundMessageBytesIs32Mib() {
    assertEquals(32 * 1024 * 1024, GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES);
  }

  @Test
  @DisplayName("Head client and Worker server both wire the shared inbound limit")
  void headAndWorkerBothWireSharedLimit() throws IOException {
    Path repoRoot = findRepoRoot();
    Assumptions.assumeTrue(repoRoot != null,
        "Could not locate repo root (settings.gradle.kts) from this test run - skipping.");

    Path clientFile = repoRoot.resolve(
        "modules/app-services/src/main/java/io/justsearch/app/services/worker/"
            + "RemoteKnowledgeClient.java");
    Path serverFile = repoRoot.resolve(
        "modules/indexer-worker/src/main/java/io/justsearch/indexerworker/server/ops/"
            + "KnowledgeServerGrpcWiring.java");
    Assumptions.assumeTrue(Files.exists(clientFile) && Files.exists(serverFile),
        "RemoteKnowledgeClient.java or KnowledgeServerGrpcWiring.java not found - skipping.");

    String expected = "maxInboundMessageSize(GrpcMessageLimits.MAX_INBOUND_MESSAGE_BYTES)";
    String clientContent = Files.readString(clientFile, StandardCharsets.UTF_8);
    String serverContent = Files.readString(serverFile, StandardCharsets.UTF_8);

    assertTrue(clientContent.contains(expected),
        "Expected RemoteKnowledgeClient.java to contain: " + expected);
    assertTrue(serverContent.contains(expected),
        "Expected KnowledgeServerGrpcWiring.java to contain: " + expected);
  }

  private static Path findRepoRoot() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    return dir;
  }
}
