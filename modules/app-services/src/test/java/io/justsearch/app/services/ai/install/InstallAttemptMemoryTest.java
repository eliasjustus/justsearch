/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 824 §3.4 — repair converges, or says why not.
 *
 * <p>{@code repair()} is literally {@code startInstall()}: round 16's user clicked it four times
 * against the same 872-byte file, got the same BITS-then-curl pair each time, and was offered
 * Repair a fifth. Nothing in the product could tell pass 4 from pass 1 because nothing survived a
 * pass. These tests pin the memory that makes both escalation and a terminal verdict possible.
 */
final class InstallAttemptMemoryTest {

  private static final String TARGET = "splade/naver-splade-v3/config.json";
  private static final String URL = "https://example/splade-config.json";

  @TempDir Path home;

  /**
   * Test 9 — three consecutive passes failing the same file at transport is terminal, and each pass
   * before that escalates to a different transport tier.
   */
  @Test
  @DisplayName("three failing repair passes: escalating tiers, then a terminal verdict")
  void threeFailingPasses_escalateThenTerminate() {
    assertEquals(0, InstallAttemptMemory.load(home).startTierFor(TARGET), "pass 1 uses today's transport");

    for (int pass = 1; pass <= InstallAttemptMemory.MAX_FAILED_PASSES; pass++) {
      InstallAttemptMemory memory = InstallAttemptMemory.load(home);
      assertEquals(
          pass - 1,
          memory.startTierFor(TARGET),
          "pass " + pass + " must meet a transport the earlier passes did not");
      assertFalse(
          memory.isTerminal(TARGET),
          "pass " + pass + " has not happened yet — a verdict now would be premature");
      memory.recordTransportFailure(TARGET, URL, 4, "Download failed for " + TARGET, pass - 1);
    }

    InstallAttemptMemory after = InstallAttemptMemory.load(home);
    assertTrue(after.isTerminal(TARGET), "three passes of a retrying transport is no longer luck");
    InstallAttemptMemory.Attempt a = after.get(TARGET);
    assertNotNull(a);
    assertEquals(12, a.attempts(), "the verdict states how many transports were actually spent");
    assertEquals(3, a.failedPasses());
    assertEquals(URL, a.url(), "the manual fallback needs the direct URL");
    assertTrue(a.lastError().contains("Download failed for"), a.lastError());
    assertTrue(a.lastAttemptEpochMs() > 0);
  }

  /**
   * The memory feeds §3.1's real ladder: the tier it asks for is the tier the policy runs, and a
   * pass count past the top rung saturates instead of producing an unknown transport. Asserted
   * against {@link TransportRetryPolicy} itself rather than a second local clamp, so the two
   * cannot drift.
   */
  @Test
  @DisplayName("the pass count feeds TransportRetryPolicy's start tier, saturating at the top rung")
  void passCountDrivesTheRealPolicyTier() {
    TransportRetryPolicy base = TransportRetryPolicy.defaultPolicy();
    assertEquals(0, base.withStartTier(0).startTier());
    assertEquals(1, base.withStartTier(1).startTier());
    assertEquals(
        TransportRetryPolicy.MAX_TRANSPORT_TIER,
        base.withStartTier(TransportRetryPolicy.MAX_TRANSPORT_TIER + 5).startTier(),
        "an out-of-range repair-pass number must never produce an unknown tier");

    InstallAttemptMemory memory = InstallAttemptMemory.load(home);
    for (int i = 0; i < 5; i++) {
      memory.recordTransportFailure(TARGET, URL, 1, "Download failed for " + TARGET, i);
    }
    assertEquals(5, memory.startTierFor(TARGET), "the memory counts passes; it does not clamp");
    assertEquals(
        TransportRetryPolicy.MAX_TRANSPORT_TIER,
        base.withStartTier(memory.startTierFor(TARGET)).startTier(),
        "…and the policy is the one authority that clamps");
  }

  /** A file that finally transfers has spent its history: the next run starts clean. */
  @Test
  @DisplayName("success forgets the file's failure history")
  void successResetsHistory() {
    InstallAttemptMemory memory = InstallAttemptMemory.load(home);
    memory.recordTransportFailure(TARGET, URL, 4, "Download failed for " + TARGET, 0);
    memory.recordTransportFailure(TARGET, URL, 4, "Download failed for " + TARGET, 1);
    memory.recordSuccess(TARGET);

    InstallAttemptMemory reloaded = InstallAttemptMemory.load(home);
    assertEquals(0, reloaded.startTierFor(TARGET));
    assertFalse(reloaded.isTerminal(TARGET));
  }

  /**
   * Only a TRANSPORT failure escalates. A SHA mismatch is an upstream/registry problem that a
   * different transport will not fix, and spending 40 s of backoff on it is pure latency.
   *
   * <p>Pinned against REAL {@code ResumableFetch.fetch} outcomes, and against the TYPED field the
   * classification reads. It used to match a prefix of the user-facing message, so rewording the
   * failure would have disabled escalation and the terminal verdict with every test still green;
   * {@link ResumableFetch.Outcome#failure()} makes that structurally impossible rather than merely
   * test-guarded.
   */
  @Test
  @DisplayName("a verification failure carries no transport classification, so it does not escalate")
  void onlyTransportOutcomesEscalate() {
    ResumableFetch.Outcome transport =
        ResumableFetch.fetch(
            new ResumableFetch.Request(
                home.resolve("model.onnx.partial"), URL, 100L, "ABCD", "splade/model.onnx"),
            (url, dest, decision, callback, tier) -> false,
            (bytes, total) -> {},
            () -> false,
            ResumableFetch.Hooks.none(),
            // One attempt, no backoff: this test is about the classification, not the ladder.
            TransportRetryPolicy.defaultPolicy().withMaxAttempts(1).withSleeper(ms -> {}));

    assertFalse(transport.ok());
    assertNotNull(transport.failure(), "a real transport failure must carry its classification");
    assertTrue(InstallAttemptMemory.isTransportFailure(transport), transport.error());

    ResumableFetch.Outcome verification =
        ResumableFetch.fetch(
            new ResumableFetch.Request(
                home.resolve("config.json.partial"), URL, 4L, "ABCD", "splade/config.json"),
            (url, dest, decision, callback, tier) -> {
              try {
                Files.write(dest, new byte[] {1, 2, 3, 4});
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
              return true;
            },
            (bytes, total) -> {},
            () -> false,
            ResumableFetch.Hooks.none(),
            TransportRetryPolicy.defaultPolicy().withMaxAttempts(1).withSleeper(ms -> {}));

    assertFalse(verification.ok());
    assertTrue(verification.error().startsWith("Verification failed"), verification.error());
    assertNull(verification.failure(), "a SHA mismatch is not a transport failure");
    assertFalse(InstallAttemptMemory.isTransportFailure(verification), verification.error());
    assertFalse(InstallAttemptMemory.isTransportFailure(null), "nor is a missing outcome");

    // …and the consumer's branch: only the transport outcome moves the next pass up a rung.
    InstallAttemptMemory memory = InstallAttemptMemory.load(home);
    recordIfTransport(memory, verification);
    assertEquals(0, memory.startTierFor(TARGET), "a verification failure must not escalate");
    recordIfTransport(memory, transport);
    assertEquals(1, memory.startTierFor(TARGET), "a transport failure escalates the next pass");
  }

  /** Mirrors {@code AiInstallService}'s branch: only a transport outcome reaches the memory. */
  private static void recordIfTransport(
      InstallAttemptMemory memory, ResumableFetch.Outcome outcome) {
    if (InstallAttemptMemory.isTransportFailure(outcome)) {
      memory.recordTransportFailure(
          TARGET, URL, outcome.transferAttempts(), outcome.error(), 0);
    }
  }

  /**
   * Degradation: a corrupt memory file must reproduce today's behaviour exactly (tier 0, never
   * terminal, Repair always offered). Losing the memory must never lose the install.
   */
  @Test
  @DisplayName("an unreadable memory degrades to no memory, never to a terminal verdict")
  void corruptFileDegradesToNoMemory() throws Exception {
    Files.writeString(home.resolve(InstallAttemptMemory.FILENAME), "{ not json");

    InstallAttemptMemory memory = InstallAttemptMemory.load(home);

    assertEquals(0, memory.startTierFor(TARGET));
    assertFalse(memory.isTerminal(TARGET));
    // …and it recovers: the next recorded failure rewrites the file rather than propagating.
    memory.recordTransportFailure(TARGET, URL, 1, "Download failed for " + TARGET, 0);
    assertEquals(1, InstallAttemptMemory.load(home).startTierFor(TARGET));
  }

  /**
   * Tempdoc 909 item 2 — the torn-write contract. Before 909 {@code save()} was
   * {@code Files.writeString}, which truncates the target before writing: a crash or a full disk
   * mid-write left a PREFIX of the json on disk. This pins both halves of the recovery: a truncated
   * file reads as "no memory" (never a crash, never a bogus terminal verdict), and the next
   * successful write replaces it wholesale rather than appending to the wreckage.
   */
  @Test
  @DisplayName("a torn (truncated) memory reads as no history and the next write replaces it")
  void tornMemoryReadsAsNoHistoryAndIsReplacedByTheNextWrite() throws Exception {
    InstallAttemptMemory.load(home).recordTransportFailure(TARGET, URL, 2, "Download failed", 0);
    Path file = home.resolve(InstallAttemptMemory.FILENAME);
    byte[] whole = Files.readAllBytes(file);
    assertTrue(whole.length > 10, "precondition: a complete memory was written");
    // A torn write: the first 60% of a REAL serialization, which is what a truncating write leaves.
    Files.write(file, java.util.Arrays.copyOf(whole, (whole.length * 6) / 10));

    InstallAttemptMemory torn = InstallAttemptMemory.load(home);
    assertNull(torn.get(TARGET), "a truncated memory is no memory, not a partial one");
    assertEquals(0, torn.startTierFor(TARGET));
    assertFalse(torn.isTerminal(TARGET), "a torn file must never fabricate a terminal verdict");

    torn.recordTransportFailure(TARGET, URL, 1, "Download failed", 0);
    InstallAttemptMemory reloaded = InstallAttemptMemory.load(home);
    assertNotNull(reloaded.get(TARGET), "the next write replaces the torn file with a readable one");
    assertEquals(1, reloaded.get(TARGET).failedPasses());
    try (var entries = Files.list(home)) {
      assertFalse(
          entries.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")),
          "the atomic replacement must leave no temporary residue beside the memory");
    }
  }

  /**
   * The memory lives under {@code homeDir}, NOT in the {@code DownloadResume} sidecar, because the
   * sidecar cannot survive the event it would record: a connection-setup failure leaves no partial
   * bytes, so the next pass decides FRESH and {@code DownloadResume.clear} deletes the sidecar.
   * This pins the choice — if a future change moves the memory into the sidecar, the history it
   * exists for disappears.
   */
  @Test
  @DisplayName("the memory survives the DownloadResume.clear that a fresh-start decision performs")
  void memorySurvivesSidecarClear() throws Exception {
    Path partial = home.resolve("config.json.partial");
    Files.writeString(partial, "x");
    DownloadResume.write(partial, new DownloadResume.State(URL, 100L, "ABCD", null));
    InstallAttemptMemory.load(home).recordTransportFailure(TARGET, URL, 2, "Download failed for x", 0);

    DownloadResume.clear(partial);

    assertFalse(Files.exists(DownloadResume.sidecarFor(partial)), "precondition: the sidecar is gone");
    assertEquals(
        1,
        InstallAttemptMemory.load(home).get(TARGET).failedPasses(),
        "the attempt history outlives the sidecar the same pass destroyed");
  }
}
