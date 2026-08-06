package io.justsearch.app.services.atrest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tempdoc 629 (FLOOR) — the disk-encryption probe. Verifies the cache window (one read per TTL), the
 * value passthrough, and the never-throws contract (a failing read mechanism degrades to UNKNOWN
 * rather than propagating).
 */
final class DiskEncryptionProbeTest {

  private static final Path VOL = Path.of("C:/data");

  @Test
  void passesThroughTheReadResult() {
    AtRestProtection enc =
        new AtRestProtection(AtRestProtection.State.ENCRYPTED, "shell-property", AtRestProtection.Confidence.MEDIUM);
    DiskEncryptionProbe probe = new DiskEncryptionProbe(VOL, () -> 0L, p -> enc);
    assertEquals(enc, probe.current());
  }

  @Test
  void cachesWithinTheTtlAndRefreshesAfter() {
    AtomicInteger reads = new AtomicInteger();
    AtomicLong clock = new AtomicLong(0L);
    Function<Path, AtRestProtection> mech =
        p -> {
          reads.incrementAndGet();
          return new AtRestProtection(
              AtRestProtection.State.NOT_ENCRYPTED, "shell-property", AtRestProtection.Confidence.MEDIUM);
        };
    DiskEncryptionProbe probe = new DiskEncryptionProbe(VOL, clock::get, mech);

    probe.current();
    probe.current();
    assertEquals(1, reads.get(), "second call within the TTL is served from cache");

    clock.set(DiskEncryptionProbe.CACHE_TTL.toMillis() + 1);
    probe.current();
    assertEquals(2, reads.get(), "a call past the TTL re-reads");
  }

  /**
   * Sandbox round 7 — the PKEY→state table, previously untested in full.
   *
   * <p>MS docs: 1=On, 2=Off, 3=Unknown, 4=NotApplicable, 5=DecryptionInProgress,
   * 6=EncryptionInProgress. PKEY 4 used to fall through the {@code default ->} arm into UNKNOWN/LOW
   * alongside PKEY 3 — so a volume the OS can never encrypt reported the same "indeterminate, needs
   * admin" story as a volume whose state simply could not be read.
   */
  @ParameterizedTest(name = "PKEY {0} -> {1}/{2}")
  @CsvSource({
    "1, ENCRYPTED,      MEDIUM",
    "2, NOT_ENCRYPTED,  MEDIUM",
    "3, UNKNOWN,        LOW",
    "4, NOT_APPLICABLE, MEDIUM",
    "5, ENCRYPTING,     MEDIUM",
    "6, ENCRYPTING,     MEDIUM",
  })
  void mapsEveryDocumentedPkeyValue(
      int pkey, AtRestProtection.State expectedState, AtRestProtection.Confidence expectedConfidence) {
    AtRestProtection mapped = DiskEncryptionProbe.mapPkeyValue(pkey);
    assertEquals(expectedState, mapped.state(), "PKEY " + pkey);
    assertEquals(expectedConfidence, mapped.confidence(), "PKEY " + pkey);
    assertEquals("shell-property", mapped.source(), "PKEY " + pkey);
  }

  @Test
  void notApplicableIsNotConflatedWithUnknown() {
    // The distinction that matters downstream: "there is nothing to encrypt here" is a determinate
    // answer, so it must NOT read as the indeterminate one the UI offers elevation for.
    assertNotEquals(
        DiskEncryptionProbe.mapPkeyValue(3).state(), DiskEncryptionProbe.mapPkeyValue(4).state());
  }

  @Test
  void notApplicableSurvivesTheProbesPublicPath() {
    // The mapping is only useful if it reaches callers — drive it through the injected read
    // mechanism (the seam the production path also funnels through) rather than asserting the
    // mapper alone.
    DiskEncryptionProbe probe =
        new DiskEncryptionProbe(VOL, () -> 0L, p -> DiskEncryptionProbe.mapPkeyValue(4));
    assertEquals(AtRestProtection.State.NOT_APPLICABLE, probe.current().state());
  }

  @Test
  void undocumentedPkeyValuesStayUnknown() {
    // Only the documented set is claimed; anything else is honestly indeterminate.
    for (int pkey : new int[] {0, 7, -1, 99}) {
      assertEquals(
          AtRestProtection.State.UNKNOWN, DiskEncryptionProbe.mapPkeyValue(pkey).state(), "PKEY " + pkey);
    }
  }

  /**
   * Tempdoc 798 Part 2 — the root cause behind the "needs admin on a machine with nothing
   * encryptable" bug: {@code ExtendedProperty} returns {@code $null} when there is no BitLocker
   * feature to report on, and PowerShell's {@code [int]$null} silently coerces that to {@code 0}
   * (an undocumented-but-parseable value), which used to reach {@link
   * DiskEncryptionProbe#mapPkeyValue} indistinguishably from a genuine probed "0". The producer now
   * emits an explicit sentinel for the null case instead, caught by {@link
   * DiskEncryptionProbe#parseShellPropertyOutput} before the value ever reaches the PKEY table.
   *
   * <p>This is deliberately a NEW test, not a change to {@link #undocumentedPkeyValuesStayUnknown()}:
   * {@code mapPkeyValue(0) -> UNKNOWN} stays correct (an undocumented numeric PKEY value really is
   * indeterminate) — the fix is that a null property no longer becomes a "0" in the first place, so
   * it never calls {@code mapPkeyValue} at all. Both degrade to {@code State.UNKNOWN}, but only the
   * absent-property path carries {@code source = "none"} (no answer at all), distinguishing it from a
   * probed-but-indeterminate reading — the distinction the FE honesty fix (atRestCard.ts) depends on.
   */
  @Test
  void absentPropertyIsDistinguishedFromAGenuineZero() {
    AtRestProtection absent = DiskEncryptionProbe.parseShellPropertyOutput("PROPERTY_ABSENT");
    assertEquals(AtRestProtection.State.UNKNOWN, absent.state());
    assertEquals("none", absent.source(), "absence must NOT be attributed to the shell-property probe");
    assertEquals(AtRestProtection.Confidence.UNKNOWN, absent.confidence());

    AtRestProtection genuineZero = DiskEncryptionProbe.parseShellPropertyOutput("0");
    assertEquals(AtRestProtection.State.UNKNOWN, genuineZero.state());
    assertEquals(
        "shell-property",
        genuineZero.source(),
        "a probed (if undocumented) numeric value keeps its shell-property provenance");
    assertEquals(AtRestProtection.Confidence.LOW, genuineZero.confidence());

    assertNotEquals(absent, genuineZero, "absence and a real 0 reading must not collapse to one value");
  }

  /**
   * Pins the actual producer change (798 Part 2), not just the parser's tolerance for an arbitrary
   * sentinel string: the emitted PowerShell must itself distinguish a null {@code ExtendedProperty}
   * read from a coerced numeric one, by checking {@code $v} for {@code $null} before casting it.
   * Without this check, {@code [int]$null} silently becomes the string {@code "0"} and the sentinel
   * is never emitted at all — the exact defect this whole slice fixes.
   */
  @Test
  void probeScriptChecksForNullBeforeCoercingToInt() {
    String script = DiskEncryptionProbe.buildProbeScript("C:\\");
    assertTrue(
        script.contains("$null -eq $v"),
        "script must check the property for null before casting it to int: " + script);
    assertTrue(
        script.contains("PROPERTY_ABSENT"),
        "script must emit the absence sentinel on the null branch: " + script);
  }

  @Test
  void parseShellPropertyOutputHandlesEmptyAndWhitespaceAndNonNumericOutput() {
    assertEquals(AtRestProtection.State.UNKNOWN, DiskEncryptionProbe.parseShellPropertyOutput("").state());
    assertEquals(AtRestProtection.State.UNKNOWN, DiskEncryptionProbe.parseShellPropertyOutput(null).state());
    assertEquals(
        AtRestProtection.State.UNKNOWN,
        DiskEncryptionProbe.parseShellPropertyOutput("   \n").state());
    assertEquals(
        AtRestProtection.State.UNKNOWN,
        DiskEncryptionProbe.parseShellPropertyOutput("not-a-number").state());
    assertEquals(
        AtRestProtection.State.ENCRYPTED,
        DiskEncryptionProbe.parseShellPropertyOutput("  1  \n").state(),
        "surrounding whitespace from the subprocess line is trimmed before parsing");
  }

  @Test
  void neverThrows_degradesToUnknown() {
    DiskEncryptionProbe probe =
        new DiskEncryptionProbe(
            VOL,
            () -> 0L,
            p -> {
              throw new RuntimeException("boom");
            });
    assertEquals(AtRestProtection.State.UNKNOWN, probe.current().state());
  }
}
