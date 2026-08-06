package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Partial-file lifecycle for {@link ResumableFetch}: a cancel must leave usable bytes, a restart must
 * resume them, and an unusable partial must be cleaned up and restarted.
 *
 * <p>Windows Sandbox validation round 7 measured the pre-fix behaviour: cancel at 458 MB of a 10.14
 * GB install, restart, and the models directory was empty — the loop deleted every {@code .partial}
 * unconditionally on each start.
 *
 * <p>The integrity cases are the load-bearing ones. Resume that can accept bytes which do not hash
 * to the manifest's sha256 is worse than the bug it replaces, so {@link
 * #corruptResumedPartialIsDiscardedAndRestartedFromZero} pins that a corrupt partial can never be
 * accepted.
 */
final class ResumableFetchTest {

  private static final String URL = "https://models.example/gguf/model-q4.gguf";

  @TempDir Path tmp;

  // -- fake transport ---------------------------------------------------------

  /**
   * Stands in for BITS/curl. Models a Range-honouring server: on a resume it keeps whatever prefix is
   * already on disk and appends the remainder of {@link #served} from that offset — so a garbage
   * prefix yields a garbage whole file, exactly as a real resume would.
   */
  private static final class FakeTransfer implements ResumableFetch.Transfer {
    private final byte[] served;
    /** When >= 0, write only this many bytes total and report failure (a cancel mid-transfer). */
    private int stopAfterBytes = -1;

    private String suspendedJobId;
    private final List<DownloadResume.Action> actions = new ArrayList<>();
    private final List<Long> resumeOffsets = new ArrayList<>();
    private final List<String> abandoned = new ArrayList<>();
    private final List<String> jobIds = new ArrayList<>();

    FakeTransfer(byte[] served) {
      this.served = served;
    }

    @Override
    public boolean transfer(
        String url,
        Path destPartial,
        DownloadResume.Decision decision,
        DownloadExecutor.ProgressCallback callback) {
      actions.add(decision.action());
      jobIds.add(decision.bitsJobId());
      long onDisk = DownloadResume.partialSize(destPartial);
      resumeOffsets.add(onDisk);
      int from = (int) onDisk;
      int to = stopAfterBytes >= 0 ? Math.min(stopAfterBytes, served.length) : served.length;
      try {
        if (from > to) return false;
        byte[] chunk = new byte[to - from];
        System.arraycopy(served, from, chunk, 0, chunk.length);
        Files.write(
            destPartial,
            chunk,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND);
      } catch (IOException e) {
        return false;
      }
      if (callback != null) callback.onProgress(to, served.length);
      return stopAfterBytes < 0;
    }

    @Override
    public String suspendedBitsJobId() {
      return suspendedJobId;
    }

    @Override
    public void abandonResumeHandle(String bitsJobId) {
      abandoned.add(bitsJobId);
    }

    int calls() {
      return actions.size();
    }
  }

  // -- helpers ----------------------------------------------------------------

  private static byte[] content(int len, int seed) {
    byte[] b = new byte[len];
    for (int i = 0; i < len; i++) b[i] = (byte) ((i * 31 + seed) & 0xFF);
    return b;
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private Path partial() {
    return tmp.resolve("model-q4.gguf.partial");
  }

  private static ResumableFetch.Request request(Path partial, byte[] full) throws Exception {
    return new ResumableFetch.Request(partial, URL, full.length, sha256(full), "gguf/model-q4.gguf");
  }

  private static ResumableFetch.Outcome fetch(
      ResumableFetch.Request request,
      ResumableFetch.Transfer transfer,
      AtomicBoolean cancelFlag,
      List<String> events) {
    return ResumableFetch.fetch(
        request,
        transfer,
        (bytes, total) -> {},
        cancelFlag::get,
        () -> events.add("fresh-start"),
        () -> events.add("verify-start"));
  }

  // -- fresh path -------------------------------------------------------------

  @Test
  void freshDownloadVerifiesAndDropsTheSidecar() throws Exception {
    byte[] full = content(2048, 1);
    FakeTransfer transfer = new FakeTransfer(full);
    List<String> events = new ArrayList<>();

    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), events);

    assertTrue(out.ok(), out.error());
    assertEquals(DownloadResume.Action.FRESH, out.firstAction());
    assertEquals(1, out.transferAttempts());
    assertArrayEquals(full, Files.readAllBytes(partial()));
    assertTrue(Files.notExists(DownloadResume.sidecarFor(partial())), "sidecar cleared on success");
    assertTrue(events.contains("fresh-start"));
    assertTrue(events.contains("verify-start"));
  }

  // -- cancel leaves a usable partial ------------------------------------------

  @Test
  void cancelLeavesAUsablePartialAndItsResumeRecord() throws Exception {
    byte[] full = content(2048, 1);
    FakeTransfer transfer = new FakeTransfer(full);
    transfer.stopAfterBytes = 458;
    AtomicBoolean cancelFlag = new AtomicBoolean(true);

    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, cancelFlag, new ArrayList<>());

    assertFalse(out.ok());
    assertTrue(out.cancelled());
    assertEquals(458, Files.size(partial()), "cancelled bytes must survive the cancel");
    DownloadResume.State recorded = DownloadResume.read(partial());
    assertNotNull(recorded, "a usable partial keeps its identity record");
    assertEquals(URL, recorded.url());
    assertEquals(full.length, recorded.sizeBytes());
    assertEquals(sha256(full), recorded.sha256());
  }

  /** The regression the whole change exists for: cancel, restart, and keep the bytes. */
  @Test
  void restartAfterCancelResumesInsteadOfStartingOver() throws Exception {
    byte[] full = content(2048, 1);
    FakeTransfer cancelled = new FakeTransfer(full);
    cancelled.stopAfterBytes = 458;
    fetch(request(partial(), full), cancelled, new AtomicBoolean(true), new ArrayList<>());

    FakeTransfer resumed = new FakeTransfer(full);
    List<String> events = new ArrayList<>();
    ResumableFetch.Outcome out =
        fetch(request(partial(), full), resumed, new AtomicBoolean(false), events);

    assertTrue(out.ok(), out.error());
    assertEquals(DownloadResume.Action.RESUME_RANGE, out.firstAction());
    assertEquals(458L, resumed.resumeOffsets.get(0), "resumed from the cancelled offset");
    assertEquals(1, out.transferAttempts(), "no restart-from-zero");
    assertFalse(
        events.contains("fresh-start"),
        "BITS scratch cleanup must not run on a resume — a suspended job still owns its tmp");
    assertArrayEquals(full, Files.readAllBytes(partial()));
  }

  /** Resume state lives on disk, so it survives a process restart, not just an in-session cancel. */
  @Test
  void resumeWorksFromDiskStateAloneAfterAProcessRestart() throws Exception {
    byte[] full = content(2048, 1);
    Files.write(partial(), java.util.Arrays.copyOf(full, 700));
    DownloadResume.write(
        partial(), new DownloadResume.State(URL, full.length, sha256(full), null));

    FakeTransfer transfer = new FakeTransfer(full);
    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), new ArrayList<>());

    assertTrue(out.ok(), out.error());
    assertEquals(DownloadResume.Action.RESUME_RANGE, out.firstAction());
    assertEquals(700L, transfer.resumeOffsets.get(0));
  }

  @Test
  void suspendedBitsJobIsRecordedOnCancelAndResumedOnRestart() throws Exception {
    byte[] full = content(2048, 1);
    FakeTransfer cancelled = new FakeTransfer(full);
    cancelled.stopAfterBytes = 0;
    cancelled.suspendedJobId = "JOB-9";
    fetch(request(partial(), full), cancelled, new AtomicBoolean(true), new ArrayList<>());

    assertEquals("JOB-9", DownloadResume.read(partial()).bitsJobId());

    FakeTransfer resumed = new FakeTransfer(full);
    ResumableFetch.Outcome out =
        fetch(request(partial(), full), resumed, new AtomicBoolean(false), new ArrayList<>());

    assertEquals(DownloadResume.Action.RESUME_BITS, out.firstAction());
    assertEquals(
        "JOB-9", resumed.jobIds.get(0), "the suspended job id must reach the transport to resume");
    assertTrue(out.ok(), out.error());
    assertTrue(
        Files.notExists(DownloadResume.sidecarFor(partial())),
        "the resume handle is dropped once the file is verified");
  }

  // -- unusable partials are cleaned up ----------------------------------------

  @Test
  void partialWithoutASidecarIsDiscardedAndRestarted() throws Exception {
    byte[] full = content(2048, 1);
    Files.write(partial(), content(700, 9));

    FakeTransfer transfer = new FakeTransfer(full);
    List<String> events = new ArrayList<>();
    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), events);

    assertTrue(out.ok(), out.error());
    assertEquals(DownloadResume.Action.FRESH, out.firstAction());
    assertEquals(0L, transfer.resumeOffsets.get(0), "unanchored bytes must be thrown away");
    assertTrue(events.contains("fresh-start"));
    assertArrayEquals(full, Files.readAllBytes(partial()));
  }

  @Test
  void partialLargerThanExpectedTotalIsDiscardedAndRestarted() throws Exception {
    byte[] full = content(2048, 1);
    Files.write(partial(), content(4096, 1));
    DownloadResume.write(
        partial(), new DownloadResume.State(URL, full.length, sha256(full), null));

    FakeTransfer transfer = new FakeTransfer(full);
    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), new ArrayList<>());

    assertTrue(out.ok(), out.error());
    assertEquals(DownloadResume.Action.FRESH, out.firstAction());
    assertEquals(0L, transfer.resumeOffsets.get(0));
    assertArrayEquals(full, Files.readAllBytes(partial()));
  }

  /** Manifest entry changed sha256 under the partial; any recorded BITS job must be abandoned too. */
  @Test
  void partialForAChangedManifestEntryIsDiscardedAndItsBitsJobAbandoned() throws Exception {
    byte[] full = content(2048, 1);
    Files.write(partial(), content(700, 5));
    DownloadResume.write(
        partial(), new DownloadResume.State(URL, full.length, "d".repeat(64), "STALE-JOB"));

    FakeTransfer transfer = new FakeTransfer(full);
    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), new ArrayList<>());

    assertTrue(out.ok(), out.error());
    assertEquals(DownloadResume.Action.FRESH, out.firstAction());
    assertEquals(List.of("STALE-JOB"), transfer.abandoned, "stale BITS job must not be left queued");
    assertArrayEquals(full, Files.readAllBytes(partial()));
  }

  @Test
  void alreadyCompletePartialSkipsTheTransferAndOnlyVerifies() throws Exception {
    byte[] full = content(2048, 1);
    Files.write(partial(), full);
    DownloadResume.write(
        partial(), new DownloadResume.State(URL, full.length, sha256(full), null));

    FakeTransfer transfer = new FakeTransfer(full);
    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), new ArrayList<>());

    assertTrue(out.ok(), out.error());
    assertEquals(DownloadResume.Action.VERIFY_ONLY, out.firstAction());
    assertEquals(0, out.transferAttempts(), "a complete partial needs no bytes fetched");
    assertEquals(0, transfer.calls());
  }

  // -- integrity ---------------------------------------------------------------

  /**
   * BITE-PROOF CASE. The partial's first 700 bytes are garbage but its sidecar looks valid (this is
   * what an upstream file changing mid-download, or a truncated/mangled write, looks like on disk).
   * The fake server honours the Range and appends the correct tail, so the assembled file hashes
   * wrong. It must be destroyed and re-fetched from zero, never accepted.
   */
  @Test
  void corruptResumedPartialIsDiscardedAndRestartedFromZero() throws Exception {
    byte[] full = content(2048, 1);
    Files.write(partial(), content(700, 77));
    DownloadResume.write(
        partial(), new DownloadResume.State(URL, full.length, sha256(full), null));

    FakeTransfer transfer = new FakeTransfer(full);
    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), new ArrayList<>());

    assertEquals(DownloadResume.Action.RESUME_RANGE, out.firstAction(), "it did attempt a resume");
    assertEquals(700L, transfer.resumeOffsets.get(0));
    assertEquals(2, out.transferAttempts(), "the failed resume must be restarted from zero once");
    assertEquals(0L, transfer.resumeOffsets.get(1), "the restart starts at byte zero");
    assertTrue(out.ok(), out.error());
    assertArrayEquals(
        full,
        Files.readAllBytes(partial()),
        "the accepted file must be the manifest's file, not the corrupt assembly");
    assertEquals(sha256(full), DownloadExecutor.sha256(partial()));
    assertTrue(Files.notExists(DownloadResume.sidecarFor(partial())));
  }

  /**
   * Upstream itself serves bytes that do not match the manifest. The restart-from-zero must happen
   * exactly once and then fail cleanly — never accept, never loop.
   */
  @Test
  void permanentlyWrongUpstreamBytesFailCleanlyWithoutLooping() throws Exception {
    byte[] expected = content(2048, 1);
    byte[] wrong = content(2048, 2);
    Files.write(partial(), java.util.Arrays.copyOf(wrong, 700));
    DownloadResume.write(
        partial(), new DownloadResume.State(URL, expected.length, sha256(expected), null));

    FakeTransfer transfer = new FakeTransfer(wrong);
    ResumableFetch.Outcome out =
        fetch(request(partial(), expected), transfer, new AtomicBoolean(false), new ArrayList<>());

    assertFalse(out.ok(), "wrong bytes must never be accepted");
    assertFalse(out.cancelled());
    assertNotNull(out.error());
    assertTrue(out.error().startsWith("Verification failed"), out.error());
    assertEquals(2, out.transferAttempts(), "exactly one restart, then give up");
    assertTrue(Files.notExists(partial()), "corrupt bytes must not be left on disk");
    assertTrue(Files.notExists(DownloadResume.sidecarFor(partial())));
  }

  /** A size mismatch is caught by the same verify step (a truncated resume can't pass as complete). */
  @Test
  void shortServedFileFailsVerificationRatherThanBeingAccepted() throws Exception {
    byte[] full = content(2048, 1);
    byte[] truncated = java.util.Arrays.copyOf(full, 1024);
    FakeTransfer transfer = new FakeTransfer(truncated);

    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), new ArrayList<>());

    assertFalse(out.ok());
    assertTrue(out.error().contains("Size mismatch"), out.error());
    assertTrue(Files.notExists(partial()));
  }

  @Test
  void transferFailureWithoutCancellationKeepsTheBytesForTheNextAttempt() throws Exception {
    byte[] full = content(2048, 1);
    FakeTransfer transfer = new FakeTransfer(full);
    transfer.stopAfterBytes = 900;

    ResumableFetch.Outcome out =
        fetch(request(partial(), full), transfer, new AtomicBoolean(false), new ArrayList<>());

    assertFalse(out.ok());
    assertFalse(out.cancelled());
    assertEquals("Download failed for gguf/model-q4.gguf", out.error());
    assertEquals(900L, Files.size(partial()), "a network drop keeps its progress too");
    assertNotNull(DownloadResume.read(partial()));
  }

  @Test
  void sidecarIsWrittenAsUtf8Json() throws Exception {
    byte[] full = content(64, 3);
    FakeTransfer transfer = new FakeTransfer(full);
    transfer.stopAfterBytes = 8;
    fetch(request(partial(), full), transfer, new AtomicBoolean(true), new ArrayList<>());

    String json =
        new String(
            Files.readAllBytes(DownloadResume.sidecarFor(partial())), StandardCharsets.UTF_8);
    assertTrue(json.contains(URL), json);
  }
}
