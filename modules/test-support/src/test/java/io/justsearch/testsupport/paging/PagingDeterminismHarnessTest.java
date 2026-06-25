package io.justsearch.testsupport.paging;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.testsupport.docs.SampleDocs;
import io.justsearch.testsupport.fixtures.MiniIndexFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PagingDeterminismHarnessTest {

  @Test
  void capturesAndWritesTrace() throws Exception {
    try (MiniIndexFixture fixture =
        MiniIndexFixture.builder().docs(SampleDocs.catalogSmoke()).build()) {
      PagingDeterminismHarness harness = PagingDeterminismHarness.from(fixture);
      PagingDeterminismHarness.PagingTrace trace = harness.capture("catalog-smoke", 3);
      assertFalse(trace.windows().isEmpty());
      Path tempFile = Files.createTempFile("paging-trace", ".json");
      harness.writeJson(trace, tempFile);
      assertTrue(Files.exists(tempFile));
      assertTrue(Files.size(tempFile) > 0);
      PageWindowAssert.assertNoDuplicates(trace.windows());
      PageWindowAssert.assertNoHoles(trace.windows());
    }
  }
}
