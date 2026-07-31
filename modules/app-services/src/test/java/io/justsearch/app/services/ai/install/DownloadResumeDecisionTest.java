package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Table-driven pin of {@link DownloadResume#decide} — the gate that decides whether bytes already on
 * disk may be reused.
 *
 * <p>Every "cannot prove this partial belongs to exactly this download" branch must land on {@link
 * DownloadResume.Action#FRESH}. A wrong RESUME here is how a stale byte range becomes a corrupt
 * multi-gigabyte model, so the negative cases matter more than the positive one.
 */
final class DownloadResumeDecisionTest {

  private static final String URL = "https://models.example/gguf/model-q4.gguf";
  private static final String SHA = "a".repeat(64);

  @TempDir Path tmp;

  private static DownloadResume.State state(String url, long size, String sha, String jobId) {
    return new DownloadResume.State(url, size, sha, jobId);
  }

  private static DownloadResume.Decision decide(long partialSize, DownloadResume.State recorded) {
    return DownloadResume.decide(partialSize, recorded, URL, 1000L, SHA);
  }

  @Test
  void noSidecarRecordMeansFresh() {
    DownloadResume.Decision d = decide(400L, null);
    assertEquals(DownloadResume.Action.FRESH, d.action());
    assertTrue(d.reason().contains("no resume record"));
  }

  @Test
  void changedSourceUrlMeansFresh() {
    DownloadResume.Decision d = decide(400L, state("https://other.example/x.gguf", 1000L, SHA, null));
    assertEquals(DownloadResume.Action.FRESH, d.action());
    assertTrue(d.reason().contains("url changed"));
  }

  /** The manifest entry changed size under an existing partial — the bytes are for another file. */
  @Test
  void changedExpectedSizeMeansFresh() {
    DownloadResume.Decision d = decide(400L, state(URL, 999L, SHA, null));
    assertEquals(DownloadResume.Action.FRESH, d.action());
    assertTrue(d.reason().contains("size changed"));
  }

  /** The manifest entry changed sha256 under an existing partial — same story. */
  @Test
  void changedExpectedSha256MeansFresh() {
    DownloadResume.Decision d = decide(400L, state(URL, 1000L, "b".repeat(64), null));
    assertEquals(DownloadResume.Action.FRESH, d.action());
    assertTrue(d.reason().contains("sha256 changed"));
  }

  @Test
  void shaComparisonIsCaseInsensitive() {
    DownloadResume.Decision d = decide(400L, state(URL, 1000L, SHA.toUpperCase(java.util.Locale.ROOT), null));
    assertEquals(DownloadResume.Action.RESUME_RANGE, d.action());
  }

  @Test
  void noBytesOnDiskMeansFresh() {
    DownloadResume.Decision d = decide(0L, state(URL, 1000L, SHA, null));
    assertEquals(DownloadResume.Action.FRESH, d.action());
    assertTrue(d.reason().contains("no partial bytes"));
  }

  /** Impossible state: more bytes than the file can possibly have. Never resume past the end. */
  @Test
  void partialLargerThanExpectedTotalMeansFresh() {
    DownloadResume.Decision d = decide(1001L, state(URL, 1000L, SHA, null));
    assertEquals(DownloadResume.Action.FRESH, d.action());
    assertTrue(d.reason().contains("impossible state"));
  }

  @Test
  void partialExactlyExpectedSizeMeansVerifyOnly() {
    DownloadResume.Decision d = decide(1000L, state(URL, 1000L, SHA, null));
    assertEquals(DownloadResume.Action.VERIFY_ONLY, d.action());
    assertEquals(1000L, d.resumeFromBytes());
  }

  @Test
  void shortPartialWithMatchingRecordResumesFromItsSize() {
    DownloadResume.Decision d = decide(458L, state(URL, 1000L, SHA, null));
    assertEquals(DownloadResume.Action.RESUME_RANGE, d.action());
    assertEquals(458L, d.resumeFromBytes());
    assertNull(d.bitsJobId());
  }

  /** Unknown total: no completeness reasoning is possible, and a Range past the end answers 416. */
  @Test
  void unknownExpectedSizeMeansFresh() {
    DownloadResume.Decision d =
        DownloadResume.decide(400L, state(URL, 0L, SHA, null), URL, 0L, SHA);
    assertEquals(DownloadResume.Action.FRESH, d.action());
    assertTrue(d.reason().contains("unknown"));
  }

  /** A suspended BITS job owns its own bytes, so the .partial size on disk is not the authority. */
  @Test
  void recordedBitsJobWinsOverOnDiskSize() {
    DownloadResume.Decision d = decide(0L, state(URL, 1000L, SHA, "JOB-42"));
    assertEquals(DownloadResume.Action.RESUME_BITS, d.action());
    assertEquals("JOB-42", d.bitsJobId());
  }

  /** A stale BITS job pointing at a changed URL must not be resumed. */
  @Test
  void recordedBitsJobForChangedUrlMeansFresh() {
    DownloadResume.Decision d =
        decide(0L, state("https://other.example/x.gguf", 1000L, SHA, "JOB-42"));
    assertEquals(DownloadResume.Action.FRESH, d.action());
    assertNull(d.bitsJobId());
  }

  @Test
  void blankBitsJobIdIsNotTreatedAsAResumeHandle() {
    DownloadResume.Decision d = decide(458L, state(URL, 1000L, SHA, "  "));
    assertEquals(DownloadResume.Action.RESUME_RANGE, d.action());
  }

  // -- sidecar IO -------------------------------------------------------------

  @Test
  void sidecarRoundTripsThroughDisk() throws Exception {
    Path partial = tmp.resolve("model.gguf.partial");
    Files.writeString(partial, "xx");
    DownloadResume.write(partial, state(URL, 1000L, SHA, "JOB-7"));

    DownloadResume.State back = DownloadResume.read(partial);
    assertEquals(URL, back.url());
    assertEquals(1000L, back.sizeBytes());
    assertEquals(SHA, back.sha256());
    assertEquals("JOB-7", back.bitsJobId());
  }

  @Test
  void malformedSidecarReadsAsNoRecord() throws Exception {
    Path partial = tmp.resolve("model.gguf.partial");
    Files.writeString(partial, "xx");
    Files.writeString(DownloadResume.sidecarFor(partial), "{ not json", StandardCharsets.UTF_8);

    assertNull(DownloadResume.read(partial), "a corrupt sidecar must degrade to a fresh download");
  }

  @Test
  void clearRemovesBothPartialAndSidecar() throws Exception {
    Path partial = tmp.resolve("model.gguf.partial");
    Files.writeString(partial, "xx");
    DownloadResume.write(partial, state(URL, 1000L, SHA, null));

    DownloadResume.clear(partial);

    assertTrue(Files.notExists(partial));
    assertTrue(Files.notExists(DownloadResume.sidecarFor(partial)));
  }
}
